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
    /** Returns the current fingerprint for an engine data dependency file name. */
    private val dataDependencyFingerprint: ((String) -> String?)? = null,
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
        var staleCacheEntries = 0
        val key = RouteCacheKey.build(request, identity)
        val routeCached = cache.get(key)
        if (routeCached != null && cacheIsCurrent(routeCached)) {
            currentCoroutineContext().ensureActive()
            onEvent(RoutingEvent.CacheHit(key))
            onEvent(RoutingEvent.Completed(routeCached.metrics.distanceMeters, routeCached.segments.size))
            return routeCached.copy(cacheHit = true, diagnostics = RouteDiagnostics(
                elapsedMillis = elapsed(),
                plannerVersion = sectionPlanner.version,
                engineVersion = identity.engineVersion,
                profileVersion = identity.profileVersion,
                dataVersion = identity.dataVersion,
            ))
        } else if (routeCached != null) {
            staleCacheEntries++
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
                val sectionKey = RouteCacheKey.section(
                    start, end, request.profile, request.preferences, identity,
                )
                try {
                    val cachedCandidate = cache.get(sectionKey)
                    val cached = cachedCandidate?.takeIf { it.segments.size == 1 && cacheIsCurrent(it) }
                    if (cachedCandidate != null && cached == null) staleCacheEntries++
                    val current = if (cached != null) {
                        cacheHits++
                        onEvent(RoutingEvent.SectionCacheHit(spec.index))
                        cached.segments.single().copy(index = routed.size)
                    } else {
                        engineCalls++
                        sectionEngine.routeSection(
                            routed.size, start, end, request.profile, request.preferences,
                        )
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
            .copy(diagnostics = RouteDiagnostics(
                elapsedMillis = elapsed(),
                firstSectionMillis = firstSection,
                sectionCacheHits = cacheHits,
                engineCalls = engineCalls,
                skippedAnchors = skipped,
                retries = retries,
                plannerVersion = sectionPlanner.version,
                engineVersion = identity.engineVersion,
                profileVersion = identity.profileVersion,
                dataVersion = identity.dataVersion,
                staleCacheEntries = staleCacheEntries,
            ))
        currentCoroutineContext().ensureActive()
        cache.put(key, result)
        onEvent(RoutingEvent.Completed(result.metrics.distanceMeters, result.segments.size))
        return result
    }

    private fun cacheIsCurrent(result: RouteResult): Boolean {
        val fingerprint = dataDependencyFingerprint ?: return true
        val dependencies = result.dataDependencies
        if (dependencies.isEmpty()) return false
        return dependencies.all { (name, expected) -> fingerprint(name) == expected }
    }

    companion object {
        private val RETRYABLE = setOf(RoutingFailureCode.NO_ROUTE, RoutingFailureCode.TIMEOUT,
            RoutingFailureCode.DISCONNECTED_ROUTE)
    }
}
