package tools.loam.wayloam.router.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class RoutingDiagnosticBundleTest {
    @Test
    fun successExportOmitsCoordinatesAndGeometry() {
        val start = GeoPoint(49.3988, 8.6724)
        val end = GeoPoint(50.1109, 8.6821)
        val request = RouteRequest(start, end, RouteProfile.TOURING)
        val segment = RouteSegment(0, start, end, listOf(start, end), RouteMetrics(90_000, 500, 450, 14_000), "brouter/test")
        val result = RouteResult(
            points = listOf(start, end),
            metrics = segment.metrics,
            segments = listOf(segment),
            engine = "brouter/test",
            diagnostics = RouteDiagnostics(
                elapsedMillis = 1_234,
                firstSectionMillis = 500,
                engineCalls = 1,
                plannerVersion = "planner-1",
                engineVersion = "brouter:1",
                profileVersion = "profiles-1",
                dataVersion = "dataset-1",
            ),
        )

        val output = StringWriter()
        RoutingDiagnosticsExport.writeJson(RoutingDiagnosticsExport.success(request, result), output)
        val json = output.toString()

        assertTrue(json.contains("\"status\":\"success\""))
        assertTrue(json.contains("\"data_version\":\"dataset-1\""))
        assertTrue(json.contains("\"distance_meters\":90000"))
        assertFalse(json.contains("49.3988"))
        assertFalse(json.contains("8.6724"))
        assertFalse(json.contains("points"))
    }

    @Test
    fun failureExportUsesStableFailureCodeWithoutLocation() {
        val request = RouteRequest(GeoPoint(49.0, 8.0), GeoPoint(50.0, 9.0), RouteProfile.BIKEPACKING)
        val error = RoutingException(RoutingFailureCode.MISSING_DATA, "missing E5_N45.rd5", sectionIndex = 2)
        val output = StringWriter()

        RoutingDiagnosticsExport.writeJson(RoutingDiagnosticsExport.failure(request, error), output)
        val json = output.toString()

        assertTrue(json.contains("\"status\":\"failure\""))
        assertTrue(json.contains("\"failure_code\":\"MISSING_DATA\""))
        assertTrue(json.contains("\"failure_section_index\":2"))
        assertFalse(json.contains("E5_N45"))
        assertFalse(json.contains("49.0"))
    }
}
