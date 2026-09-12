package tools.loam.wayloam.router.brouter

import btools.mapcreator.OsmFastCutter
import btools.mapcreator.PosUnifier
import btools.mapcreator.WayLinker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import tools.loam.wayloam.router.api.GeoPoint
import tools.loam.wayloam.router.api.RouteProfile
import tools.loam.wayloam.router.core.GeoMath
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

class LocalBRouterSmokeTest {
    @Test
    fun generatedRd5RoutesOfflineThroughEmbeddedEngine() {
        System.setProperty("avoidMapPolling", "true")

        val repoRoot = findRepositoryRoot()
        val upstream = File(repoRoot, "vendor/brouter")
        val fixtureDir = File(upstream, "brouter-map-creator/src/test/resources")
        val profileDir = File(upstream, "misc/profiles2")
        val pbf = File(fixtureDir, "dreieich.pbf")
        val osmGzip = File(fixtureDir, "dreieich.osm.gz")

        assertTrue("Pinned PBF fixture is missing: ${pbf.absolutePath}", pbf.isFile)
        assertTrue("Pinned OSM fixture is missing: ${osmGzip.absolutePath}", osmGzip.isFile)
        assertTrue(
            "Pinned BRouter profiles are missing: ${File(profileDir, "trekking.brf").absolutePath}",
            File(profileDir, "trekking.brf").isFile,
        )

        val endpoints = selectRoutableWayEndpoints(osmGzip)
        val working = Files.createTempDirectory("wayloam-brouter-fixture").toFile()

        try {
            val segments = generateRd5Fixture(
                pbf = pbf,
                elevationDir = fixtureDir,
                profileDir = profileDir,
                workingDir = working,
            )
            assertTrue(
                "Map creator did not produce any rd5 files in ${segments.absolutePath}",
                segments.listFiles().orEmpty().any { it.isFile && it.extension == "rd5" && it.length() > 0L },
            )

            val backend = LocalBRouterBackend(
                segmentDirectory = segments,
                profileDirectory = profileDir,
                maxRunningTimeMillis = 30_000L,
            )
            val result = runBlocking {
                backend.route(
                    BRouterBackendRequest(
                        start = endpoints.first,
                        end = endpoints.second,
                        preset = WayloamProfiles.forProfile(RouteProfile.TOURING),
                    )
                )
            }

            assertTrue("Embedded route should contain geometry", result.points.size >= 2)
            assertTrue("Embedded route should have positive distance", result.metrics.distanceMeters > 0L)
            assertTrue(
                "Route start should remain near the selected OSM way",
                GeoMath.distanceMeters(endpoints.first, result.points.first()) < 1_000.0,
            )
            assertTrue(
                "Route end should remain near the selected OSM way",
                GeoMath.distanceMeters(endpoints.second, result.points.last()) < 1_000.0,
            )
        } finally {
            working.deleteRecursively()
        }
    }

    private fun findRepositoryRoot(): File {
        val configured = System.getProperty("wayloam.repoRoot")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.canonicalFile
        if (configured != null && File(configured, "vendor/brouter").isDirectory) {
            return configured
        }

        val start = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(start) { current -> current.parentFile }
            .firstOrNull { candidate -> File(candidate, "vendor/brouter").isDirectory }
            ?: error(
                "Could not locate repository root from ${start.absolutePath}; " +
                    "expected an ancestor containing vendor/brouter"
            )
    }

    private fun generateRd5Fixture(
        pbf: File,
        elevationDir: File,
        profileDir: File,
        workingDir: File,
    ): File {
        fun directory(name: String) = File(workingDir, name).apply {
            check(mkdirs() || isDirectory) { "Could not create $absolutePath" }
        }

        val nodes = directory("nodetiles")
        val ways = directory("waytiles")
        val nodes55 = directory("nodes55")
        val ways55 = directory("waytiles55")
        val unodes55 = directory("unodes55")
        val segments = directory("segments4")

        val lookupFile = File(profileDir, "lookups.dat")
        val relationFile = File(workingDir, "cycleways.dat")
        val restrictionFile = File(workingDir, "restrictions.dat")
        val borderIds = File(workingDir, "bordernids.dat")
        val borderNodes = File(workingDir, "bordernodes.dat")
        val profileAll = File(profileDir, "all.brf")
        val profileReport = File(profileDir, "trekking.brf")
        val profileCheck = File(profileDir, "softaccess.brf")

        check(lookupFile.isFile) { "Missing lookups.dat: ${lookupFile.absolutePath}" }
        check(profileAll.isFile) { "Missing all.brf: ${profileAll.absolutePath}" }
        check(profileReport.isFile) { "Missing trekking.brf: ${profileReport.absolutePath}" }
        check(profileCheck.isFile) { "Missing softaccess.brf: ${profileCheck.absolutePath}" }

        OsmFastCutter.doCut(
            lookupFile,
            nodes,
            ways,
            nodes55,
            ways55,
            borderIds,
            relationFile,
            restrictionFile,
            profileAll,
            profileReport,
            profileCheck,
            pbf,
            null,
        )

        PosUnifier().process(
            nodes55,
            unodes55,
            borderIds,
            borderNodes,
            elevationDir.absolutePath,
            null,
        )

        WayLinker().process(
            unodes55,
            ways55,
            borderNodes,
            restrictionFile,
            lookupFile,
            profileAll,
            segments,
            "rd5",
        )
        return segments
    }

    /**
     * Selects the endpoints of a real, non-trivial bicycle-usable way from the exact XML fixture
     * paired with the pinned PBF. This avoids hardcoding coordinates that could drift if upstream's
     * fixture is ever deliberately bumped together with the pinned BRouter baseline.
     */
    private fun selectRoutableWayEndpoints(osmGzip: File): Pair<GeoPoint, GeoPoint> {
        val nodes = HashMap<Long, GeoPoint>()
        val factory = XMLInputFactory.newFactory()
        var currentWayRefs: MutableList<Long>? = null
        var currentHighway: String? = null
        var selected: Pair<GeoPoint, GeoPoint>? = null

        GZIPInputStream(osmGzip.inputStream().buffered()).use { gzip ->
            val reader = factory.createXMLStreamReader(gzip)
            try {
                while (reader.hasNext()) {
                    when (reader.next()) {
                        XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                            "node" -> {
                                val id = reader.attribute("id")?.toLongOrNull() ?: continue
                                val lat = reader.attribute("lat")?.toDoubleOrNull() ?: continue
                                val lon = reader.attribute("lon")?.toDoubleOrNull() ?: continue
                                nodes[id] = GeoPoint(lat, lon)
                            }

                            "way" -> {
                                currentWayRefs = mutableListOf()
                                currentHighway = null
                            }

                            "nd" -> currentWayRefs?.let { refs ->
                                reader.attribute("ref")?.toLongOrNull()?.let(refs::add)
                            }

                            "tag" -> if (currentWayRefs != null && reader.attribute("k") == "highway") {
                                currentHighway = reader.attribute("v")
                            }
                        }

                        XMLStreamConstants.END_ELEMENT -> if (reader.localName == "way") {
                            if (selected == null && currentHighway in ROUTABLE_HIGHWAYS) {
                                val refs = currentWayRefs.orEmpty()
                                if (refs.size >= 2) {
                                    val first = nodes[refs.first()]
                                    val last = nodes[refs.last()]
                                    if (
                                        first != null &&
                                        last != null &&
                                        GeoMath.distanceMeters(first, last) >= MIN_FIXTURE_WAY_METERS
                                    ) {
                                        selected = first to last
                                    }
                                }
                            }
                            currentWayRefs = null
                            currentHighway = null
                        }
                    }
                }
            } finally {
                reader.close()
            }
        }

        return requireNotNull(selected) { "No suitable routable way found in pinned Dreieich fixture" }
    }

    private fun javax.xml.stream.XMLStreamReader.attribute(name: String): String? =
        getAttributeValue(null, name)

    companion object {
        private const val MIN_FIXTURE_WAY_METERS = 100.0
        private val ROUTABLE_HIGHWAYS = setOf(
            "residential",
            "unclassified",
            "tertiary",
            "secondary",
            "primary",
            "service",
            "cycleway",
            "track",
        )
    }
}
