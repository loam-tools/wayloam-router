package tools.loam.wayloam.router.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tools.loam.wayloam.router.api.*
import kotlin.math.roundToLong

class TileAwareCacheTest {
    @Test
    fun changingOneRd5FileRecalculatesOnlyDependentSection() = runBlocking {
        val cache = InMemoryRouteCache()
        val fingerprints = mutableMapOf(
            "W5_N40.rd5" to "spain-v1",
            "E10_N50.rd5" to "germany-v1",
        )
        val engine = DependencyEngine()
        val coordinator = LongRouteCoordinator(
            sectionEngine = engine,
            sectionPlanner = UserWaypointSectionPlanner(),
            cache = cache,
            profileVersion = "profile-1",
            dataVersion = "rd5-schema-1",
            dataDependencyFingerprint = fingerprints::get,
        )
        val middle = GeoPoint(48.8566, 2.3522)
        val request = RouteRequest(
            start = GeoPoint(41.1579, -8.6291),
            via = listOf(middle),
            end = GeoPoint(52.5200, 13.4050),
            profile = RouteProfile.TOURING,
        )

        val first = coordinator.route(request) {}
        assertEquals(2, engine.calls)
        assertFalse(first.cacheHit)

        val warm = coordinator.route(request) {}
        assertTrue(warm.cacheHit)
        assertEquals(2, engine.calls)

        fingerprints["E10_N50.rd5"] = "germany-v2"
        val afterGermanyUpdate = coordinator.route(request) {}

        assertFalse(afterGermanyUpdate.cacheHit)
        assertEquals("Only the Germany-dependent section should reroute", 3, engine.calls)
        assertEquals(1, afterGermanyUpdate.diagnostics.sectionCacheHits)
        assertTrue(afterGermanyUpdate.diagnostics.staleCacheEntries >= 2)
    }

    private class DependencyEngine : RouteSectionEngine {
        override val engineId = "dependency-fake"
        override val engineVersion = "1"
        var calls = 0
            private set

        override suspend fun routeSection(
            index: Int,
            start: GeoPoint,
            end: GeoPoint,
            profile: RouteProfile,
        ): RouteSegment {
            calls++
            val dependency = if (index == 0) "W5_N40.rd5" to "spain-v1"
            else "E10_N50.rd5" to if (calls <= 2) "germany-v1" else "germany-v2"
            return RouteSegment(
                index = index,
                start = start,
                end = end,
                points = listOf(start, end),
                metrics = RouteMetrics(GeoMath.distanceMeters(start, end).roundToLong()),
                engine = "$engineId/$engineVersion",
                dataDependencies = mapOf(dependency),
            )
        }
    }
}
