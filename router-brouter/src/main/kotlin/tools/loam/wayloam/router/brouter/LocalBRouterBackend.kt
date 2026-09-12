package tools.loam.wayloam.router.brouter

import btools.router.OsmTrack
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.RoutingParamCollector
import kotlinx.coroutines.suspendCancellableCoroutine
import tools.loam.wayloam.router.api.GeoPoint
import tools.loam.wayloam.router.api.RouteMetrics
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

class LocalBRouterBackend(
    private val segmentDirectory: File,
    private val profileDirectory: File,
    private val maxRunningTimeMillis: Long = 90_000L,
) : EmbeddedBRouterBackend {

    init {
        require(maxRunningTimeMillis > 0L)
    }

    override suspend fun route(request: BRouterBackendRequest): BRouterBackendResult =
        suspendCancellableCoroutine { continuation ->
            val profileFile = File(profileDirectory, "${request.preset.baseProfile}.brf")
            if (!profileFile.isFile) {
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

            val collector = RoutingParamCollector()
            val waypoints = collector.readPositions(
                doubleArrayOf(request.start.longitude, request.end.longitude),
                doubleArrayOf(request.start.latitude, request.end.latitude),
            )
            val context = RoutingContext().apply {
                localFunction = profileFile.absolutePath
            }
            collector.setProfileParams(context, request.preset.parameters)

            val engine = RoutingEngine(
                null,
                null,
                segmentDirectory,
                waypoints,
                context,
                RoutingEngine.BROUTER_ENGINEMODE_ROUTING,
            ).apply {
                quite = true
            }

            val worker = thread(
                start = false,
                isDaemon = true,
                name = "wayloam-brouter",
            ) {
                val result = runCatching {
                    engine.doRun(maxRunningTimeMillis)
                    engine.getErrorMessage()?.let { throw classifyBRouterFailure(it) }
                    val track = engine.getFoundTrack()
                        ?: throw NoRouteException("BRouter returned no route")
                    track.toBackendResult()
                }

                if (continuation.isActive) {
                    continuation.resumeWith(result)
                }
            }

            continuation.invokeOnCancellation {
                engine.terminate()
                worker.interrupt()
            }
            worker.start()
        }

    private fun OsmTrack.toBackendResult(): BRouterBackendResult {
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
        )
    }

    private fun classifyBRouterFailure(message: String): IOException {
        val normalized = message.lowercase()
        return when {
            normalized.contains("rd5") ||
                normalized.contains("datafile") ||
                normalized.contains("segment") && normalized.contains("not found") ->
                MissingRoutingDataException(message)

            normalized.contains("timeout") ||
                normalized.contains("time limit") ||
                normalized.contains("terminated") ->
                RoutingTimeoutException(message)

            normalized.contains("no route") || normalized.contains("target island") ->
                NoRouteException(message)

            else -> BRouterEngineException(message)
        }
    }

    companion object {
        private const val POSITION_SCALE = 1_000_000.0
        private const val LONGITUDE_OFFSET = 180_000_000
        private const val LATITUDE_OFFSET = 90_000_000
    }
}

open class BRouterEngineException(message: String) : IOException(message)
class MissingBRouterProfileException(message: String) : BRouterEngineException(message)
class MissingRoutingDataException(message: String) : BRouterEngineException(message)
class RoutingTimeoutException(message: String) : BRouterEngineException(message)
class NoRouteException(message: String) : BRouterEngineException(message)
