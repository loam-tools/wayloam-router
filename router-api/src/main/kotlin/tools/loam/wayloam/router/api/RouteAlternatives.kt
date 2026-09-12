package tools.loam.wayloam.router.api

enum class RouteAlternativeKind {
    RECOMMENDED,
    FASTER,
    QUIETER_SCENIC,
}

data class RouteAlternative(
    val kind: RouteAlternativeKind,
    val result: RouteResult,
    /** Fraction in 0..1 overlapping the recommended route; recommended is always 1. */
    val overlapWithRecommended: Double,
) {
    init {
        require(overlapWithRecommended in 0.0..1.0 && overlapWithRecommended.isFinite())
    }
}
