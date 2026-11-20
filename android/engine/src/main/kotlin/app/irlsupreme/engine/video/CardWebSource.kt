package app.irlsupreme.engine.video

import android.content.Context

/**
 * A scene card's web page (Settings › Scene cards › Web page): an https URL, such as a StreamElements scene or the
 * streamer's own HTML, shown full-frame in place of a phone-drawn card.
 *
 * It's the browser overlay's machinery ([BrowserSource]: a WebView in a Presentation on a private virtual display,
 * read as a GL texture) opened as a second source, so it inherits everything that already keeps the overlay safe:
 * sound blocked, a crashed renderer reloaded instead of taking the app down, and the GL side freed only once the main
 * thread has closed the page. It renders at the overlay's 720p, laid out as 1080p CSS, in the stream's shape.
 *
 * Until the page has drawn its first frame of its own ([ready]), and whenever it fails or its renderer is reloading,
 * the compositor shows the normal card, so a slow or broken page never puts a black or blank frame on stream.
 *
 * Created, updated and retired on the render thread, like [BrowserSource].
 */
internal class CardWebSource(ctx: Context, val url: String, width: Int, height: Int, cssWidth: Int, cssHeight: Int) {
    val browser = BrowserSource(ctx, width, height, cssWidth, cssHeight, BrowserSource.Content.Url(url), label = "card page")

    /** The page itself is on its texture and nothing has gone wrong: it can replace the card. */
    val ready: Boolean get() = browser.pageShown && browser.hasFrame && !browser.loadFailed

    /** The page couldn't load: the compositor gives up on it until the card is shown again or the URL changes. */
    val failed: Boolean get() = browser.loadFailed

    val texture: Int get() = browser.texture
    val texMatrix: FloatArray get() = browser.texMatrix
    val crashes: Int get() = browser.crashes

    /** Render thread, each tick it may be shown: latch the page's newest frame. */
    fun update() = browser.update()

    companion object {
        /**
         * Whether [url] may be opened as a card: https only (a card page runs JavaScript on stream, so never over
         * plain http, file: or a custom scheme) with a host. Pure, for tests.
         */
        fun usable(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val u = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
            return u.scheme.equals("https", ignoreCase = true) && !u.host.isNullOrEmpty()
        }
    }
}
