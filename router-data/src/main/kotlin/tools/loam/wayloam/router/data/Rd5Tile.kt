package tools.loam.wayloam.router.data

import tools.loam.wayloam.router.api.GeoPoint
import kotlin.math.floor

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
     * This is intentionally conservative for bootstrap data planning; corridor-aware pruning comes
     * after the embedded engine can report the exact tiles it touches.
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
}

