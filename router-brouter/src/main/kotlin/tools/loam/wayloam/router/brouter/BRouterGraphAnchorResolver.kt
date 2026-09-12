package tools.loam.wayloam.router.brouter

import btools.mapaccess.MatchedWaypoint
import btools.mapaccess.NodesCache
import btools.mapaccess.OsmNodePairSet
import btools.router.OsmNodeNamed
import btools.router.ProfileCache
import btools.router.RoutingContext
import btools.router.RoutingParamCollector
import kotlinx.coroutines.*
import tools.loam.wayloam.router.api.GeoPoint
import tools.loam.wayloam.router.api.RouteProfile
import tools.loam.wayloam.router.core.GeoMath
import tools.loam.wayloam.router.core.GraphAnchorResolver
import java.io.File
import kotlin.math.*

/** Uses the pinned engine's profile-filtered graph matcher; upstream types stay in this module. */
class BRouterGraphAnchorResolver(
    private val segmentDirectory: File,
    private val profileDirectory: File,
    private val memoryLimitMb: Int = 64,
) : GraphAnchorResolver {
    init { require(memoryLimitMb in 32..2048) }

    override suspend fun candidates(point: GeoPoint, profile: RouteProfile): List<GeoPoint> =
        withContext(Dispatchers.IO) {
            val preset = WayloamProfiles.forProfile(profile)
            val context = RoutingContext().apply {
                localFunction = File(profileDirectory, "${preset.baseProfile}.brf").absolutePath
                memoryclass = memoryLimitMb
            }
            val collector = RoutingParamCollector()
            collector.setProfileParams(context, preset.parameters)
            var cache: NodesCache? = null
            try {
                currentCoroutineContext().ensureActive()
                ProfileCache.parseProfile(context)
                cache = NodesCache(segmentDirectory, context.expctxWay, false,
                    memoryLimitMb * 1024L * 1024L, null, false)
                val matches = mutableListOf<GeoPoint>()
                val seeds = listOf(point) + listOf(10_000.0, 25_000.0).flatMap { radius ->
                    (0 until 8).map { destination(point, radius, it * PI / 4) }
                }
                for ((i, seed) in seeds.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val wp = MatchedWaypoint().apply {
                        waypoint = OsmNodeNamed().apply {
                            ilon = ((seed.longitude + 180.0) * 1_000_000.0 + 0.5).toInt()
                            ilat = ((seed.latitude + 90.0) * 1_000_000.0 + 0.5).toInt()
                        }
                        name = "anchor"
                    }
                    try {
                        cache.matchWaypointsToNodes(mutableListOf(wp), 1_000.0, OsmNodePairSet(500))
                    } catch (error: Exception) {
                        val classified = classifyBRouterFailure(error.message ?: "Graph matching failed")
                        // A neighboring candidate may be outside downloaded coverage. Never hide
                        // corrupt/incompatible data or a missing tile at the original search seed.
                        if (i > 0 && classified is MissingRoutingDataException) continue
                        throw classified
                    }
                    val crosspoint = wp.crosspoint ?: continue
                    val snapped = GeoPoint((crosspoint.ilat - 90_000_000) / 1_000_000.0,
                        (crosspoint.ilon - 180_000_000) / 1_000_000.0)
                    if (GeoMath.distanceMeters(seed, snapped) <= 1_000 &&
                        matches.none { GeoMath.distanceMeters(it, snapped) < 100 }) matches += snapped
                    if (matches.size >= 3) break
                }
                matches.sortedBy { GeoMath.distanceMeters(point, it) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: tools.loam.wayloam.router.api.RoutingException) {
                throw error
            } catch (error: Exception) {
                throw classifyBRouterFailure(error.message ?: "Graph matching failed")
            } finally {
                cache?.close()
                ProfileCache.releaseProfile(context)
            }
        }

    private fun destination(start: GeoPoint, distance: Double, bearing: Double): GeoPoint {
        val lat = Math.toRadians(start.latitude)
        val lon = Math.toRadians(start.longitude)
        val arc = distance / 6_371_008.8
        val targetLat = asin((sin(lat) * cos(arc) + cos(lat) * sin(arc) * cos(bearing)).coerceIn(-1.0, 1.0))
        val targetLon = lon + atan2(sin(bearing) * sin(arc) * cos(lat), cos(arc) - sin(lat) * sin(targetLat))
        return GeoPoint(Math.toDegrees(targetLat), ((Math.toDegrees(targetLon) + 540) % 360) - 180)
    }
}
