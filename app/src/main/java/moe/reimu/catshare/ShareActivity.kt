package moe.reimu.catshare

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.core.view.WindowCompat
import moe.reimu.catshare.models.DiscoveredDevice
import moe.reimu.catshare.models.FileInfo
import moe.reimu.catshare.models.TaskInfo
import moe.reimu.catshare.services.P2pSenderService
import moe.reimu.catshare.ui.theme.CatShareTheme
import moe.reimu.catshare.utils.BleUtils
import moe.reimu.catshare.utils.DeviceUtils
import moe.reimu.catshare.utils.NotificationUtils
import moe.reimu.catshare.utils.TAG
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.random.Random

class ShareActivity : ComponentActivity() {
    private lateinit var bluetoothManager: BluetoothManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bluetoothManager = getSystemService(BluetoothManager::class.java)!!
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            NotificationUtils.showBluetoothToast(this)
            finish()
            return
        }

        val wifiManager = getSystemService(WifiManager::class.java)!!
        if (!wifiManager.isWifiEnabled) {
            NotificationUtils.showWifiToast(this)
            finish()
            return
        }

        val fileInfos = try {
            if (intent.action == Intent.ACTION_SEND) {
                @Suppress("DEPRECATION") val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    listOf(uri).mapNotNull { extractFileInfo(it) }
                } else {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    listOf(
                        FileInfo(
                            Uri.EMPTY, "", "", 0, text
                        )
                    )
                }
            } else {
                @Suppress("DEPRECATION") val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                uris?.mapNotNull { extractFileInfo(it) } ?: emptyList()
            }
        } catch (e: Throwable) {
            Log.e("ShareActivity", "Failed to extract file info", e)
            Toast.makeText(this, R.string.no_file_shared, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (fileInfos.isEmpty()) {
            Toast.makeText(this, R.string.no_file_shared, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Log.i(TAG, "Shared ${fileInfos.size} files")
        setFinishOnTouchOutside(true)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        disableSystemBarContrast()

        enableEdgeToEdge()
        setContent {
            CatShareTheme {
                ShareActivityContent(fileInfos) {
                    finish()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun disableSystemBarContrast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun extractFileInfo(uri: Uri): FileInfo? {
        val cr = contentResolver
        val proj = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE
        )
        return cr.query(uri, proj, null, null)?.use {
            if (it.moveToFirst()) {
                val mimeIndex = it.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                FileInfo(
                    uri,
                    it.getString(it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)),
                    if (mimeIndex < 0) {
                        "application/octet-stream"
                    } else {
                        it.getString(mimeIndex)
                    },
                    it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)),
                    null
                )
            } else {
                null
            }
        }
    }
}

@Composable
fun ShareActivityContent(files: List<FileInfo>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val discoveredDevices = deviceScanner()

    val listState = rememberLazyListState()
    val consumeClicks = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.22f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.48f)
                .clickable(
                    interactionSource = consumeClicks,
                    indication = null
                ) {}
                .navigationBarsPadding(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp),
                        shape = RoundedCornerShape(100),
                        color = MaterialTheme.colorScheme.outlineVariant,
                        content = {}
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.choose_recipient),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = stringResource(R.string.scanning_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(android.R.string.cancel)
                        )
                    }
                }

                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    if (discoveredDevices.isEmpty()) {
                        item {
                            ScanningRecipientCard()
                        }
                    } else {
                        items(discoveredDevices, key = { it.id }) {
                            RecipientDeviceCard(
                                device = it,
                                onClick = {
                                    val task = TaskInfo(
                                        id = Random.nextInt(),
                                        device = it,
                                        files = files
                                    )
                                    P2pSenderService.startTaskChecked(context, task)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipientDeviceCard(device: DiscoveredDevice, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(148.dp)
            .height(176.dp),
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Text(
                text = device.name,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            )
            Text(
                text = device.brand ?: stringResource(R.string.unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ScanningRecipientCard() {
    Surface(
        modifier = Modifier
            .width(240.dp)
            .height(128.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Text(
                stringResource(R.string.scanning_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun deviceScanner(): List<DiscoveredDevice> {
    val context = LocalContext.current
    var discoveredDevices by remember { mutableStateOf(emptyList<DiscoveredDevice>()) }

    LifecycleResumeEffect(context) {
        val manager = context.getSystemService(BluetoothManager::class.java)!!
        val adapter = manager.adapter
        val devicesLock = Object()

        val callback = object : ScanCallback() {
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed: $errorCode")
            }

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val newDevice = parseMiShareDevice(result) ?: return
                var replaced = false
                synchronized(devicesLock) {
                    val newList = discoveredDevices.map {
                        if (it.id == newDevice.id) {
                            replaced = true
                            newDevice
                        } else {
                            it
                        }
                    }.toMutableList()
                    if (!replaced) {
                        newList.add(newDevice)
                    }
                    discoveredDevices = newList
                }
            }
        }

        var startedScanner: BluetoothLeScanner? = null

        if (adapter != null) {
            val scanner = adapter.bluetoothLeScanner
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            try {
                scanner.startScan(null, settings, callback)
                startedScanner = scanner
                Log.d(TAG, "Started scanning")
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to start scan", e)
            }
        }

        onPauseOrDispose {
            try {
                startedScanner?.stopScan(callback)
                Log.d(TAG, "Stopped scanning")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop scan", e)
            }
        }
    }

    return discoveredDevices
}

private fun parseMiShareDevice(result: ScanResult): DiscoveredDevice? {
    val record = result.scanRecord ?: return null
    if (record.serviceUuids?.any { it.uuid == BleUtils.ADV_SERVICE_UUID } != true) {
        return null
    }

    val fromServiceData = parseMiShareServiceData(result, record.serviceData)
    if (fromServiceData != null) {
        return fromServiceData
    }

    val bytes = record.bytes
    if (bytes.size <= 61) {
        return null
    }

    return try {
        val manufacturerId = bytes[23].toInt() and 0xff
        val flags = bytes[24].toInt() and 0xff
        val senderIdRaw = (bytes[59].toInt() and 0xff).shl(8)
            .or(bytes[60].toInt() and 0xff)
        val name = parseMiShareName(bytes.copyOfRange(45, 61))
        if (name.isEmpty()) {
            return null
        }

        DiscoveredDevice(
            device = result.device,
            id = String.format("%04x", senderIdRaw),
            name = name,
            brand = DeviceUtils.deviceNameById(manufacturerId.toByte()),
            supports5Ghz = (flags and BleUtils.MISHARE_FLAG_SUPPORT_5GHZ) != 0,
        )
    } catch (e: Exception) {
        Log.d("ShareActivity", "Failed to parse MiShare raw scan record", e)
        null
    }
}

private fun parseMiShareServiceData(
    result: ScanResult,
    serviceData: Map<ParcelUuid, ByteArray>
): DiscoveredDevice? {
    var supports5Ghz = false
    var deviceName: String? = null
    var brandId: Byte? = null
    var senderId: String? = null

    for ((uuid, data) in serviceData.entries) {
        when (data.size) {
            6 -> {
                val uuidBytes = ByteBuffer.allocate(16)
                    .putLong(uuid.uuid.mostSignificantBits)
                    .putLong(uuid.uuid.leastSignificantBits)
                    .array()
                supports5Ghz = (uuidBytes[2].toInt() and BleUtils.MISHARE_FLAG_SUPPORT_5GHZ) != 0
                brandId = uuidBytes[3]
            }

            27 -> {
                val senderIdRaw = (data[8].toInt() and 0xff).shl(8)
                    .or(data[9].toInt() and 0xff)
                senderId = String.format("%04x", senderIdRaw)
                deviceName = parseMiShareName(data.copyOfRange(10, 26))
            }
        }
    }

    val name = deviceName
    val id = senderId
    if (name.isNullOrEmpty() || id == null) {
        return null
    }

    return DiscoveredDevice(
        device = result.device,
        id = id,
        name = name,
        brand = brandId?.let(DeviceUtils::deviceNameById),
        supports5Ghz = supports5Ghz,
    )
}

private fun parseMiShareName(bytes: ByteArray): String {
    var length = bytes.size
    var hasMore = false
    while (length > 0) {
        val value = bytes[length - 1].toInt()
        if (value == 0) {
            length -= 1
            continue
        }
        if (value == '\t'.code) {
            hasMore = true
            length -= 1
        }
        break
    }

    val name = if (length > 0) {
        String(bytes, 0, length, StandardCharsets.UTF_8)
    } else {
        ""
    }
    return if (hasMore) "$name..." else name
}
