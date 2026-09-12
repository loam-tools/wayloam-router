package tools.loam.wayloam.router.api

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double? = null,
) {
    init {
        require(latitude in -90.0..90.0) { "Latitude must be between -90 and 90" }
        require(longitude in -180.0..180.0) { "Longitude must be between -180 and 180" }
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
) {
    init {
        require(maxSectionDistanceKm >= 20.0) { "Section distance must be at least 20 km" }
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
) {
    init {
        require(index >= 0)
        require(points.size >= 2) { "A route segment must contain at least two points" }
    }
}

data class RouteResult(
    val points: List<GeoPoint>,
    val metrics: RouteMetrics,
    val segments: List<RouteSegment>,
    val engine: String,
    val cacheHit: Boolean = false,
) {
    init {
        require(points.size >= 2) { "A route must contain at least two points" }
        require(segments.isNotEmpty()) { "A route must contain at least one segment" }
    }
}

sealed interface RoutingEvent {
    data class Started(val sectionCount: Int) : RoutingEvent
    data class SectionStarted(val index: Int, val total: Int) : RoutingEvent
    data class SectionCompleted(
        val index: Int,
        val total: Int,
        val distanceMeters: Long,
    ) : RoutingEvent
    data class CacheHit(val key: String) : RoutingEvent
    data class Completed(val distanceMeters: Long, val sectionCount: Int) : RoutingEvent
}

fun interface WayloamRouter {
    suspend fun route(
        request: RouteRequest,
        onEvent: (RoutingEvent) -> Unit,
    ): RouteResult
}
