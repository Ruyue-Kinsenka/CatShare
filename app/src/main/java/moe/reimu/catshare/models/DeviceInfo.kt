package moe.reimu.catshare.models

import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfo(
    val state: Int,
    val key: String? = null,
    val mac: String? = null,
    val reason: Int? = null,
    val frequency: Int? = null,
    val catShare: Int? = null
)
