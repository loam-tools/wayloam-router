package tools.loam.wayloam.router.core

import tools.loam.wayloam.router.api.GeoPoint
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
}
