package tools.loam.wayloam.router.brouter

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tools.loam.wayloam.router.api.GeoPoint
import tools.loam.wayloam.router.api.RouteMetrics
import tools.loam.wayloam.router.api.RouteProfile

class BRouterAdapterTest {
    @Test
    fun presetsStayPinnedToExpectedUpstreamProfiles() {
        assertEquals("fastbike", WayloamProfiles.DIRECT.baseProfile)
        assertEquals("trekking", WayloamProfiles.TOURING.baseProfile)
        assertEquals("trekking", WayloamProfiles.BIKEPACKING.baseProfile)
        assertEquals("0", WayloamProfiles.BIKEPACKING.parameters["allow_steps"])
        assertEquals("1", WayloamProfiles.BIKEPACKING.parameters["avoid_unsafe"])
        assertTrue(WayloamProfiles.BIKEPACKING.versionKey.startsWith(BRouterBaseline.RELEASE))

        listOf(
            WayloamProfiles.DIRECT,
            WayloamProfiles.TOURING,
            WayloamProfiles.BIKEPACKING,
        ).forEach { preset ->
            assertTrue(
                "BRouter override values must remain numeric: ${preset.parameters}",
                preset.parameters.values.all { it.toDoubleOrNull() != null },
            )
        }
    }

    @Test
    fun sectionEngineTranslatesBackendResultIntoLoamModel() = runBlocking {
        val start = GeoPoint(49.3988, 8.6724)
        val end = GeoPoint(50.1109, 8.6821)
        var receivedPreset: BRouterProfilePreset? = null
        val backend = EmbeddedBRouterBackend { request ->
            receivedPreset = request.preset
            BRouterBackendResult(
                points = listOf(request.start, request.end),
                metrics = RouteMetrics(distanceMeters = 90_000, durationSeconds = 18_000),
            )
        }
        val engine = BRouterSectionEngine(backend)

        val result = engine.routeSection(0, start, end, RouteProfile.TOURING)

        assertEquals(WayloamProfiles.TOURING, receivedPreset)
        assertEquals(90_000, result.metrics.distanceMeters)
        assertEquals(start, result.points.first())
        assertEquals(end, result.points.last())
        assertFalse(result.engine.isBlank())
    }
}
