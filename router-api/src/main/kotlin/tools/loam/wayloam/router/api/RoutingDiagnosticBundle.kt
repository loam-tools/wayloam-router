package tools.loam.wayloam.router.api

import java.io.Writer

data class RoutingDiagnosticBundle(
    val status: String,
    val profile: RouteProfile,
    val engineVersion: String = "",
    val profileVersion: String = "",
    val dataVersion: String = "",
    val plannerVersion: String = "",
    val distanceMeters: Long? = null,
    val ascentMeters: Int? = null,
    val descentMeters: Int? = null,
    val durationSeconds: Long? = null,
    val sectionCount: Int? = null,
    val cacheHit: Boolean? = null,
    val elapsedMillis: Long? = null,
    val firstSectionMillis: Long? = null,
    val sectionCacheHits: Int? = null,
    val engineCalls: Int? = null,
    val skippedAnchors: Int? = null,
    val retries: Int? = null,
    val failureCode: RoutingFailureCode? = null,
    val failureSectionIndex: Int? = null,
) {
    init {
        require(status == "success" || status == "failure")
        if (status == "failure") require(failureCode != null)
    }
}

/**
 * Builds and writes diagnostic records that deliberately exclude coordinates, geometry, cache keys,
 * filenames and user-entered waypoint labels. The output is safe to attach to a bug report without
 * disclosing where the rider travelled.
 */
object RoutingDiagnosticsExport {
    fun success(request: RouteRequest, result: RouteResult): RoutingDiagnosticBundle {
        val diagnostics = result.diagnostics
        return RoutingDiagnosticBundle(
            status = "success",
            profile = request.profile,
            engineVersion = diagnostics.engineVersion.ifBlank { result.engine },
            profileVersion = diagnostics.profileVersion,
            dataVersion = diagnostics.dataVersion,
            plannerVersion = diagnostics.plannerVersion,
            distanceMeters = result.metrics.distanceMeters,
            ascentMeters = result.metrics.ascentMeters,
            descentMeters = result.metrics.descentMeters,
            durationSeconds = result.metrics.durationSeconds,
            sectionCount = result.segments.size,
            cacheHit = result.cacheHit,
            elapsedMillis = diagnostics.elapsedMillis,
            firstSectionMillis = diagnostics.firstSectionMillis,
            sectionCacheHits = diagnostics.sectionCacheHits,
            engineCalls = diagnostics.engineCalls,
            skippedAnchors = diagnostics.skippedAnchors,
            retries = diagnostics.retries,
        )
    }

    fun failure(
        request: RouteRequest,
        error: RoutingException,
        engineVersion: String = "",
        profileVersion: String = "",
        dataVersion: String = "",
        plannerVersion: String = "",
    ): RoutingDiagnosticBundle = RoutingDiagnosticBundle(
        status = "failure",
        profile = request.profile,
        engineVersion = engineVersion,
        profileVersion = profileVersion,
        dataVersion = dataVersion,
        plannerVersion = plannerVersion,
        failureCode = error.code,
        failureSectionIndex = error.sectionIndex,
    )

    fun writeJson(bundle: RoutingDiagnosticBundle, writer: Writer) {
        writer.write(buildString {
            append('{')
            field("schema", "wayloam-routing-diagnostics-v1")
            field("status", bundle.status)
            field("profile", bundle.profile.name)
            field("engine_version", bundle.engineVersion)
            field("profile_version", bundle.profileVersion)
            field("data_version", bundle.dataVersion)
            field("planner_version", bundle.plannerVersion)
            number("distance_meters", bundle.distanceMeters)
            number("ascent_meters", bundle.ascentMeters)
            number("descent_meters", bundle.descentMeters)
            number("duration_seconds", bundle.durationSeconds)
            number("section_count", bundle.sectionCount)
            bool("cache_hit", bundle.cacheHit)
            number("elapsed_millis", bundle.elapsedMillis)
            number("first_section_millis", bundle.firstSectionMillis)
            number("section_cache_hits", bundle.sectionCacheHits)
            number("engine_calls", bundle.engineCalls)
            number("skipped_anchors", bundle.skippedAnchors)
            number("retries", bundle.retries)
            nullableField("failure_code", bundle.failureCode?.name)
            number("failure_section_index", bundle.failureSectionIndex)
            if (last() == ',') deleteCharAt(lastIndex)
            append('}')
        })
    }

    private fun StringBuilder.field(name: String, value: String) {
        append('"').append(escape(name)).append("\":\"").append(escape(value)).append("\",")
    }

    private fun StringBuilder.nullableField(name: String, value: String?) {
        append('"').append(escape(name)).append("\":")
        if (value == null) append("null,") else append('"').append(escape(value)).append("\",")
    }

    private fun StringBuilder.number(name: String, value: Number?) {
        append('"').append(escape(name)).append("\":")
        if (value == null) append("null,") else append(value).append(',')
    }

    private fun StringBuilder.bool(name: String, value: Boolean?) {
        append('"').append(escape(name)).append("\":")
        if (value == null) append("null,") else append(value).append(',')
    }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }
}
