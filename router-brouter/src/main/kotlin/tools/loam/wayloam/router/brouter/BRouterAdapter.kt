package tools.loam.wayloam.router.brouter

import tools.loam.wayloam.router.api.GeoPoint
import tools.loam.wayloam.router.api.RouteMetrics
import tools.loam.wayloam.router.api.RoutePreferences
import tools.loam.wayloam.router.api.RouteProfile
import tools.loam.wayloam.router.api.RouteSegment
import tools.loam.wayloam.router.api.SurfacePreference
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
    init {
        require(Regex("[a-zA-Z0-9_-]+").matches(baseProfile))
        require(parameters.values.all { it.toDoubleOrNull()?.isFinite() == true }) {
            "BRouter profile overrides must use numeric expression values"
        }
    }

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
    val version: String get() = listOf(DIRECT, TOURING, BIKEPACKING).joinToString(";") { it.versionKey }

    private const val OFF = "0"
    private const val ON = "1"

    val DIRECT = BRouterProfilePreset(
        baseProfile = "fastbike",
        parameters = mapOf(
            "allow_steps" to OFF,
            "allow_ferries" to ON,
            "consider_traffic" to ON,
            "consider_elevation" to ON,
        ),
    )

    val TOURING = BRouterProfilePreset(
        baseProfile = "trekking",
        parameters = mapOf(
            "allow_steps" to OFF,
            "allow_ferries" to ON,
            "avoid_unsafe" to ON,
            "consider_traffic" to ON,
            "consider_elevation" to ON,
        ),
    )

    val BIKEPACKING = BRouterProfilePreset(
        baseProfile = "trekking",
        parameters = mapOf(
            "allow_steps" to OFF,
            "allow_ferries" to ON,
            "avoid_unsafe" to ON,
            "consider_traffic" to ON,
            "consider_forest" to ON,
            "consider_elevation" to ON,
        ),
    )

    fun forProfile(profile: RouteProfile): BRouterProfilePreset = when (profile) {
        RouteProfile.DIRECT -> DIRECT
        RouteProfile.TOURING -> TOURING
        RouteProfile.BIKEPACKING -> BIKEPACKING
    }

    /**
     * Applies only variables already exposed by the pinned upstream profiles. Preferences that need
     * new profile logic (for example explicit tunnel or cycleway penalties) stay in the public model
     * and cache identity until that behavior can be implemented and benchmarked without pretending
     * the upstream profile already supports it.
     */
    fun forRequest(profile: RouteProfile, preferences: RoutePreferences): BRouterProfilePreset {
        val base = forProfile(profile)
        val parameters = base.parameters.toMutableMap().apply {
            this["allow_steps"] = preferences.allowSteps.flag()
            this["allow_ferries"] = preferences.allowFerries.flag()
            if ("avoid_unsafe" in this) this["avoid_unsafe"] = preferences.avoidMajorRoads.flag()
            if ("consider_traffic" in this) this["consider_traffic"] =
                (preferences.trafficSensitivity >= 0.5).flag()
            if ("consider_elevation" in this) this["consider_elevation"] =
                (preferences.hillSensitivity >= 0.5).flag()
            if ("consider_forest" in this) this["consider_forest"] =
                (preferences.surfacePreference == SurfacePreference.PREFER_UNPAVED).flag()
        }
        return BRouterProfilePreset(base.baseProfile, parameters)
    }

    private fun Boolean.flag(): String = if (this) ON else OFF
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
    override val engineVersion: String = "${BRouterBaseline.RELEASE}+wayloam.3"

    override suspend fun routeSection(
        index: Int,
        start: GeoPoint,
        end: GeoPoint,
        profile: RouteProfile,
    ): RouteSegment = routeSection(index, start, end, profile, RoutePreferences.forProfile(profile))

    override suspend fun routeSection(
        index: Int,
        start: GeoPoint,
        end: GeoPoint,
        profile: RouteProfile,
        preferences: RoutePreferences,
    ): RouteSegment {
        val result = backend.route(
            BRouterBackendRequest(
                start = start,
                end = end,
                preset = WayloamProfiles.forRequest(profile, preferences),
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
