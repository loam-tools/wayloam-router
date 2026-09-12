package tools.loam.wayloam.router.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tools.loam.wayloam.router.api.GeoPoint
import tools.loam.wayloam.router.api.RouteMetrics
import tools.loam.wayloam.router.api.RouteProfile
import tools.loam.wayloam.router.api.RouteRequest
import tools.loam.wayloam.router.api.RouteSegment
import tools.loam.wayloam.router.api.RoutingEvent
import kotlin.math.roundToLong

class LongRouteCoordinatorTest {
    @Test
    fun coordinatorStitchesSectionsAndCachesCompletedRoute() = runBlocking {
        val cache = InMemoryRouteCache()
        val engine = FakeSectionEngine()
        val coordinator = LongRouteCoordinator(
            sectionEngine = engine,
            cache = cache,
            profileVersion = "test-profile-1",
            dataVersion = "test-data-1",
        )
        val request = RouteRequest(
            start = GeoPoint(49.3988, 8.6724),
            end = GeoPoint(55.6761, 12.5683),
            profile = RouteProfile.TOURING,
            maxSectionDistanceKm = 180.0,
        )
        val events = mutableListOf<RoutingEvent>()

        val first = coordinator.route(request, events::add)
        val callsAfterFirst = engine.calls
        val second = coordinator.route(request) { }

        assertTrue(first.segments.size > 1)
        assertTrue(first.metrics.distanceMeters > 0)
        assertFalse(first.cacheHit)
        assertTrue(second.cacheHit)
        assertEquals(callsAfterFirst, engine.calls)
        assertTrue(events.first() is RoutingEvent.Started)
        assertTrue(events.last() is RoutingEvent.Completed)
        assertEquals(first.points.first(), request.start)
        assertEquals(first.points.last(), request.end)
    }

    private class FakeSectionEngine : RouteSectionEngine {
        override val engineId: String = "fake"
        override val engineVersion: String = "1"
        var calls: Int = 0
            private set

        override suspend fun routeSection(
            index: Int,
            start: GeoPoint,
            end: GeoPoint,
            profile: RouteProfile,
        ): RouteSegment {
            calls++
            return RouteSegment(
                index = index,
                start = start,
                end = end,
                points = listOf(start, end),
                metrics = RouteMetrics(
                    distanceMeters = GeoMath.distanceMeters(start, end).roundToLong(),
                    durationSeconds = 1,
                ),
                engine = "$engineId/$engineVersion",
            )
        }
    }
}
