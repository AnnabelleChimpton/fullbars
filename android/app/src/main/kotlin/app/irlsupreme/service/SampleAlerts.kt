package app.irlsupreme.service

import app.irlsupreme.twitch.TwitchAlert

/**
 * Pretend alerts, for seeing and hearing what each kind looks like: in Settings > Overlays' preview, and on stream from
 * the Scenes sheet during a Test stream (never a public one, where viewers would take them for real). The words and
 * numbers are made up but typical, so the card sizes and wraps as it will for the real thing.
 */
enum class SampleAlert(val title: String) {
    FOLLOW("Follow"),
    SUB("Sub"),
    GIFT("Gift subs"),
    BITS("Bits"),
    RAID("Raid"),
    TIP("Tip"),
    ;

    /** A fresh alert of this kind; [seq] keeps each one's id unique so the queue never takes it for a repeat. */
    fun alert(seq: Long = System.nanoTime()): TwitchAlert {
        val id = "sample-${name.lowercase()}-$seq"
        return when (this) {
            FOLLOW -> TwitchAlert.Follow(id, "sample_viewer")
            SUB -> TwitchAlert.Sub(id, "kit_irl", "1000", prime = false, months = null)
            GIFT -> TwitchAlert.GiftSubs(id, "Anonymous", 5, "1000")
            BITS -> TwitchAlert.Cheer(id, "kit_irl", 500)
            RAID -> TwitchAlert.Raid(id, "BigStreamer", 230)
            TIP -> TwitchAlert.Tip(id, "kit_irl", "$5.00")
        }
    }
}
