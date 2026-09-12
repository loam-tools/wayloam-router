package tools.loam.wayloam.router.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import tools.loam.wayloam.router.data.Rd5RemoteArtifact
import tools.loam.wayloam.router.data.Rd5TileId
import tools.loam.wayloam.router.data.RoutingDataDownloadRequest
import tools.loam.wayloam.router.data.RoutingDataDownloadResult
import tools.loam.wayloam.router.data.RoutingDataManifest
import tools.loam.wayloam.router.data.RoutingDataTransport
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * Official BRouter segment source plus resumable HTTP transport.
 *
 * The default base URL follows BRouter's documented `segments4` host, but callers may inject a
 * mirror. The verification/install policy remains in router-data; this class only resolves remote
 * metadata and transfers bytes.
 */
class BRouterHttpDataSource(
    baseUrl: String = DEFAULT_BASE_URL,
    private val sourceId: String = DEFAULT_SOURCE_ID,
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 60_000,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : RoutingDataManifest, RoutingDataTransport {
    private val normalizedBaseUrl = baseUrl.trimEnd('/') + "/"

    init {
        require(normalizedBaseUrl.startsWith("https://") || normalizedBaseUrl.startsWith("http://"))
        require(sourceId.isNotBlank())
        require(connectTimeoutMillis > 0)
        require(readTimeoutMillis > 0)
        require(userAgent.isNotBlank())
    }

    override suspend fun artifact(tile: Rd5TileId): Rd5RemoteArtifact? = runInterruptible(Dispatchers.IO) {
        val url = tileUrl(tile)
        val head = execute(method = "HEAD", url = url)
        try {
            when (head.status) {
                HttpURLConnection.HTTP_NOT_FOUND -> null
                HttpURLConnection.HTTP_BAD_METHOD,
                HttpURLConnection.HTTP_NOT_IMPLEMENTED -> probeWithRange(tile, url)
                in 200..299 -> head.toArtifact(tile, url)
                else -> throw classifyHttpFailure(url, head.status, head.message)
            }
        } finally {
            head.connection.disconnect()
        }
    }

    override suspend fun download(request: RoutingDataDownloadRequest): RoutingDataDownloadResult =
        runInterruptible(Dispatchers.IO) {
            downloadBlocking(request, allowRestart = true)
        }

    private fun probeWithRange(tile: Rd5TileId, url: String): Rd5RemoteArtifact? {
        val response = execute(method = "GET", url = url, range = "bytes=0-0")
        try {
            return when (response.status) {
                HttpURLConnection.HTTP_NOT_FOUND -> null
                HttpURLConnection.HTTP_OK,
                HttpURLConnection.HTTP_PARTIAL -> response.toArtifact(tile, url)
                else -> throw classifyHttpFailure(url, response.status, response.message)
            }
        } finally {
            runCatching { response.connection.inputStream?.close() }
            response.connection.disconnect()
        }
    }

    private fun downloadBlocking(
        request: RoutingDataDownloadRequest,
        allowRestart: Boolean,
    ): RoutingDataDownloadResult {
        val resumeFrom = request.resumeFromBytes
        val response = execute(
            method = "GET",
            url = request.artifact.downloadUrl,
            range = resumeFrom.takeIf { it > 0L }?.let { "bytes=$it-" },
            ifRange = request.artifact.sourceVersion.takeIf { resumeFrom > 0L }
                ?.takeIf { it.startsWith("etag:") || it.startsWith("last-modified:") }
                ?.substringAfter(':'),
        )

        try {
            if (response.status == HTTP_RANGE_NOT_SATISFIABLE && resumeFrom > 0L) {
                if (!allowRestart) {
                    throw RoutingDataHttpException(
                        request.artifact.downloadUrl,
                        response.status,
                        "Server rejected a fresh download range",
                    )
                }
                Files.deleteIfExists(request.destination)
                return downloadBlocking(request.copy(resumeFromBytes = 0L), allowRestart = false)
            }

            when (response.status) {
                HttpURLConnection.HTTP_NOT_FOUND ->
                    throw RemoteRoutingDataNotFoundException(request.artifact.downloadUrl)

                HttpURLConnection.HTTP_OK,
                HttpURLConnection.HTTP_PARTIAL -> Unit

                else -> throw classifyHttpFailure(
                    request.artifact.downloadUrl,
                    response.status,
                    response.message,
                )
            }

            val version = request.artifact.sourceVersion
            val observedVersion = when {
                version.startsWith("etag:") -> response.connection.getHeaderField("ETag")?.let { "etag:${it.trim()}" }
                version.startsWith("last-modified:") -> response.connection.getHeaderField("Last-Modified")?.let { "last-modified:${it.trim()}" }
                else -> version
            }
            if (version != observedVersion) throw RoutingDataHttpException(
                request.artifact.downloadUrl, response.status, "Routing data changed since metadata lookup; refresh and retry")
            val resumed = resumeFrom > 0L && response.status == HttpURLConnection.HTTP_PARTIAL
            if (resumed) {
                validateContentRange(response.connection.getHeaderField("Content-Range"), resumeFrom)
            }

            Files.createDirectories(request.destination.parent)
            val options = if (resumed) {
                arrayOf(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                )
            } else {
                arrayOf(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                )
            }

            var written = 0L
            response.connection.inputStream.buffered().use { input ->
                Files.newOutputStream(request.destination, *options).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("Download cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        written += read
                        request.artifact.sizeBytes?.let { expected ->
                            if (written + (if (resumed) resumeFrom else 0L) > expected) {
                                throw IOException("Routing data exceeds the advertised size")
                            }
                        }
                    }
                }
            }

            return RoutingDataDownloadResult(
                bytesWritten = written,
                resumed = resumed,
            )
        } finally {
            response.connection.disconnect()
        }
    }

    private fun execute(
        method: String,
        url: String,
        range: String? = null,
        ifRange: String? = null,
    ): HttpResponse {
        var current = URI(url)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val scheme = current.scheme?.lowercase()
            require(scheme == "https" || scheme == "http") { "Unsupported URL scheme: $scheme" }

            val connection = (current.toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = method
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("Accept-Encoding", "identity")
                if (range != null) setRequestProperty("Range", range)
                if (ifRange != null) setRequestProperty("If-Range", ifRange)
            }
            val status = connection.responseCode
            if (status !in REDIRECT_STATUSES) {
                return HttpResponse(connection, status, connection.responseMessage)
            }

            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) {
                throw RoutingDataHttpException(current.toString(), status, "Redirect missing Location header")
            }
            if (redirectCount >= MAX_REDIRECTS) {
                throw RoutingDataHttpException(current.toString(), status, "Too many redirects")
            }
            val next = current.resolve(location)
            require(current.scheme != "https" || next.scheme == "https") { "Refusing HTTPS downgrade" }
            current = next
        }
        error("unreachable")
    }

    private fun HttpResponse.toArtifact(tile: Rd5TileId, canonicalUrl: String): Rd5RemoteArtifact {
        val etag = connection.getHeaderField("ETag")?.trim()?.takeIf(String::isNotEmpty)
        val lastModified = connection.getHeaderField("Last-Modified")?.trim()?.takeIf(String::isNotEmpty)
        val totalFromRange = parseContentRangeTotal(connection.getHeaderField("Content-Range"))
        val length = totalFromRange ?: connection.contentLengthLong.takeIf { it > 0L }
        val sourceVersion = when {
            etag != null -> "etag:$etag"
            lastModified != null -> "last-modified:$lastModified"
            length != null -> "size:$length"
            else -> "unversioned"
        }

        return Rd5RemoteArtifact(
            tile = tile,
            sourceId = sourceId,
            sourceVersion = sourceVersion,
            downloadUrl = canonicalUrl,
            sizeBytes = length,
            sha256 = null,
        )
    }

    private fun validateContentRange(header: String?, expectedStart: Long) {
        val match = CONTENT_RANGE_REGEX.matchEntire(header.orEmpty())
            ?: throw RoutingDataHttpException(
                "range",
                HttpURLConnection.HTTP_PARTIAL,
                "Missing or malformed Content-Range header: ${header.orEmpty()}",
            )
        val actualStart = match.groupValues[1].toLong()
        if (actualStart != expectedStart) {
            throw RoutingDataHttpException(
                "range",
                HttpURLConnection.HTTP_PARTIAL,
                "Server resumed at byte $actualStart instead of $expectedStart",
            )
        }
    }

    private fun parseContentRangeTotal(header: String?): Long? =
        CONTENT_RANGE_REGEX.matchEntire(header.orEmpty())
            ?.groupValues
            ?.get(3)
            ?.takeUnless { it == "*" }
            ?.toLongOrNull()

    private fun tileUrl(tile: Rd5TileId): String = normalizedBaseUrl + tile.fileName

    private fun classifyHttpFailure(url: String, status: Int, message: String?): IOException = when {
        status == HttpURLConnection.HTTP_NOT_FOUND -> RemoteRoutingDataNotFoundException(url)
        status == HttpURLConnection.HTTP_CLIENT_TIMEOUT ||
            status == 425 ||
            status == 429 ||
            status in 500..599 -> TransientRoutingDataHttpException(url, status, message)
        else -> RoutingDataHttpException(url, status, message)
    }

    private data class HttpResponse(
        val connection: HttpURLConnection,
        val status: Int,
        val message: String?,
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://brouter.de/brouter/segments4/"
        const val DEFAULT_SOURCE_ID = "brouter.de/segments4"
        const val DEFAULT_USER_AGENT =
            "WAYLOAM-Router/0.1 (+https://github.com/loam-tools/wayloam-router)"

        private const val MAX_REDIRECTS = 5
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        private val CONTENT_RANGE_REGEX = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)")
    }
}

open class RoutingDataHttpException(
    val requestUrl: String,
    val statusCode: Int,
    message: String?,
) : IOException("HTTP $statusCode for $requestUrl${message?.let { ": $it" }.orEmpty()}")

class RemoteRoutingDataNotFoundException(url: String) :
    RoutingDataHttpException(url, HttpURLConnection.HTTP_NOT_FOUND, "Routing tile not found")

class TransientRoutingDataHttpException(url: String, status: Int, message: String?) :
    RoutingDataHttpException(url, status, message)

