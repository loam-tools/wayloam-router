package tools.loam.wayloam.router.core

import tools.loam.wayloam.router.api.*
import kotlin.math.max

/** Derives rider-facing cycling intelligence from route geometry and proven backend annotations. */
object RouteAnalyzer {
    private const val MIN_CLIMB_GAIN_METERS = 30.0
    private const val MIN_CLIMB_LENGTH_METERS = 500.0
    private const val CLIMB_END_DESCENT_METERS = 10.0

    fun analyze(
        route: RouteResult,
        annotations: List<RouteAnnotation> = emptyList(),
    ): RouteAnalysis {
        val routeLength = geometryLength(route.points)
        val climbs = detectClimbs(route.points)
        val warnings = mergeWarnings(
            annotations.flatMap(::annotationWarnings) + climbs.mapNotNull(::climbWarning)
        )
        val bySurface = annotations
            .filter { it.surface != SurfaceType.UNKNOWN }
            .groupingBy { it.surface }
            .fold(0.0) { total, annotation -> total + annotation.lengthMeters }
        val known = bySurface.values.sum().coerceAtMost(routeLength)

        return RouteAnalysis(
            annotations = annotations.toList(),
            climbs = climbs,
            warnings = warnings,
            surfaceSummary = RouteSurfaceSummary(
                knownDistanceMeters = known,
                unknownDistanceMeters = (routeLength - known).coerceAtLeast(0.0),
                distanceBySurfaceMeters = bySurface,
            ),
        )
    }

    private fun detectClimbs(points: List<GeoPoint>): List<RouteClimb> {
        if (points.size < 2) return emptyList()
        val climbs = mutableListOf<RouteClimb>()
        var routeDistance = 0.0
        var startDistance: Double? = null
        var endDistance = 0.0
        var gain = 0.0
        var descentSinceHigh = 0.0
        var maxGradient = 0.0

        fun finish() {
            val start = startDistance ?: return
            val length = endDistance - start
            if (gain >= MIN_CLIMB_GAIN_METERS && length >= MIN_CLIMB_LENGTH_METERS) {
                climbs += RouteClimb(
                    startDistanceMeters = start,
                    endDistanceMeters = endDistance,
                    lengthMeters = length,
                    elevationGainMeters = gain,
                    averageGradientPercent = gain / length * 100.0,
                    maxGradientPercent = maxGradient,
                )
            }
            startDistance = null
            gain = 0.0
            descentSinceHigh = 0.0
            maxGradient = 0.0
        }

        points.zipWithNext().forEach { (a, b) ->
            val edgeLength = GeoMath.distanceMeters(a, b)
            if (edgeLength <= 0.01) return@forEach
            val edgeStartDistance = routeDistance
            routeDistance += edgeLength
            val aElevation = a.elevationMeters
            val bElevation = b.elevationMeters
            if (aElevation == null || bElevation == null) {
                finish()
                return@forEach
            }

            val delta = bElevation - aElevation
            val gradient = delta / edgeLength * 100.0
            if (delta > 0.0) {
                if (startDistance == null) startDistance = edgeStartDistance
                gain += delta
                endDistance = routeDistance
                descentSinceHigh = 0.0
                maxGradient = max(maxGradient, gradient)
            } else if (startDistance != null) {
                descentSinceHigh += -delta
                if (descentSinceHigh >= CLIMB_END_DESCENT_METERS) finish()
            }
        }
        finish()
        return climbs
    }

    private fun annotationWarnings(annotation: RouteAnnotation): List<RouteWarning> = buildList {
        val lengthText = distanceText(annotation.lengthMeters)
        val rough = annotation.surface in setOf(
            SurfaceType.GRAVEL, SurfaceType.DIRT, SurfaceType.SAND, SurfaceType.TRAIL,
        ) || annotation.smoothness in setOf(
            Smoothness.BAD, Smoothness.VERY_BAD, Smoothness.HORRIBLE,
            Smoothness.VERY_HORRIBLE, Smoothness.IMPASSABLE,
        ) || annotation.trackType in setOf(TrackType.GRADE4, TrackType.GRADE5)
        if (rough) add(annotation.warning(
            RouteWarningType.ROUGH_SURFACE,
            RouteWarningSeverity.CAUTION,
            "$lengthText rough surface",
        ))
        if (annotation.ferry == true) add(annotation.warning(
            RouteWarningType.FERRY, RouteWarningSeverity.INFO, "$lengthText ferry crossing",
        ))
        if (annotation.steps == true || annotation.smoothness == Smoothness.IMPASSABLE) add(annotation.warning(
            RouteWarningType.BIKE_CARRY, RouteWarningSeverity.HARD, "Bike carrying may be required",
        ))
        if (annotation.trafficStress in setOf(TrafficStress.HIGH, TrafficStress.VERY_HIGH) ||
            annotation.roadClass in setOf(RoadClass.PRIMARY, RoadClass.TRUNK)) add(annotation.warning(
            RouteWarningType.BUSY_ROAD, RouteWarningSeverity.CAUTION, "$lengthText higher-traffic road",
        ))
        if (annotation.limitedAccess == true) add(annotation.warning(
            RouteWarningType.LIMITED_ACCESS, RouteWarningSeverity.HARD, "Limited-access crossing",
        ))
    }

    private fun climbWarning(climb: RouteClimb): RouteWarning? {
        if (climb.maxGradientPercent < 12.0 && climb.averageGradientPercent < 8.0) return null
        val severity = if (climb.maxGradientPercent >= 18.0 || climb.averageGradientPercent >= 12.0)
            RouteWarningSeverity.HARD else RouteWarningSeverity.CAUTION
        return RouteWarning(
            type = RouteWarningType.STEEP_CLIMB,
            severity = severity,
            startDistanceMeters = climb.startDistanceMeters,
            endDistanceMeters = climb.endDistanceMeters,
            message = "${distanceText(climb.lengthMeters)} climb · ${"%.1f".format(climb.averageGradientPercent)}% avg · ${"%.1f".format(climb.maxGradientPercent)}% max",
        )
    }

    private fun RouteAnnotation.warning(
        type: RouteWarningType,
        severity: RouteWarningSeverity,
        message: String,
    ) = RouteWarning(type, severity, startDistanceMeters, endDistanceMeters, message)

    private fun mergeWarnings(warnings: List<RouteWarning>): List<RouteWarning> {
        val sorted = warnings.sortedWith(compareBy<RouteWarning> { it.startDistanceMeters }.thenBy { it.type.name })
        val merged = mutableListOf<RouteWarning>()
        for (warning in sorted) {
            val previous = merged.lastOrNull()
            if (previous != null && previous.type == warning.type && previous.severity == warning.severity &&
                warning.startDistanceMeters <= previous.endDistanceMeters + 20.0) {
                merged[merged.lastIndex] = previous.copy(
                    endDistanceMeters = max(previous.endDistanceMeters, warning.endDistanceMeters),
                    message = warning.message,
                )
            } else merged += warning
        }
        return merged
    }

    private fun geometryLength(points: List<GeoPoint>): Double =
        points.zipWithNext().sumOf { (a, b) -> GeoMath.distanceMeters(a, b) }

    private fun distanceText(meters: Double): String = if (meters >= 1_000.0)
        "${"%.1f".format(meters / 1_000.0)} km" else "${meters.toInt()} m"
}
