package tools.loam.wayloam.router.brouter

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest

object BundledBRouterProfiles {
    private val files = listOf("fastbike.brf", "trekking.brf", "lookups.dat")

    /** Versioned directories prevent an upstream update from overwriting profiles in active use. */
    @Synchronized fun install(root: File): File {
        val destination = File(root, BRouterBaseline.COMMIT)
        Files.createDirectories(destination.toPath())
        files.forEach { name ->
            val bytes = javaClass.getResourceAsStream("/$name")?.use { it.readBytes() }
                ?: throw MissingBRouterProfileException("Bundled BRouter resource is missing: $name")
            val target = File(destination, name).toPath()
            val digest = MessageDigest.getInstance("SHA-256")
            if (Files.isRegularFile(target) && MessageDigest.isEqual(
                    digest.digest(bytes), digest.digest(Files.readAllBytes(target)))) return@forEach
            val temporary = Files.createTempFile(destination.toPath(), name, ".tmp")
            try {
                Files.write(temporary, bytes)
                try { Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(temporary) }
        }
        return destination
    }
}
