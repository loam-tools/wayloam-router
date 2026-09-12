package tools.loam.wayloam.router.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tools.loam.wayloam.router.api.*

/**
 * Rejoins several candidate points ahead on the old route but recalculates the complete affected
 * future route. Unlike boundary suffix reuse, no distance/elevation/duration is estimated from a
 * clipped geometry fraction.
 */
class ExactRouteRerouter(
    private val router: WayloamRouter,
    private val candidateOffsetsMeters: List<Double> = listOf(500.0, 2_000.0, 5_000.0, 10_000.0),
) {
    init {
        require(candidateOffsetsMeters.isNotEmpty())
        require(candidateOffsetsMeters.all { it > 0.0 && it.isFinite() })
        require(candidateOffsetsMeters == candidateOffsetsMeters.sorted())
    }

    suspend fun reroute(
        originalRequest: RouteRequest,
        originalResult: RouteResult,
        current: GeoPoint,
        onEvent: (RoutingEvent) -> Unit = {},
    ): RouteResult {
        val mandatory = originalRequest.via + originalRequest.end
        val matcher = RouteMatcher(originalResult, mandatory)
        val currentMatch = matcher.match(current)
        val viaPositions = originalRequest.via.map { waypoint ->
            waypoint to matcher.match(waypoint).distanceAlongRouteMeters
        }
        val destinationDistance = matcher.match(originalRequest.end).distanceAlongRouteMeters

        val candidateDistances = candidateOffsetsMeters
            .map { currentMatch.distanceAlongRouteMeters + it }
            .filter { it < destinationDistance - 5.0 }
            .toMutableList()
        if (candidateDistances.isEmpty() && destinationDistance > currentMatch.distanceAlongRouteMeters + 5.0) {
            candidateDistances += destinationDistance
        }

        var lastFailure: RoutingException? = null
        for (candidateDistance in candidateDistances) {
            currentCoroutineContext().ensureActive()
            val candidate = pointAtDistance(originalResult, candidateDistance)
            val orderedVia = buildList {
                viaPositions
                    .filter { (_, distance) -> distance > currentMatch.distanceAlongRouteMeters + 5.0 && distance < candidateDistance - 5.0 }
                    .forEach { (point, _) -> add(point) }
                if (candidateDistance < destinationDistance - 5.0) add(candidate)
                viaPositions
                    .filter { (_, distance) -> distance >= candidateDistance - 5.0 }
                    .forEach { (point, _) -> if (point !in this) add(point) }
            }
            try {
                return router.route(
                    originalRequest.copy(
                        start = current,
                        via = orderedVia,
                        timeoutMillis = originalRequest.timeoutMillis,
                    ),
                    onEvent,
                )
            } catch (error: RoutingException) {
                if (error.code !in RETRYABLE) throw error
                lastFailure = error
            }
        }

        throw RoutingException(
            lastFailure?.code ?: RoutingFailureCode.NO_ROUTE,
            "No exact forward rejoin candidate could be routed",
            lastFailure,
        )
    }

    private fun pointAtDistance(route: RouteResult, distanceMeters: Double): GeoPoint {
        var travelled = 0.0
        route.points.zipWithNext().forEach { (start, end) ->
            val edge = GeoMath.distanceMeters(start, end)
            if (travelled + edge >= distanceMeters && edge > 0.0) {
                val fraction = ((distanceMeters - travelled) / edge).coerceIn(0.0, 1.0)
                return GeoPoint(
                    latitude = start.latitude + (end.latitude - start.latitude) * fraction,
                    longitude = interpolateLongitude(start.longitude, end.longitude, fraction),
                    elevationMeters = if (start.elevationMeters != null && end.elevationMeters != null)
                        start.elevationMeters + (end.elevationMeters - start.elevationMeters) * fraction
                    else start.elevationMeters ?: end.elevationMeters,
                )
            }
            travelled += edge
        }
        return route.points.last()
    }

    private fun interpolateLongitude(start: Double, end: Double, fraction: Double): Double {
        var delta = end - start
        while (delta > 180.0) delta -= 360.0
        while (delta < -180.0) delta += 360.0
        var value = start + delta * fraction
        while (value > 180.0) value -= 360.0
        while (value < -180.0) value += 360.0
        return value
    }

    companion object {
        private val RETRYABLE = setOf(
            RoutingFailureCode.NO_ROUTE,
            RoutingFailureCode.TIMEOUT,
            RoutingFailureCode.DISCONNECTED_ROUTE,
        )
    }
}
