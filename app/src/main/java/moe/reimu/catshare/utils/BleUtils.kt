package moe.reimu.catshare.utils

import java.nio.ByteBuffer
import java.util.Random
import java.util.UUID


object BleUtils {
    val ADV_SERVICE_UUID = UUID.fromString("00003331-0000-1000-8000-008123456789")
    val SERVICE_UUID = UUID.fromString("00009955-0000-1000-8000-00805f9b34fb")
    val CHAR_STATUS_UUID = UUID.fromString("00009954-0000-1000-8000-00805f9b34fb")
    val CHAR_P2P_UUID = UUID.fromString("00009953-0000-1000-8000-00805f9b34fb")

    const val MISHARE_MANUFACTURER_XIAOMI = 30
    const val MISHARE_GENERIC_XIAOMI_PHONE = 1
    const val MISHARE_ADV_VERSION = 1
    const val MISHARE_FLAG_SUPPORT_5GHZ = 1

    val RANDOM_DATA: ByteArray = run {
        ByteArray(10).also {
            Random().nextBytes(it)
        }
    }

    fun getSenderId(): String {
        val senderIdRaw = (RANDOM_DATA[8].toInt() and 0xff).shl(8)
            .or(RANDOM_DATA[9].toInt() and 0xff)
        return String.format("%04x", senderIdRaw)
    }

    fun miShareServiceDataUuid(firstRawByte: Int, secondRawByte: Int): UUID {
        return UUID.fromString(
            String.format(
                "0000%02x%02x-0000-1000-8000-00805f9b34fb",
                secondRawByte and 0xff,
                firstRawByte and 0xff,
            )
        )
    }

    fun miShareDeviceCodeUuid(deviceCode: Int): UUID {
        return miShareServiceDataUuid(
            (deviceCode ushr 8) and 0xff,
            deviceCode and 0xff,
        )
    }
}
