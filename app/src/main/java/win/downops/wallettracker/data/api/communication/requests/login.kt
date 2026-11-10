package win.downops.wallettracker.data.api.communication.requests

import com.google.gson.annotations.SerializedName

data class CipheredRequest(
    @SerializedName("encrypted_aes_key") val encryptedAesKey: String?,
    val iv: String?,
    val ciphertext: String?,
    val tag: String?
)
data class LoginRequest(
    val username: String,
    val password: String
)
data class AutoLoginRequest(
    val userId: Int,
    val ciphered: String
)
data class ServerPubKeyRequest(
    val publicKey: String
)