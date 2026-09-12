package tools.loam.wayloam.router.android

import android.content.Context
import tools.loam.wayloam.router.api.*
import tools.loam.wayloam.router.core.ExactRouteRerouter
import tools.loam.wayloam.router.core.RouteAlternativePlanner
import tools.loam.wayloam.router.core.RouteMatcher
import tools.loam.wayloam.router.data.MissingRoutingDataArtifactException
import tools.loam.wayloam.router.data.RoutingTileState
import tools.loam.wayloam.router.data.VerifiedRoutingDataManager
import tools.loam.wayloam.router.http.BRouterHttpDataSource
import tools.loam.wayloam.router.runtime.EmbeddedWayloamRouter
import tools.loam.wayloam.router.runtime.LocalRouterConfig

/** One corridor tile and what WAYLOAM needs to do before offline routing can use it. */
data class RoutingMapTilePlan(
    val fileName: String,
    val sizeBytes: Long,
    val ready: Boolean,
)

data class RoutingMapPlan(
    val tiles: List<RoutingMapTilePlan>,
    /** Conservative full-file budget. Resumed downloads can transfer fewer bytes than this. */
    val requiredDownloadBytes: Long,
) {
    val downloadCount: Int get() = tiles.count { !it.ready }
    val readyCount: Int get() = tiles.size - downloadCount
}

data class RoutingMapProgress(
    val completed: Int,
    val total: Int,
    val fileName: String,
    val downloaded: Boolean,
    val installedBytes: Long,
)

data class RoutingMapPreparationResult(
    val fileCount: Int,
    val downloadedFileCount: Int,
    val installedBytes: Long,
)

class RoutingMapBudgetExceededException(
    val requiredBytes: Long,
    val allowedBytes: Long,
) : IllegalStateException("Routing maps need up to $requiredBytes bytes; budget is $allowedBytes bytes")

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

    /**
     * Resolves remote metadata before any transfer. This lets the app show the exact tile set and a
     * conservative byte budget, and lets the rider explicitly approve a large offline-map download.
     */
    suspend fun planMaps(
        request: RouteRequest,
        baseUrl: String = BRouterHttpDataSource.DEFAULT_BASE_URL,
    ): RoutingMapPlan {
        val source = BRouterHttpDataSource(baseUrl)
        val manager = dataManager(source)
        val plans = runtime.estimatedTiles(request)
            .sortedWith(compareBy({ it.westLongitude }, { it.southLatitude }))
            .map { tile ->
                val artifact = source.artifact(tile) ?: throw MissingRoutingDataArtifactException(tile)
                val size = requireNotNull(artifact.sizeBytes) {
                    "Routing source did not advertise a size for ${tile.fileName}"
                }
                val state = manager.status(tile).state
                RoutingMapTilePlan(
                    fileName = tile.fileName,
                    sizeBytes = size,
                    ready = state == RoutingTileState.INSTALLED_VERIFIED,
                )
            }
        return RoutingMapPlan(
            tiles = plans,
            requiredDownloadBytes = plans.filterNot { it.ready }.sumOf { it.sizeBytes },
        )
    }

    /** Resumably downloads, verifies and atomically installs the corridor required by [request]. */
    suspend fun prepareMaps(
        request: RouteRequest,
        maxDownloadBytes: Long,
        baseUrl: String = BRouterHttpDataSource.DEFAULT_BASE_URL,
        onProgress: (RoutingMapProgress) -> Unit = {},
    ): RoutingMapPreparationResult {
        require(maxDownloadBytes > 0L) { "Download budget must be positive" }
        val plan = planMaps(request, baseUrl)
        if (plan.requiredDownloadBytes > maxDownloadBytes) {
            throw RoutingMapBudgetExceededException(plan.requiredDownloadBytes, maxDownloadBytes)
        }

        val source = BRouterHttpDataSource(baseUrl)
        val manager = dataManager(source)
        val tiles = runtime.estimatedTiles(request)
            .sortedWith(compareBy({ it.westLongitude }, { it.southLatitude }))
        var downloadedFiles = 0
        var installedBytes = 0L
        tiles.forEachIndexed { index, tile ->
            val result = manager.ensureTile(tile)
            if (result.downloaded) downloadedFiles++
            installedBytes += result.bytes
            onProgress(
                RoutingMapProgress(
                    completed = index + 1,
                    total = tiles.size,
                    fileName = tile.fileName,
                    downloaded = result.downloaded,
                    installedBytes = result.bytes,
                )
            )
        }
        return RoutingMapPreparationResult(
            fileCount = tiles.size,
            downloadedFileCount = downloadedFiles,
            installedBytes = installedBytes,
        )
    }

    fun observedMapFiles(result: RouteResult): Set<String> =
        runtime.observedDataFiles(result).toSortedSet()

    suspend fun clearRouteCache() = runtime.clearRouteCache()

    private fun dataManager(source: BRouterHttpDataSource) = VerifiedRoutingDataManager(
        segmentsRoot = runtime.segmentsDirectory.toPath(),
        manifest = source,
        transport = source,
    )

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
