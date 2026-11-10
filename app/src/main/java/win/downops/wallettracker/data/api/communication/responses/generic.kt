package win.downops.wallettracker.data.api.communication.responses

import com.google.gson.annotations.SerializedName
import win.downops.wallettracker.data.api.communication.requests.CipheredRequest

data class CipheredResponse(
    val signature: String?,
    @SerializedName("encrypted_data") val encryptedData: CipheredRequest?
)

data class BaseResponse<T>(
    val data: T?,
    val message: String,
    val success: Boolean
)