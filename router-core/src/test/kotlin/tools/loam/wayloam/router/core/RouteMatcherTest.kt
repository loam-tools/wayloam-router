package tools.loam.wayloam.router.core

import org.junit.Assert.*
import org.junit.Test
import tools.loam.wayloam.router.api.*

class RouteMatcherTest {
    private fun route(): RouteResult {
        val points = listOf(
            GeoPoint(49.4000, 8.6800, 100.0),
            GeoPoint(49.4000, 8.6900, 105.0),
            GeoPoint(49.4100, 8.6900, 120.0),
        )
        return RouteResult(
            points = points,
            metrics = RouteMetrics(2_000, 20, 0, 600),
            segments = listOf(
                RouteSegment(0, points[0], points[1], points.subList(0, 2), RouteMetrics(800), "test"),
                RouteSegment(1, points[1], points[2], points.subList(1, 3), RouteMetrics(1_200), "test"),
            ),
            engine = "test",
        )
    }

    @Test fun matchesPositionAndReportsCurrentSegment() {
        val matcher = RouteMatcher(route())
        val match = matcher.match(GeoPoint(49.40005, 8.6850))

        assertEquals(RouteMatchStatus.ON_ROUTE, match.status)
        assertEquals(0, match.segmentIndex)
        assertTrue(match.distanceFromRouteMeters < 10.0)
        assertTrue(match.distanceAlongRouteMeters > 300.0)
        assertTrue(match.distanceRemainingMeters > 1_000.0)
    }

    @Test fun distinguishesDriftingFromOffRoute() {
        val matcher = RouteMatcher(route(), config = RouteMatcherConfig(
            onRouteThresholdMeters = 20.0,
            offRouteThresholdMeters = 80.0,
        ))

        val drifting = matcher.match(GeoPoint(49.40045, 8.6850))
        val offRoute = matcher.match(GeoPoint(49.4020, 8.6850))

        assertEquals(RouteMatchStatus.DRIFTING, drifting.status)
        assertEquals(RouteMatchStatus.OFF_ROUTE, offRoute.status)
    }

    @Test fun advancesMandatoryWaypointAfterItIsReached() {
        val via = GeoPoint(49.4000, 8.6900)
        val destination = GeoPoint(49.4100, 8.6900)
        val matcher = RouteMatcher(route(), listOf(via, destination))

        assertEquals(via, matcher.match(GeoPoint(49.4000, 8.6850)).nextMandatoryWaypoint)
        assertEquals(destination, matcher.match(GeoPoint(49.4000, 8.6900)).nextMandatoryWaypoint)
    }
}
