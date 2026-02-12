package moe.reimu.catshare.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import java.net.NetworkInterface

interface MacAddressProvider {
    suspend fun getMacAddress(context: Context, name: String): String?
}

object SystemMacAddressProvider : MacAddressProvider {
    private const val LOCAL_MAC_PERMISSION = "android.permission.LOCAL_MAC_ADDRESS"

    override suspend fun getMacAddress(context: Context, name: String): String? {
        if (context.checkSelfPermission(LOCAL_MAC_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val directMac = readWithNetworkInterface(name)
        if (isUsableMacAddress(directMac)) {
            return directMac
        }

        val nmsMac = readWithNetworkManagementService(name)
        if (isUsableMacAddress(nmsMac)) {
            return nmsMac
        }

        return null
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun readWithNetworkInterface(name: String): String? {
        return try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            for (intf in ifs) {
                if (intf.name == name) {
                    return intf.hardwareAddress?.toHexString(HexFormat {
                        bytes.byteSeparator = ":"
                    })
                }
            }
            null
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to read $name MAC via NetworkInterface", e)
            null
        }
    }

    private fun readWithNetworkManagementService(name: String): String? {
        return try {
            val serviceManagerClz = Class.forName("android.os.ServiceManager")
            val getService = serviceManagerClz.getDeclaredMethod("getService", String::class.java)
            val binder = getService.invoke(null, "network_management") as? IBinder ?: return null

            val nmsStubClz = Class.forName("android.os.INetworkManagementService\$Stub")
            val asInterface = nmsStubClz.getDeclaredMethod("asInterface", IBinder::class.java)
            val nms = asInterface.invoke(null, binder) ?: return null

            val getInterfaceConfig = nms.javaClass.getDeclaredMethod(
                "getInterfaceConfig",
                String::class.java
            )
            val ifaceCfg = getInterfaceConfig.invoke(nms, name) ?: return null

            val getHardwareAddress = ifaceCfg.javaClass.methods.firstOrNull {
                it.name == "getHardwareAddress" && it.parameterCount == 0
            }
            if (getHardwareAddress != null) {
                return getHardwareAddress.invoke(ifaceCfg) as? String
            }

            val hwAddrFieldNames = listOf("mHwAddr", "hwAddr")
            for (fieldName in hwAddrFieldNames) {
                val field = runCatching { ifaceCfg.javaClass.getDeclaredField(fieldName) }.getOrNull()
                    ?: continue
                field.isAccessible = true
                val value = field.get(ifaceCfg) as? String
                if (!value.isNullOrBlank()) {
                    return value
                }
            }

            null
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to read $name MAC via network_management", e)
            null
        }
    }

    private fun isUsableMacAddress(mac: String?): Boolean {
        if (mac.isNullOrBlank()) {
            return false
        }
        if (!MAC_ADDRESS_REGEX.matches(mac)) {
            return false
        }
        return !ALL_ZERO_MAC_REGEX.matches(mac)
    }

    private val MAC_ADDRESS_REGEX = Regex("(?i)^([0-9a-f]{2}:){5}[0-9a-f]{2}$")
    private val ALL_ZERO_MAC_REGEX = Regex("(?i)^(00:){5}00$")
}
