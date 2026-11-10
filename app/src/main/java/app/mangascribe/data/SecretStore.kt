package app.mangascribe.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where API keys actually live.
 *
 * A key typed into Settings has to survive closing the app, a reboot, an update, and a
 * low-memory kill — and it must not be readable by another app or land in a cloud backup.
 * DataStore alone gives the first and fails the rest, so keys are kept in a separate file,
 * encrypted with an AES-GCM key that lives in the Android Keystore and never leaves the TEE:
 *
 *  - the ciphertext file is written synchronously and atomically (write + rename), so a key is
 *    on disk before the user finishes typing, and a crash mid-write cannot lose the old one;
 *  - backup rules exclude the file, and the Keystore key is device-bound, so a copy of the
 *    file is useless on another phone (which is the correct trade for a credential);
 *  - the plaintext exists only in the settings snapshot held in memory.
 *
 * If the Keystore key is missing (a restore from another device, a key invalidated by a lock
 * change), the encrypted blob is reported as unreadable and cleared rather than silently
 * producing empty keys — the settings screen then says the key must be entered again.
 */
class SecretStore(private val context: Context) {

    private val file = File(context.filesDir, "secrets.bin")
    private val alias = "mangascribe-secrets-v1"

    /** The keys, or empty strings when nothing has been stored yet. */
    @Synchronized
    fun load(): Secrets {
        if (!file.exists()) return Secrets()
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return Secrets()
        val plain = runCatching { decrypt(bytes) }.getOrNull()
        if (plain == null) {
            // A blob we cannot open is worse than none: drop it so the UI can say "re-enter".
            runCatching { file.delete() }
            lastFailure = "The saved keys could not be opened on this device. Enter them again."
            return Secrets()
        }
        return runCatching { Secrets.fromJson(plain) }.getOrDefault(Secrets())
    }

    /** What went wrong last time a stored blob was unreadable, for the settings screen. */
    @Volatile
    var lastFailure: String? = null
        private set

    /** Writes the keys immediately, and throws if the write fails (the caller shows it). */
    @Synchronized
    fun save(secrets: Secrets) {
        val json = secrets.toJson()
        val encrypted = if (secrets.isEmpty) {
            // Nothing to protect: remove the file instead of storing an empty document.
            runCatching { file.delete() }
            return
        } else {
            encrypt(json)
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        // Synchronous on purpose: an asynchronous write is how a key gets lost on a kill.
        tmp.outputStream().use { out ->
            out.write(encrypted)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            // rename can fail on a filesystem that dislikes atomic replace; fall back to copy
            file.outputStream().use { out ->
                out.write(encrypted)
                out.flush()
                out.fd.sync()
            }
            tmp.delete()
        }
        lastFailure = null
    }

    fun clear() {
        runCatching { file.delete() }
    }

    // ---- crypto ---------------------------------------------------------------------------

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No user-authentication requirement: the reader must not be asked for a
                // fingerprint to open a page, and the key's purpose is to hide the value from
                // backup dumps and other apps, not from the phone's owner.
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // header: [version][iv length][iv][body]
        return byteArrayOf(VERSION, iv.size.toByte()) + iv + body
    }

    private fun decrypt(data: ByteArray): String {
        require(data.size > 2 && data[0] == VERSION) { "unknown secret format" }
        val ivLength = data[1].toInt()
        require(data.size > 2 + ivLength) { "truncated secret" }
        val iv = data.copyOfRange(2, 2 + ivLength)
        val body = data.copyOfRange(2 + ivLength, data.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
    }
}

