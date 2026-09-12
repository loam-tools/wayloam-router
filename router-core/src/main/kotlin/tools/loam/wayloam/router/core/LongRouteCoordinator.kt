package tools.loam.wayloam.router.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tools.loam.wayloam.router.api.RouteRequest
import tools.loam.wayloam.router.api.RouteResult
import tools.loam.wayloam.router.api.RoutingEvent
import tools.loam.wayloam.router.api.WayloamRouter

class LongRouteCoordinator(
    private val sectionEngine: RouteSectionEngine,
    private val sectionPlanner: SectionPlanner = FixedDistanceSectionPlanner(),
    private val cache: RouteCache = NoRouteCache,
    private val profileVersion: String = "bootstrap-1",
    private val dataVersion: String = "unknown",
) : WayloamRouter {

    override suspend fun route(
        request: RouteRequest,
        onEvent: (RoutingEvent) -> Unit,
    ): RouteResult {
        val identity = RouteIdentity(
            engineVersion = "${sectionEngine.engineId}:${sectionEngine.engineVersion}",
            profileVersion = profileVersion,
            dataVersion = dataVersion,
        )
        val key = RouteCacheKey.build(request, identity)

        cache.get(key)?.let { cached ->
            onEvent(RoutingEvent.CacheHit(key))
            return cached.copy(cacheHit = true)
        }

        val specs = sectionPlanner.plan(request)
        require(specs.isNotEmpty()) { "Section planner returned no sections" }
        onEvent(RoutingEvent.Started(specs.size))

        val routed = buildList {
            specs.forEach { spec ->
                currentCoroutineContext().ensureActive()
                onEvent(RoutingEvent.SectionStarted(spec.index, specs.size))

                val segment = sectionEngine.routeSection(
                    index = spec.index,
                    start = spec.start,
                    end = spec.end,
                    profile = request.profile,
                )
                require(segment.index == spec.index) {
                    "Section engine returned index ${segment.index} for requested section ${spec.index}"
                }
                add(segment)

                onEvent(
                    RoutingEvent.SectionCompleted(
                        index = spec.index,
                        total = specs.size,
                        distanceMeters = segment.metrics.distanceMeters,
                    )
                )
            }
        }

        val engineLabel = "${sectionEngine.engineId}/${sectionEngine.engineVersion}"
        val result = RouteStitcher.stitch(routed, engineLabel)
        cache.put(key, result)
        onEvent(RoutingEvent.Completed(result.metrics.distanceMeters, result.segments.size))
        return result
    }
}
