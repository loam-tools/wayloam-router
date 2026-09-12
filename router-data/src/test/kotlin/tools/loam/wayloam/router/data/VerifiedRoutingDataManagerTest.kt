package tools.loam.wayloam.router.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

class VerifiedRoutingDataManagerTest {
    private val tile = Rd5TileId(5, 45)

    @Test
    fun verifiedDownloadIsPromotedAndReused() = runBlocking {
        val root = Files.createTempDirectory("wayloam-data-test")
        val bytes = "verified-rd5-data".toByteArray()
        var calls = 0
        val manager = manager(root, bytes) { request ->
            calls++
            Files.createDirectories(request.destination.parent)
            Files.write(request.destination, bytes)
            RoutingDataDownloadResult(bytes.size.toLong(), resumed = false)
        }

        try {
            val first = manager.ensureTile(tile)
            val second = manager.ensureTile(tile)

            assertTrue(first.downloaded)
            assertFalse(second.downloaded)
            assertEquals(1, calls)
            assertEquals(RoutingTileState.INSTALLED_VERIFIED, manager.status(tile).state)
            assertArrayEquals(bytes, Files.readAllBytes(root.resolve(tile.fileName)))
            assertFalse(Files.exists(root.resolve(".staging/${tile.fileName}.part")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun changedRemoteVersionRedownloadsEvenWhenByteSizeIsUnchanged() = runBlocking {
        val root = Files.createTempDirectory("wayloam-data-version-test")
        val v1 = "route-data-v1".toByteArray()
        val v2 = "route-data-v2".toByteArray()
        var currentVersion = "etag:v1"
        var currentBytes = v1
        var calls = 0
        val manifest = RoutingDataManifest {
            Rd5RemoteArtifact(
                tile = tile,
                sourceId = "fixture",
                sourceVersion = currentVersion,
                downloadUrl = "https://example.invalid/${tile.fileName}",
                sizeBytes = currentBytes.size.toLong(),
                sha256 = null,
            )
        }
        val manager = VerifiedRoutingDataManager(
            segmentsRoot = root,
            manifest = manifest,
            transport = RoutingDataTransport { request ->
                calls++
                Files.createDirectories(request.destination.parent)
                Files.write(request.destination, currentBytes)
                RoutingDataDownloadResult(currentBytes.size.toLong(), resumed = false)
            },
        )

        try {
            manager.ensureTile(tile)
            assertArrayEquals(v1, Files.readAllBytes(root.resolve(tile.fileName)))

            currentVersion = "etag:v2"
            currentBytes = v2
            val refreshed = manager.ensureTile(tile)

            assertTrue(refreshed.downloaded)
            assertEquals(2, calls)
            assertArrayEquals(v2, Files.readAllBytes(root.resolve(tile.fileName)))
            assertEquals("etag:v2", refreshed.sourceVersion)
            assertEquals(RoutingTileState.INSTALLED_VERIFIED, manager.status(tile).state)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun checksumMismatchNeverBecomesInstalled() = runBlocking {
        val root = Files.createTempDirectory("wayloam-data-test")
        val expected = "correct".toByteArray()
        val wrong = "corrupt".toByteArray()
        val manager = manager(root, expected) { request ->
            Files.createDirectories(request.destination.parent)
            Files.write(request.destination, wrong)
            RoutingDataDownloadResult(wrong.size.toLong(), resumed = false)
        }

        try {
            val failure = runCatching { manager.ensureTile(tile) }.exceptionOrNull()
            assertTrue(failure is RoutingDataVerificationException)
            assertFalse(Files.exists(root.resolve(tile.fileName)))
            assertFalse(Files.exists(root.resolve(".staging/${tile.fileName}.part")))
            assertEquals(RoutingTileState.MISSING, manager.status(tile).state)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun partialDownloadResumesButIsNotInstalledBeforeVerification() = runBlocking {
        val root = Files.createTempDirectory("wayloam-data-test")
        val bytes = "0123456789abcdef".toByteArray()
        val partial = bytes.copyOfRange(0, 6)
        val staging = root.resolve(".staging/${tile.fileName}.part")
        Files.createDirectories(staging.parent)
        Files.write(staging, partial)

        var observedResume = -1L
        val manager = manager(root, bytes) { request ->
            observedResume = request.resumeFromBytes
            Files.write(
                request.destination,
                bytes.copyOfRange(request.resumeFromBytes.toInt(), bytes.size),
                StandardOpenOption.APPEND,
            )
            RoutingDataDownloadResult(
                bytesWritten = (bytes.size.toLong() - request.resumeFromBytes).coerceAtLeast(0L),
                resumed = request.resumeFromBytes > 0L,
            )
        }

        try {
            assertFalse(Files.exists(root.resolve(tile.fileName)))
            assertEquals(RoutingTileState.PARTIAL, manager.status(tile).state)

            val result = manager.ensureTile(tile)
            assertEquals(partial.size.toLong(), observedResume)
            assertTrue(result.resumed)
            assertArrayEquals(bytes, Files.readAllBytes(root.resolve(tile.fileName)))
            assertEquals(RoutingTileState.INSTALLED_VERIFIED, manager.status(tile).state)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun corruptInstalledTileIsRepairedThroughStaging() = runBlocking {
        val root = Files.createTempDirectory("wayloam-data-test")
        val bytes = "replacement-rd5".toByteArray()
        Files.write(root.resolve(tile.fileName), "bad".toByteArray())
        var calls = 0
        val manager = manager(root, bytes) { request ->
            calls++
            Files.createDirectories(request.destination.parent)
            Files.write(request.destination, bytes)
            RoutingDataDownloadResult(bytes.size.toLong(), resumed = false)
        }

        try {
            assertEquals(RoutingTileState.CORRUPT, manager.status(tile).state)
            manager.ensureTile(tile)
            assertEquals(1, calls)
            assertArrayEquals(bytes, Files.readAllBytes(root.resolve(tile.fileName)))
            assertEquals(RoutingTileState.INSTALLED_VERIFIED, manager.status(tile).state)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedRefreshPreservesTheInstalledVersion() = runBlocking {
        val root = Files.createTempDirectory("refresh-preserve")
        val oldBytes = "old-valid".toByteArray()
        var artifact = Rd5RemoteArtifact(tile, "fixture", "v1", "https://example.invalid/tile",
            oldBytes.size.toLong(), sha256(oldBytes))
        var fail = false
        val manager = VerifiedRoutingDataManager(root, RoutingDataManifest { artifact }, RoutingDataTransport { request ->
            if (fail) throw java.io.IOException("network unavailable")
            Files.write(request.destination, oldBytes)
            RoutingDataDownloadResult(oldBytes.size.toLong(), false)
        })
        try {
            manager.ensureTile(tile)
            artifact = artifact.copy(sourceVersion = "v2", sha256 = sha256("new-valid".toByteArray()))
            fail = true
            assertTrue(runCatching { manager.ensureTile(tile) }.isFailure)
            assertArrayEquals(oldBytes, Files.readAllBytes(root.resolve(tile.fileName)))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun savedChecksumDetectsSameSizeCorruptionWithoutRemoteDigest() = runBlocking {
        val root = Files.createTempDirectory("local-hash")
        val bytes = "original".toByteArray()
        val artifact = Rd5RemoteArtifact(tile, "fixture", "etag:1", "https://example.invalid/tile", bytes.size.toLong())
        var downloads = 0
        val manager = VerifiedRoutingDataManager(root, StaticRoutingDataManifest(listOf(artifact)), RoutingDataTransport { request ->
            downloads++
            Files.write(request.destination, bytes)
            RoutingDataDownloadResult(bytes.size.toLong(), false)
        })
        try {
            manager.ensureTile(tile)
            Files.write(root.resolve(tile.fileName), "modified".toByteArray())
            assertEquals(RoutingTileState.CORRUPT, manager.status(tile).state)
            manager.ensureTile(tile)
            assertEquals(2, downloads)
            assertArrayEquals(bytes, Files.readAllBytes(root.resolve(tile.fileName)))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun aChangedSourceNeverResumesAnOldUnpinnedPartial() = runBlocking {
        val root = Files.createTempDirectory("partial-version")
        var artifact = Rd5RemoteArtifact(tile, "fixture", "v1", "https://example.invalid/tile", 8)
        var first = true
        var offset = -1L
        val manager = VerifiedRoutingDataManager(root, RoutingDataManifest { artifact }, RoutingDataTransport { request ->
            if (first) {
                Files.write(request.destination, "old".toByteArray())
                first = false
                throw java.io.IOException("interrupted")
            }
            offset = request.resumeFromBytes
            Files.write(request.destination, "new-data".toByteArray())
            RoutingDataDownloadResult(8, false)
        })
        try {
            assertTrue(runCatching { manager.ensureTile(tile) }.isFailure)
            artifact = artifact.copy(sourceVersion = "v2")
            manager.ensureTile(tile)
            assertEquals(0L, offset)
            assertArrayEquals("new-data".toByteArray(), Files.readAllBytes(root.resolve(tile.fileName)))
        } finally { root.toFile().deleteRecursively() }
    }

    private fun manager(
        root: java.nio.file.Path,
        bytes: ByteArray,
        transport: suspend (RoutingDataDownloadRequest) -> RoutingDataDownloadResult,
    ): VerifiedRoutingDataManager {
        val artifact = Rd5RemoteArtifact(
            tile = tile,
            sourceId = "fixture",
            sourceVersion = "2026-09-12",
            downloadUrl = "https://example.invalid/${tile.fileName}",
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256(bytes),
        )
        return VerifiedRoutingDataManager(
            segmentsRoot = root,
            manifest = StaticRoutingDataManifest(listOf(artifact)),
            transport = RoutingDataTransport { request -> transport(request) },
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

