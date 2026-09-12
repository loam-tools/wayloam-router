package tools.loam.wayloam.router.api

enum class SurfaceType {
    UNKNOWN,
    PAVED,
    ASPHALT,
    CONCRETE,
    PAVING_STONES,
    SETT,
    COBBLESTONE,
    COMPACTED,
    FINE_GRAVEL,
    GRAVEL,
    GROUND,
    DIRT,
    MUD,
    SAND,
    GRASS,
    WOOD,
    TRAIL,
}

enum class RoadClass {
    UNKNOWN,
    CYCLEWAY,
    PATH,
    TRACK,
    FOOTWAY,
    BRIDLEWAY,
    PEDESTRIAN,
    RESIDENTIAL,
    LIVING_STREET,
    SERVICE,
    UNCLASSIFIED,
    TERTIARY,
    SECONDARY,
    PRIMARY,
    TRUNK,
    MOTORWAY,
}

enum class CyclewayType {
    UNKNOWN, NONE, SHARED_LANE, PAINTED_LANE, PROTECTED_LANE, SEPARATE_PATH,
}

enum class TrackType { UNKNOWN, GRADE1, GRADE2, GRADE3, GRADE4, GRADE5 }

enum class Smoothness {
    UNKNOWN, EXCELLENT, GOOD, INTERMEDIATE, BAD, VERY_BAD, HORRIBLE, VERY_HORRIBLE, IMPASSABLE,
}

enum class TrafficStress { UNKNOWN, VERY_LOW, LOW, MODERATE, HIGH, VERY_HIGH }

/**
 * Proven metadata for one distance interval of the final route. Unknown values are explicit so
 * callers never need to infer an OSM/BRouter tag that the backend did not actually provide.
 */
data class RouteAnnotation(
    val startDistanceMeters: Double,
    val endDistanceMeters: Double,
    val surface: SurfaceType = SurfaceType.UNKNOWN,
    val roadClass: RoadClass = RoadClass.UNKNOWN,
    val cycleway: CyclewayType = CyclewayType.UNKNOWN,
    val trackType: TrackType = TrackType.UNKNOWN,
    val smoothness: Smoothness = Smoothness.UNKNOWN,
    val trafficStress: TrafficStress = TrafficStress.UNKNOWN,
    val ferry: Boolean? = null,
    val tunnel: Boolean? = null,
    val steps: Boolean? = null,
    val unpaved: Boolean? = null,
    val limitedAccess: Boolean? = null,
    val bikeCarryLikely: Boolean? = null,
) {
    init {
        require(startDistanceMeters >= 0.0 && startDistanceMeters.isFinite())
        require(endDistanceMeters > startDistanceMeters && endDistanceMeters.isFinite())
    }

    val lengthMeters: Double get() = endDistanceMeters - startDistanceMeters
}

enum class RouteWarningType {
    ROUGH_SURFACE,
    STEEP_CLIMB,
    FERRY,
    BIKE_CARRY,
    BUSY_ROAD,
    LIMITED_ACCESS,
}

enum class RouteWarningSeverity { INFO, CAUTION, HARD }

data class RouteWarning(
    val type: RouteWarningType,
    val severity: RouteWarningSeverity,
    val startDistanceMeters: Double,
    val endDistanceMeters: Double,
    val message: String,
) {
    init {
        require(startDistanceMeters >= 0.0 && startDistanceMeters.isFinite())
        require(endDistanceMeters >= startDistanceMeters && endDistanceMeters.isFinite())
        require(message.isNotBlank())
    }
}

data class RouteClimb(
    val startDistanceMeters: Double,
    val endDistanceMeters: Double,
    val lengthMeters: Double,
    val elevationGainMeters: Double,
    val averageGradientPercent: Double,
    val maxGradientPercent: Double,
) {
    init {
        require(startDistanceMeters >= 0.0 && endDistanceMeters > startDistanceMeters)
        require(lengthMeters > 0.0 && lengthMeters.isFinite())
        require(elevationGainMeters > 0.0 && elevationGainMeters.isFinite())
        require(averageGradientPercent.isFinite() && maxGradientPercent.isFinite())
    }
}

data class RouteSurfaceSummary(
    val knownDistanceMeters: Double,
    val unknownDistanceMeters: Double,
    val distanceBySurfaceMeters: Map<SurfaceType, Double>,
) {
    val pavedDistanceMeters: Double
        get() = distanceBySurfaceMeters
            .filterKeys { it in PAVED_SURFACES }
            .values.sum()

    val unpavedDistanceMeters: Double
        get() = distanceBySurfaceMeters
            .filterKeys { it != SurfaceType.UNKNOWN && it !in PAVED_SURFACES }
            .values.sum()

    companion object {
        val PAVED_SURFACES = setOf(
            SurfaceType.PAVED,
            SurfaceType.ASPHALT,
            SurfaceType.CONCRETE,
            SurfaceType.PAVING_STONES,
            SurfaceType.SETT,
            SurfaceType.COBBLESTONE,
        )
    }
}

data class RouteAnalysis(
    val annotations: List<RouteAnnotation> = emptyList(),
    val climbs: List<RouteClimb> = emptyList(),
    val warnings: List<RouteWarning> = emptyList(),
    val surfaceSummary: RouteSurfaceSummary = RouteSurfaceSummary(0.0, 0.0, emptyMap()),
)
