package tools.loam.wayloam.router.api

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double? = null,
) {
    init {
        require(latitude in -90.0..90.0) { "Latitude must be between -90 and 90" }
        require(longitude in -180.0..180.0) { "Longitude must be between -180 and 180" }
        require(elevationMeters == null || elevationMeters.isFinite()) { "Elevation must be finite" }
    }
}

enum class RouteProfile {
    DIRECT,
    TOURING,
    BIKEPACKING,
}

data class RouteRequest(
    val start: GeoPoint,
    val end: GeoPoint,
    val profile: RouteProfile,
    val via: List<GeoPoint> = emptyList(),
    val maxSectionDistanceKm: Double = 220.0,
    val timeoutMillis: Long = 600_000L,
    val preferences: RoutePreferences = RoutePreferences.forProfile(profile),
) {
    init {
        require(maxSectionDistanceKm.isFinite() && maxSectionDistanceKm >= 20.0) {
            "Section distance must be finite and at least 20 km"
        }
        require(timeoutMillis > 0L) { "Routing timeout must be positive" }
        require(via.size <= 256) { "A route supports at most 256 via points" }
    }
}

data class RouteMetrics(
    val distanceMeters: Long,
    val ascentMeters: Int = 0,
    val descentMeters: Int = 0,
    val durationSeconds: Long = 0,
) {
    init {
        require(distanceMeters >= 0)
        require(ascentMeters >= 0)
        require(descentMeters >= 0)
        require(durationSeconds >= 0)
    }

    operator fun plus(other: RouteMetrics): RouteMetrics = RouteMetrics(
        distanceMeters = distanceMeters + other.distanceMeters,
        ascentMeters = ascentMeters + other.ascentMeters,
        descentMeters = descentMeters + other.descentMeters,
        durationSeconds = durationSeconds + other.durationSeconds,
    )

    companion object {
        val ZERO = RouteMetrics(0)
    }
}

data class RouteSegment(
    val index: Int,
    val start: GeoPoint,
    val end: GeoPoint,
    val points: List<GeoPoint>,
    val metrics: RouteMetrics,
    val engine: String,
    /** Distances are local to this segment; RouteStitcher shifts them into route coordinates. */
    val annotations: List<RouteAnnotation> = emptyList(),
    /** Exact data files opened by the engine and their local fingerprints at calculation time. */
    val dataDependencies: Map<String, String> = emptyMap(),
    /** Maneuver point indices/distances are local until RouteStitcher combines the route. */
    val maneuvers: List<RouteManeuver> = emptyList(),
) {
    init {
        require(index >= 0)
        require(points.size >= 2) { "A route segment must contain at least two points" }
        require(dataDependencies.keys.none { '/' in it || '\\' in it }) { "Data dependency keys must be file names" }
        require(maneuvers.all { it.pointIndex in points.indices }) { "Maneuver point index must belong to the segment" }
    }
}

data class RouteResult(
    val points: List<GeoPoint>,
    val metrics: RouteMetrics,
    val segments: List<RouteSegment>,
    val engine: String,
    val cacheHit: Boolean = false,
    val diagnostics: RouteDiagnostics = RouteDiagnostics(),
    val analysis: RouteAnalysis = RouteAnalysis(),
    /** Global route-coordinate maneuvers stitched from all routed sections. */
    val maneuvers: List<RouteManeuver> = emptyList(),
) {
    init {
        require(points.size >= 2) { "A route must contain at least two points" }
        require(segments.isNotEmpty()) { "A route must contain at least one segment" }
        require(maneuvers.all { it.pointIndex in points.indices }) { "Maneuver point index must belong to the route" }
    }

    val dataDependencies: Map<String, String>
        get() = buildMap {
            segments.forEach { segment -> segment.dataDependencies.forEach { (name, fingerprint) -> put(name, fingerprint) } }
        }
}

sealed interface RoutingEvent {
    data object Preparing : RoutingEvent
    data class Started(val sectionCount: Int) : RoutingEvent
    data class SectionStarted(val index: Int, val total: Int) : RoutingEvent
    data class SectionCompleted(
        val index: Int,
        val total: Int,
        val distanceMeters: Long,
    ) : RoutingEvent
    data class CacheHit(val key: String) : RoutingEvent
    data class SectionCacheHit(val index: Int) : RoutingEvent
    data class AnchorSkipped(val index: Int) : RoutingEvent
    data class SectionRetry(val index: Int, val attempt: Int, val reason: RoutingFailureCode) : RoutingEvent
    data class SectionReady(val segment: RouteSegment) : RoutingEvent
    data class Completed(val distanceMeters: Long, val sectionCount: Int) : RoutingEvent
}

fun interface WayloamRouter {
    suspend fun route(
        request: RouteRequest,
        onEvent: (RoutingEvent) -> Unit,
    ): RouteResult
}
