package es.robertodorado.robstore

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
class LauncherIconTest {
    private val application get() = RuntimeEnvironment.getApplication()

    private fun foreground(): Bitmap = BitmapFactory.decodeResource(
        application.resources, R.drawable.ic_launcher_foreground,
        BitmapFactory.Options().apply { inScaled = false },
    )

    @Test fun adaptiveLaunchersHaveTransparentBackgrounds() {
        for (resource in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val icon = application.getDrawable(resource) as AdaptiveIconDrawable
            assertEquals(Color.TRANSPARENT, (icon.background as ColorDrawable).color)
            assertNotNull(icon.foreground)
        }
    }

    @Test fun completeArtworkFitsTheAdaptiveSafeCircle() {
        val bitmap = foreground()
        assertEquals(108, bitmap.width)
        assertEquals(108, bitmap.height)
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        var visiblePixels = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                    visiblePixels++
                    val distanceSquared = (x - 53.5) * (x - 53.5) + (y - 53.5) * (y - 53.5)
                    assertTrue("Artwork outside safe circle at $x,$y", distanceSquared <= 33 * 33)
                }
            }
        }
        assertTrue(visiblePixels > 1000)
    }

    @Test fun devBadgeIsPresentOnlyInDebug() {
        val bitmap = foreground()
        val badge = bitmap.getPixel(62, 65)
        if (BuildConfig.BUILD_TYPE == "debug") {
            assertTrue(Color.red(badge) in 90..120)
            assertTrue(Color.green(badge) in 200..235)
            assertTrue(Color.blue(badge) >= 240)
            val letter = bitmap.getPixel(65, 66)
            assertTrue(Color.red(letter) < 40 && Color.green(letter) < 40 && Color.blue(letter) < 40)
        } else {
            assertTrue(Color.red(badge) < 40 && Color.green(badge) < 80)
        }
    }
}
