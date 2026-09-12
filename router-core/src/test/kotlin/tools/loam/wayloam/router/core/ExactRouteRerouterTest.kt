package tools.loam.wayloam.router.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tools.loam.wayloam.router.api.*
import kotlin.math.roundToLong

class ExactRouteRerouterTest {
    @Test
    fun preservesRemainingMandatoryStopsAndPreferences() = runBlocking {
        val start = GeoPoint(49.4000, 8.6800, 100.0)
        val via = GeoPoint(49.4200, 8.6800, 120.0)
        val end = GeoPoint(49.4400, 8.6800, 140.0)
        val originalRequest = RouteRequest(
            start = start,
            end = end,
            profile = RouteProfile.BIKEPACKING,
            via = listOf(via),
            preferences = RoutePreferences.forProfile(RouteProfile.BIKEPACKING).copy(allowFerries = false),
        )
        val originalResult = resultFor(originalRequest)
        val current = GeoPoint(49.4050, 8.6810, 105.0)
        var rerouteRequest: RouteRequest? = null
        val router = WayloamRouter { request, _ ->
            rerouteRequest = request
            resultFor(request)
        }

        val result = ExactRouteRerouter(router, listOf(500.0)).reroute(
            originalRequest = originalRequest,
            originalResult = originalResult,
            current = current,
        )

        val submitted = rerouteRequest ?: error("Expected reroute request")
        assertEquals(current, submitted.start)
        assertEquals(end, submitted.end)
        assertEquals(originalRequest.preferences, submitted.preferences)
        assertTrue(via in submitted.via)
        assertTrue(submitted.via.size >= 2) // exact rejoin candidate + remaining mandatory via
        assertEquals(current, result.points.first())
        assertEquals(end, result.points.last())
    }

    private fun resultFor(request: RouteRequest): RouteResult {
        val points = listOf(request.start) + request.via + request.end
        val segments = points.zipWithNext().mapIndexed { index, (start, end) ->
            RouteSegment(
                index = index,
                start = start,
                end = end,
                points = listOf(start, end),
                metrics = RouteMetrics(GeoMath.distanceMeters(start, end).roundToLong()),
                engine = "test",
            )
        }
        return RouteStitcher.stitch(segments, "test")
    }
}
