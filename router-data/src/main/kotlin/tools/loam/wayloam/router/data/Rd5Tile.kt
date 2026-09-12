package tools.loam.wayloam.router.data

import tools.loam.wayloam.router.api.GeoPoint
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

data class Rd5TileId(
    val westLongitude: Int,
    val southLatitude: Int,
) {
    init {
        require(westLongitude in -180..175 && westLongitude % 5 == 0)
        require(southLatitude in -90..85 && southLatitude % 5 == 0)
    }

    val fileName: String
        get() = "${hemisphere(westLongitude, 'E', 'W')}_${hemisphere(southLatitude, 'N', 'S')}.rd5"

    companion object {
        fun from(point: GeoPoint): Rd5TileId {
            val lon = if (point.longitude == 180.0) -180.0 else point.longitude
            val lat = point.latitude.coerceIn(-89.999999, 89.999999)
            return Rd5TileId(
                westLongitude = tileFloor(lon),
                southLatitude = tileFloor(lat),
            )
        }

        private fun tileFloor(value: Double): Int = (floor(value / 5.0) * 5.0).toInt()

        private fun hemisphere(value: Int, positive: Char, negative: Char): String =
            if (value >= 0) "$positive$value" else "$negative${-value}"
    }
}

object Rd5TileSet {
    /**
     * Returns every 5° BRouter tile intersecting the bounding box of the supplied points.
     * Retained as a conservative fallback for hosts that do not yet have a route-shaped corridor.
     */
    fun forEnvelope(points: List<GeoPoint>): Set<Rd5TileId> {
        require(points.isNotEmpty())
        val ids = points.map(Rd5TileId::from)
        // Remove the largest empty arc so a dateline crossing does not request the whole planet.
        val columns = ids.map { (it.westLongitude + 180) / 5 }.distinct().sorted()
        val gaps = columns.indices.map { i ->
            val next = if (i == columns.lastIndex) columns.first() + 72 else columns[i + 1]
            next - columns[i]
        }
        val gapIndex = gaps.indices.maxBy { gaps[it] }
        val startColumn = columns[(gapIndex + 1) % columns.size]
        val columnCount = 73 - gaps[gapIndex]
        val minLat = ids.minOf { it.southLatitude }
        val maxLat = ids.maxOf { it.southLatitude }

        return buildSet {
            repeat(columnCount) { offset ->
                val lon = ((startColumn + offset) % 72) * 5 - 180
                var lat = minLat
                while (lat <= maxLat) {
                    add(Rd5TileId(lon, lat))
                    lat += 5
                }
            }
        }
    }

    /**
     * Selects tiles along the supplied polyline instead of downloading its entire bounding box.
     * Long legs are sampled on the great-circle path. `safetyTileRadius=1` adds the immediately
     * neighboring 5° tiles so reasonable road/ferry detours remain available.
     *
     * This is a prefetch estimate, not proof of graph coverage. After a real route exists, call it
     * with the routed geometry for a much tighter refresh/download plan.
     */
    fun forCorridor(
        points: List<GeoPoint>,
        safetyTileRadius: Int = 1,
        sampleSpacingKm: Double = 120.0,
    ): Set<Rd5TileId> {
        require(points.isNotEmpty())
        require(safetyTileRadius in 0..3)
        require(sampleSpacingKm in 10.0..500.0 && sampleSpacingKm.isFinite())

        val centerTiles = buildSet {
            add(Rd5TileId.from(points.first()))
            points.zipWithNext().forEach { (start, end) ->
                val steps = ceil(distanceMeters(start, end) / (sampleSpacingKm * 1_000.0))
                    .toInt().coerceAtLeast(1)
                repeat(steps + 1) { step ->
                    add(Rd5TileId.from(interpolateGreatCircle(start, end, step.toDouble() / steps)))
                }
            }
        }
        if (safetyTileRadius == 0) return centerTiles

        return buildSet {
            centerTiles.forEach { center ->
                for (latOffset in -safetyTileRadius..safetyTileRadius) {
                    val lat = center.southLatitude + latOffset * 5
                    if (lat !in -90..85) continue
                    for (lonOffset in -safetyTileRadius..safetyTileRadius) {
                        val lon = wrapTileLongitude(center.westLongitude + lonOffset * 5)
                        add(Rd5TileId(lon, lat))
                    }
                }
            }
        }
    }

    private fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = a.latitude.toRadians()
        val lat2 = b.latitude.toRadians()
        val dLat = (b.latitude - a.latitude).toRadians()
        val dLon = shortestLongitudeDelta(a.longitude, b.longitude).toRadians()
        val hav = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        val bounded = hav.coerceIn(0.0, 1.0)
        return EARTH_RADIUS_METERS * 2.0 * atan2(sqrt(bounded), sqrt(1.0 - bounded))
    }

    private fun interpolateGreatCircle(start: GeoPoint, end: GeoPoint, fraction: Double): GeoPoint {
        if (fraction <= 0.0) return start
        if (fraction >= 1.0) return end
        val lat1 = start.latitude.toRadians()
        val lon1 = start.longitude.toRadians()
        val lat2 = end.latitude.toRadians()
        val lon2 = end.longitude.toRadians()
        val angular = distanceMeters(start, end) / EARTH_RADIUS_METERS
        if (angular < 1e-12) return start
        val sinAngular = sin(angular)
        if (kotlin.math.abs(sinAngular) < 1e-12) return GeoPoint(
            start.latitude + (end.latitude - start.latitude) * fraction,
            normalizeLongitude(start.longitude + shortestLongitudeDelta(start.longitude, end.longitude) * fraction),
        )
        val a = sin((1.0 - fraction) * angular) / sinAngular
        val b = sin(fraction * angular) / sinAngular
        val x = a * cos(lat1) * cos(lon1) + b * cos(lat2) * cos(lon2)
        val y = a * cos(lat1) * sin(lon1) + b * cos(lat2) * sin(lon2)
        val z = a * sin(lat1) + b * sin(lat2)
        return GeoPoint(
            latitude = atan2(z, sqrt(x * x + y * y)) * 180.0 / PI,
            longitude = normalizeLongitude(atan2(y, x) * 180.0 / PI),
        )
    }

    private fun wrapTileLongitude(value: Int): Int {
        var wrapped = value
        while (wrapped < -180) wrapped += 360
        while (wrapped > 175) wrapped -= 360
        return wrapped
    }

    private fun shortestLongitudeDelta(from: Double, to: Double): Double {
        var delta = to - from
        while (delta > 180.0) delta -= 360.0
        while (delta < -180.0) delta += 360.0
        return delta
    }

    private fun normalizeLongitude(value: Double): Double {
        var normalized = value
        while (normalized > 180.0) normalized -= 360.0
        while (normalized < -180.0) normalized += 360.0
        return normalized
    }

    private fun Double.toRadians(): Double = this * PI / 180.0

    private const val EARTH_RADIUS_METERS = 6_371_008.8
}
