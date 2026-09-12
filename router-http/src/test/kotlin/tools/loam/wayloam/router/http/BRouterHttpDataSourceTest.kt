package tools.loam.wayloam.router.http

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tools.loam.wayloam.router.data.Rd5RemoteArtifact
import tools.loam.wayloam.router.data.Rd5TileId
import tools.loam.wayloam.router.data.RoutingDataDownloadRequest
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class BRouterHttpDataSourceTest {
    private val tile = Rd5TileId(5, 45)

    @Test
    fun headMetadataBuildsVersionedArtifact() = runBlocking {
        LocalHttpServer(
            responses = listOf(
                HttpFixtureResponse(
                    status = 200,
                    headers = mapOf(
                        "Content-Length" to "12345",
                        "ETag" to "\"fixture-v1\"",
                    ),
                )
            )
        ).use { server ->
            val source = BRouterHttpDataSource(baseUrl = server.baseUrl)
            val artifact = source.artifact(tile)
            server.await()

            requireNotNull(artifact)
            assertEquals(tile, artifact.tile)
            assertEquals(12_345L, artifact.sizeBytes)
            assertEquals("etag:\"fixture-v1\"", artifact.sourceVersion)
            assertEquals(server.baseUrl + tile.fileName, artifact.downloadUrl)
            assertEquals("HEAD", server.requests.single().method)
        }
    }

    @Test
    fun missingHeadReturnsNoArtifact() = runBlocking {
        LocalHttpServer(listOf(HttpFixtureResponse(status = 404))).use { server ->
            val source = BRouterHttpDataSource(baseUrl = server.baseUrl)
            assertNull(source.artifact(tile))
            server.await()
        }
    }

    @Test
    fun partialDownloadResumesWithRangeAndAppends() = runBlocking {
        val complete = "helloworld".toByteArray()
        val partial = "hello".toByteArray()
        LocalHttpServer(
            listOf(
                HttpFixtureResponse(
                    status = 206,
                    headers = mapOf(
                        "Content-Length" to "5",
                        "Content-Range" to "bytes 5-9/10",
                    ),
                    body = "world".toByteArray(),
                )
            )
        ).use { server ->
            val destination = Files.createTempFile("wayloam-http", ".part")
            try {
                Files.write(destination, partial)
                val artifact = artifact(server, complete.size.toLong())
                val result = BRouterHttpDataSource(baseUrl = server.baseUrl).download(
                    RoutingDataDownloadRequest(
                        artifact = artifact,
                        destination = destination,
                        resumeFromBytes = partial.size.toLong(),
                    )
                )
                server.await()

                assertTrue(result.resumed)
                assertEquals(5L, result.bytesWritten)
                assertEquals("bytes=5-", server.requests.single().headers["range"])
                assertArrayEquals(complete, Files.readAllBytes(destination))
            } finally {
                Files.deleteIfExists(destination)
            }
        }
    }

    @Test
    fun serverIgnoringRangeRestartsDestinationInsteadOfAppendingDuplicateBytes() = runBlocking {
        val complete = "helloworld".toByteArray()
        LocalHttpServer(
            listOf(
                HttpFixtureResponse(
                    status = 200,
                    headers = mapOf("Content-Length" to complete.size.toString()),
                    body = complete,
                )
            )
        ).use { server ->
            val destination = Files.createTempFile("wayloam-http", ".part")
            try {
                Files.write(destination, "hello".toByteArray())
                val result = BRouterHttpDataSource(baseUrl = server.baseUrl).download(
                    RoutingDataDownloadRequest(
                        artifact = artifact(server, complete.size.toLong()),
                        destination = destination,
                        resumeFromBytes = 5L,
                    )
                )
                server.await()

                assertFalse(result.resumed)
                assertEquals("bytes=5-", server.requests.single().headers["range"])
                assertArrayEquals(complete, Files.readAllBytes(destination))
            } finally {
                Files.deleteIfExists(destination)
            }
        }
    }

    @Test
    fun aChangedRemoteVersionCannotAppendToTheOldPartial() = runBlocking {
        LocalHttpServer(listOf(HttpFixtureResponse(206,
            mapOf("Content-Length" to "5", "Content-Range" to "bytes 5-9/10", "ETag" to "new"),
            "world".toByteArray()))).use { server ->
            val destination = Files.createTempFile("changed-source", ".part")
            try {
                Files.write(destination, "hello".toByteArray())
                val error = runCatching {
                    BRouterHttpDataSource(baseUrl = server.baseUrl).download(RoutingDataDownloadRequest(
                        artifact(server, 10).copy(sourceVersion = "etag:old"), destination, 5))
                }.exceptionOrNull()
                server.await()
                assertTrue(error is RoutingDataHttpException)
                assertEquals("old", server.requests.single().headers["if-range"])
                assertArrayEquals("hello".toByteArray(), Files.readAllBytes(destination))
            } finally { Files.deleteIfExists(destination) }
        }
    }

    private fun artifact(server: LocalHttpServer, size: Long) = Rd5RemoteArtifact(
        tile = tile,
        sourceId = "fixture",
        sourceVersion = "fixture",
        downloadUrl = server.baseUrl + tile.fileName,
        sizeBytes = size,
        sha256 = null,
    )
}

private data class HttpFixtureRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
)

private data class HttpFixtureResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
)

private class LocalHttpServer(
    private val responses: List<HttpFixtureResponse>,
) : AutoCloseable {
    private val socket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    private val failure = AtomicReference<Throwable?>(null)
    val requests: MutableList<HttpFixtureRequest> = Collections.synchronizedList(mutableListOf())
    val baseUrl: String = "http://127.0.0.1:${socket.localPort}/"

    private val worker = thread(start = true, isDaemon = true, name = "wayloam-http-test") {
        try {
            responses.forEach { response ->
                socket.accept().use { client ->
                    client.soTimeout = 5_000
                    val reader = client.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                    val requestLine = reader.readLine() ?: error("HTTP client closed before request line")
                    val parts = requestLine.split(' ')
                    require(parts.size >= 2) { "Malformed request line: $requestLine" }
                    val headers = linkedMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        if (separator > 0) {
                            headers[line.substring(0, separator).trim().lowercase()] =
                                line.substring(separator + 1).trim()
                        }
                    }
                    requests += HttpFixtureRequest(parts[0], parts[1], headers)

                    val output = client.getOutputStream().buffered()
                    output.write("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n".toByteArray())
                    val responseHeaders = LinkedHashMap(response.headers)
                    responseHeaders.putIfAbsent("Connection", "close")
                    if (response.body.isNotEmpty()) {
                        responseHeaders.putIfAbsent("Content-Length", response.body.size.toString())
                    }
                    responseHeaders.forEach { (name, value) ->
                        output.write("$name: $value\r\n".toByteArray())
                    }
                    output.write("\r\n".toByteArray())
                    if (response.body.isNotEmpty()) output.write(response.body)
                    output.flush()
                }
            }
        } catch (error: Throwable) {
            if (!socket.isClosed) failure.set(error)
        }
    }

    fun await() {
        worker.join(5_000)
        check(!worker.isAlive) { "Loopback HTTP fixture did not finish" }
        failure.get()?.let { throw AssertionError("Loopback HTTP fixture failed", it) }
        assertEquals(responses.size, requests.size)
    }

    override fun close() {
        socket.close()
        worker.join(1_000)
        failure.get()?.let { throw AssertionError("Loopback HTTP fixture failed", it) }
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        404 -> "Not Found"
        else -> "Fixture"
    }
}

