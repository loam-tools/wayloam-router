package tools.loam.wayloam.router.api

enum class SurfacePreference { AVOID_UNPAVED, BALANCED, PREFER_UNPAVED }
enum class TrackTolerance { LOW, MEDIUM, HIGH }

data class RoutePreferences(
    val allowFerries: Boolean = true,
    val allowSteps: Boolean = false,
    val allowTunnels: Boolean = true,
    val avoidMajorRoads: Boolean = true,
    val preferCycleways: Boolean = true,
    val surfacePreference: SurfacePreference = SurfacePreference.BALANCED,
    val trackTolerance: TrackTolerance = TrackTolerance.MEDIUM,
    /** 0 = ignore traffic, 1 = strongly penalize higher-traffic roads. */
    val trafficSensitivity: Double = 0.7,
    /** 0 = ignore climbing, 1 = strongly penalize elevation gain. */
    val hillSensitivity: Double = 0.5,
) {
    init {
        require(trafficSensitivity in 0.0..1.0 && trafficSensitivity.isFinite())
        require(hillSensitivity in 0.0..1.0 && hillSensitivity.isFinite())
    }

    companion object {
        fun forProfile(profile: RouteProfile): RoutePreferences = when (profile) {
            RouteProfile.DIRECT -> RoutePreferences(
                avoidMajorRoads = false,
                preferCycleways = true,
                surfacePreference = SurfacePreference.AVOID_UNPAVED,
                trackTolerance = TrackTolerance.LOW,
                trafficSensitivity = 0.3,
                hillSensitivity = 0.35,
            )
            RouteProfile.TOURING -> RoutePreferences(
                avoidMajorRoads = true,
                preferCycleways = true,
                surfacePreference = SurfacePreference.AVOID_UNPAVED,
                trackTolerance = TrackTolerance.LOW,
                trafficSensitivity = 0.8,
                hillSensitivity = 0.55,
            )
            RouteProfile.BIKEPACKING -> RoutePreferences(
                avoidMajorRoads = true,
                preferCycleways = true,
                surfacePreference = SurfacePreference.PREFER_UNPAVED,
                trackTolerance = TrackTolerance.HIGH,
                trafficSensitivity = 0.85,
                hillSensitivity = 0.4,
            )
        }
    }
}
