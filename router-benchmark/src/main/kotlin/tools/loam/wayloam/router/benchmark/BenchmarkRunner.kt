package tools.loam.wayloam.router.benchmark

import kotlinx.coroutines.*
import tools.loam.wayloam.router.api.*
import tools.loam.wayloam.router.brouter.*
import tools.loam.wayloam.router.data.*
import tools.loam.wayloam.router.http.BRouterHttpDataSource
import tools.loam.wayloam.router.runtime.*
import java.io.File
import java.io.Writer
import java.security.MessageDigest

internal fun runBenchmarks(args: Array<String>): Int = runBlocking {
    val options = parseOptions(args)
    val root = File(options.getValue("root"))
    val version = options.getValue("data-version")
    val routeId = options["route"] ?: "heidelberg-frankfurt"
    val routes = if (routeId == "all") BenchmarkCatalog.routes else
        listOf(requireNotNull(BenchmarkCatalog.routes.find { it.id == routeId }) { "Unknown route: $routeId" })
    val profile = RouteProfile.valueOf((options["profile"] ?: "TOURING").uppercase())
    val iterations = (options["iterations"] ?: "3").toInt().also { require(it in 1..10) }
    val sectionTimeout = (options["section-timeout-ms"] ?: "90000").toLong()
    val routeTimeout = (options["route-timeout-ms"] ?: "600000").toLong().also { require(it > 0) }
    val memoryMb = (options["memory-mb"] ?: "512").toInt()
    val config = LocalRouterConfig(root, version, sectionTimeout, memoryMb)
    val output = File(options["output"] ?: "benchmark-results.jsonl").absoluteFile
    output.parentFile.mkdirs()
    val runtime = EmbeddedWayloamRouter.create(config)

    if (options["prepare"] == "true") {
        val tiles = routes.flatMap { runtime.estimatedTiles(it.request(profile)) }.toSet()
        val source = BRouterHttpDataSource(baseUrl = options["source"] ?: BRouterHttpDataSource.DEFAULT_BASE_URL)
        val artifacts = tiles.sortedBy { it.fileName }.mapNotNull { source.artifact(it) }
        val limit = (options["max-download-mb"] ?: "10240").toLong() * 1024 * 1024
        require(limit > 0 && artifacts.all { it.sizeBytes != null }) { "Download size must be known before transfer" }
        val estimated = artifacts.sumOf { it.sizeBytes!! }
        require(estimated <= limit) { "Data requires $estimated bytes, exceeding the download budget $limit" }
        val manager = VerifiedRoutingDataManager(runtime.segmentsDirectory.toPath(), StaticRoutingDataManifest(artifacts), source)
        File(output.parentFile, "routing-data-manifest.jsonl").bufferedWriter().use { manifest ->
            for (artifact in artifacts) {
                System.err.println("Preparing ${artifact.tile.fileName} (${artifact.sizeBytes} bytes)")
                val installed = manager.ensureTile(artifact.tile)
                manifest.record(mapOf("file" to artifact.tile.fileName, "source" to installed.sourceId,
                    "version" to installed.sourceVersion, "sha256" to installed.sha256, "bytes" to installed.bytes))
            }
        }
    }

    // Hash the exact local data once, outside timed trials; this gives repeatable result provenance.
    val dataDigest = MessageDigest.getInstance("SHA-256")
    root.resolve("segments4").listFiles().orEmpty().filter { it.extension == "rd5" }.sortedBy { it.name }.forEach { file ->
        dataDigest.update(file.name.toByteArray())
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                dataDigest.update(buffer, 0, count)
            }
        }
    }
    val dataSha256 = dataDigest.digest().joinToString("") { "%02x".format(it) }
    var failures = 0
    output.bufferedWriter().use { writer ->
        for (route in routes) repeat(iterations) { iteration ->
            runtime.clearRouteCache()
            var coldGeometry: String? = null
            for (cacheState in listOf("cold", "warm")) {
                val started = System.nanoTime()
                val row = linkedMapOf<String, Any?>(
                    "schema" to 1, "router_version" to "0.1.0-SNAPSHOT",
                    "upstream_commit" to BRouterBaseline.COMMIT,
                    "profile" to profile.name, "profile_version" to WayloamProfiles.forProfile(profile).versionKey,
                    "data_version" to version, "data_sha256" to dataSha256,
                    "route_id" to route.id, "iteration" to iteration + 1, "cache" to cacheState,
                    "section_target_km" to route.request(profile).maxSectionDistanceKm,
                    "section_timeout_ms" to sectionTimeout, "route_timeout_ms" to routeTimeout,
                    "memory_budget_mb" to memoryMb,
                )
                try {
                    // Recreate the facade for warm trials to verify persistence, not an in-memory hit.
                    val instance = EmbeddedWayloamRouter.create(config)
                    val result = instance.route(route.request(profile).copy(timeoutMillis = routeTimeout)) { event ->
                        if (event is RoutingEvent.SectionCompleted) System.err.println(
                            "${route.id}: section ${event.index + 1}/${event.total}")
                    }
                    val geometry = MessageDigest.getInstance("SHA-256")
                    result.points.forEach { geometry.update("${it.latitude},${it.longitude},${it.elevationMeters};".toByteArray()) }
                    val hash = geometry.digest().joinToString("") { "%02x".format(it) }
                    if (cacheState == "cold") coldGeometry = hash
                    if (cacheState == "warm" && (!result.cacheHit || hash != coldGeometry)) {
                        throw IllegalStateException("Warm reopen did not reuse the identical completed route")
                    }
                    row.putAll(mapOf("status" to "success", "distance_m" to result.metrics.distanceMeters,
                        "ascent_m" to result.metrics.ascentMeters, "duration_s" to result.metrics.durationSeconds,
                        "section_count" to result.segments.size, "cache_hit" to result.cacheHit,
                        "section_cache_hits" to result.diagnostics.sectionCacheHits,
                        "engine_calls" to result.diagnostics.engineCalls, "retries" to result.diagnostics.retries,
                        "skipped_anchors" to result.diagnostics.skippedAnchors,
                        "planner" to result.diagnostics.plannerVersion,
                        "first_section_ms" to result.diagnostics.firstSectionMillis, "geometry_sha256" to hash))
                    if (cacheState == "cold" && iteration == 0) {
                        File(output.parentFile, "${route.id}-${profile.name.lowercase()}.gpx").bufferedWriter().use {
                            RouteExport.writeGpx(result, it, "${route.startName} → ${route.endName}")
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failures++
                    row.putAll(mapOf("status" to "failure", "failure_code" to
                        ((error as? RoutingException)?.code?.name ?: error.javaClass.simpleName),
                        "failed_section" to (error as? RoutingException)?.sectionIndex,
                        "message" to error.message))
                }
                val jvm = Runtime.getRuntime()
                row["elapsed_ms"] = (System.nanoTime() - started) / 1_000_000
                row["heap_used_bytes_after_run"] = jvm.totalMemory() - jvm.freeMemory()
                writer.record(row)
            }
        }
    }
    println("Benchmark results: ${output.absolutePath}; failures=$failures")
    if (failures == 0) 0 else 1
}

internal fun parseOptions(args: Array<String>): Map<String, String> {
    require(args.size % 2 == 0) { "Options use --name value pairs; see docs/BENCHMARKS.md" }
    val allowed = setOf("root", "data-version", "route", "profile", "iterations", "output", "prepare",
        "source", "max-download-mb", "memory-mb", "section-timeout-ms", "route-timeout-ms")
    val values = mutableMapOf<String, String>()
    args.toList().chunked(2).forEach { pair ->
        require(pair[0].startsWith("--")) { "Expected --option" }
        val key = pair[0].removePrefix("--")
        require(key in allowed && !values.containsKey(key)) { "Unknown or repeated option: $key" }
        values[key] = pair[1]
    }
    require(!values["root"].isNullOrBlank() && !values["data-version"].isNullOrBlank()) {
        "--root and --data-version are required"
    }
    require(values["prepare"] == null || values["prepare"] in setOf("true", "false"))
    return values
}

internal fun Writer.record(values: Map<String, Any?>) {
    write(values.entries.joinToString(",", "{", "}\n") { (key, value) ->
        "${jsonString(key)}:" + when (value) {
            null -> "null"
            is Number, is Boolean -> value.toString()
            else -> jsonString(value.toString())
        }
    })
    flush()
}

private fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { c ->
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}
