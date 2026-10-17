package app.mebox.android.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.mebox.android.core.model.AccountScope
import app.mebox.android.core.model.Credentials
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 使用 Android Keystore 中的 AES-GCM 密钥加密凭证文件。
 *
 * - 文件位于应用私有目录，按 server_id + user_id 分文件；
 * - 先写临时文件并 fsync，再原子重命名，避免进程被杀留下半个令牌对；
 * - 密钥丢失或备份恢复导致解密失败时删除文件并返回 null，由界面要求
 *   重新登录，不进入反复崩溃的循环。
 */
class KeystoreCredentialStore(
    context: Context,
    private val json: Json,
) : CredentialStore {

    private val dir = File(context.filesDir, "credentials").apply { mkdirs() }

    @Synchronized
    override fun read(scope: AccountScope): Credentials? {
        val file = fileFor(scope)
        if (!file.exists()) return null
        return try {
            val payload = file.readBytes()
            if (payload.size < MIN_PAYLOAD_SIZE || (payload[0].toInt() and 0xFF) != VERSION) {
                file.delete()
                return null
            }
            val ivLength = payload[1].toInt() and 0xFF
            if (ivLength <= 0 || payload.size <= 2 + ivLength) {
                file.delete()
                return null
            }
            val iv = payload.copyOfRange(2, 2 + ivLength)
            val ciphertext = payload.copyOfRange(2 + ivLength, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyForRead() ?: return null, GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(ciphertext)
            json.decodeFromString(Credentials.serializer(), plain.decodeToString())
        } catch (_: GeneralSecurityException) {
            file.delete()
            null
        } catch (_: IllegalArgumentException) {
            file.delete()
            null
        } catch (_: kotlinx.serialization.SerializationException) {
            file.delete()
            null
        } catch (_: java.io.IOException) {
            null
        }
    }

    @Synchronized
    override fun write(scope: AccountScope, credentials: Credentials) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val plain = json.encodeToString(Credentials.serializer(), credentials).encodeToByteArray()
        val ciphertext = cipher.doFinal(plain)

        val payload = ByteArray(2 + iv.size + ciphertext.size)
        payload[0] = VERSION.toByte()
        payload[1] = iv.size.toByte()
        iv.copyInto(payload, 2)
        ciphertext.copyInto(payload, 2 + iv.size)

        val target = fileFor(scope)
        val temp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(payload)
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IllegalStateException("cannot persist credentials")
            }
        }
    }

    @Synchronized
    override fun clear(scope: AccountScope) {
        val file = fileFor(scope)
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    private fun fileFor(scope: AccountScope): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${scope.serverId}\u0000${scope.userId}".encodeToByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(dir, "$name.bin")
    }

    private fun keyForRead(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun getOrCreateKey(): SecretKey {
        keyForRead()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "mebox_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION = 1
        const val MIN_PAYLOAD_SIZE = 2 + 12 + 16
    }
}
