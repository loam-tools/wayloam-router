package tools.loam.wayloam.router.benchmark

import tools.loam.wayloam.router.api.GeoPoint
import tools.loam.wayloam.router.api.RouteProfile
import tools.loam.wayloam.router.api.RouteRequest
import tools.loam.wayloam.router.core.GeoMath
import kotlin.math.roundToInt

data class BenchmarkRoute(
    val id: String,
    val startName: String,
    val start: GeoPoint,
    val endName: String,
    val end: GeoPoint,
    val tier: Tier,
) {
    enum class Tier { SMOKE, REGIONAL, LONG, STRESS }

    fun request(profile: RouteProfile = RouteProfile.TOURING): RouteRequest = RouteRequest(
        start = start,
        end = end,
        profile = profile,
    )
}

object BenchmarkCatalog {
    private val HEIDELBERG = GeoPoint(49.3988, 8.6724)

    val routes = listOf(
        BenchmarkRoute(
            id = "heidelberg-frankfurt",
            startName = "Heidelberg",
            start = HEIDELBERG,
            endName = "Frankfurt",
            end = GeoPoint(50.1109, 8.6821),
            tier = BenchmarkRoute.Tier.SMOKE,
        ),
        BenchmarkRoute(
            id = "heidelberg-hamburg",
            startName = "Heidelberg",
            start = HEIDELBERG,
            endName = "Hamburg",
            end = GeoPoint(53.5511, 9.9937),
            tier = BenchmarkRoute.Tier.REGIONAL,
        ),
        BenchmarkRoute(
            id = "heidelberg-copenhagen",
            startName = "Heidelberg",
            start = HEIDELBERG,
            endName = "Copenhagen",
            end = GeoPoint(55.6761, 12.5683),
            tier = BenchmarkRoute.Tier.LONG,
        ),
        BenchmarkRoute(
            id = "heidelberg-oslo",
            startName = "Heidelberg",
            start = HEIDELBERG,
            endName = "Oslo",
            end = GeoPoint(59.9139, 10.7522),
            tier = BenchmarkRoute.Tier.LONG,
        ),
        BenchmarkRoute(
            id = "heidelberg-barcelona",
            startName = "Heidelberg",
            start = HEIDELBERG,
            endName = "Barcelona",
            end = GeoPoint(41.3851, 2.1734),
            tier = BenchmarkRoute.Tier.LONG,
        ),
        BenchmarkRoute(
            id = "lisbon-helsinki",
            startName = "Lisbon",
            start = GeoPoint(38.7223, -9.1393),
            endName = "Helsinki",
            end = GeoPoint(60.1699, 24.9384),
            tier = BenchmarkRoute.Tier.STRESS,
        ),
    )
}

fun main() {
    println("WAYLOAM Router benchmark catalogue")
    println("id\ttier\tgeodesic_km\tfrom\tto")
    BenchmarkCatalog.routes.forEach { route ->
        val distanceKm = (GeoMath.distanceMeters(route.start, route.end) / 1_000.0).roundToInt()
        println("${route.id}\t${route.tier}\t$distanceKm\t${route.startName}\t${route.endName}")
    }
    println()
    println("Engine execution is intentionally disabled until the embedded BRouter backend is wired.")
}
