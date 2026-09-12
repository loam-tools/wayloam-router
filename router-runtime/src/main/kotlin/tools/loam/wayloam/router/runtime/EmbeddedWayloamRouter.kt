package tools.loam.wayloam.router.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tools.loam.wayloam.router.api.*
import tools.loam.wayloam.router.brouter.*
import tools.loam.wayloam.router.core.*
import tools.loam.wayloam.router.data.*
import java.io.File

/** Host-provided dataset identity is stable for one compatible RD5 catalogue/schema generation. */
data class LocalRouterConfig(
    val root: File,
    val dataVersion: String,
    val sectionTimeoutMillis: Long = 90_000L,
    val memoryLimitMb: Int = 128,
    val cacheBytes: Long = 256L * 1024 * 1024,
    val graphAnchors: Boolean = true,
    val corridorSafetyTileRadius: Int = 1,
) {
    init {
        require(dataVersion.isNotBlank() && dataVersion != "unknown")
        require(sectionTimeoutMillis > 0)
        require(memoryLimitMb in 32..2048)
        require(cacheBytes >= 16L * 1024 * 1024)
        require(corridorSafetyTileRadius in 0..3)
    }
}

/** Offline facade shared by Android and the benchmark CLI. It never starts a network request. */
class EmbeddedWayloamRouter private constructor(
    private val config: LocalRouterConfig,
    private val profiles: File,
    private val cache: FileRouteCache,
) : WayloamRouter {
    private val mutation = Mutex()
    val segmentsDirectory: File = File(config.root, "segments4")
    private val store = FileRoutingDataStore(segmentsDirectory.toPath())

    /** Route-shaped prefetch estimate with a configurable detour safety margin. */
    suspend fun estimatedTiles(request: RouteRequest): Set<Rd5TileId> = withContext(Dispatchers.Default) {
        Rd5TileSet.forCorridor(
            listOf(request.start) + request.via + request.end,
            safetyTileRadius = config.corridorSafetyTileRadius,
        )
    }

    /** Tight refresh/download set after a real route is known. */
    fun routeCorridorTiles(result: RouteResult, safetyTileRadius: Int = 0): Set<Rd5TileId> =
        Rd5TileSet.forCorridor(result.points, safetyTileRadius = safetyTileRadius)

    /** Exact files the engine actually opened while calculating this route. */
    fun observedDataFiles(result: RouteResult): Set<String> = result.dataDependencies.keys

    suspend fun missingStopTiles(request: RouteRequest): Set<Rd5TileId> = withContext(Dispatchers.IO) {
        (listOf(request.start) + request.via + request.end).map(Rd5TileId::from)
            .filterNot(store::contains).toSet()
    }

    suspend fun missingEstimatedTiles(request: RouteRequest): Set<Rd5TileId> = withContext(Dispatchers.IO) {
        estimatedTiles(request).filterNot(store::contains).toSet()
    }

    override suspend fun route(request: RouteRequest, onEvent: (RoutingEvent) -> Unit): RouteResult =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(request.timeoutMillis) {
                mutation.withLock {
                    val missing = missingStopTiles(request)
                    if (missing.isNotEmpty()) throw RoutingException(RoutingFailureCode.MISSING_DATA,
                        "Download routing maps: ${missing.joinToString { it.fileName }}")
                    val backend = LocalBRouterBackend(segmentsDirectory, profiles,
                        config.sectionTimeoutMillis, config.memoryLimitMb)
                    val planner: SectionPlanner = if (config.graphAnchors) GraphAwareSectionPlanner(
                        BRouterGraphAnchorResolver(segmentsDirectory, profiles), BRouterBaseline.COMMIT,
                    ) else UserWaypointSectionPlanner()
                    LongRouteCoordinator(
                        sectionEngine = BRouterSectionEngine(backend),
                        sectionPlanner = planner,
                        cache = cache,
                        profileVersion = WayloamProfiles.version,
                        dataVersion = config.dataVersion,
                        dataDependencyFingerprint = ::dataDependencyFingerprint,
                    ).route(request, onEvent)
                }
            } ?: throw RoutingException(RoutingFailureCode.TIMEOUT, "Route calculation exceeded its time budget")
        }

    suspend fun clearRouteCache() = withContext(Dispatchers.IO) { mutation.withLock { cache.clear() } }

    private fun dataDependencyFingerprint(fileName: String): String? {
        if (!RD5_FILE.matches(fileName)) return null
        val file = File(segmentsDirectory, fileName)
        return if (file.isFile) "${file.length()}:${file.lastModified()}" else null
    }

    companion object {
        private val RD5_FILE = Regex("(?:E|W)\\d+_(?:N|S)\\d+\\.rd5")

        suspend fun create(config: LocalRouterConfig): EmbeddedWayloamRouter = withContext(Dispatchers.IO) {
            java.nio.file.Files.createDirectories(File(config.root, "segments4").toPath())
            val profiles = BundledBRouterProfiles.install(File(config.root, "profiles2"))
            EmbeddedWayloamRouter(config, profiles, FileRouteCache(File(config.root, "cache").toPath(), config.cacheBytes))
        }
    }
}
