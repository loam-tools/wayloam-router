package tools.loam.wayloam.router.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import tools.loam.wayloam.router.api.*
import java.nio.file.Files
import java.io.StringWriter

class RoutingReliabilityTest {
    private val a = GeoPoint(49.0, 8.0)
    private val b = GeoPoint(50.0, 8.0)
    private val c = GeoPoint(51.0, 8.0)
    private val request = RouteRequest(a, c, RouteProfile.TOURING, via = listOf(b))

    private class Engine(val action: suspend (Int, GeoPoint, GeoPoint) -> RouteSegment) : RouteSectionEngine {
        override val engineId = "fixture"
        override val engineVersion = "1"
        var calls = 0
        override suspend fun routeSection(index: Int, start: GeoPoint, end: GeoPoint, profile: RouteProfile): RouteSegment {
            calls++
            return action(index, start, end)
        }
    }
    private fun segment(i: Int, start: GeoPoint, end: GeoPoint) = RouteSegment(i, start, end,
        listOf(start, end), RouteMetrics(1_000, 10, 5, 100), "fixture/1")

    @Test fun diskCacheReopensWithoutEngineAndRejectsCorruption() = runBlocking {
        val root = Files.createTempDirectory("route-cache-test")
        try {
            val engine = Engine(::segment)
            val first = LongRouteCoordinator(engine, cache = FileRouteCache(root)).route(request) {}
            val second = LongRouteCoordinator(engine, cache = FileRouteCache(root)).route(request) {}
            assertEquals(2, engine.calls)
            assertTrue(second.cacheHit)
            assertEquals(first.points, second.points)
            val key = RouteCacheKey.build(request, RouteIdentity("fixture:1", "1", "unknown", "user-waypoints-1"))
            Files.write(root.resolve("$key.route"), byteArrayOf(0, 1, 2))
            val repaired = LongRouteCoordinator(engine, cache = FileRouteCache(root)).route(request) {}
            assertFalse(repaired.cacheHit)
            assertEquals(2, engine.calls) // intact section entries repair the whole route
            assertEquals(2, repaired.diagnostics.sectionCacheHits)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun retryAfterFailureReusesCompletedSections() = runBlocking {
        var fail = true
        val engine = Engine { i, start, end ->
            if (i == 1 && fail) throw RoutingException(RoutingFailureCode.NO_ROUTE, "island")
            segment(i, start, end)
        }
        val coordinator = LongRouteCoordinator(engine, cache = InMemoryRouteCache())
        assertTrue(runCatching { coordinator.route(request) {} }.exceptionOrNull() is RoutingException)
        fail = false
        val result = coordinator.route(request) {}
        assertEquals(3, engine.calls)
        assertEquals(1, result.diagnostics.sectionCacheHits)
        assertEquals(c, result.points.last())
    }

    @Test fun generatedAnchorCanBeSkippedButViaCannot() = runBlocking {
        val engine = Engine { i, start, end ->
            if (end == b) throw RoutingException(RoutingFailureCode.NO_ROUTE, "island")
            segment(i, start, end)
        }
        val planner = SectionPlanner { listOf(SectionSpec(0, a, b, false), SectionSpec(1, b, c)) }
        val result = LongRouteCoordinator(engine, planner).route(request.copy(via = emptyList())) {}
        assertEquals(listOf(a, c), result.points)
        assertEquals(1, result.diagnostics.skippedAnchors)
        val failure = runCatching { LongRouteCoordinator(engine).route(request) {} }.exceptionOrNull()
        assertTrue(failure is RoutingException)
        assertEquals(0, (failure as RoutingException).sectionIndex)
    }

    @Test fun alternativeAnchorCarriesItsActualEndpointIntoTheNextSection() = runBlocking {
        val alternative = GeoPoint(50.0, 8.01)
        val engine = Engine { i, start, end ->
            if (end == b) throw RoutingException(RoutingFailureCode.NO_ROUTE, "island")
            segment(i, start, end)
        }
        val planner = SectionPlanner { listOf(SectionSpec(0, a, b, false, listOf(b, alternative)), SectionSpec(1, b, c)) }
        val result = LongRouteCoordinator(engine, planner).route(request.copy(via = emptyList())) {}
        assertEquals(listOf(a, alternative, c), result.points)
        assertEquals(alternative, result.segments.last().start)
        assertEquals(1, result.diagnostics.retries)
        assertEquals(3, result.diagnostics.engineCalls)
    }

    @Test fun cancellationDoesNotCommitTheInterruptedSection() = runBlocking {
        val cache = InMemoryRouteCache()
        val started = CompletableDeferred<Unit>()
        val engine = Engine { i, start, end ->
            started.complete(Unit)
            delay(10_000)
            segment(i, start, end)
        }
        val job = launch { LongRouteCoordinator(engine, cache = cache).route(request) {} }
        started.await()
        job.cancelAndJoin()
        val replacement = Engine(::segment)
        val result = LongRouteCoordinator(replacement, cache = cache).route(request) {}
        assertEquals(2, replacement.calls)
        assertFalse(result.cacheHit)
    }

    @Test fun timeoutIsTypedAndExternalCancellationRemainsCancellation() = runBlocking {
        val engine = Engine { i, start, end -> delay(5_000); segment(i, start, end) }
        val error = runCatching { LongRouteCoordinator(engine).route(request.copy(timeoutMillis = 50)) {} }.exceptionOrNull()
        assertEquals(RoutingFailureCode.TIMEOUT, (error as RoutingException).code)
        val parent = runCatching { withTimeout(50) { LongRouteCoordinator(engine).route(request) {} } }.exceptionOrNull()
        assertTrue(parent is CancellationException)
    }

    @Test fun disconnectedGeometryIsRejectedAndSeamElevationDoesNotDuplicatePoint() {
        val first = segment(0, a, b)
        val second = segment(1, b.copy(elevationMeters = 12.0), c)
        assertEquals(3, RouteStitcher.stitch(listOf(first, second), "fixture").points.size)
        val disconnected = second.copy(points = listOf(GeoPoint(50.1, 8.0), c))
        val error = runCatching { RouteStitcher.stitch(listOf(first, disconnected), "fixture") }.exceptionOrNull()
        assertEquals(RoutingFailureCode.DISCONNECTED_ROUTE, (error as RoutingException).code)
    }

    @Test fun cacheIdentityIncludesPlannerAndVersionsButNotTimeBudget() {
        val identity = RouteIdentity("engine", "profiles", "data", "planner")
        assertEquals(RouteCacheKey.build(request, identity), RouteCacheKey.build(request.copy(timeoutMillis = 1), identity))
        assertNotEquals(RouteCacheKey.build(request, identity), RouteCacheKey.build(request, identity.copy(plannerVersion = "new")))
        assertNotEquals(RouteCacheKey.build(request, identity), RouteCacheKey.build(request, identity.copy(dataVersion = "new")))
        assertNotEquals(RouteCacheKey.build(request, identity), RouteCacheKey.build(request, identity.copy(profileVersion = "new")))
        // Length-prefixed fields avoid delimiter collisions in externally supplied version names.
        assertNotEquals(RouteCacheKey.build(request, identity.copy(engineVersion = "a|b", profileVersion = "c")),
            RouteCacheKey.build(request, identity.copy(engineVersion = "a", profileVersion = "b|c")))
    }

    @Test fun graphPlannerNeverPromotesUnmatchedSeedsAndPreservesUserStops() = runBlocking {
        val planner = GraphAwareSectionPlanner(GraphAnchorResolver { _, _ -> emptyList() }, "fixture")
        val planned = planner.plan(request.copy(maxSectionDistanceKm = 20.0))
        assertEquals(listOf(b, c), planned.map { it.end })
        assertTrue(planned.all { it.endIsUserWaypoint })
    }

    @Test fun reconnectionReusesExactForwardSuffix() = runBlocking {
        val original = RouteStitcher.stitch(listOf(segment(0, a, b), segment(1, b, c)), "fixture")
        val current = GeoPoint(50.01, 8.0)
        val calls = mutableListOf<RouteRequest>()
        val router = WayloamRouter { request, _ ->
            calls += request
            RouteStitcher.stitch(listOf(segment(0, request.start, request.end)), "fixture")
        }
        val result = PartialRerouter(router).reconnect(original, current, RouteProfile.TOURING, 1)
        assertEquals(1, calls.size)
        assertEquals(b, calls.single().end)
        assertEquals(c, result.points.last())
        assertEquals(2_000, result.metrics.distanceMeters)
        assertEquals(original.segments.last().metrics, result.segments.last().metrics)
    }

    @Test fun diskCacheEvictsAndDoesNotTouchOtherFiles() {
        val root = Files.createTempDirectory("route-cache-eviction")
        try {
            val cache = FileRouteCache(root, 1024, 1024)
            val unrelated = root.resolve("keep.txt")
            Files.write(unrelated, byteArrayOf(42))
            val route = RouteStitcher.stitch(listOf(segment(0, a, b)), "fixture")
            repeat(10) { cache.put("%064x".format(it), route) }
            assertTrue(cache.sizeBytes() <= 1024)
            assertNotNull(cache.get("%064x".format(9)))
            cache.clear()
            assertEquals(0, cache.sizeBytes())
            assertTrue(Files.exists(unrelated))
            assertTrue(runCatching { cache.get("../escape") }.isFailure)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun gpxExportEscapesNamesAndPreservesElevation() {
        val writer = StringWriter()
        RouteExport.writeGpx(RouteStitcher.stitch(listOf(segment(0, a.copy(elevationMeters = 12.5), b)), "fixture"),
            writer, "A & <B>")
        assertTrue(writer.toString().contains("A &amp; &lt;B&gt;"))
        assertTrue(writer.toString().contains("<ele>12.5</ele>"))
    }
}
