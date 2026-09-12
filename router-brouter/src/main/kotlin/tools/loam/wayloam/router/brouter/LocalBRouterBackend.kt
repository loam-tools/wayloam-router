package tools.loam.wayloam.router.brouter

import btools.router.OsmTrack
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.RoutingParamCollector
import btools.router.WayloamVoiceHintBridge
import kotlinx.coroutines.suspendCancellableCoroutine
import tools.loam.wayloam.router.api.GeoPoint
import tools.loam.wayloam.router.api.RouteManeuver
import tools.loam.wayloam.router.api.RouteManeuverType
import tools.loam.wayloam.router.api.RouteMetrics
import tools.loam.wayloam.router.api.RoutingException
import tools.loam.wayloam.router.api.RoutingFailureCode
import tools.loam.wayloam.router.core.GeoMath
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class LocalBRouterBackend(
    private val segmentDirectory: File,
    private val profileDirectory: File,
    private val maxRunningTimeMillis: Long = 90_000L,
    private val memoryLimitMb: Int = 128,
) : EmbeddedBRouterBackend {

    init {
        require(maxRunningTimeMillis > 0L)
        require(memoryLimitMb in 32..2048)
    }

    override suspend fun route(request: BRouterBackendRequest): BRouterBackendResult =
        suspendCancellableCoroutine { continuation ->
            val profileFile = File(profileDirectory, "${request.preset.baseProfile}.brf")
            if (!profileFile.isFile || !File(profileDirectory, "lookups.dat").isFile) {
                continuation.resumeWith(
                    Result.failure(
                        MissingBRouterProfileException(
                            "Missing BRouter profile: ${profileFile.absolutePath}"
                        )
                    )
                )
                return@suspendCancellableCoroutine
            }
            if (!segmentDirectory.isDirectory) {
                continuation.resumeWith(
                    Result.failure(
                        MissingRoutingDataException(
                            "Routing-data directory does not exist: ${segmentDirectory.absolutePath}"
                        )
                    )
                )
                return@suspendCancellableCoroutine
            }

            val activeEngine = AtomicReference<RoutingEngine?>()
            val future = try {
                workers.submit {
                    if (!continuation.isActive) return@submit
                    val result = runCatching {
                        val collector = RoutingParamCollector()
                        val waypoints = collector.readPositions(
                            doubleArrayOf(request.start.longitude, request.end.longitude),
                            doubleArrayOf(request.start.latitude, request.end.latitude),
                        )
                        val context = RoutingContext().apply {
                            localFunction = profileFile.absolutePath
                            memoryclass = memoryLimitMb
                        }
                        collector.setProfileParams(context, request.preset.parameters)
                        val engine = try {
                            RoutingEngine(null, null, segmentDirectory, waypoints, context,
                                RoutingEngine.BROUTER_ENGINEMODE_ROUTING).apply { quite = true }
                        } catch (error: Exception) {
                            btools.router.ProfileCache.releaseProfile(context)
                            throw classifyBRouterFailure(error.message ?: "Cannot initialize BRouter")
                        }
                        // Do not silently move a rider's stop tens of kilometres or create a
                        // beeline over water when BRouter's dynamic matching cannot find a road.
                        context.useDynamicDistance = false
                        context.buildBeelineOnRange = false
                        context.waypointCatchingRange = 1_000.0
                        activeEngine.set(engine)
                        if (!continuation.isActive) engine.terminate()

                        val dependencyObserver = BRouterRouteMetadata.DataDependencyObserver(
                            engine = engine,
                            segmentDirectory = segmentDirectory,
                        )
                        dependencyObserver.start()
                        val dependencies = try {
                            engine.doRun(maxRunningTimeMillis)
                            dependencyObserver.finish()
                        } finally {
                            dependencyObserver.close()
                        }

                        engine.getErrorMessage()?.let { throw classifyBRouterFailure(it) }
                        val track = engine.getFoundTrack() ?: throw NoRouteException("BRouter returned no route")
                        track.toBackendResult(dependencies)
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            } catch (_: RejectedExecutionException) {
                continuation.resumeWith(Result.failure(RoutingException(
                    RoutingFailureCode.ENGINE_BUSY, "The routing worker queue is full")))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                activeEngine.get()?.terminate()
                future.cancel(true)
                workers.purge()
            }
        }

    private fun OsmTrack.toBackendResult(dataDependencies: Map<String, String>): BRouterBackendResult {
        if (nodes.size < 2) throw NoRouteException("BRouter returned an empty route")

        val points = nodes.map { node ->
            GeoPoint(
                latitude = (node.getILat() - LATITUDE_OFFSET) / POSITION_SCALE,
                longitude = (node.getILon() - LONGITUDE_OFFSET) / POSITION_SCALE,
                elevationMeters = node.getSElev()
                    .takeUnless { it == Short.MIN_VALUE }
                    ?.let { it / 4.0 },
            )
        }

        val startElevation = points.first().elevationMeters
        val endElevation = points.last().elevationMeters
        val netElevation = if (startElevation != null && endElevation != null) {
            (endElevation - startElevation).roundToInt()
        } else {
            0
        }

        return BRouterBackendResult(
            points = points,
            metrics = RouteMetrics(
                distanceMeters = distance.toLong(),
                ascentMeters = max(0, ascend),
                descentMeters = max(0, ascend - netElevation),
                durationSeconds = max(0, getTotalSeconds()).toLong(),
            ),
            annotations = BRouterRouteMetadata.annotations(this),
            dataDependencies = dataDependencies,
            maneuvers = maneuvers(points),
        )
    }

    private fun OsmTrack.maneuvers(points: List<GeoPoint>): List<RouteManeuver> {
        if (points.size < 2) return emptyList()
        val cumulative = DoubleArray(points.size)
        for (index in 1 until points.size) {
            cumulative[index] = cumulative[index - 1] + GeoMath.distanceMeters(points[index - 1], points[index])
        }

        return WayloamVoiceHintBridge.read(this).mapNotNull { hint ->
            val index = hint.indexInTrack
            if (index !in points.indices) return@mapNotNull null
            val type = hint.command.toManeuverType() ?: return@mapNotNull null
            val angle = hint.angle
                .takeIf { it.isFinite() && abs(it) <= 360.0 }
                ?.roundToInt()
            val exit = if (type == RouteManeuverType.ROUNDABOUT || type == RouteManeuverType.ROUNDABOUT_LEFT) {
                abs(hint.exitNumber).takeIf { it > 0 }
            } else {
                null
            }
            RouteManeuver(
                type = type,
                pointIndex = index,
                point = points[index],
                distanceAlongRouteMeters = cumulative[index],
                distanceToNextMeters = hint.distanceToNext.coerceAtLeast(0.0),
                turnAngleDegrees = angle,
                roundaboutExit = exit,
            )
        }
    }

    private fun Int.toManeuverType(): RouteManeuverType? = when (this) {
        1 -> RouteManeuverType.CONTINUE
        2 -> RouteManeuverType.TURN_LEFT
        3 -> RouteManeuverType.SLIGHT_LEFT
        4 -> RouteManeuverType.SHARP_LEFT
        5 -> RouteManeuverType.TURN_RIGHT
        6 -> RouteManeuverType.SLIGHT_RIGHT
        7 -> RouteManeuverType.SHARP_RIGHT
        8 -> RouteManeuverType.KEEP_LEFT
        9 -> RouteManeuverType.KEEP_RIGHT
        10, 11, 15 -> RouteManeuverType.U_TURN
        12 -> RouteManeuverType.OFF_ROUTE
        13 -> RouteManeuverType.ROUNDABOUT
        14 -> RouteManeuverType.ROUNDABOUT_LEFT
        16 -> RouteManeuverType.BEELINE
        17 -> RouteManeuverType.EXIT_LEFT
        18 -> RouteManeuverType.EXIT_RIGHT
        else -> null
    }

    companion object {
        // One active native search per process bounds memory and keeps cancelled searches from
        // spawning overlapping workers. Parsing profiles also happens here, off the caller thread.
        private val workers = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(16),
            { task -> Thread(task, "wayloam-brouter").apply { isDaemon = true } })
        private const val POSITION_SCALE = 1_000_000.0
        private const val LONGITUDE_OFFSET = 180_000_000
        private const val LATITUDE_OFFSET = 90_000_000
    }
}
