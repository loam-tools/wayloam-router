package tools.loam.wayloam.router.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tools.loam.wayloam.router.api.*
import tools.loam.wayloam.router.brouter.*
import tools.loam.wayloam.router.core.*
import tools.loam.wayloam.router.data.*
import java.io.File
import java.security.MessageDigest

/** Host-provided dataset identity is required; updating data must never reuse stale routes. */
data class LocalRouterConfig(
    val root: File,
    val dataVersion: String,
    val sectionTimeoutMillis: Long = 90_000L,
    val memoryLimitMb: Int = 128,
    val cacheBytes: Long = 256L * 1024 * 1024,
    val graphAnchors: Boolean = true,
) {
    init {
        require(dataVersion.isNotBlank() && dataVersion != "unknown")
        require(sectionTimeoutMillis > 0)
        require(memoryLimitMb in 32..2048)
        require(cacheBytes >= 16L * 1024 * 1024)
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

    /** Conservative planning estimate; actual routes may require neighboring tiles around barriers. */
    suspend fun estimatedTiles(request: RouteRequest): Set<Rd5TileId> {
        val anchors = FixedDistanceSectionPlanner().plan(request)
        return Rd5TileSet.forEnvelope(listOf(request.start) + anchors.map { it.end })
    }

    suspend fun missingStopTiles(request: RouteRequest): Set<Rd5TileId> = withContext(Dispatchers.IO) {
        (listOf(request.start) + request.via + request.end).map(Rd5TileId::from)
            .filterNot(store::contains).toSet()
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
                    LongRouteCoordinator(BRouterSectionEngine(backend), planner, cache,
                        WayloamProfiles.version, datasetFingerprint()).route(request, onEvent)
                }
            } ?: throw RoutingException(RoutingFailureCode.TIMEOUT, "Route calculation exceeded its time budget")
        }

    suspend fun clearRouteCache() = withContext(Dispatchers.IO) { mutation.withLock { cache.clear() } }

    private fun datasetFingerprint(): String {
        // Conservative invalidation also catches externally added, removed or replaced map files.
        // Callers must not mutate the dataset during a route; use one data manager per root.
        val canonical = buildString {
            append(config.dataVersion).append('\n')
            segmentsDirectory.listFiles().orEmpty().filter { it.extension == "rd5" }.sortedBy { it.name }
                .forEach { append(it.name).append(':').append(it.length()).append(':').append(it.lastModified()).append('\n') }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        suspend fun create(config: LocalRouterConfig): EmbeddedWayloamRouter = withContext(Dispatchers.IO) {
            java.nio.file.Files.createDirectories(File(config.root, "segments4").toPath())
            val profiles = BundledBRouterProfiles.install(File(config.root, "profiles2"))
            EmbeddedWayloamRouter(config, profiles, FileRouteCache(File(config.root, "cache").toPath(), config.cacheBytes))
        }
    }
}
