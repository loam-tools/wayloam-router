package tools.loam.wayloam.router.core

import org.junit.Assert.*
import org.junit.Test
import tools.loam.wayloam.router.api.*

class RouteAnalyzerTest {
    private fun climbingRoute(): RouteResult {
        val points = listOf(
            GeoPoint(49.4000, 8.6800, 100.0),
            GeoPoint(49.4050, 8.6800, 160.0),
            GeoPoint(49.4100, 8.6800, 220.0),
        )
        val segment = RouteSegment(
            index = 0,
            start = points.first(),
            end = points.last(),
            points = points,
            metrics = RouteMetrics(1_120, 120, 0, 500),
            engine = "test",
        )
        return RouteResult(points, segment.metrics, listOf(segment), "test")
    }

    @Test fun derivesClimbsAndSteepClimbWarningsFromElevation() {
        val analysis = RouteAnalyzer.analyze(climbingRoute())

        assertEquals(1, analysis.climbs.size)
        val climb = analysis.climbs.single()
        assertTrue(climb.elevationGainMeters >= 120.0)
        assertTrue(climb.lengthMeters >= 1_000.0)
        assertTrue(climb.averageGradientPercent > 8.0)
        assertTrue(analysis.warnings.any { it.type == RouteWarningType.STEEP_CLIMB })
    }

    @Test fun producesSurfaceAndCyclingWarningsOnlyFromProvidedAnnotations() {
        val annotation = RouteAnnotation(
            startDistanceMeters = 0.0,
            endDistanceMeters = 1_000.0,
            surface = SurfaceType.GRAVEL,
            roadClass = RoadClass.TRACK,
            smoothness = Smoothness.BAD,
            trafficStress = TrafficStress.LOW,
            unpaved = true,
        )

        val analysis = RouteAnalyzer.analyze(climbingRoute(), listOf(annotation))

        assertEquals(1_000.0, analysis.surfaceSummary.distanceBySurfaceMeters[SurfaceType.GRAVEL]!!, 0.01)
        assertTrue(analysis.warnings.any { it.type == RouteWarningType.ROUGH_SURFACE })
        assertFalse(analysis.warnings.any { it.type == RouteWarningType.BUSY_ROAD })
    }

    @Test fun keepsSurfaceUnknownWhenBackendHasNotProvenIt() {
        val analysis = RouteAnalyzer.analyze(climbingRoute())

        assertEquals(0.0, analysis.surfaceSummary.knownDistanceMeters, 0.01)
        assertTrue(analysis.surfaceSummary.unknownDistanceMeters > 1_000.0)
        assertTrue(analysis.surfaceSummary.distanceBySurfaceMeters.isEmpty())
    }
}
