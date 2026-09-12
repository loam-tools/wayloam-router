package tools.loam.wayloam.router.api

import java.io.IOException

enum class RoutingFailureCode {
    MISSING_DATA, INCOMPATIBLE_DATA, MISSING_PROFILE, NO_ROUTE, TIMEOUT,
    DISCONNECTED_ROUTE, ENGINE_BUSY, ENGINE_ERROR,
}

/** Stable failures for app recovery actions. Cancellation remains CancellationException. */
open class RoutingException(
    val code: RoutingFailureCode,
    message: String,
    cause: Throwable? = null,
    val sectionIndex: Int? = null,
) : IOException(message, cause)

data class RouteDiagnostics(
    val elapsedMillis: Long = 0,
    val firstSectionMillis: Long? = null,
    val sectionCacheHits: Int = 0,
    val engineCalls: Int = 0,
    val skippedAnchors: Int = 0,
    val retries: Int = 0,
    val plannerVersion: String = "",
)
