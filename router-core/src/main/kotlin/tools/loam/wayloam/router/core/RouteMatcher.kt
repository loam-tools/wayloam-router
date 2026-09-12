package tools.loam.wayloam.router.core

import tools.loam.wayloam.router.api.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Matches live locations to an already calculated route without performing another routing search.
 * The matcher is immutable and safe to reuse for every GPS update of a ride.
 */
class RouteMatcher(
    private val route: RouteResult,
    mandatoryWaypoints: List<GeoPoint> = emptyList(),
    private val config: RouteMatcherConfig = RouteMatcherConfig(),
) {
    private data class Edge(
        val start: GeoPoint,
        val end: GeoPoint,
        val segmentIndex: Int,
        val startDistanceMeters: Double,
        val lengthMeters: Double,
    )

    private data class Projection(
        val point: GeoPoint,
        val edge: Edge,
        val fraction: Double,
        val distanceMeters: Double,
    ) {
        val routeDistanceMeters: Double
            get() = edge.startDistanceMeters + edge.lengthMeters * fraction
    }

    private data class WaypointPosition(
        val point: GeoPoint,
        val routeDistanceMeters: Double,
    )

    private val edges: List<Edge>
    private val routeLengthMeters: Double
    private val waypointPositions: List<WaypointPosition>

    init {
        val built = mutableListOf<Edge>()
        var cumulative = 0.0
        route.segments.forEach { segment ->
            segment.points.zipWithNext().forEach { (start, end) ->
                val length = GeoMath.distanceMeters(start, end)
                if (length > MIN_EDGE_METERS) {
                    built += Edge(start, end, segment.index, cumulative, length)
                    cumulative += length
                }
            }
        }
        require(built.isNotEmpty()) { "Cannot match against a route with no usable geometry" }
        edges = built
        routeLengthMeters = cumulative
        waypointPositions = mandatoryWaypoints.map { waypoint ->
            WaypointPosition(waypoint, closestProjection(waypoint).routeDistanceMeters)
        }
    }

    fun match(location: GeoPoint): RouteMatch {
        val projection = closestProjection(location)
        val status = when {
            projection.distanceMeters <= config.onRouteThresholdMeters -> RouteMatchStatus.ON_ROUTE
            projection.distanceMeters < config.offRouteThresholdMeters -> RouteMatchStatus.DRIFTING
            else -> RouteMatchStatus.OFF_ROUTE
        }
        val along = projection.routeDistanceMeters.coerceIn(0.0, routeLengthMeters)
        val nextWaypoint = waypointPositions.firstOrNull { waypoint ->
            val clearlyPassed = waypoint.routeDistanceMeters < along - config.waypointReachedThresholdMeters
            val reachedNow = GeoMath.distanceMeters(location, waypoint.point) <= config.waypointReachedThresholdMeters
            !clearlyPassed && !reachedNow
        }?.point

        return RouteMatch(
            snappedPosition = projection.point,
            distanceFromRouteMeters = projection.distanceMeters,
            distanceAlongRouteMeters = along,
            distanceRemainingMeters = (routeLengthMeters - along).coerceAtLeast(0.0),
            segmentIndex = projection.edge.segmentIndex,
            status = status,
            nextMandatoryWaypoint = nextWaypoint,
        )
    }

    private fun closestProjection(location: GeoPoint): Projection = edges.minBy { edge ->
        project(location, edge).distanceMeters
    }.let { project(location, it) }

    private fun project(location: GeoPoint, edge: Edge): Projection {
        val metersPerDegreeLatitude = EARTH_RADIUS_METERS * PI / 180.0
        val metersPerDegreeLongitude = metersPerDegreeLatitude * cos(location.latitude * PI / 180.0)
        val ax = longitudeDelta(edge.start.longitude, location.longitude) * metersPerDegreeLongitude
        val ay = (edge.start.latitude - location.latitude) * metersPerDegreeLatitude
        val bx = longitudeDelta(edge.end.longitude, location.longitude) * metersPerDegreeLongitude
        val by = (edge.end.latitude - location.latitude) * metersPerDegreeLatitude
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val fraction = if (lengthSquared <= 1e-9) 0.0 else
            (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val px = ax + dx * fraction
        val py = ay + dy * fraction
        val elevation = interpolateElevation(edge.start.elevationMeters, edge.end.elevationMeters, fraction)
        return Projection(
            point = GeoPoint(
                latitude = edge.start.latitude + (edge.end.latitude - edge.start.latitude) * fraction,
                longitude = normalizeLongitude(edge.start.longitude +
                    shortestLongitudeDelta(edge.start.longitude, edge.end.longitude) * fraction),
                elevationMeters = elevation,
            ),
            edge = edge,
            fraction = fraction,
            distanceMeters = sqrt(px * px + py * py),
        )
    }

    private fun interpolateElevation(start: Double?, end: Double?, fraction: Double): Double? =
        if (start != null && end != null) start + (end - start) * fraction else start ?: end

    private fun longitudeDelta(value: Double, reference: Double): Double =
        shortestLongitudeDelta(reference, value)

    private fun shortestLongitudeDelta(from: Double, to: Double): Double {
        var delta = to - from
        while (delta > 180.0) delta -= 360.0
        while (delta < -180.0) delta += 360.0
        return delta
    }

    private fun normalizeLongitude(value: Double): Double {
        var normalized = value
        while (normalized > 180.0) normalized -= 360.0
        while (normalized < -180.0) normalized += 360.0
        return normalized
    }

    companion object {
        private const val EARTH_RADIUS_METERS = 6_371_008.8
        private const val MIN_EDGE_METERS = 0.01
    }
}
