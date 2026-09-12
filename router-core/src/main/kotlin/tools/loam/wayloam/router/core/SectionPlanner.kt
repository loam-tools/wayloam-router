package tools.loam.wayloam.router.core

import tools.loam.wayloam.router.api.GeoPoint
import tools.loam.wayloam.router.api.RouteRequest
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class SectionSpec(
    val index: Int,
    val start: GeoPoint,
    val end: GeoPoint,
)

fun interface SectionPlanner {
    fun plan(request: RouteRequest): List<SectionSpec>
}

/**
 * Bootstrap planner that limits the geometric span of a routing operation.
 *
 * It deliberately does not claim to understand seas, mountain barriers or the road graph. BRouter
 * still decides the actual network path. Before WAYLOAM Router becomes the app default, inaccessible
 * intermediate anchors will be replaced by graph-aware/snap-aware anchors.
 */
class FixedDistanceSectionPlanner : SectionPlanner {
    override fun plan(request: RouteRequest): List<SectionSpec> {
        val requestedAnchors = buildList {
            add(request.start)
            addAll(request.via)
            add(request.end)
        }

        val expanded = mutableListOf<GeoPoint>()
        requestedAnchors.windowed(2).forEachIndexed { legIndex, pair ->
            val start = pair[0]
            val end = pair[1]
            if (legIndex == 0) expanded += start

            val distanceKm = GeoMath.distanceMeters(start, end) / 1_000.0
            val sectionCount = ceil(distanceKm / request.maxSectionDistanceKm)
                .toInt()
                .coerceAtLeast(1)

            for (section in 1 until sectionCount) {
                expanded += GeoMath.interpolateGreatCircle(
                    start = start,
                    end = end,
                    fraction = section.toDouble() / sectionCount.toDouble(),
                )
            }
            expanded += end
        }

        return expanded.windowed(2).mapIndexed { index, pair ->
            SectionSpec(index = index, start = pair[0], end = pair[1])
        }
    }
}

object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = a.latitude.toRadians()
        val lat2 = b.latitude.toRadians()
        val deltaLat = (b.latitude - a.latitude).toRadians()
        val deltaLon = (b.longitude - a.longitude).toRadians()
        val hav = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        val angle = 2 * atan2(sqrt(hav), sqrt((1 - hav).coerceAtLeast(0.0)))
        return EARTH_RADIUS_METERS * angle
    }

    fun interpolateGreatCircle(start: GeoPoint, end: GeoPoint, fraction: Double): GeoPoint {
        require(fraction in 0.0..1.0)
        if (fraction == 0.0) return start
        if (fraction == 1.0) return end

        val lat1 = start.latitude.toRadians()
        val lon1 = start.longitude.toRadians()
        val lat2 = end.latitude.toRadians()
        val lon2 = end.longitude.toRadians()
        val angularDistance = distanceMeters(start, end) / EARTH_RADIUS_METERS
        if (angularDistance < 1e-12) return start

        val sinDistance = sin(angularDistance)
        val a = sin((1 - fraction) * angularDistance) / sinDistance
        val b = sin(fraction * angularDistance) / sinDistance

        val x = a * cos(lat1) * cos(lon1) + b * cos(lat2) * cos(lon2)
        val y = a * cos(lat1) * sin(lon1) + b * cos(lat2) * sin(lon2)
        val z = a * sin(lat1) + b * sin(lat2)

        return GeoPoint(
            latitude = atan2(z, sqrt(x * x + y * y)).toDegrees(),
            longitude = atan2(y, x).toDegrees(),
        )
    }

    private fun Double.toRadians(): Double = this * PI / 180.0
    private fun Double.toDegrees(): Double = this * 180.0 / PI
}
