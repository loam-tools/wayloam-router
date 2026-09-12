package tools.loam.wayloam.router.api

/** Graph-derived rider instruction produced by the routing engine. */
enum class RouteManeuverType {
    CONTINUE,
    TURN_LEFT,
    SLIGHT_LEFT,
    SHARP_LEFT,
    TURN_RIGHT,
    SLIGHT_RIGHT,
    SHARP_RIGHT,
    KEEP_LEFT,
    KEEP_RIGHT,
    U_TURN,
    ROUNDABOUT,
    ROUNDABOUT_LEFT,
    EXIT_LEFT,
    EXIT_RIGHT,
    OFF_ROUTE,
    BEELINE,
}

data class RouteManeuver(
    val type: RouteManeuverType,
    /** Point index local to a segment, or global after RouteStitcher has combined sections. */
    val pointIndex: Int,
    val point: GeoPoint,
    /** Distance local to a segment, or global after RouteStitcher has combined sections. */
    val distanceAlongRouteMeters: Double,
    /** Distance from this maneuver to the following BRouter maneuver. */
    val distanceToNextMeters: Double,
    val turnAngleDegrees: Int? = null,
    /** One-based roundabout exit number when BRouter provides it. */
    val roundaboutExit: Int? = null,
) {
    init {
        require(pointIndex >= 0)
        require(distanceAlongRouteMeters >= 0.0 && distanceAlongRouteMeters.isFinite())
        require(distanceToNextMeters >= 0.0 && distanceToNextMeters.isFinite())
        require(turnAngleDegrees == null || turnAngleDegrees in -360..360)
        require(roundaboutExit == null || roundaboutExit > 0)
    }
}
