package tools.loam.wayloam.router.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tools.loam.wayloam.router.api.GeoPoint

class Rd5TileTest {
    @Test
    fun resolvesKnownEuropeanTiles() {
        assertEquals("E5_N45.rd5", Rd5TileId.from(GeoPoint(49.3988, 8.6724)).fileName)
        assertEquals("E10_N55.rd5", Rd5TileId.from(GeoPoint(59.9139, 10.7522)).fileName)
        assertEquals("W10_N35.rd5", Rd5TileId.from(GeoPoint(38.7223, -9.1393)).fileName)
    }

    @Test
    fun exactFiveDegreeBoundariesBelongToTheTileStartingAtThatBoundary() {
        assertEquals("E5_N45.rd5", Rd5TileId.from(GeoPoint(45.0, 5.0)).fileName)
        assertEquals("E10_N50.rd5", Rd5TileId.from(GeoPoint(50.0, 10.0)).fileName)
        assertEquals("W5_N45.rd5", Rd5TileId.from(GeoPoint(45.0, -5.0)).fileName)
        assertEquals("W10_N45.rd5", Rd5TileId.from(GeoPoint(45.0, -10.0)).fileName)
    }

    @Test
    fun negativeCoordinatesFloorTowardTheSouthWestTile() {
        assertEquals("W5_S5.rd5", Rd5TileId.from(GeoPoint(-0.000001, -0.000001)).fileName)
        assertEquals("W10_S10.rd5", Rd5TileId.from(GeoPoint(-5.000001, -5.000001)).fileName)
        assertEquals("W5_N0.rd5", Rd5TileId.from(GeoPoint(0.000001, -0.000001)).fileName)
        assertEquals("E0_S5.rd5", Rd5TileId.from(GeoPoint(-0.000001, 0.000001)).fileName)
    }

    @Test
    fun envelopeIncludesEveryIntersectingFiveDegreeTile() {
        val tiles = Rd5TileSet.forEnvelope(
            listOf(
                GeoPoint(49.3988, 8.6724),
                GeoPoint(59.9139, 10.7522),
            )
        )

        assertEquals(6, tiles.size)
        assertTrue(tiles.any { it.fileName == "E5_N45.rd5" })
        assertTrue(tiles.any { it.fileName == "E10_N55.rd5" })
    }

    @Test
    fun envelopeCrossingPrimeMeridianIncludesWesternAndEasternTiles() {
        val tiles = Rd5TileSet.forEnvelope(
            listOf(
                GeoPoint(49.0, -0.1),
                GeoPoint(51.0, 0.1),
            )
        )

        assertEquals(
            setOf("W5_N45.rd5", "E0_N45.rd5", "W5_N50.rd5", "E0_N50.rd5"),
            tiles.map(Rd5TileId::fileName).toSet(),
        )
    }
}
