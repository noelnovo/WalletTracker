package win.downops.wallettracker.v2

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import java.security.spec.MGF1ParameterSpec

/** Non-exportable keys. This limits extraction, not abuse of an already compromised process. */
class DeviceVault(private val context: Context, private val account: String) {
    private val keys get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val suffix = hash(account)
    private val dataAlias = "wallet.v2.data.$suffix"
    private val loginAlias = "wallet.v2.login.$suffix"
    private val encoding = Base64.NO_WRAP
    private val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    @Synchronized private fun dataKey(): SecretKey {
        (keys.getKey(dataAlias, null) as? SecretKey)?.let { return it }
        fun generate(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(dataAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
            if (Build.VERSION.SDK_INT >= 28) spec.setIsStrongBoxBacked(strongBox)
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec.build()) }.generateKey()
        }
        if (Build.VERSION.SDK_INT >= 28 && context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
            try { return generate(true) } catch (_: StrongBoxUnavailableException) { /* Fall back to the device Keystore. */ }
        }
        return generate(false)
    }

    fun seal(value: String, purpose: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, dataKey())
        cipher.updateAAD("$account:$purpose".toByteArray())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), encoding)
    }

    fun open(value: String, purpose: String): String {
        val bytes = Base64.decode(value, encoding)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, dataKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD("$account:$purpose".toByteArray())
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
    }

    fun wrapRefresh(value: String): String {
        if (!keys.containsAlias(loginAlias)) {
            val spec = KeyGenParameterSpec.Builder(loginAlias, KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true).build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keys.getCertificate(loginAlias).publicKey, oaep)
        return Base64.encodeToString(cipher.doFinal(value.toByteArray()), encoding)
    }

    fun unlockCipher(): Cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply {
        init(Cipher.DECRYPT_MODE, keys.getKey(loginAlias, null), oaep)
    }

    fun forgetLogin() { if (keys.containsAlias(loginAlias)) keys.deleteEntry(loginAlias) }

    companion object {
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
