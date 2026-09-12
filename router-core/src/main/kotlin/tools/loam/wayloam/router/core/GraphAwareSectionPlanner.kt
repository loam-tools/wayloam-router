package tools.loam.wayloam.router.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tools.loam.wayloam.router.api.GeoPoint
import tools.loam.wayloam.router.api.RouteProfile
import tools.loam.wayloam.router.api.RouteRequest

/** Returns ordered positions on the bicycle graph, never an invented connecting line. */
fun interface GraphAnchorResolver {
    suspend fun candidates(point: GeoPoint, profile: RouteProfile): List<GeoPoint>
}

/** A safe fallback that routes only the rider's explicit legs with the engine's time budget. */
class UserWaypointSectionPlanner : SectionPlanner {
    override val version: String = "user-waypoints-1"

    override suspend fun plan(request: RouteRequest): List<SectionSpec> =
        (listOf(request.start) + request.via + request.end).windowed(2).mapIndexed { i, pair ->
            SectionSpec(i, pair[0], pair[1])
        }
}

/**
 * Geodesic positions are only search seeds. Only verified graph candidates become auto-anchors.
 * Local snapping does not prove continent-wide connectivity: the coordinator retries or removes
 * failed automatic anchors while retaining explicit via points and a bounded total time budget.
 */
class GraphAwareSectionPlanner(
    private val resolver: GraphAnchorResolver,
    private val resolverVersion: String,
    private val maxCandidates: Int = 3,
) : SectionPlanner {
    init { require(maxCandidates in 1..8) }
    override val version: String = "graph-anchors-1:$resolverVersion:$maxCandidates"

    override suspend fun plan(request: RouteRequest): List<SectionSpec> {
        val planned = mutableListOf<SectionSpec>()
        var start = request.start
        for (spec in FixedDistanceSectionPlanner().plan(request)) {
            currentCoroutineContext().ensureActive()
            val candidates = if (spec.endIsUserWaypoint) listOf(spec.end) else
                resolver.candidates(spec.end, request.profile).distinctBy { it.latitude to it.longitude }
                    .filter { GeoMath.distanceMeters(it, start) > 1.0 }.take(maxCandidates)
            if (candidates.isEmpty()) continue
            planned += SectionSpec(planned.size, start, candidates.first(), spec.endIsUserWaypoint, candidates)
            start = candidates.first()
        }
        return planned
    }
}
