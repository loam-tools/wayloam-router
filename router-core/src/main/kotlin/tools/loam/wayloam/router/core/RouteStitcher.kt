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
        val annotations = buildList {
            var offset = 0.0
            normalized.forEach { segment ->
                segment.annotations.forEach { annotation ->
                    add(annotation.copy(
                        startDistanceMeters = annotation.startDistanceMeters + offset,
                        endDistanceMeters = annotation.endDistanceMeters + offset,
                    ))
                }
                offset += segment.metrics.distanceMeters.toDouble()
            }
        }
        val result = RouteResult(
            points = points,
            metrics = normalized.fold(RouteMetrics.ZERO) { total, segment -> total + segment.metrics },
            segments = normalized,
            engine = engine,
        )
        return result.copy(analysis = RouteAnalyzer.analyze(result, annotations))
    }
}
