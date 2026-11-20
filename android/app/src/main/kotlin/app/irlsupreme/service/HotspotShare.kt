package app.irlsupreme.service

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * How a streaming phone hands its private hotspot's name and password to its helper phones over Bluetooth LE, so they
 * join it by themselves. Android 16.0 gives that hotspot a new name and password every stream, so a QR code can't
 * carry them; the helper code (the Moblink password both phones already share) can protect them instead.
 *
 *  - The streaming phone advertises service data under [SERVICE]: an 8-byte [Keys.tag] derived from the helper code,
 *    so a helper picks out its own streaming phone without connecting to every Fullbars phone nearby.
 *  - A GATT read of [CREDENTIALS] returns [seal]ed bytes: version, 12-byte nonce, AES-256-GCM of the name, password
 *    and security type. Anyone can read them; only a phone with the helper code can open them.
 *
 * The code is 12 random characters (60 bits); PBKDF2 makes guessing it from a captured read slow on top of that, and
 * a guessed one only gets onto a hotspot with no internet.
 */
object HotspotShare {
    val SERVICE: UUID = UUID.fromString("6d1f6a52-3c0e-4b7e-9f0a-8a7c2e5b1f01")
    val CREDENTIALS: UUID = UUID.fromString("6d1f6a52-3c0e-4b7e-9f0a-8a7c2e5b1f02")
    const val TAG_BYTES = 8
    private const val VERSION: Byte = 1
    private const val NONCE = 12
    private const val GCM_TAG_BITS = 128
    private const val ITERATIONS = 20_000
    private val SALT = "fullbars-hotspot-share-v1".toByteArray()
    private val AAD = "fullbars-hotspot-v1".toByteArray()

    /** Android's SoftApConfiguration security types we pass on (others are sent as-is and treated as WPA2). */
    const val SECURITY_WPA2 = 1
    const val SECURITY_WPA3_TRANSITION = 2
    const val SECURITY_WPA3 = 3

    data class Credentials(val ssid: String, val passphrase: String, val security: Int) {
        override fun toString() = "Credentials(ssid=$ssid, security=$security)" // never the password
    }

    /** Derived once per helper code (PBKDF2 is deliberately slow): the encryption key and the advertised tag. */
    class Keys(password: String) {
        internal val enc: ByteArray
        val tag: ByteArray

        init {
            val spec = PBEKeySpec(password.toCharArray(), SALT, ITERATIONS, 256)
            val master = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            spec.clearPassword()
            enc = hmac(master, "enc")
            tag = hmac(master, "tag").copyOf(TAG_BYTES)
        }
    }

    private val rng = SecureRandom()

    fun seal(keys: Keys, c: Credentials): ByteArray {
        val ssid = c.ssid.toByteArray(Charsets.UTF_8)
        val pass = c.passphrase.toByteArray(Charsets.UTF_8)
        require(ssid.size in 1..32 && pass.size <= 63) { "not a Wi-Fi name and password" }
        val plain = ByteBuffer.allocate(3 + ssid.size + pass.size)
            .put(ssid.size.toByte()).put(ssid).put(pass.size.toByte()).put(pass).put(c.security.toByte()).array()
        val nonce = ByteArray(NONCE).also(rng::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.enc, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(AAD)
        return byteArrayOf(VERSION) + nonce + cipher.doFinal(plain)
    }

    /** The credentials, or null if [bytes] weren't sealed with this helper code (or aren't ours at all). */
    fun open(keys: Keys, bytes: ByteArray): Credentials? {
        if (bytes.size < 1 + NONCE + GCM_TAG_BITS / 8 || bytes[0] != VERSION) return null
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.enc, "AES"), GCMParameterSpec(GCM_TAG_BITS, bytes, 1, NONCE))
            cipher.updateAAD(AAD)
            cipher.doFinal(bytes, 1 + NONCE, bytes.size - 1 - NONCE)
        } catch (_: java.security.GeneralSecurityException) {
            return null
        }
        return runCatching {
            val b = ByteBuffer.wrap(plain)
            val ssid = ByteArray(b.get().toInt() and 0xff).also { b.get(it) }
            val pass = ByteArray(b.get().toInt() and 0xff).also { b.get(it) }
            Credentials(String(ssid, Charsets.UTF_8), String(pass, Charsets.UTF_8), b.get().toInt() and 0xff)
        }.getOrNull()?.takeIf { it.ssid.isNotEmpty() }
    }

    private fun hmac(key: ByteArray, label: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(label.toByteArray())
}
