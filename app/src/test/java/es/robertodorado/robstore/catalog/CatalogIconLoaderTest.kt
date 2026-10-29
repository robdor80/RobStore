package es.robertodorado.robstore.catalog

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class CatalogIconLoaderTest {
    @get:Rule val temp = TemporaryFolder()
    private fun png(width: Int = 16, height: Int = 16): ByteArray = ByteArrayOutputStream().use {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        it.toByteArray()
    }
    @Test fun missingIconFallsBackAndDoesNotRepeatedlyFetchInSession() = runTest {
        val transport = FixtureTransport()
        val loader = CatalogIconLoader(temp.newFolder(), GitHubClient(transport, MemoryCache()) { TEST_NOW })
        val icon = fixtureSnapshot().apps.single().icon!!
        assertNull(loader.load(icon)); assertNull(loader.load(icon))
        assertEquals(1, transport.requests.size)
        assertEquals(icon.tag, transport.requests.single().url.queryParameter("ref"))
    }
    @Test fun validImageIsCachedInMemoryAndOnDiskForOfflineUse() = runTest {
        val bytes = png()
        val transport = FixtureTransport().apply { intercept = { HttpResult(200, bytes) } }
        val directory = temp.newFolder()
        val client = GitHubClient(transport, MemoryCache()) { TEST_NOW }
        val loader = CatalogIconLoader(directory, client)
        val icon = fixtureSnapshot().apps.single().icon!!
        assertNotNull(loader.load(icon)); assertNotNull(loader.load(icon)); assertEquals(1, transport.requests.size)
        transport.intercept = { throw java.io.IOException("offline") }
        assertNotNull(CatalogIconLoader(directory, client).load(icon)); assertEquals(1, transport.requests.size)
    }
    @Test fun invalidFormatOversizedBodyAndDimensionsAreRejected() {
        val loader = CatalogIconLoader(temp.newFolder(), GitHubClient(FixtureTransport(), MemoryCache()) { TEST_NOW })
        assertNull(loader.decode("not an image".toByteArray()))
        assertNull(loader.decode(ByteArray(CatalogPolicy.MAX_ICON_BYTES + 1)))
        assertNull(loader.decode(png(2049, 2)))
        assertNotNull(loader.decode(png()))
    }
    @Test fun iconFromNewReleaseUsesDifferentCacheEntry() = runTest {
        val transport = FixtureTransport().apply { intercept = { HttpResult(200, png()) } }
        val loader = CatalogIconLoader(temp.newFolder(), GitHubClient(transport, MemoryCache()) { TEST_NOW })
        val icon = fixtureSnapshot().apps.single().icon!!
        loader.load(icon); loader.load(icon.copy(releaseId = 21, tag = "new-edition"))
        assertEquals(2, transport.requests.size)
        assertEquals("new-edition", transport.requests.last().url.queryParameter("ref"))
    }
}
