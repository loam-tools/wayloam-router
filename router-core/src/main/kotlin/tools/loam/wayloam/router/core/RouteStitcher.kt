package tools.loam.wayloam.router.core

import tools.loam.wayloam.router.api.*

object RouteStitcher {
    private const val MAX_SEAM_METERS = 5.0

    fun checkJoin(previous: RouteSegment, next: RouteSegment) {
        if (GeoMath.distanceMeters(previous.points.last(), next.points.first()) > MAX_SEAM_METERS) {
            throw RoutingException(RoutingFailureCode.DISCONNECTED_ROUTE,
                "Routed sections do not meet on the bicycle network", sectionIndex = next.index)
        }
    }

    fun stitch(segments: List<RouteSegment>, engine: String): RouteResult {
        require(segments.isNotEmpty()) { "Cannot stitch an empty route" }
        val normalized = segments.mapIndexed { i, segment -> segment.copy(index = i) }
        normalized.zipWithNext().forEach { (previous, next) -> checkJoin(previous, next) }
        val points = buildList<GeoPoint> {
            normalized.forEach { segment ->
                segment.points.forEachIndexed { pointIndex, point ->
                    val last = lastOrNull()
                    if (pointIndex > 0 || last == null ||
                        last.latitude != point.latitude || last.longitude != point.longitude) add(point)
                }
            }
        }
        return RouteResult(points, normalized.fold(RouteMetrics.ZERO) { total, segment ->
            total + segment.metrics
        }, normalized, engine)
    }
}
