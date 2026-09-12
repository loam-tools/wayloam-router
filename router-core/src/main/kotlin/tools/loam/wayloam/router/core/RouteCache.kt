package tools.loam.wayloam.router.core

import tools.loam.wayloam.router.api.GeoPoint
import tools.loam.wayloam.router.api.RouteProfile
import tools.loam.wayloam.router.api.RouteRequest
import tools.loam.wayloam.router.api.RouteResult
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

data class RouteIdentity(
    val engineVersion: String,
    val profileVersion: String,
    val dataVersion: String,
    val plannerVersion: String = "legacy",
)

/** Completed routes and single-section results share one bounded cache. */
interface RouteCache {
    fun get(key: String): RouteResult?
    fun put(key: String, result: RouteResult)
}

object NoRouteCache : RouteCache {
    override fun get(key: String): RouteResult? = null
    override fun put(key: String, result: RouteResult) = Unit
}

class InMemoryRouteCache(private val maxEntries: Int = 128) : RouteCache {
    init { require(maxEntries > 0) }
    private val values = LinkedHashMap<String, RouteResult>(16, 0.75f, true)

    @Synchronized override fun get(key: String): RouteResult? = values[key]?.snapshot()

    @Synchronized override fun put(key: String, result: RouteResult) {
        values[key] = result.snapshot()
        while (values.size > maxEntries) values.remove(values.keys.first())
    }

    @Synchronized fun clear() = values.clear()
}

internal fun RouteResult.snapshot() = copy(
    points = points.toList(),
    segments = segments.map { it.copy(points = it.points.toList()) },
)

object RouteCacheKey {
    fun build(request: RouteRequest, identity: RouteIdentity): String = digest {
        writeUTF("route-v2")
        writeIdentity(identity)
        writeUTF(identity.plannerVersion)
        writeUTF(request.profile.name)
        writePoint(request.start)
        writeInt(request.via.size)
        request.via.forEach { writePoint(it) }
        writePoint(request.end)
        writeDouble(request.maxSectionDistanceKm)
    }

    /** Index, stage targets, total timeout and distant via points do not affect a section. */
    fun section(start: GeoPoint, end: GeoPoint, profile: RouteProfile, identity: RouteIdentity): String = digest {
        writeUTF("section-v2")
        writeIdentity(identity)
        writeUTF(profile.name)
        writePoint(start)
        writePoint(end)
    }

    private fun DataOutputStream.writeIdentity(identity: RouteIdentity) {
        writeUTF(identity.engineVersion)
        writeUTF(identity.profileVersion)
        writeUTF(identity.dataVersion)
    }

    private fun DataOutputStream.writePoint(point: GeoPoint) {
        writeDouble(if (point.latitude == 0.0) 0.0 else point.latitude)
        writeDouble(if (point.longitude == 0.0) 0.0 else point.longitude)
    }

    private fun digest(write: DataOutputStream.() -> Unit): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { it.write() }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
