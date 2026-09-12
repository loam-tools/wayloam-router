package tools.loam.wayloam.router.data

import java.io.BufferedInputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties

enum class RoutingTileState {
    MISSING,
    PARTIAL,
    INSTALLED_UNVERIFIED,
    INSTALLED_VERIFIED,
    CORRUPT,
}

data class RoutingTileStatus(
    val tile: Rd5TileId,
    val state: RoutingTileState,
    val installedBytes: Long = 0L,
    val stagedBytes: Long = 0L,
    val sourceId: String? = null,
    val sourceVersion: String? = null,
)

data class RoutingTileInstallResult(
    val tile: Rd5TileId,
    val installedPath: Path,
    val downloaded: Boolean,
    val resumed: Boolean,
    val bytes: Long,
    val sha256: String,
    val sourceId: String,
    val sourceVersion: String,
)

class MissingRoutingDataArtifactException(tile: Rd5TileId) :
    IllegalStateException("No routing-data artifact is available for ${tile.fileName}")

class RoutingDataVerificationException(message: String) : IllegalStateException(message)

/**
 * Owns the lifecycle of local .rd5 data: staging, verification, atomic promotion and metadata.
 * No HTTP implementation lives here; [RoutingDataTransport] is injected by the host application.
 */
class VerifiedRoutingDataManager(
    private val segmentsRoot: Path,
    private val manifest: RoutingDataManifest,
    private val transport: RoutingDataTransport,
    private val clock: () -> Instant = Instant::now,
) {
    private val stagingRoot: Path = segmentsRoot.resolve(".staging")
    private val metadataRoot: Path = segmentsRoot.resolve(".metadata")

    suspend fun ensureTiles(tiles: Set<Rd5TileId>): List<RoutingTileInstallResult> =
        tiles.sortedWith(compareBy(Rd5TileId::westLongitude, Rd5TileId::southLatitude))
            .map { ensureTile(it) }

    suspend fun ensureTile(tile: Rd5TileId): RoutingTileInstallResult {
        val artifact = manifest.artifact(tile) ?: throw MissingRoutingDataArtifactException(tile)
        val finalPath = segmentsRoot.resolve(tile.fileName)

        Files.createDirectories(segmentsRoot)
        Files.createDirectories(stagingRoot)
        Files.createDirectories(metadataRoot)

        if (Files.isRegularFile(finalPath)) {
            val verification = verify(finalPath, artifact)
            if (verification.valid) {
                ensureMetadata(finalPath, artifact, verification)
                return result(
                    artifact = artifact,
                    path = finalPath,
                    downloaded = false,
                    resumed = false,
                    verification = verification,
                )
            }
            Files.deleteIfExists(finalPath)
            Files.deleteIfExists(metadataPath(tile))
        }

        val stagingPath = stagingPath(tile)
        val resumeFrom = Files.takeIf { Files.isRegularFile(stagingPath) }
            ?.size(stagingPath)
            ?: 0L

        val response = transport.download(
            RoutingDataDownloadRequest(
                artifact = artifact,
                destination = stagingPath,
                resumeFromBytes = resumeFrom,
            )
        )

        if (!Files.isRegularFile(stagingPath)) {
            throw RoutingDataVerificationException(
                "Transport completed without creating ${stagingPath.fileName}"
            )
        }

        val verification = verify(stagingPath, artifact)
        if (!verification.valid) {
            Files.deleteIfExists(stagingPath)
            throw RoutingDataVerificationException(verification.failureMessage(tile))
        }

        promoteAtomically(stagingPath, finalPath)
        writeMetadata(tile, artifact, verification)

        return result(
            artifact = artifact,
            path = finalPath,
            downloaded = true,
            resumed = response.resumed || resumeFrom > 0L,
            verification = verification,
        )
    }

    suspend fun status(tile: Rd5TileId): RoutingTileStatus {
        val finalPath = segmentsRoot.resolve(tile.fileName)
        val stagedPath = stagingPath(tile)
        val installedBytes = sizeOrZero(finalPath)
        val stagedBytes = sizeOrZero(stagedPath)

        if (!Files.isRegularFile(finalPath)) {
            return RoutingTileStatus(
                tile = tile,
                state = if (stagedBytes > 0L) RoutingTileState.PARTIAL else RoutingTileState.MISSING,
                stagedBytes = stagedBytes,
            )
        }

        val artifact = manifest.artifact(tile)
            ?: return RoutingTileStatus(
                tile = tile,
                state = RoutingTileState.INSTALLED_UNVERIFIED,
                installedBytes = installedBytes,
                stagedBytes = stagedBytes,
            )

        val verification = verify(finalPath, artifact)
        val metadata = readMetadata(tile)
        val state = when {
            !verification.valid -> RoutingTileState.CORRUPT
            metadata == null -> RoutingTileState.INSTALLED_UNVERIFIED
            metadata.sourceId != artifact.sourceId || metadata.sourceVersion != artifact.sourceVersion ->
                RoutingTileState.INSTALLED_UNVERIFIED
            else -> RoutingTileState.INSTALLED_VERIFIED
        }

        return RoutingTileStatus(
            tile = tile,
            state = state,
            installedBytes = installedBytes,
            stagedBytes = stagedBytes,
            sourceId = metadata?.sourceId,
            sourceVersion = metadata?.sourceVersion,
        )
    }

    fun remove(tile: Rd5TileId) {
        Files.deleteIfExists(segmentsRoot.resolve(tile.fileName))
        Files.deleteIfExists(stagingPath(tile))
        Files.deleteIfExists(metadataPath(tile))
    }

    fun storageBytes(): Long {
        if (!Files.isDirectory(segmentsRoot)) return 0L
        return Files.walk(segmentsRoot).use { paths ->
            paths.iterator().asSequence()
                .filter(Files::isRegularFile)
                .sumOf(::sizeOrZero)
        }
    }

    private fun ensureMetadata(
        installedPath: Path,
        artifact: Rd5RemoteArtifact,
        verification: Verification,
    ) {
        val metadata = readMetadata(artifact.tile)
        if (
            metadata == null ||
            metadata.sourceId != artifact.sourceId ||
            metadata.sourceVersion != artifact.sourceVersion ||
            metadata.sha256 != verification.sha256 ||
            metadata.sizeBytes != verification.sizeBytes
        ) {
            writeMetadata(artifact.tile, artifact, verification)
        }
        check(Files.isRegularFile(installedPath))
    }

    private fun verify(path: Path, artifact: Rd5RemoteArtifact): Verification {
        if (!Files.isRegularFile(path)) return Verification(0L, "", false, "file is missing")
        val size = sizeOrZero(path)
        if (size <= 0L) return Verification(size, "", false, "file is empty")

        val expectedSize = artifact.sizeBytes
        if (expectedSize != null && size != expectedSize) {
            return Verification(size, "", false, "expected $expectedSize bytes but found $size")
        }

        val digest = sha256(path)
        val expectedDigest = artifact.normalizedSha256
        if (expectedDigest != null && digest != expectedDigest) {
            return Verification(size, digest, false, "SHA-256 mismatch")
        }
        return Verification(size, digest, true, null)
    }

    private fun promoteAtomically(stagedPath: Path, finalPath: Path) {
        try {
            Files.move(
                stagedPath,
                finalPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(stagedPath, finalPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun writeMetadata(
        tile: Rd5TileId,
        artifact: Rd5RemoteArtifact,
        verification: Verification,
    ) {
        Files.createDirectories(metadataRoot)
        val target = metadataPath(tile)
        val temporary = metadataRoot.resolve("${tile.fileName}.properties.tmp")
        val properties = Properties().apply {
            setProperty("sourceId", artifact.sourceId)
            setProperty("sourceVersion", artifact.sourceVersion)
            setProperty("downloadUrl", artifact.downloadUrl)
            setProperty("sizeBytes", verification.sizeBytes.toString())
            setProperty("sha256", verification.sha256)
            setProperty("verifiedAt", clock().toString())
        }
        Files.newOutputStream(temporary).use { properties.store(it, "WAYLOAM routing data") }
        promoteAtomically(temporary, target)
    }

    private fun readMetadata(tile: Rd5TileId): InstalledMetadata? {
        val path = metadataPath(tile)
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            val properties = Properties()
            Files.newInputStream(path).use(properties::load)
            InstalledMetadata(
                sourceId = properties.getProperty("sourceId") ?: return@runCatching null,
                sourceVersion = properties.getProperty("sourceVersion") ?: return@runCatching null,
                sizeBytes = properties.getProperty("sizeBytes")?.toLongOrNull() ?: return@runCatching null,
                sha256 = properties.getProperty("sha256") ?: return@runCatching null,
            )
        }.getOrNull()
    }

    private fun stagingPath(tile: Rd5TileId): Path = stagingRoot.resolve("${tile.fileName}.part")
    private fun metadataPath(tile: Rd5TileId): Path = metadataRoot.resolve("${tile.fileName}.properties")

    private fun result(
        artifact: Rd5RemoteArtifact,
        path: Path,
        downloaded: Boolean,
        resumed: Boolean,
        verification: Verification,
    ) = RoutingTileInstallResult(
        tile = artifact.tile,
        installedPath = path,
        downloaded = downloaded,
        resumed = resumed,
        bytes = verification.sizeBytes,
        sha256 = verification.sha256,
        sourceId = artifact.sourceId,
        sourceVersion = artifact.sourceVersion,
    )

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(Files.newInputStream(path)).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sizeOrZero(path: Path): Long =
        runCatching { if (Files.isRegularFile(path)) Files.size(path) else 0L }.getOrDefault(0L)

    private data class Verification(
        val sizeBytes: Long,
        val sha256: String,
        val valid: Boolean,
        val failure: String?,
    ) {
        fun failureMessage(tile: Rd5TileId): String =
            "Verification failed for ${tile.fileName}: ${failure ?: "unknown failure"}"
    }

    private data class InstalledMetadata(
        val sourceId: String,
        val sourceVersion: String,
        val sizeBytes: Long,
        val sha256: String,
    )
}
