package tools.loam.wayloam.router.data

import java.nio.file.Path

/**
 * Network/storage transport boundary. Implementations may use HTTP, a local mirror, tests, etc.
 * The manager owns staging, verification and atomic installation; the transport only writes bytes.
 */
fun interface RoutingDataTransport {
    suspend fun download(request: RoutingDataDownloadRequest): RoutingDataDownloadResult
}

data class RoutingDataDownloadRequest(
    val artifact: Rd5RemoteArtifact,
    val destination: Path,
    val resumeFromBytes: Long = 0L,
) {
    init {
        require(resumeFromBytes >= 0L)
    }
}

data class RoutingDataDownloadResult(
    val bytesWritten: Long,
    val resumed: Boolean,
) {
    init {
        require(bytesWritten >= 0L)
    }
}
