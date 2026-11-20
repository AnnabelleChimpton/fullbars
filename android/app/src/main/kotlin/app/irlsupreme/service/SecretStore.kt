package app.irlsupreme.service

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted store for secrets (stream key, Twitch tokens, pairing): AES-GCM with a non-exportable Android
 * Keystore key; ciphertext in SharedPreferences. Excluded from backup (allowBackup=false). Values are never logged.
 *
 * Keystore round trips are slow (milliseconds each, on the main thread at cold start the setup screen made about ten),
 * so the key handle and the decrypted values are cached for the life of the process. The cache is shared by every
 * instance (they all read the same file) and kept in step by [put], the only writer. Plaintext stays in this
 * process's memory only, as it already did while the screens held it.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        synchronized(SecretStore::class.java) {
            cachedKey?.let { return it }
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: run {
                val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                gen.init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
                gen.generateKey()
            }
            cachedKey = key
            return key
        }
    }

    fun put(name: String, value: String?) {
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(name).apply()
            values[name] = ABSENT
            return
        }
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, b64(c.iv) + ":" + b64(ct)).apply()
        values[name] = value
    }

    fun get(name: String): String? {
        values[name]?.let { return it.ifEmpty { null } }
        val stored = prefs.getString(name, null)
        val plain = stored?.let {
            runCatching {
                val (iv, ct) = it.split(":").map { part -> Base64.decode(part, Base64.NO_WRAP) }
                val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
                String(c.doFinal(ct), Charsets.UTF_8)
            }.getOrNull()
        }
        // A value that fails to decrypt isn't cached, so a passing Keystore hiccup doesn't hide it for the whole process.
        // putIfAbsent: a put() that landed while this was decrypting wins over the older value read here.
        val cached = if (stored == null) values.putIfAbsent(name, ABSENT) else plain?.let { values.putIfAbsent(name, it) }
        return if (cached != null) cached.ifEmpty { null } else plain
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private companion object {
        const val ALIAS = "irlsupreme.secrets"
        const val TRANSFORM = "AES/GCM/NoPadding"

        @Volatile var cachedKey: SecretKey? = null

        /**
         * Decrypted values by name; [ABSENT] marks "nothing stored" (ConcurrentHashMap can't hold null). put() never
         * stores an empty string, so an empty entry can only mean absent.
         */
        val values = ConcurrentHashMap<String, String>()
        const val ABSENT = ""
    }
}
