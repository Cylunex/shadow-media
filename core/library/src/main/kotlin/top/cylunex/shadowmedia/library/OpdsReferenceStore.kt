package top.cylunex.shadowmedia.library

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** OPDS servers may put authentication in any URL path segment, including entry identifiers.
 * Room only receives account-bound opaque references; original navigation lives in the vault.
 * Acquisition URLs are deliberately never registered. */
internal class OpdsReferenceStore(context: Context) {
    private val preferences = context.getSharedPreferences("opds_references", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
        }.generateKey()
    }
    @Synchronized fun encode(scope: String, values: List<String>): Map<String, String> {
        val editor = preferences.edit()
        val result = values.distinct().associateWith { value ->
            if (isReference(value)) { decode(scope, value); value } else {
                val reference = reference(scope, value)
                if (!preferences.contains(reference)) {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(scope.toByteArray()) }
                    editor.putString(reference, Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(value.toByteArray())))
                }
                reference
            }
        }
        check(editor.commit()) { "目录定位信息保存失败" }
        return result
    }
    @Synchronized fun decode(scope: String, value: String): String {
        if (!isReference(value)) return value // Legacy input is migrated without changing asset ids.
        val bytes = Base64.getDecoder().decode(requireNotNull(preferences.getString(value, null)) { "目录连接定位已丢失，请刷新来源" })
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); updateAAD(scope.toByteArray())
        }
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    companion object {
        private const val ALIAS = "shadow_opds_references_v1"
        fun isReference(value: String) = value.startsWith("opds-ref:v1:")
        fun reference(scope: String, value: String) = "opds-ref:v1:" + MessageDigest.getInstance("SHA-256")
            .digest(top.cylunex.shadowmedia.model.scopedContentId(scope, value).toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
