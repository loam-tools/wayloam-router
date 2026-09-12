package tools.loam.wayloam.router.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tools.loam.wayloam.router.api.*

class LongRouteCoordinator(
    private val sectionEngine: RouteSectionEngine,
    private val sectionPlanner: SectionPlanner = UserWaypointSectionPlanner(),
    private val cache: RouteCache = NoRouteCache,
    private val profileVersion: String = sectionEngine.engineVersion,
    private val dataVersion: String = "unknown",
) : WayloamRouter {
    private val calculation = Mutex()

    override suspend fun route(request: RouteRequest, onEvent: (RoutingEvent) -> Unit): RouteResult =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(request.timeoutMillis) {
                calculation.withLock { calculate(request.copy(via = request.via.toList()), onEvent) }
            } ?: throw RoutingException(RoutingFailureCode.TIMEOUT, "Route calculation exceeded its time budget")
        }

    private suspend fun calculate(request: RouteRequest, onEvent: (RoutingEvent) -> Unit): RouteResult {
        currentCoroutineContext().ensureActive()
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        val identity = RouteIdentity(
            "${sectionEngine.engineId}:${sectionEngine.engineVersion}",
            profileVersion, dataVersion, sectionPlanner.version,
        )
        val key = RouteCacheKey.build(request, identity)
        cache.get(key)?.let { cached ->
            currentCoroutineContext().ensureActive()
            onEvent(RoutingEvent.CacheHit(key))
            onEvent(RoutingEvent.Completed(cached.metrics.distanceMeters, cached.segments.size))
            return cached.copy(cacheHit = true, diagnostics = RouteDiagnostics(
                elapsedMillis = elapsed(), plannerVersion = sectionPlanner.version,
            ))
        }

        onEvent(RoutingEvent.Preparing)
        val specs = sectionPlanner.plan(request)
        require(specs.isNotEmpty()) { "Section planner returned no sections" }
        require(specs.last().endIsUserWaypoint && specs.last().end == request.end) {
            "Section planner must preserve the destination"
        }
        val explicitStops = specs.filter { it.endIsUserWaypoint }.map { it.end }
        require(explicitStops == request.via + request.end) { "Section planner must preserve every requested stop in order" }
        onEvent(RoutingEvent.Started(specs.size))
        val routed = mutableListOf<RouteSegment>()
        var start = request.start
        var cacheHits = 0
        var engineCalls = 0
        var skipped = 0
        var retries = 0
        var firstSection: Long? = null
        for (spec in specs) {
            currentCoroutineContext().ensureActive()
            onEvent(RoutingEvent.SectionStarted(spec.index, specs.size))
            val candidates = if (spec.endIsUserWaypoint) listOf(spec.end) else spec.endCandidates
            require(candidates.isNotEmpty()) { "Section has no endpoint candidates" }
            var segment: RouteSegment? = null
            var failure: RoutingException? = null
            for ((attempt, end) in candidates.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (attempt > 0) {
                    retries++
                    onEvent(RoutingEvent.SectionRetry(spec.index, attempt + 1, failure!!.code))
                }
                val sectionKey = RouteCacheKey.section(start, end, request.profile, identity)
                try {
                    val cached = cache.get(sectionKey)
                    val current = if (cached != null && cached.segments.size == 1) {
                        cacheHits++
                        onEvent(RoutingEvent.SectionCacheHit(spec.index))
                        cached.segments.single().copy(index = routed.size)
                    } else {
                        engineCalls++
                        sectionEngine.routeSection(routed.size, start, end, request.profile)
                    }
                    currentCoroutineContext().ensureActive()
                    require(current.index == routed.size) { "Engine returned an incorrect section index" }
                    routed.lastOrNull()?.let { RouteStitcher.checkJoin(it, current) }
                    if (cached == null) cache.put(sectionKey, RouteStitcher.stitch(listOf(current), current.engine))
                    segment = current
                    break
                } catch (error: RoutingException) {
                    if (error.code !in RETRYABLE) throw RoutingException(
                        error.code, "Section ${spec.index + 1}: ${error.message}", error, spec.index,
                    )
                    failure = error
                }
            }
            if (segment == null) {
                if (!spec.endIsUserWaypoint) {
                    skipped++
                    onEvent(RoutingEvent.AnchorSkipped(spec.index))
                    continue
                }
                throw RoutingException(failure?.code ?: RoutingFailureCode.NO_ROUTE,
                    "Section ${spec.index + 1} could not reach the requested stop: ${failure?.message}",
                    failure, spec.index)
            }
            routed += segment
            start = segment.points.last()
            if (firstSection == null) firstSection = elapsed()
            onEvent(RoutingEvent.SectionReady(segment))
            onEvent(RoutingEvent.SectionCompleted(spec.index, specs.size, segment.metrics.distanceMeters))
        }
        currentCoroutineContext().ensureActive()
        val result = RouteStitcher.stitch(routed, "${sectionEngine.engineId}/${sectionEngine.engineVersion}")
            .copy(diagnostics = RouteDiagnostics(elapsed(), firstSection, cacheHits, engineCalls,
                skipped, retries, sectionPlanner.version))
        currentCoroutineContext().ensureActive()
        cache.put(key, result)
        onEvent(RoutingEvent.Completed(result.metrics.distanceMeters, result.segments.size))
        return result
    }

    companion object {
        private val RETRYABLE = setOf(RoutingFailureCode.NO_ROUTE, RoutingFailureCode.TIMEOUT,
            RoutingFailureCode.DISCONNECTED_ROUTE)
    }
}
