package tools.loam.wayloam.router.brouter

import btools.router.OsmTrack
import btools.router.RoutingEngine
import tools.loam.wayloam.router.api.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * Extraction against the pinned BRouter baseline. WayTags come from BRouter's public
 * [OsmTrack.aggregateMessages] output. Tile observation is isolated here because upstream closes
 * and nulls RoutingEngine.nodesCache before doRun() returns.
 */
internal object BRouterRouteMetadata {
    fun annotations(track: OsmTrack): List<RouteAnnotation> {
        var distance = 0.0
        return buildList {
            track.aggregateMessages().forEach { message ->
                val columns = message.split('\t')
                if (columns.size < 10) return@forEach
                val length = columns[3].toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }
                    ?: return@forEach
                val tags = parseTags(columns[9])
                val start = distance
                distance += length
                add(annotation(start, distance, tags))
            }
        }
    }

    /**
     * Retains the exact NodesCache object while RoutingEngine is active. The cache's fileCache is
     * cumulative; NodesCache.close() closes file handles but does not erase that map, so it remains
     * safe to inspect after doRun() has completed.
     */
    class DataDependencyObserver(
        private val engine: RoutingEngine,
        private val segmentDirectory: File,
    ) : AutoCloseable {
        private val running = AtomicBoolean(false)
        private val retainedCache = AtomicReference<Any?>()
        private var thread: Thread? = null

        fun start() {
            check(running.compareAndSet(false, true)) { "Observer already started" }
            thread = Thread({
                while (running.get() && retainedCache.get() == null) {
                    retainCurrentCache()
                    if (retainedCache.get() == null) LockSupport.parkNanos(POLL_NANOS)
                }
            }, "wayloam-rd5-observer").apply {
                isDaemon = true
                start()
            }
        }

        fun finish(): Map<String, String> {
            running.set(false)
            thread?.join(250L)
            retainCurrentCache()
            return dependenciesFrom(retainedCache.get(), segmentDirectory)
        }

        override fun close() {
            running.set(false)
            thread?.interrupt()
        }

        private fun retainCurrentCache() {
            if (retainedCache.get() != null) return
            val cache = runCatching {
                NODES_CACHE_FIELD.get(engine)
            }.getOrNull()
            if (cache != null) retainedCache.compareAndSet(null, cache)
        }
    }

    private fun dependenciesFrom(nodesCache: Any?, segmentDirectory: File): Map<String, String> {
        if (nodesCache == null) return emptyMap()
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val fileCache = FILE_CACHE_FIELD.get(nodesCache) as? Map<String, *>
                ?: return@runCatching emptyMap()
            fileCache.keys.asSequence()
                .map { "$it.rd5" }
                .mapNotNull { fileName ->
                    val file = File(segmentDirectory, fileName)
                    if (!file.isFile) null else fileName to fingerprint(file)
                }
                .sortedBy { it.first }
                .toMap(linkedMapOf())
        }.getOrDefault(emptyMap())
    }

    fun fingerprint(file: File): String = "${file.length()}:${file.lastModified()}"

    private fun annotation(start: Double, end: Double, tags: Map<String, String>): RouteAnnotation {
        val highway = tags["highway"]
        val surface = surface(tags["surface"])
        val track = trackType(tags["tracktype"])
        val smoothness = smoothness(tags["smoothness"])
        val unpaved = when {
            surface in RouteSurfaceSummary.PAVED_SURFACES -> false
            surface != SurfaceType.UNKNOWN -> true
            track in setOf(TrackType.GRADE2, TrackType.GRADE3, TrackType.GRADE4, TrackType.GRADE5) -> true
            else -> null
        }
        val bicycle = tags["bicycle"]
        val access = tags["access"]
        val limited = when {
            tags["motorroad"] == "yes" -> true
            access in setOf("no", "private", "destination", "customers", "permit") -> true
            bicycle in setOf("no", "private", "use_sidepath") -> true
            access != null || bicycle != null -> false
            else -> null
        }
        val tunnel = tags["tunnel"]?.let { it !in setOf("no", "false", "0") }
        val ferry = if (tags["route"] == "ferry") true else null
        val steps = highway?.let { it == "steps" }
        val carry = when {
            highway == "steps" || bicycle == "dismount" || smoothness == Smoothness.IMPASSABLE -> true
            bicycle != null || highway != null -> false
            else -> null
        }

        return RouteAnnotation(
            startDistanceMeters = start,
            endDistanceMeters = end,
            surface = surface,
            roadClass = roadClass(highway),
            cycleway = cycleway(tags, highway),
            trackType = track,
            smoothness = smoothness,
            trafficStress = trafficStress(tags["estimated_traffic_class"]),
            ferry = ferry,
            tunnel = tunnel,
            steps = steps,
            unpaved = unpaved,
            limitedAccess = limited,
            bikeCarryLikely = carry,
        )
    }

    private fun parseTags(description: String): Map<String, String> = buildMap {
        description.split(' ').forEach { entry ->
            val separator = entry.indexOf('=')
            if (separator > 0 && separator < entry.lastIndex) {
                put(entry.substring(0, separator), entry.substring(separator + 1))
            }
        }
    }

    private fun surface(value: String?): SurfaceType = when (value) {
        "paved" -> SurfaceType.PAVED
        "asphalt" -> SurfaceType.ASPHALT
        "concrete", "concrete:lanes", "concrete:plates" -> SurfaceType.CONCRETE
        "paving_stones" -> SurfaceType.PAVING_STONES
        "sett" -> SurfaceType.SETT
        "cobblestone", "unhewn_cobblestone" -> SurfaceType.COBBLESTONE
        "compacted" -> SurfaceType.COMPACTED
        "fine_gravel" -> SurfaceType.FINE_GRAVEL
        "gravel", "pebblestone" -> SurfaceType.GRAVEL
        "ground", "earth" -> SurfaceType.GROUND
        "dirt" -> SurfaceType.DIRT
        "mud" -> SurfaceType.MUD
        "sand" -> SurfaceType.SAND
        "grass", "grass_paver" -> SurfaceType.GRASS
        "wood", "woodchips" -> SurfaceType.WOOD
        else -> SurfaceType.UNKNOWN
    }

    private fun roadClass(value: String?): RoadClass = when (value) {
        "cycleway" -> RoadClass.CYCLEWAY
        "path" -> RoadClass.PATH
        "track" -> RoadClass.TRACK
        "footway" -> RoadClass.FOOTWAY
        "bridleway" -> RoadClass.BRIDLEWAY
        "pedestrian" -> RoadClass.PEDESTRIAN
        "residential" -> RoadClass.RESIDENTIAL
        "living_street" -> RoadClass.LIVING_STREET
        "service" -> RoadClass.SERVICE
        "unclassified", "road" -> RoadClass.UNCLASSIFIED
        "tertiary", "tertiary_link" -> RoadClass.TERTIARY
        "secondary", "secondary_link" -> RoadClass.SECONDARY
        "primary", "primary_link" -> RoadClass.PRIMARY
        "trunk", "trunk_link" -> RoadClass.TRUNK
        "motorway", "motorway_link" -> RoadClass.MOTORWAY
        else -> RoadClass.UNKNOWN
    }

    private fun cycleway(tags: Map<String, String>, highway: String?): CyclewayType {
        if (highway == "cycleway") return CyclewayType.SEPARATE_PATH
        val values = listOfNotNull(
            tags["cycleway"], tags["cycleway:left"], tags["cycleway:right"], tags["cycleway:both"],
        )
        if (values.any { it in setOf("track", "opposite_track") }) return CyclewayType.PROTECTED_LANE
        if (values.any { it in setOf("lane", "opposite_lane") }) return CyclewayType.PAINTED_LANE
        if (values.any { it in setOf("shared_lane", "share_busway", "opposite_share_busway") }) {
            return CyclewayType.SHARED_LANE
        }
        if (values.isNotEmpty() && values.all { it in setOf("no", "none", "separate") }) {
            return if (values.any { it == "separate" }) CyclewayType.SEPARATE_PATH else CyclewayType.NONE
        }
        return CyclewayType.UNKNOWN
    }

    private fun trackType(value: String?): TrackType = when (value) {
        "grade1" -> TrackType.GRADE1
        "grade2" -> TrackType.GRADE2
        "grade3" -> TrackType.GRADE3
        "grade4" -> TrackType.GRADE4
        "grade5" -> TrackType.GRADE5
        else -> TrackType.UNKNOWN
    }

    private fun smoothness(value: String?): Smoothness = when (value) {
        "excellent" -> Smoothness.EXCELLENT
        "good" -> Smoothness.GOOD
        "intermediate" -> Smoothness.INTERMEDIATE
        "bad" -> Smoothness.BAD
        "very_bad" -> Smoothness.VERY_BAD
        "horrible" -> Smoothness.HORRIBLE
        "very_horrible" -> Smoothness.VERY_HORRIBLE
        "impassable" -> Smoothness.IMPASSABLE
        else -> Smoothness.UNKNOWN
    }

    private fun trafficStress(value: String?): TrafficStress = when (value?.toIntOrNull()) {
        1 -> TrafficStress.VERY_LOW
        2 -> TrafficStress.LOW
        3 -> TrafficStress.MODERATE
        4 -> TrafficStress.HIGH
        5, 6, 7 -> TrafficStress.VERY_HIGH
        else -> TrafficStress.UNKNOWN
    }

    private const val POLL_NANOS = 100_000L
    private val NODES_CACHE_FIELD = RoutingEngine::class.java.getDeclaredField("nodesCache").apply { isAccessible = true }
    private val FILE_CACHE_FIELD = Class.forName("btools.mapaccess.NodesCache")
        .getDeclaredField("fileCache").apply { isAccessible = true }
}
