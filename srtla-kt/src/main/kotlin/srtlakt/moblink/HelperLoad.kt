// SPDX-License-Identifier: Apache-2.0
package srtlakt.moblink

/**
 * How much of the stream one helper phone should carry, from the heat and battery it reports every few seconds (pure,
 * one per helper). A helper that runs hot or low is eased off before it fails, rather than carrying its full share
 * until its OS throttles or kills it mid-stream:
 *  - [Moblink.Thermal.RED] or under [lowBatteryPercent]: [demoted] (one priority class down: it only carries what the
 *    other links can't, plus a warm-up trickle);
 *  - [Moblink.Thermal.YELLOW]: [capScale] halved (a smaller in-flight cap, so a smaller share).
 * Each eases back only after [clearReports] reports in a row are clear again (and the battery is [recoverBatteryPercent]
 * or more), so a helper hovering at a threshold doesn't flip its share every report.
 */
class HelperLoad(
    private val lowBatteryPercent: Int = 15,
    private val recoverBatteryPercent: Int = 20,
    private val clearReports: Int = 2,
) {
    var demoted = false
        private set
    var capScale = 1.0
        private set
    private var demoteClear = 0
    private var capClear = 0

    /** Takes one status report; true when [demoted] or [capScale] changed. */
    fun update(thermal: Moblink.Thermal?, batteryPercent: Int?): Boolean {
        val before = demoted to capScale
        val hot = thermal == Moblink.Thermal.RED
        val low = batteryPercent != null && batteryPercent < lowBatteryPercent
        if (hot || low) {
            demoted = true
            demoteClear = 0
        } else if (demoted) {
            val recovered = batteryPercent == null || batteryPercent >= recoverBatteryPercent
            demoteClear = if (recovered) demoteClear + 1 else 0
            if (demoteClear >= clearReports) demoted = false
        }
        if (thermal == Moblink.Thermal.YELLOW || hot) {
            capScale = WARM_CAP_SCALE
            capClear = 0
        } else if (capScale < 1.0) {
            if (++capClear >= clearReports) capScale = 1.0
        }
        return before != (demoted to capScale)
    }

    private companion object {
        const val WARM_CAP_SCALE = 0.5
    }
}
