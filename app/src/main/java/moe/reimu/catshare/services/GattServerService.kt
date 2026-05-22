package moe.reimu.catshare.services


import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.reimu.catshare.AppSettings
import moe.reimu.catshare.BleSecurity
import moe.reimu.catshare.BuildConfig
import moe.reimu.catshare.R
import moe.reimu.catshare.models.DeviceInfo
import moe.reimu.catshare.models.P2pInfo
import moe.reimu.catshare.utils.BleUtils
import moe.reimu.catshare.utils.JsonWithUnknownKeys
import moe.reimu.catshare.utils.SystemMacAddressProvider
import moe.reimu.catshare.utils.NotificationUtils
import moe.reimu.catshare.utils.ServiceState
import moe.reimu.catshare.utils.TAG
import moe.reimu.catshare.utils.checkBluetoothPermissions
import moe.reimu.catshare.utils.registerInternalBroadcastReceiver
import moe.reimu.catshare.utils.INTERNAL_BROADCAST_PERMISSION
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

class GattServerService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var btManager: BluetoothManager
    private var wifiManager: WifiManager? = null
    private var btAdvertiser: BluetoothLeAdvertiser? = null

    private var advertisingSet: AdvertisingSet? = null
    private var restartAdvRunnable: Runnable? = null

    private val localDeviceInfoLock = Object()
    private var localDeviceInfo = DeviceInfo(
        state = 0,
        key = BleSecurity.getEncodedPublicKey(),
        mac = "02:00:00:00:00:00",
        frequency = 0,
        catShare = BuildConfig.VERSION_CODE,
    )
    private var localDeviceStatusBytes = Json.encodeToString(localDeviceInfo).toByteArray()

    private val internalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ServiceState.ACTION_QUERY_RECEIVER_STATE -> {
                    context.sendBroadcast(ServiceState.getUpdateIntent(true), INTERNAL_BROADCAST_PERMISSION)
                }

                ServiceState.ACTION_STOP_SERVICE -> {
                    Log.i(GattServerService.TAG, "Received ACTION_STOP_SERVICE")
                    stopSelf()
                }
            }
        }
    }
    private var internalReceiverRegistered = false

    private val advSetCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(
            advertisingSet: AdvertisingSet?, txPower: Int, status: Int
        ) {
            if (status == ADVERTISE_SUCCESS) {
                this@GattServerService.advertisingSet = advertisingSet
                Log.i(TAG, "Advertising started, txPower=$txPower")
            } else {
                Log.e(TAG, "Advertising failed: $status")
                scheduleAdvRestart()
            }
        }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            if (this@GattServerService.advertisingSet == advertisingSet) {
                this@GattServerService.advertisingSet = null
            }
            Log.i(TAG, "Advertising stopped")
        }
    }

    private var gattServer: BluetoothGattServer? = null

    @SuppressLint("MissingPermission")
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        private val writeRequests =
            ConcurrentHashMap<BluetoothDevice, Pair<ByteArray, Int>>()

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != BleUtils.CHAR_STATUS_UUID) {
                gattServer?.sendResponse(device, requestId, 257, 0, null)
                return
            }

            refreshWifiFrequency()
            val data = synchronized(localDeviceInfoLock) {
                if (offset < localDeviceStatusBytes.size) {
                    localDeviceStatusBytes.copyOfRange(offset, localDeviceStatusBytes.size)
                } else {
                    null
                }
            }
            Log.i(TAG, "Responding device status offset=$offset data=${data?.decodeToString()}")

            gattServer?.sendResponse(device, requestId, 0, 0, data)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid != BleUtils.CHAR_P2P_UUID) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, 257, 0, null)
                }
                return
            }

            val key = device

            val writeReq = writeRequests.getOrPut(key) {
                Pair(ByteArray(1024), 0)
            }

            val writeBuffer = if (offset + value.size > writeReq.first.size) {
                writeReq.first.copyOf(offset + value.size)
            } else {
                writeReq.first
            }
            System.arraycopy(value, 0, writeBuffer, offset, value.size)
            val newLength = max(writeReq.second, offset + value.size)

            val data = if (preparedWrite) {
                writeRequests[key] = Pair(writeBuffer, newLength)
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, 0, 0, null)
                }
                return
            } else {
                writeRequests.remove(key)
                writeBuffer.copyOfRange(0, newLength)
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, 0, 0, null)
            }

            processP2pInfo(data)
        }

        override fun onExecuteWrite(
            device: BluetoothDevice,
            requestId: Int,
            execute: Boolean
        ) {
            val writeReq = writeRequests.remove(device)
            if (execute && writeReq != null) {
                processP2pInfo(writeReq.first.copyOfRange(0, writeReq.second))
            }
            gattServer?.sendResponse(device, requestId, 0, 0, null)
        }

        private fun processP2pInfo(data: ByteArray) {
            if (data.isEmpty()) {
                Log.e(TAG, "Received empty P2P info")
                return
            }
            var p2pInfo: P2pInfo = JsonWithUnknownKeys.decodeFromString(data.decodeToString())
            val ecKey = p2pInfo.key
            if (ecKey != null) {
                val cipher = BleSecurity.deriveSessionKey(ecKey)
                p2pInfo = P2pInfo(
                    id = BleUtils.getSenderId(),
                    ssid = cipher.decrypt(p2pInfo.ssid),
                    psk = cipher.decrypt(p2pInfo.psk),
                    mac = cipher.decrypt(p2pInfo.mac),
                    port = p2pInfo.port,
                    key = null,
                    catShare = BuildConfig.VERSION_CODE,
                )
            }
            startService(P2pReceiverService.getIntent(this@GattServerService, p2pInfo))
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (!checkBluetoothPermissions()) {
            stopSelf()
            return
        }

        try {
            btManager = getSystemService(BluetoothManager::class.java)!!
            wifiManager = applicationContext.getSystemService(WifiManager::class.java)
            val btAdapter = btManager.adapter
            if (btAdapter == null || !btAdapter.isEnabled) {
                throw IllegalStateException("Bluetooth not enabled")
            }
            btAdvertiser = btAdapter.bluetoothLeAdvertiser
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize BT", e)
            NotificationUtils.showBluetoothToast(this)
            stopSelf()
            return
        }

        try {
            startForeground(
                NotificationUtils.GATT_SERVER_FG_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "Service startup not allowed", e)
            } else {
                Log.e(TAG, "Service startup failed", e)
            }
            stopSelf()
            return
        }

        serviceScope.launch {
            val mac = SystemMacAddressProvider.getMacAddress(this@GattServerService, "p2p0")
            if (mac != null) {
                updateMacAddress(mac)
            }
        }

        if (!startGattServer()) {
            return
        }
        startAdv()

        registerInternalBroadcastReceiver(internalReceiver, IntentFilter().apply {
            addAction(ServiceState.ACTION_QUERY_RECEIVER_STATE)
            addAction(ServiceState.ACTION_STOP_SERVICE)
        })
        internalReceiverRegistered = true
        sendBroadcast(ServiceState.getUpdateIntent(true), INTERNAL_BROADCAST_PERMISSION)
    }

    private fun createNotification(): Notification {
        val pi = PendingIntent.getBroadcast(
            this,
            0,
            ServiceState.getStopIntent(),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationUtils.RECEIVER_FG_CHAN_ID)
            .setSmallIcon(R.drawable.ic_bluetooth_searching)
            .setContentTitle(getString(R.string.noti_receiver_title))
            .setContentText(getString(R.string.discoverable_desc))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_close, getString(R.string.stop), pi)
            .build()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer(): Boolean {
        if (gattServer != null) {
            return true
        }

        try {
            gattServer = btManager.openGattServer(this, gattServerCallback).apply {
                addService(buildGattService())
            }
            Log.i(TAG, "GATT server started")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GATT server", e)
            stopSelf()
            return false
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdv() {
        if (advertisingSet != null) {
            return
        }

        val advertiser = btAdvertiser ?: run {
            Log.e(TAG, "Bluetooth LE advertiser is unavailable")
            stopSelf()
            return
        }

        val advData = AdvertiseData.Builder().apply {
            addServiceUuid(ParcelUuid(BleUtils.ADV_SERVICE_UUID))
            addServiceData(
                ParcelUuid(BleUtils.miShareServiceDataUuid(
                    BleUtils.MISHARE_MANUFACTURER_XIAOMI,
                    BleUtils.MISHARE_FLAG_SUPPORT_5GHZ,
                )),
                Arrays.copyOfRange(BleUtils.RANDOM_DATA, 0, 6)
            )
        }.build()
        val scanRespData = AdvertiseData.Builder().apply {
            val data = ByteArray(27)
            data[8] = BleUtils.RANDOM_DATA[8]
            data[9] = BleUtils.RANDOM_DATA[9]

            val name = AppSettings(this@GattServerService).deviceName
            var nameBytes = name.toByteArray(Charsets.UTF_8)
            if (nameBytes.size > 15) {
                var str = String(nameBytes.copyOf(15), Charsets.UTF_8)
                var length = str.length - 1

                // Scan backwards for char boundary
                while (length >= 0 && !name.startsWith(str)) {
                    str = str.substring(0, length)
                    length -= 1
                }

                nameBytes = (str + "\t").toByteArray(Charsets.UTF_8)
            }
            System.arraycopy(nameBytes, 0, data, 10, min(nameBytes.size, 16))

            data[26] = 1

            addServiceData(
                ParcelUuid(BleUtils.miShareDeviceCodeUuid(BleUtils.MISHARE_GENERIC_XIAOMI_PHONE)),
                data
            )
        }.build()

        val params = AdvertisingSetParameters.Builder().apply {
            setLegacyMode(true)
            setConnectable(true)
            setScannable(true)
            setInterval(160)
            setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
        }.build()

        try {
            advertiser.startAdvertisingSet(
                params, advData, scanRespData, null, null, 0, 0, advSetCallback
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Got SecurityException when trying to advertise", e)
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start advertising", e)
            scheduleAdvRestart()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdv() {
        restartAdvRunnable?.let(mainHandler::removeCallbacks)
        restartAdvRunnable = null

        try {
            advertisingSet?.run {
                btAdvertiser?.stopAdvertisingSet(advSetCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop advertising", e)
        }
        advertisingSet = null
    }

    private fun scheduleAdvRestart() {
        restartAdvRunnable?.let(mainHandler::removeCallbacks)
        val runnable = Runnable {
            restartAdvRunnable = null
            if (gattServer == null) {
                if (!startGattServer()) {
                    return@Runnable
                }
            }
            startAdv()
        }
        restartAdvRunnable = runnable
        mainHandler.postDelayed(runnable, ADV_RESTART_DELAY_MS)
    }

    private fun buildGattService(): BluetoothGattService {
        val svc = BluetoothGattService(
            BleUtils.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        svc.addCharacteristic(
            BluetoothGattCharacteristic(BleUtils.CHAR_STATUS_UUID, 10, 17)
        )
        svc.addCharacteristic(
            BluetoothGattCharacteristic(BleUtils.CHAR_P2P_UUID, 10, 17)
        )
        return svc
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (internalReceiverRegistered) {
            unregisterReceiver(internalReceiver)
        }
        sendBroadcast(ServiceState.getUpdateIntent(false), INTERNAL_BROADCAST_PERMISSION)

        stopAdv()


        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop GATT server", e)
        }
        gattServer = null
    }

    private fun updateMacAddress(mac: String) {
        Log.i(TAG, "Updating local MAC address to $mac")
        synchronized(localDeviceInfoLock) {
            localDeviceInfo = DeviceInfo(
                state = localDeviceInfo.state,
                mac = mac,
                key = localDeviceInfo.key,
                reason = localDeviceInfo.reason,
                frequency = localDeviceInfo.frequency,
                catShare = BuildConfig.VERSION_CODE,
            )
            localDeviceStatusBytes = Json.encodeToString(localDeviceInfo).toByteArray()
        }
    }

    private fun refreshWifiFrequency() {
        val frequency = try {
            wifiManager?.connectionInfo?.frequency ?: 0
        } catch (e: Exception) {
            Log.d(TAG, "Failed to read Wi-Fi frequency", e)
            0
        }

        synchronized(localDeviceInfoLock) {
            if (localDeviceInfo.frequency == frequency) {
                return
            }
            localDeviceInfo = DeviceInfo(
                state = localDeviceInfo.state,
                mac = localDeviceInfo.mac,
                key = localDeviceInfo.key,
                reason = localDeviceInfo.reason,
                frequency = frequency,
                catShare = BuildConfig.VERSION_CODE,
            )
            localDeviceStatusBytes = Json.encodeToString(localDeviceInfo).toByteArray()
        }
    }

    companion object {
        private const val ADV_RESTART_DELAY_MS = 2_000L

        fun getIntent(context: Context): Intent {
            return Intent(context, GattServerService::class.java)
        }

        fun start(context: Context) {
            context.startService(getIntent(context))
        }

        fun stop(context: Context) {
            context.stopService(getIntent(context))
        }
    }
}
