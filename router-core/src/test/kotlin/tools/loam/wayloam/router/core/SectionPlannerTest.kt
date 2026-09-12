package tools.loam.wayloam.router.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tools.loam.wayloam.router.api.GeoPoint
import tools.loam.wayloam.router.api.RouteProfile
import tools.loam.wayloam.router.api.RouteRequest

class SectionPlannerTest {
    private val planner = FixedDistanceSectionPlanner()

    @Test
    fun shortRouteStaysSingleSection() {
        val request = RouteRequest(
            start = GeoPoint(49.3988, 8.6724),
            end = GeoPoint(50.1109, 8.6821),
            profile = RouteProfile.TOURING,
        )

        val sections = planner.plan(request)

        assertEquals(1, sections.size)
        assertEquals(request.start, sections.first().start)
        assertEquals(request.end, sections.last().end)
    }

    @Test
    fun longRouteIsSplitIntoBoundedSections() {
        val request = RouteRequest(
            start = GeoPoint(49.3988, 8.6724),
            end = GeoPoint(59.9139, 10.7522),
            profile = RouteProfile.TOURING,
            maxSectionDistanceKm = 220.0,
        )

        val sections = planner.plan(request)

        assertTrue(sections.size > 4)
        assertEquals(request.start, sections.first().start)
        assertEquals(request.end, sections.last().end)
        sections.forEachIndexed { index, section ->
            assertEquals(index, section.index)
            assertTrue(GeoMath.distanceMeters(section.start, section.end) <= 220_500.0)
            if (index > 0) assertEquals(sections[index - 1].end, section.start)
        }
    }

    @Test
    fun explicitViaPointRemainsASectionBoundary() {
        val via = GeoPoint(53.5511, 9.9937)
        val request = RouteRequest(
            start = GeoPoint(49.3988, 8.6724),
            end = GeoPoint(55.6761, 12.5683),
            via = listOf(via),
            profile = RouteProfile.BIKEPACKING,
            maxSectionDistanceKm = 180.0,
        )

        val sections = planner.plan(request)

        assertTrue(sections.any { it.end == via })
        assertTrue(sections.any { it.start == via })
    }
}
