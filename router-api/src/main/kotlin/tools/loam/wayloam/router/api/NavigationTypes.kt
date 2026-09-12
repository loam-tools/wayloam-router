package tools.loam.wayloam.router.api

/** Rider position relative to a calculated route. */
enum class RouteMatchStatus {
    ON_ROUTE,
    DRIFTING,
    OFF_ROUTE,
}

data class RouteMatcherConfig(
    val onRouteThresholdMeters: Double = 25.0,
    val offRouteThresholdMeters: Double = 75.0,
    val waypointReachedThresholdMeters: Double = 40.0,
) {
    init {
        require(onRouteThresholdMeters > 0.0 && onRouteThresholdMeters.isFinite())
        require(offRouteThresholdMeters > onRouteThresholdMeters && offRouteThresholdMeters.isFinite())
        require(waypointReachedThresholdMeters > 0.0 && waypointReachedThresholdMeters.isFinite())
    }
}

data class RouteMatch(
    val snappedPosition: GeoPoint,
    val distanceFromRouteMeters: Double,
    val distanceAlongRouteMeters: Double,
    val distanceRemainingMeters: Double,
    val segmentIndex: Int,
    val status: RouteMatchStatus,
    val nextMandatoryWaypoint: GeoPoint? = null,
) {
    init {
        require(distanceFromRouteMeters >= 0.0 && distanceFromRouteMeters.isFinite())
        require(distanceAlongRouteMeters >= 0.0 && distanceAlongRouteMeters.isFinite())
        require(distanceRemainingMeters >= 0.0 && distanceRemainingMeters.isFinite())
        require(segmentIndex >= 0)
    }
}
