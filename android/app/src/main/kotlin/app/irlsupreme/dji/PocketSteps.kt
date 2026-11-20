package app.irlsupreme.dji

import app.irlsupreme.service.ExternalCamera.CameraNet

/**
 * The five things that happen, in this order, once you go live with the Pocket 3 ([PocketSession] then [PocketLink]).
 * The live screen shows where it is as "2 of 5 · Tap OK on the Pocket 3" ([PocketState.label]) and the Camera page
 * lists the same five with the same words, so the streamer knows the order before they start (after the first real
 * stream: "I didn't know the order").
 */
enum class PocketStep {
    /** The network the Pocket 3 streams over: Fullbars' hotspot starts, or the phone's hotspot or Wi‑Fi is up. */
    HOTSPOT,
    /** Bluetooth: connect, then pair (the first time the Pocket 3 asks you to tap OK). */
    BLUETOOTH,
    /** The Pocket 3 gets ready and joins the hotspot or Wi‑Fi. */
    JOIN,
    /** The Pocket 3 starts sending its stream to this phone. */
    START,
    /** Its first picture reaches the phone (the engine sees frames). */
    PICTURE,
    ;

    val number: Int get() = ordinal + 1

    /** "2 of 5 · Tap OK on the Pocket 3": a live-screen line that says where in the order it is. */
    fun progress(what: String): String = "$number of ${entries.size} · $what"

    /** The step's name on the Camera page, in the words the live screen uses. */
    fun title(net: CameraNet): String = when (this) {
        HOTSPOT -> when (net) {
            CameraNet.PRIVATE -> "Fullbars starts its hotspot"
            CameraNet.PHONE_HOTSPOT -> "Fullbars finds your hotspot"
            CameraNet.WIFI -> "Fullbars finds the Wi‑Fi"
        }
        BLUETOOTH -> "Fullbars connects to the Pocket 3 over Bluetooth"
        JOIN -> if (net == CameraNet.WIFI) "The Pocket 3 joins the Wi‑Fi" else "The Pocket 3 joins the hotspot"
        START -> "The Pocket 3 starts streaming"
        PICTURE -> "Its picture arrives"
    }

    /** What you do during this step, or that there's nothing to do (one line). */
    fun guide(net: CameraNet): String = when (this) {
        HOTSPOT -> when (net) {
            CameraNet.PRIVATE -> "Nothing to do."
            CameraNet.PHONE_HOTSPOT -> "Keep your hotspot on."
            CameraNet.WIFI -> "Keep this phone on that Wi‑Fi."
        }
        BLUETOOTH -> "Turn the Pocket 3 on and keep it close. The first time, tap OK on the Pocket 3."
        JOIN -> "Nothing to do. Fullbars gives it the name and password."
        START -> "Nothing to do. It sends its video to this phone."
        PICTURE -> "A few seconds. Until then, viewers see the phone camera."
    }
}

/** Where a setup step stands: finished, the one to do now, or still to come. */
enum class StepStatus(val word: String) { DONE("Done"), NOW("Now"), NEXT("Next") }

/** One numbered setup step: its name, where it stands and, unless it's done, what to do. */
data class SetupStep(val title: String, val status: StepStatus, val detail: String?)

/**
 * The Camera page's setup before going live, in the order it has to happen: find the Pocket 3, give it a hotspot or
 * Wi‑Fi, go live. Pure, and built from the same facts as goLiveBlocker (SetupController), so "Go live" is the step to do
 * now exactly when Home has no Pocket blocker.
 */
object PocketSteps {
    fun setup(chosen: Boolean, scanning: Boolean, net: CameraNet, hasNetworkDetails: Boolean, hotspotUp: Boolean): List<SetupStep> {
        val found = chosen && !scanning
        val networkReady = net == CameraNet.PRIVATE || (hasNetworkDetails && (net != CameraNet.PHONE_HOTSPOT || hotspotUp))
        val findDetail = if (scanning) "Looking over Bluetooth. Keep the Pocket 3 on and close."
        else "Turn the Pocket 3 on and keep it close, then tap Find my Pocket 3. Close DJI Mimo if it's connected to it."
        val networkDetail = when {
            net == CameraNet.PRIVATE -> null
            !hasNetworkDetails && net == CameraNet.WIFI -> "Type the Wi‑Fi's name and password below."
            !hasNetworkDetails && hotspotUp -> "Type your hotspot's name and password below."
            !hasNetworkDetails -> "Turn on your hotspot and type its name and password below."
            else -> "Turn on your hotspot."
        }
        val done = listOf(found, networkReady)
        val firstOpen = done.indexOfFirst { !it }.let { if (it < 0) done.size else it }
        fun status(i: Int) = when {
            i < done.size && done[i] -> StepStatus.DONE
            i == firstOpen -> StepStatus.NOW
            else -> StepStatus.NEXT
        }
        return listOf(
            SetupStep("Find your Pocket 3", status(0), findDetail.takeIf { status(0) != StepStatus.DONE }),
            SetupStep("Choose its hotspot or Wi‑Fi", status(1), networkDetail.takeIf { status(1) != StepStatus.DONE }),
            SetupStep(
                "Go live", status(2),
                if (status(2) == StepStatus.NOW) "Start a test or go live from Home. Fullbars starts the Pocket 3 for you."
                else "Once the steps above are done.",
            ),
        )
    }
}
