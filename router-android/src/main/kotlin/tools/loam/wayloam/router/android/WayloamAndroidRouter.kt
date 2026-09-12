package tools.loam.wayloam.router.android

import android.content.Context
import tools.loam.wayloam.router.api.*
import tools.loam.wayloam.router.core.ExactRouteRerouter
import tools.loam.wayloam.router.core.RouteAlternativePlanner
import tools.loam.wayloam.router.core.RouteMatcher
import tools.loam.wayloam.router.runtime.EmbeddedWayloamRouter
import tools.loam.wayloam.router.runtime.LocalRouterConfig

/**
 * Android-facing entry point for WAYLOAM. The app should depend on this facade rather than assemble
 * BRouter/core/data implementation classes itself.
 */
class WayloamAndroidRouter private constructor(
    private val runtime: EmbeddedWayloamRouter,
    val storage: RouterStorage,
) {
    suspend fun route(
        request: RouteRequest,
        onEvent: (RoutingEvent) -> Unit = {},
    ): RouteResult = runtime.route(request, onEvent)

    suspend fun alternatives(
        request: RouteRequest,
        onEvent: (RouteAlternativeKind, RoutingEvent) -> Unit = { _, _ -> },
    ): List<RouteAlternative> = RouteAlternativePlanner(runtime).calculate(request, onEvent)

    fun navigation(
        request: RouteRequest,
        result: RouteResult,
        matcherConfig: RouteMatcherConfig = RouteMatcherConfig(),
    ): WayloamNavigationSession = WayloamNavigationSession(runtime, request, result, matcherConfig)

    suspend fun missingMapFiles(request: RouteRequest): Set<String> =
        runtime.missingEstimatedTiles(request).mapTo(sortedSetOf()) { it.fileName }

    fun observedMapFiles(result: RouteResult): Set<String> =
        runtime.observedDataFiles(result).toSortedSet()

    suspend fun clearRouteCache() = runtime.clearRouteCache()

    companion object {
        /** Creates the complete embedded router using app-private no-backup storage. */
        suspend fun create(
            context: Context,
            dataVersion: String,
            sectionTimeoutMillis: Long = 90_000L,
            memoryLimitMb: Int = 128,
            cacheBytes: Long = 256L * 1024 * 1024,
            graphAnchors: Boolean = true,
            corridorSafetyTileRadius: Int = 1,
        ): WayloamAndroidRouter {
            val storage = RouterStorage.create(context.applicationContext)
            val runtime = EmbeddedWayloamRouter.create(
                LocalRouterConfig(
                    root = storage.root(),
                    dataVersion = dataVersion,
                    sectionTimeoutMillis = sectionTimeoutMillis,
                    memoryLimitMb = memoryLimitMb,
                    cacheBytes = cacheBytes,
                    graphAnchors = graphAnchors,
                    corridorSafetyTileRadius = corridorSafetyTileRadius,
                )
            )
            return WayloamAndroidRouter(runtime, storage)
        }
    }
}

/** Stateful live-navigation helper. Matching stays cheap because route geometry is indexed once. */
class WayloamNavigationSession internal constructor(
    private val router: EmbeddedWayloamRouter,
    val request: RouteRequest,
    initialResult: RouteResult,
    matcherConfig: RouteMatcherConfig,
) {
    private val config = matcherConfig
    var result: RouteResult = initialResult
        private set
    private var matcher = matcherFor(initialResult)

    fun match(location: GeoPoint): RouteMatch = matcher.match(location)

    /** Recomputes an exact forward rejoin and atomically moves this session to the new route. */
    suspend fun reroute(
        location: GeoPoint,
        onEvent: (RoutingEvent) -> Unit = {},
    ): RouteResult {
        val rerouted = ExactRouteRerouter(router).reroute(request, result, location, onEvent)
        result = rerouted
        matcher = matcherFor(rerouted)
        return rerouted
    }

    private fun matcherFor(route: RouteResult): RouteMatcher =
        RouteMatcher(route, request.via + request.end, config)
}
