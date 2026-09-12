package tools.loam.wayloam.router.core

import tools.loam.wayloam.router.api.*
import java.io.*
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

/** Disposable, checksummed, atomic disk cache. Never use Java object deserialization for cache data. */
class FileRouteCache(
    private val directory: Path,
    private val maxBytes: Long = 256L * 1024 * 1024,
    private val maxEntryBytes: Int = 16 * 1024 * 1024,
) : RouteCache {
    init {
        require(maxEntryBytes >= 1024 && maxBytes >= maxEntryBytes)
        Files.createDirectories(directory)
    }

    @Synchronized override fun get(key: String): RouteResult? {
        val path = path(key)
        if (!Files.isRegularFile(path)) return null
        return try {
            val size = Files.size(path)
            if (size !in 36L..maxEntryBytes.toLong()) throw IOException("Invalid cache size")
            val bytes = Files.newInputStream(path).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > maxEntryBytes) throw IOException("Cache entry exceeds limit")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            if (bytes.size < 36) throw IOException("Truncated cache")
            val payloadSize = bytes.size - 32
            val digest = MessageDigest.getInstance("SHA-256").apply { update(bytes, 0, payloadSize) }.digest()
            if (!MessageDigest.isEqual(digest, bytes.copyOfRange(payloadSize, bytes.size))) {
                throw IOException("Cache checksum mismatch")
            }
            val result = DataInputStream(ByteArrayInputStream(bytes, 0, payloadSize)).use { input ->
                if (input.readInt() != MAGIC || input.readUTF() != key) throw IOException("Unknown cache schema")
                val engine = input.readUTF()
                val count = input.readInt()
                if (count !in 1..4096) throw IOException("Invalid segment count")
                var remainingPoints = maxEntryBytes / 17
                val segments = List(count) { i ->
                    val start = input.readPoint()
                    val end = input.readPoint()
                    val metrics = RouteMetrics(input.readLong(), input.readInt(), input.readInt(), input.readLong())
                    val label = input.readUTF()
                    val points = input.readInt()
                    if (points < 2 || points > remainingPoints) throw IOException("Invalid point count")
                    remainingPoints -= points
                    val geometry = List(points) { input.readPoint() }
                    val annotationCount = input.readInt()
                    if (annotationCount !in 0..MAX_ANNOTATIONS_PER_SEGMENT) throw IOException("Invalid annotation count")
                    val annotations = List(annotationCount) { input.readAnnotation() }
                    val dependencyCount = input.readInt()
                    if (dependencyCount !in 0..MAX_DEPENDENCIES_PER_SEGMENT) throw IOException("Invalid dependency count")
                    val dependencies = buildMap {
                        repeat(dependencyCount) {
                            val name = input.readUTF()
                            val fingerprint = input.readUTF()
                            if (name.isBlank() || '/' in name || '\\' in name || fingerprint.isBlank()) {
                                throw IOException("Invalid cache dependency")
                            }
                            put(name, fingerprint)
                        }
                    }
                    RouteSegment(i, start, end, geometry, metrics, label, annotations, dependencies)
                }
                if (input.read() != -1) throw IOException("Trailing cache data")
                RouteStitcher.stitch(segments, engine)
            }
            runCatching { Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis())) }
            result
        } catch (_: IOException) {
            discard(path)
            null
        } catch (_: IllegalArgumentException) {
            discard(path)
            null
        }
    }

    @Synchronized override fun put(key: String, result: RouteResult) {
        val destination = path(key)
        var temporary: Path? = null
        try {
            val bytes = ByteArrayOutputStream()
            val bounded = object : FilterOutputStream(bytes) {
                private fun checkSize(n: Int) {
                    if (bytes.size().toLong() + n > maxEntryBytes - 32L) throw IOException("Cache entry too large")
                }
                override fun write(b: Int) { checkSize(1); out.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { checkSize(len); out.write(b, off, len) }
            }
            DataOutputStream(bounded).use { output ->
                output.writeInt(MAGIC)
                output.writeUTF(key)
                output.writeUTF(result.engine)
                output.writeInt(result.segments.size)
                result.segments.forEach { segment ->
                    output.writePoint(segment.start)
                    output.writePoint(segment.end)
                    output.writeLong(segment.metrics.distanceMeters)
                    output.writeInt(segment.metrics.ascentMeters)
                    output.writeInt(segment.metrics.descentMeters)
                    output.writeLong(segment.metrics.durationSeconds)
                    output.writeUTF(segment.engine)
                    output.writeInt(segment.points.size)
                    segment.points.forEach { output.writePoint(it) }
                    if (segment.annotations.size > MAX_ANNOTATIONS_PER_SEGMENT) throw IOException("Too many annotations")
                    output.writeInt(segment.annotations.size)
                    segment.annotations.forEach { output.writeAnnotation(it) }
                    if (segment.dataDependencies.size > MAX_DEPENDENCIES_PER_SEGMENT) throw IOException("Too many dependencies")
                    output.writeInt(segment.dataDependencies.size)
                    segment.dataDependencies.toSortedMap().forEach { (name, fingerprint) ->
                        output.writeUTF(name)
                        output.writeUTF(fingerprint)
                    }
                }
            }
            val payload = bytes.toByteArray()
            Files.createDirectories(directory)
            temporary = Files.createTempFile(directory, "route-", ".tmp")
            FileOutputStream(temporary.toFile()).use { file ->
                file.write(payload)
                file.write(MessageDigest.getInstance("SHA-256").digest(payload))
                file.fd.sync()
            }
            try {
                Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, REPLACE_EXISTING)
            }
            trim()
        } catch (_: IOException) {
            // A full/removed cache must not turn a successfully calculated route into a failure.
        } finally {
            temporary?.let(::discard)
        }
    }

    @Synchronized fun clear() {
        entries().forEach(::discard)
    }

    @Synchronized fun sizeBytes(): Long = entries().sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }

    private fun trim() {
        val entries = entries().sortedBy { Files.getLastModifiedTime(it).toMillis() }
        var total = entries.sumOf { Files.size(it) }
        for (file in entries) {
            if (total <= maxBytes) break
            val size = Files.size(file)
            if (Files.deleteIfExists(file)) total -= size
        }
    }

    private fun entries(): List<Path> = if (!Files.isDirectory(directory)) emptyList() else
        Files.list(directory).use { stream ->
            stream.iterator().asSequence().filter {
                Files.isRegularFile(it) && FILE_PATTERN.matches(it.fileName.toString())
            }.toList()
        }

    private fun path(key: String): Path {
        require(KEY_PATTERN.matches(key)) { "Cache key must be a SHA-256 digest" }
        return directory.resolve("$key.route")
    }

    private fun discard(path: Path) { runCatching { Files.deleteIfExists(path) } }

    private fun DataOutputStream.writePoint(point: GeoPoint) {
        writeDouble(point.latitude)
        writeDouble(point.longitude)
        writeBoolean(point.elevationMeters != null)
        point.elevationMeters?.let { writeDouble(it) }
    }

    private fun DataInputStream.readPoint() = GeoPoint(readDouble(), readDouble(), if (readBoolean()) readDouble() else null)

    private fun DataOutputStream.writeAnnotation(annotation: RouteAnnotation) {
        writeDouble(annotation.startDistanceMeters)
        writeDouble(annotation.endDistanceMeters)
        writeInt(annotation.surface.ordinal)
        writeInt(annotation.roadClass.ordinal)
        writeInt(annotation.cycleway.ordinal)
        writeInt(annotation.trackType.ordinal)
        writeInt(annotation.smoothness.ordinal)
        writeInt(annotation.trafficStress.ordinal)
        writeNullableBoolean(annotation.ferry)
        writeNullableBoolean(annotation.tunnel)
        writeNullableBoolean(annotation.steps)
        writeNullableBoolean(annotation.unpaved)
        writeNullableBoolean(annotation.limitedAccess)
        writeNullableBoolean(annotation.bikeCarryLikely)
    }

    private fun DataInputStream.readAnnotation() = RouteAnnotation(
        startDistanceMeters = readDouble(),
        endDistanceMeters = readDouble(),
        surface = readEnum(SurfaceType.entries),
        roadClass = readEnum(RoadClass.entries),
        cycleway = readEnum(CyclewayType.entries),
        trackType = readEnum(TrackType.entries),
        smoothness = readEnum(Smoothness.entries),
        trafficStress = readEnum(TrafficStress.entries),
        ferry = readNullableBoolean(),
        tunnel = readNullableBoolean(),
        steps = readNullableBoolean(),
        unpaved = readNullableBoolean(),
        limitedAccess = readNullableBoolean(),
        bikeCarryLikely = readNullableBoolean(),
    )

    private fun DataOutputStream.writeNullableBoolean(value: Boolean?) {
        writeByte(when (value) { null -> 0; false -> 1; true -> 2 })
    }

    private fun DataInputStream.readNullableBoolean(): Boolean? = when (readUnsignedByte()) {
        0 -> null
        1 -> false
        2 -> true
        else -> throw IOException("Invalid nullable boolean")
    }

    private fun <T : Enum<T>> DataInputStream.readEnum(values: List<T>): T {
        val ordinal = readInt()
        if (ordinal !in values.indices) throw IOException("Invalid enum ordinal")
        return values[ordinal]
    }

    companion object {
        private const val MAGIC = 0x574C5203
        private const val MAX_ANNOTATIONS_PER_SEGMENT = 100_000
        private const val MAX_DEPENDENCIES_PER_SEGMENT = 512
        private val KEY_PATTERN = Regex("[a-f0-9]{64}")
        private val FILE_PATTERN = Regex("[a-f0-9]{64}\\.route")
    }
}
