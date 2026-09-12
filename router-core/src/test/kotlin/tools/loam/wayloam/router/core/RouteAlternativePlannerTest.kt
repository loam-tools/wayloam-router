package tools.loam.wayloam.router.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tools.loam.wayloam.router.api.*

class RouteAlternativePlannerTest {
    @Test
    fun keepsDistinctAlternativeAndFiltersDuplicate() = runBlocking {
        val start = GeoPoint(49.4000, 8.6800)
        val end = GeoPoint(49.4100, 8.6800)
        val router = WayloamRouter { request, _ ->
            val middle = if (request.profile == RouteProfile.DIRECT)
                GeoPoint(49.4050, 8.6820)
            else GeoPoint(49.4050, 8.6800)
            result(listOf(request.start, middle, request.end))
        }
        val planner = RouteAlternativePlanner(router)

        val alternatives = planner.calculate(RouteRequest(start, end, RouteProfile.TOURING))

        assertEquals(2, alternatives.size)
        assertEquals(RouteAlternativeKind.RECOMMENDED, alternatives[0].kind)
        assertEquals(RouteAlternativeKind.FASTER, alternatives[1].kind)
        assertTrue(alternatives[1].overlapWithRecommended < 0.94)
    }

    private fun result(points: List<GeoPoint>): RouteResult {
        val segments = points.zipWithNext().mapIndexed { index, (start, end) ->
            RouteSegment(index, start, end, listOf(start, end), RouteMetrics(1_000), "test")
        }
        return RouteStitcher.stitch(segments, "test")
    }
}
