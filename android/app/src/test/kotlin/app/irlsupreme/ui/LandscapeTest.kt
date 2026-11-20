package app.irlsupreme.ui

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeTest {
    @Test fun `setup takes the chosen shape, live the streaming one, the welcome follows the phone`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, screenOrientation(false, false, StreamOrientation.LANDSCAPE, welcome = false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, screenOrientation(false, false, StreamOrientation.PORTRAIT, welcome = false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, screenOrientation(false, false, StreamOrientation.LANDSCAPE, welcome = true))
        // Live: the stream's real shape wins over a choice changed mid-stream.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, screenOrientation(true, true, StreamOrientation.LANDSCAPE, welcome = false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, screenOrientation(true, false, StreamOrientation.PORTRAIT, welcome = true))
    }

    @Test fun `side-by-side only when sideways and wide enough`() {
        assertTrue(wideLayout(915, 412)) // a phone held sideways
        assertFalse(wideLayout(412, 915)) // upright
        assertFalse(wideLayout(560, 400)) // a narrow split-screen window
        assertTrue(wideLayout(1280, 800)) // a tablet
    }
}
