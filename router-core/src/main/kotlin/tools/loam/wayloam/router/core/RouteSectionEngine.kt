package tools.loam.wayloam.router.core

import tools.loam.wayloam.router.api.GeoPoint
import tools.loam.wayloam.router.api.RoutePreferences
import tools.loam.wayloam.router.api.RouteProfile
import tools.loam.wayloam.router.api.RouteSegment

interface RouteSectionEngine {
    val engineId: String
    val engineVersion: String

    suspend fun routeSection(
        index: Int,
        start: GeoPoint,
        end: GeoPoint,
        profile: RouteProfile,
    ): RouteSegment

    /**
     * Preference-aware entry point. Engines that have not adopted advanced preferences retain the
     * preset behavior; production engines should override this method and apply only verified rules.
     */
    suspend fun routeSection(
        index: Int,
        start: GeoPoint,
        end: GeoPoint,
        profile: RouteProfile,
        preferences: RoutePreferences,
    ): RouteSegment = routeSection(index, start, end, profile)
}
