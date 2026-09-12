package tools.loam.wayloam.router.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tools.loam.wayloam.router.api.GeoPoint
import tools.loam.wayloam.router.api.RouteManeuver
import tools.loam.wayloam.router.api.RouteManeuverType
import tools.loam.wayloam.router.api.RouteMetrics
import tools.loam.wayloam.router.api.RouteSegment
import java.nio.file.Files

class RouteManeuverTest {
    private val a = GeoPoint(49.0, 8.0)
    private val b = GeoPoint(49.01, 8.01)
    private val c = GeoPoint(49.02, 8.02)

    @Test
    fun stitchOffsetsManeuversAcrossSections() {
        val first = RouteSegment(
            index = 0,
            start = a,
            end = b,
            points = listOf(a, b),
            metrics = RouteMetrics(1_000),
            engine = "fixture",
            maneuvers = listOf(
                RouteManeuver(RouteManeuverType.TURN_RIGHT, 1, b, 1_000.0, 2_000.0, 90),
            ),
        )
        val second = RouteSegment(
            index = 1,
            start = b,
            end = c,
            points = listOf(b, c),
            metrics = RouteMetrics(2_000),
            engine = "fixture",
            maneuvers = listOf(
                RouteManeuver(RouteManeuverType.ROUNDABOUT, 1, c, 2_000.0, 0.0, 45, 2),
            ),
        )

        val route = RouteStitcher.stitch(listOf(first, second), "fixture")

        assertEquals(3, route.points.size)
        assertEquals(listOf(1, 2), route.maneuvers.map { it.pointIndex })
        assertEquals(listOf(1_000.0, 3_000.0), route.maneuvers.map { it.distanceAlongRouteMeters })
        assertEquals(2, route.maneuvers.last().roundaboutExit)
    }

    @Test
    fun diskCachePreservesManeuversAndClearSweepsStaleArtifacts() {
        val root = Files.createTempDirectory("maneuver-cache")
        try {
            val segment = RouteSegment(
                index = 0,
                start = a,
                end = b,
                points = listOf(a, b),
                metrics = RouteMetrics(1_000),
                engine = "fixture",
                maneuvers = listOf(
                    RouteManeuver(RouteManeuverType.KEEP_LEFT, 1, b, 1_000.0, 500.0, -10),
                ),
            )
            val route = RouteStitcher.stitch(listOf(segment), "fixture")
            val cache = FileRouteCache(root)
            val key = "0".repeat(64)
            cache.put(key, route)

            assertEquals(route.maneuvers, cache.get(key)?.maneuvers)

            val stale = root.resolve("corrupt.route")
            val interrupted = root.resolve("route-interrupted.tmp")
            val unrelated = root.resolve("keep.txt")
            Files.write(stale, byteArrayOf(1, 2, 3))
            Files.write(interrupted, byteArrayOf(4, 5, 6))
            Files.write(unrelated, byteArrayOf(7))

            cache.clear()

            assertFalse(Files.exists(stale))
            assertFalse(Files.exists(interrupted))
            assertTrue(Files.exists(unrelated))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
