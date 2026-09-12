package tools.loam.wayloam.router.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import tools.loam.wayloam.router.api.*

/** The caller supplies ride progress so loops cannot reconnect behind the rider. */
class PartialRerouter(private val router: WayloamRouter) {
    suspend fun reconnect(
        original: RouteResult,
        current: GeoPoint,
        profile: RouteProfile,
        nextSegmentIndex: Int,
        maxConnectionKm: Double = 30.0,
        timeoutMillis: Long = 90_000L,
        onEvent: (RoutingEvent) -> Unit = {},
        lastAllowedRejoinSegmentIndex: Int = nextSegmentIndex,
    ): RouteResult {
        require(nextSegmentIndex in original.segments.indices)
        require(lastAllowedRejoinSegmentIndex in nextSegmentIndex..original.segments.lastIndex)
        require(maxConnectionKm.isFinite() && maxConnectionKm > 0)
        require(timeoutMillis > 0)
        return withTimeoutOrNull(timeoutMillis) {
            val candidates = original.segments.subList(nextSegmentIndex, lastAllowedRejoinSegmentIndex + 1).mapIndexed { offset, segment ->
                (nextSegmentIndex + offset) to GeoMath.distanceMeters(current, segment.points.first())
            }.filter { it.second <= maxConnectionKm * 1_000 }.sortedBy { it.second }.take(3)
            var failure: RoutingException? = null
            for ((index, distance) in candidates) {
                currentCoroutineContext().ensureActive()
                val suffix = original.segments.drop(index)
                if (distance <= 1.0) return@withTimeoutOrNull RouteStitcher.stitch(suffix, original.engine)
                try {
                    val connection = router.route(RouteRequest(current, suffix.first().points.first(),
                        profile, timeoutMillis = timeoutMillis), onEvent)
                    // Metrics of the suffix are reused exactly; never guess proportions of an old leg.
                    return@withTimeoutOrNull RouteStitcher.stitch(connection.segments + suffix, original.engine)
                        .copy(diagnostics = connection.diagnostics)
                } catch (error: RoutingException) {
                    if (error.code !in setOf(RoutingFailureCode.NO_ROUTE, RoutingFailureCode.TIMEOUT,
                            RoutingFailureCode.DISCONNECTED_ROUTE)) throw error
                    failure = error
                }
            }
            throw RoutingException(failure?.code ?: RoutingFailureCode.NO_ROUTE,
                "No forward route boundary can be reached within the reconnection radius", failure)
        } ?: throw RoutingException(RoutingFailureCode.TIMEOUT, "Reconnection exceeded its time budget")
    }
}
