package tools.loam.wayloam.router.core

import tools.loam.wayloam.router.api.*
import kotlin.math.ceil
import kotlin.math.min

/** Calculates useful alternatives and removes variants that substantially duplicate the recommended route. */
class RouteAlternativePlanner(
    private val router: WayloamRouter,
    private val maximumOverlap: Double = 0.94,
    private val overlapDistanceMeters: Double = 40.0,
) {
    init {
        require(maximumOverlap in 0.5..1.0 && maximumOverlap.isFinite())
        require(overlapDistanceMeters > 0.0 && overlapDistanceMeters.isFinite())
    }

    suspend fun calculate(
        request: RouteRequest,
        onEvent: (RouteAlternativeKind, RoutingEvent) -> Unit = { _, _ -> },
    ): List<RouteAlternative> {
        val recommended = router.route(request) { onEvent(RouteAlternativeKind.RECOMMENDED, it) }
        val alternatives = mutableListOf(
            RouteAlternative(RouteAlternativeKind.RECOMMENDED, recommended, 1.0)
        )

        val variants = listOf(
            RouteAlternativeKind.FASTER to request.copy(
                profile = RouteProfile.DIRECT,
                preferences = request.preferences.copy(
                    avoidMajorRoads = false,
                    surfacePreference = SurfacePreference.AVOID_UNPAVED,
                    trackTolerance = TrackTolerance.LOW,
                    trafficSensitivity = min(request.preferences.trafficSensitivity, 0.3),
                    hillSensitivity = min(request.preferences.hillSensitivity, 0.35),
                ),
            ),
            RouteAlternativeKind.QUIETER_SCENIC to request.copy(
                profile = RouteProfile.TOURING,
                preferences = request.preferences.copy(
                    avoidMajorRoads = true,
                    preferCycleways = true,
                    trafficSensitivity = maxOf(request.preferences.trafficSensitivity, 0.9),
                    hillSensitivity = maxOf(request.preferences.hillSensitivity, 0.5),
                ),
            ),
        )

        for ((kind, variantRequest) in variants) {
            val result = try {
                router.route(variantRequest) { onEvent(kind, it) }
            } catch (error: RoutingException) {
                if (error.code in OPTIONAL_FAILURES) continue else throw error
            }
            val overlap = overlapScore(recommended, result)
            if (overlap <= maximumOverlap) {
                alternatives += RouteAlternative(kind, result, overlap)
            }
        }
        return alternatives
    }

    fun overlapScore(first: RouteResult, second: RouteResult): Double {
        val firstMatcher = RouteMatcher(first)
        val secondMatcher = RouteMatcher(second)
        val secondOnFirst = sample(second.points).count {
            firstMatcher.match(it).distanceFromRouteMeters <= overlapDistanceMeters
        }.toDouble() / sample(second.points).size
        val firstOnSecond = sample(first.points).count {
            secondMatcher.match(it).distanceFromRouteMeters <= overlapDistanceMeters
        }.toDouble() / sample(first.points).size
        return ((secondOnFirst + firstOnSecond) / 2.0).coerceIn(0.0, 1.0)
    }

    private fun sample(points: List<GeoPoint>, maximumSamples: Int = 200): List<GeoPoint> {
        if (points.size <= maximumSamples) return points
        val stride = ceil(points.size.toDouble() / maximumSamples).toInt().coerceAtLeast(1)
        return buildList {
            var index = 0
            while (index < points.size) {
                add(points[index])
                index += stride
            }
            if (last() != points.last()) add(points.last())
        }
    }

    companion object {
        private val OPTIONAL_FAILURES = setOf(
            RoutingFailureCode.NO_ROUTE,
            RoutingFailureCode.TIMEOUT,
            RoutingFailureCode.DISCONNECTED_ROUTE,
        )
    }
}
