package tools.loam.wayloam.router.data

/** Immutable description of a downloadable BRouter tile. */
data class Rd5RemoteArtifact(
    val tile: Rd5TileId,
    val sourceId: String,
    val sourceVersion: String,
    val downloadUrl: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
) {
    init {
        require(sourceId.isNotBlank())
        require(sourceVersion.isNotBlank())
        require(downloadUrl.isNotBlank())
        require(sizeBytes == null || sizeBytes > 0L)
        require(sha256 == null || SHA256_PATTERN.matches(sha256))
    }

    val normalizedSha256: String? get() = sha256?.lowercase()

    companion object {
        private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
    }
}

fun interface RoutingDataManifest {
    suspend fun artifact(tile: Rd5TileId): Rd5RemoteArtifact?
}

data class StaticRoutingDataManifest(
    private val artifacts: Map<Rd5TileId, Rd5RemoteArtifact>,
) : RoutingDataManifest {
    constructor(artifacts: Iterable<Rd5RemoteArtifact>) : this(
        artifacts.associateBy { it.tile }.also { indexed ->
            require(indexed.size == artifacts.count()) { "Routing-data manifest contains duplicate tiles" }
        }
    )

    override suspend fun artifact(tile: Rd5TileId): Rd5RemoteArtifact? = artifacts[tile]
}
