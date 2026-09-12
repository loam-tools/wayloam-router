package tools.loam.wayloam.router.core

import tools.loam.wayloam.router.api.RouteMetrics
import tools.loam.wayloam.router.api.RouteResult
import tools.loam.wayloam.router.api.RouteSegment

object RouteStitcher {
    fun stitch(segments: List<RouteSegment>, engine: String): RouteResult {
        require(segments.isNotEmpty()) { "Cannot stitch an empty route" }

        val points = buildList {
            segments.forEachIndexed { segmentIndex, segment ->
                segment.points.forEachIndexed { pointIndex, point ->
                    if (segmentIndex == 0 || pointIndex > 0 || lastOrNull() != point) {
                        add(point)
                    }
                }
            }
        }

        val metrics = segments.fold(RouteMetrics.ZERO) { total, segment ->
            total + segment.metrics
        }

        return RouteResult(
            points = points,
            metrics = metrics,
            segments = segments,
            engine = engine,
        )
    }
}
