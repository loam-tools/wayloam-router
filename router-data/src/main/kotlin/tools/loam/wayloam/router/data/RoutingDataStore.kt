package tools.loam.wayloam.router.data

import java.nio.file.Files
import java.nio.file.Path

interface RoutingDataStore {
    fun contains(tile: Rd5TileId): Boolean
    fun path(tile: Rd5TileId): Path?
    fun installedTiles(): Set<Rd5TileId>
}

class FileRoutingDataStore(
    private val root: Path,
) : RoutingDataStore {

    override fun contains(tile: Rd5TileId): Boolean = path(tile) != null

    override fun path(tile: Rd5TileId): Path? {
        val candidate = root.resolve(tile.fileName)
        return candidate.takeIf {
            Files.isRegularFile(it) && runCatching { Files.size(it) > 0L }.getOrDefault(false)
        }
    }

    override fun installedTiles(): Set<Rd5TileId> {
        if (!Files.isDirectory(root)) return emptySet()
        return Files.list(root).use { stream ->
            stream
                .filter(Files::isRegularFile)
                .map { it.fileName.toString() }
                .filter { it.endsWith(".rd5") }
                .mapNotNull(::parseFileName)
                .toList()
                .toSet()
        }
    }

    private fun parseFileName(fileName: String): Rd5TileId? {
        val match = TILE_PATTERN.matchEntire(fileName) ?: return null
        val lonSign = if (match.groupValues[1] == "W") -1 else 1
        val latSign = if (match.groupValues[3] == "S") -1 else 1
        val lon = match.groupValues[2].toIntOrNull()?.times(lonSign) ?: return null
        val lat = match.groupValues[4].toIntOrNull()?.times(latSign) ?: return null
        return runCatching { Rd5TileId(lon, lat) }.getOrNull()
    }

    companion object {
        private val TILE_PATTERN = Regex("([EW])(\\d+)_([NS])(\\d+)\\.rd5")
    }
}
