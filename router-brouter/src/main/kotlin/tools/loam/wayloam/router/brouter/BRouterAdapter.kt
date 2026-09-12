package tools.loam.wayloam.router.brouter

import tools.loam.wayloam.router.api.GeoPoint
import tools.loam.wayloam.router.api.RouteMetrics
import tools.loam.wayloam.router.api.RouteProfile
import tools.loam.wayloam.router.api.RouteSegment
import tools.loam.wayloam.router.core.RouteSectionEngine

object BRouterBaseline {
    const val UPSTREAM = "abrensch/brouter"
    const val RELEASE = "v1.7.10"
    const val COMMIT = "4d2639af77ea5ed9c30d3e400764eb6f9e8522da"
}

data class BRouterProfilePreset(
    val baseProfile: String,
    val parameters: Map<String, String>,
) {
    val versionKey: String = buildString {
        append(BRouterBaseline.RELEASE)
        append(':')
        append(baseProfile)
        parameters.toSortedMap().forEach { (key, value) ->
            append('|').append(key).append('=').append(value)
        }
    }
}

object WayloamProfiles {
    val DIRECT = BRouterProfilePreset(
        baseProfile = "fastbike",
        parameters = mapOf(
            "allow_steps" to "false",
            "allow_ferries" to "true",
            "consider_traffic" to "0.3",
            "consider_elevation" to "true",
        ),
    )

    val TOURING = BRouterProfilePreset(
        baseProfile = "trekking",
        parameters = mapOf(
            "allow_steps" to "false",
            "allow_ferries" to "true",
            "avoid_unsafe" to "true",
            "consider_traffic" to "true",
            "consider_elevation" to "true",
        ),
    )

    val BIKEPACKING = BRouterProfilePreset(
        baseProfile = "trekking",
        parameters = mapOf(
            "allow_steps" to "false",
            "allow_ferries" to "true",
            "avoid_unsafe" to "true",
            "consider_traffic" to "true",
            "consider_forest" to "true",
            "consider_elevation" to "true",
        ),
    )

    fun forProfile(profile: RouteProfile): BRouterProfilePreset = when (profile) {
        RouteProfile.DIRECT -> DIRECT
        RouteProfile.TOURING -> TOURING
        RouteProfile.BIKEPACKING -> BIKEPACKING
    }
}

data class BRouterBackendRequest(
    val start: GeoPoint,
    val end: GeoPoint,
    val preset: BRouterProfilePreset,
)

data class BRouterBackendResult(
    val points: List<GeoPoint>,
    val metrics: RouteMetrics,
)

/**
 * Narrow boundary around the actual BRouter core. Keeping this tiny is intentional: upstream source
 * can be updated without leaking BRouter implementation classes through the Loam Tools API.
 */
fun interface EmbeddedBRouterBackend {
    suspend fun route(request: BRouterBackendRequest): BRouterBackendResult
}

class BRouterSectionEngine(
    private val backend: EmbeddedBRouterBackend,
) : RouteSectionEngine {
    override val engineId: String = "brouter"
    override val engineVersion: String = BRouterBaseline.RELEASE

    override suspend fun routeSection(
        index: Int,
        start: GeoPoint,
        end: GeoPoint,
        profile: RouteProfile,
    ): RouteSegment {
        val result = backend.route(
            BRouterBackendRequest(
                start = start,
                end = end,
                preset = WayloamProfiles.forProfile(profile),
            )
        )
        require(result.points.size >= 2) { "BRouter returned fewer than two route points" }
        return RouteSegment(
            index = index,
            start = start,
            end = end,
            points = result.points,
            metrics = result.metrics,
            engine = "$engineId/$engineVersion",
        )
    }
}
