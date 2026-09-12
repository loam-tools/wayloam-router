package tools.loam.wayloam.router.core

import tools.loam.wayloam.router.api.GeoPoint
import tools.loam.wayloam.router.api.RouteRequest
import tools.loam.wayloam.router.api.RouteResult
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class RouteIdentity(
    val engineVersion: String,
    val profileVersion: String,
    val dataVersion: String,
)

interface RouteCache {
    fun get(key: String): RouteResult?
    fun put(key: String, result: RouteResult)
}

object NoRouteCache : RouteCache {
    override fun get(key: String): RouteResult? = null
    override fun put(key: String, result: RouteResult) = Unit
}

class InMemoryRouteCache : RouteCache {
    private val values = ConcurrentHashMap<String, RouteResult>()

    override fun get(key: String): RouteResult? = values[key]

    override fun put(key: String, result: RouteResult) {
        values[key] = result
    }

    fun clear() = values.clear()
}

object RouteCacheKey {
    fun build(request: RouteRequest, identity: RouteIdentity): String {
        val canonical = buildString {
            append(identity.engineVersion).append('|')
            append(identity.profileVersion).append('|')
            append(identity.dataVersion).append('|')
            append(request.profile.name).append('|')
            appendPoint(request.start)
            append('|')
            request.via.forEach { point ->
                appendPoint(point)
                append(';')
            }
            append('|')
            appendPoint(request.end)
            append('|')
            append(request.maxSectionDistanceKm)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun StringBuilder.appendPoint(point: GeoPoint) {
        append("%.7f,%.7f".format(java.util.Locale.US, point.latitude, point.longitude))
    }
}
