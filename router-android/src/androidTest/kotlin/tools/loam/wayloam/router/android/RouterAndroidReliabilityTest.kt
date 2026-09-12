package tools.loam.wayloam.router.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tools.loam.wayloam.router.api.GeoPoint
import tools.loam.wayloam.router.api.RouteProfile
import tools.loam.wayloam.router.api.RouteRequest
import tools.loam.wayloam.router.api.RoutingException
import tools.loam.wayloam.router.api.RoutingFailureCode
import java.io.File

@RunWith(AndroidJUnit4::class)
class RouterAndroidReliabilityTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun resetStorage() {
        File(context.noBackupFilesDir, "wayloam-router").deleteRecursively()
    }

    @Test
    fun storageAndDownloadedDataSurviveFacadeRecreation() {
        val first = RouterStorage.create(context)
        val marker = File(first.segments(), "fixture.rd5")
        marker.writeText("persist")

        val second = RouterStorage.create(context)

        assertEquals(first.root().canonicalFile, second.root().canonicalFile)
        assertTrue(File(second.segments(), "fixture.rd5").isFile)
    }

    @Test
    fun concurrentInitializationUsesOneStablePrivateRoot() = runBlocking {
        val roots = coroutineScope {
            List(4) {
                async {
                    WayloamAndroidRouter.create(context, "android-instrumentation").storage.root().canonicalPath
                }
            }.awaitAll()
        }

        assertEquals(1, roots.toSet().size)
        assertTrue(File(roots.first()).isDirectory)
    }

    @Test
    fun corruptUnknownCacheFilesDoNotPreventRestart() = runBlocking {
        val storage = RouterStorage.create(context)
        File(storage.cache(), "corrupt.route").writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        val restarted = WayloamAndroidRouter.create(context, "android-instrumentation")

        assertTrue(restarted.storage.cache().isDirectory)
        restarted.clearRouteCache()
        assertTrue(restarted.storage.cache().listFiles().orEmpty().none { it.name.endsWith(".route") })
    }

    @Test
    fun missingOfflineMapReturnsStableRecoveryCode() = runBlocking {
        val router = WayloamAndroidRouter.create(context, "android-instrumentation")
        val request = RouteRequest(
            start = GeoPoint(49.3988, 8.6724),
            end = GeoPoint(49.4200, 8.6900),
            profile = RouteProfile.TOURING,
        )

        val error = try {
            router.route(request)
            null
        } catch (routing: RoutingException) {
            routing
        }

        requireNotNull(error)
        assertEquals(RoutingFailureCode.MISSING_DATA, error.code)
        assertTrue(router.missingMapFiles(request).isNotEmpty())
    }
}
