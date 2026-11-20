package app.irlsupreme.engine.video

import app.irlsupreme.engine.CameraPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFlipTest {
    @Test fun framesOutsideAFlipAlwaysGoOut() {
        for (p in CameraPhase.entries) assertTrue(cameraFrameUsable(switching = false, phase = p))
    }

    @Test fun oldCameraFramesDuringAFlipAreDrainedNotShown() {
        // The new camera's transform is already set while it opens, so a leftover frame would be turned wrong.
        assertFalse(cameraFrameUsable(switching = true, phase = CameraPhase.OFF))
        assertFalse(cameraFrameUsable(switching = true, phase = CameraPhase.OPENING))
        assertFalse(cameraFrameUsable(switching = true, phase = CameraPhase.LOST))
    }

    @Test fun theNewCamerasFramesEndTheFlip() {
        assertTrue(cameraFrameUsable(switching = true, phase = CameraPhase.LIVE))
    }
}
