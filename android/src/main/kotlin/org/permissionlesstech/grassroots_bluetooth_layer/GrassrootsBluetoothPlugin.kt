package org.permissionlesstech.grassroots_bluetooth_layer

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "GrassrootsBluetoothPlugin"
private const val DEFAULT_ATT_MTU = 23
private const val DEFAULT_RSSI = -100
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

@SuppressLint("MissingPermission")
class GrassrootsBluetoothPlugin : FlutterPlugin, GrassrootsBluetoothLayerHostApi {
    private var applicationContext: Context? = null
    private var mainHandler = Handler(Looper.getMainLooper())
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var flutterApi: GrassrootsBluetoothLayerFlutterApi? = null
    private var verboseLogging = false
    private var receiverRegistered = false

    private var scanCallback: ScanCallback? = null
    private var scanTimeoutRunnable: Runnable? = null
    private var currentScanRequest: BleScanRequest? = null
    private var isScanning = false
    private val seenAdvertisements: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var pendingAdvertiseSettings: AdvertiseSettings? = null
    private var pendingAdvertiseData: AdvertiseData? = null
    private var pendingScanResponseData: AdvertiseData? = null
    private var gattServer: BluetoothGattServer? = null
    private var serverCharacteristic: BluetoothGattCharacteristic? = null
    private var advertisedServiceUuid: UUID? = null
    private var advertisedCharacteristicUuid: UUID? = null

    private val centralPaths = ConcurrentHashMap<String, CentralPath>()
    private val peripheralPaths = ConcurrentHashMap<String, PeripheralPath>()

    private data class CentralPath(
        val pathId: String,
        val address: String,
        var device: BluetoothDevice? = null,
        var gatt: BluetoothGatt? = null,
        var characteristic: BluetoothGattCharacteristic? = null,
        var serviceUuid: UUID? = null,
        var characteristicUuid: UUID? = null,
        var state: BlePathState = BlePathState.DISCOVERED,
        var rssi: Int = DEFAULT_RSSI,
        var mtu: Int = DEFAULT_ATT_MTU,
        var canSend: Boolean = false,
        var subscribeRequested: Boolean = true,
        var subscriptionReady: Boolean = false,
        var connectTimeoutRunnable: Runnable? = null,
        var forgetOnDisconnect: Boolean = false,
        var error: String? = null,
        // Android only allows ONE outstanding GATT operation (write,
        // read, descriptor write, MTU request, service discovery) per
        // BluetoothGatt instance at a time. Issuing a second op before
        // the first completes silently fails (`gatt.writeCharacteristic`
        // returns false, sometimes status=133 storms). Queue ops here
        // and only call into BluetoothGatt when the previous op's
        // callback has fired. The queue lives per-path because each
        // gatt instance has its own outstanding-op slot.
        val pendingOps: ArrayDeque<GattOp> = ArrayDeque(),
        var inFlightOp: GattOp? = null,
    )

    /// One queued GATT operation against a central path. The plugin
    /// executes ops in submission order and only marks the next one
    /// in-flight when the previous one's BluetoothGattCallback fires.
    private sealed class GattOp {
        data class WriteCharacteristic(
            val characteristic: BluetoothGattCharacteristic,
            val value: ByteArray,
            val writeType: Int,
        ) : GattOp()

        data class WriteDescriptor(
            val descriptor: BluetoothGattDescriptor,
            val value: ByteArray,
        ) : GattOp()

        object DiscoverServices : GattOp()
        data class RequestMtu(val mtu: Int) : GattOp()
    }

    private data class PeripheralPath(
        val pathId: String,
        val address: String,
        val device: BluetoothDevice,
        var serviceUuid: UUID? = null,
        var characteristicUuid: UUID? = null,
        var state: BlePathState = BlePathState.CONNECTED,
        var rssi: Int = DEFAULT_RSSI,
        var mtu: Int = DEFAULT_ATT_MTU,
        var subscribed: Boolean = false,
        var canSend: Boolean = false,
        var forgetOnDisconnect: Boolean = false,
        var error: String? = null,
    )

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = binding.applicationContext
        mainHandler = Handler(Looper.getMainLooper())
        bluetoothManager =
            binding.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        flutterApi = GrassrootsBluetoothLayerFlutterApi(binding.binaryMessenger)
        GrassrootsBluetoothLayerHostApi.setUp(binding.binaryMessenger, this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        GrassrootsBluetoothLayerHostApi.setUp(binding.binaryMessenger, null)
        cleanup(emitEvents = false)
        flutterApi = null
        bluetoothAdapter = null
        bluetoothManager = null
        applicationContext = null
    }

    override fun initialize(options: BleInitializeOptions) {
        verboseLogging = options.verboseLogging
        ensureBluetoothReferences()
        registerAdapterReceiver()
        emitAdapterState(adapterState())
        logToFlutter("Initialized Android BLE transport")
    }

    override fun isSupported(): Boolean {
        val context = applicationContext ?: return false
        val adapter = bluetoothAdapter ?: bluetoothManager?.adapter
        return adapter != null &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }

    override fun adapterState(): BleAdapterState {
        val adapter = bluetoothAdapter ?: bluetoothManager?.adapter ?: return BleAdapterState.UNSUPPORTED
        if (!isSupported()) return BleAdapterState.UNSUPPORTED
        if (!hasConnectPermission()) return BleAdapterState.UNAUTHORIZED
        return try {
            if (adapter.isEnabled) BleAdapterState.POWERED_ON else BleAdapterState.POWERED_OFF
        } catch (securityException: SecurityException) {
            BleAdapterState.UNAUTHORIZED
        }
    }

    override fun startAdvertising(request: BleAdvertiseRequest) {
        ensurePoweredOn()
        requireAdvertisePermission()
        requireConnectPermission()

        stopAdvertisingInternal(emitEvents = true)

        val context = requireContext()
        val manager = bluetoothManager ?: throw unavailable("Bluetooth manager is unavailable")
        val adapter = bluetoothAdapter ?: throw unsupported("Bluetooth adapter is unavailable")
        val serviceUuid = parseUuid(request.serviceUuid)
        val characteristicUuid = parseUuid(request.characteristicUuid)

        if (!adapter.isMultipleAdvertisementSupported) {
            throw unsupported("Bluetooth LE advertising is not supported on this device")
        }

        advertiser = adapter.bluetoothLeAdvertiser
            ?: throw unsupported("Bluetooth LE advertiser is unavailable")

        // We deliberately do NOT mutate the global Bluetooth adapter name —
        // it's a system-wide setting unrelated to Grassroots identity, and
        // including a long device name in the advertisement risks exceeding
        // the 31-byte legacy advertise budget (which silently fails with
        // ADVERTISE_FAILED_DATA_TOO_LARGE). The peer learns our identity
        // through the post-connection ANNOUNCE handshake.
        if (!request.localName.isNullOrEmpty()) {
            logToFlutter(
                "Ignoring localName='${request.localName}' on Android — adapter " +
                    "name is system-wide and not used to identify Grassroots peers.",
            )
        }

        val permissions = if (request.bondless) {
            BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE
        } else {
            logToFlutter(
                "Warning: bondless=false will request encrypted attributes; Grassroots does not expect bonding"
            )
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED
        }

        val characteristic = BluetoothGattCharacteristic(
            characteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            permissions,
        )
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        characteristic.addDescriptor(cccd)

        val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // The advertise packet carries only the 128-bit service UUID + flags
        // (~22 bytes) — well within the 31-byte legacy AD budget. Anything
        // larger (device name, manufacturer data) goes in the scan response.
        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(serviceUuid))
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()

        val scanResponseBuilder = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(request.includeDeviceName)

        val manufacturerId = request.manufacturerId
        val manufacturerData = request.manufacturerData
        if (manufacturerId != null && manufacturerData != null) {
            scanResponseBuilder.addManufacturerData(manufacturerId.toInt(), manufacturerData)
        }
        val scanResponseData = try {
            scanResponseBuilder.build()
        } catch (illegalArgumentException: IllegalArgumentException) {
            throw FlutterError("advertise-failed", illegalArgumentException.message)
        }

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                logToFlutter("Advertising started for $serviceUuid")
            }

            override fun onStartFailure(errorCode: Int) {
                val self = this
                mainHandler.post {
                    if (advertiseCallback !== self) return@post
                    logToFlutter("Advertising failed: ${advertiseFailureMessage(errorCode)}")
                    stopAdvertisingInternal(emitEvents = false)
                }
            }
        }

        gattServer = manager.openGattServer(context, gattServerCallback)
            ?: throw unavailable("Unable to open Bluetooth GATT server")
        serverCharacteristic = characteristic
        advertisedServiceUuid = serviceUuid
        advertisedCharacteristicUuid = characteristicUuid
        advertiseCallback = callback
        pendingAdvertiseSettings = settings
        pendingAdvertiseData = advertiseData
        pendingScanResponseData = scanResponseData

        val added = gattServer?.addService(service) ?: false
        if (!added) {
            stopAdvertisingInternal(emitEvents = false)
            throw unavailable("Unable to add GATT service")
        }
        logToFlutter("GATT service add requested for $serviceUuid")
    }

    override fun stopAdvertising() {
        stopAdvertisingInternal(emitEvents = true)
    }

    override fun startScan(request: BleScanRequest) {
        ensurePoweredOn()
        requireScanPermission()

        stopScanInternal()
        seenAdvertisements.clear()
        currentScanRequest = request

        val scanner = bluetoothAdapter?.bluetoothLeScanner
            ?: throw unavailable("Bluetooth LE scanner is unavailable")

        // Only apply OS-level filters when full service UUIDs are given.
        // For prefix-only filtering we rely on user-space matching in
        // `scanResultMatchesRequest` because UUID-with-mask filters are
        // not reliably supported across all Android chipsets.
        val filterBuilders = mutableListOf<ScanFilter>()
        for (rawUuid in request.serviceUuids) {
            if (rawUuid == null) continue
            filterBuilders.add(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(parseUuid(rawUuid)))
                    .build(),
            )
        }
        // Pass null when no filter — `emptyList()` is undefined-behavior on
        // some Android builds (interpreted as "match nothing").
        val filters: List<ScanFilter>? =
            if (filterBuilders.isEmpty()) null else filterBuilders

        val settings = ScanSettings.Builder()
            .setScanMode(scanModeFromRequest(request.androidScanMode.toInt()))
            .setReportDelay(0L)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                mainHandler.post { handleScanResult(result) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                mainHandler.post { results.forEach { handleScanResult(it) } }
            }

            override fun onScanFailed(errorCode: Int) {
                val self = this
                mainHandler.post {
                    if (scanCallback !== self) return@post
                    logToFlutter("Scan failed: ${scanFailureMessage(errorCode)}")
                    stopScanInternal()
                }
            }
        }

        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (securityException: SecurityException) {
            stopScanInternal()
            throw unauthorized("Bluetooth scan permission is required")
        } catch (illegalArgumentException: IllegalArgumentException) {
            stopScanInternal()
            throw FlutterError("scan-failed", illegalArgumentException.message)
        }
        isScanning = true
        logToFlutter("Scan started")

        if (request.timeoutMs > 0) {
            scanTimeoutRunnable = Runnable {
                stopScanInternal()
                logToFlutter("Scan stopped after ${request.timeoutMs}ms timeout")
            }
            mainHandler.postDelayed(scanTimeoutRunnable!!, request.timeoutMs)
        }
    }

    override fun stopScan() {
        stopScanInternal()
    }

    override fun connect(request: BleConnectRequest): BlePath {
        ensurePoweredOn()
        requireConnectPermission()

        val adapter = bluetoothAdapter ?: throw unsupported("Bluetooth adapter is unavailable")
        val address = normalizeAddress(request.remoteId)
        val pathId = centralPathId(address)
        val serviceUuid = parseUuid(request.serviceUuid)
        val characteristicUuid = parseUuid(request.characteristicUuid)
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (illegalArgumentException: IllegalArgumentException) {
            throw FlutterError("invalid-argument", "remoteId is not a valid Android BLE address")
        }

        val existing = centralPaths[pathId]
        if (existing != null) {
            if (existing.gatt != null &&
                existing.state != BlePathState.DISCONNECTED &&
                existing.state != BlePathState.FAILED
            ) {
                return existing.toBlePath()
            }
            // Stale path; cancel any pending timeout and close the old GATT.
            existing.connectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            existing.connectTimeoutRunnable = null
            safeDisconnectAndClose(existing)
        }

        val path = CentralPath(
            pathId = pathId,
            address = address,
            device = device,
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
            state = BlePathState.CONNECTING,
            rssi = existing?.rssi ?: DEFAULT_RSSI,
            subscribeRequested = request.subscribeToNotifications,
        )
        centralPaths[pathId] = path
        emitPath(path.toBlePath())

        val callback = createCentralGattCallback(pathId)
        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(requireContext(), false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(requireContext(), false, callback)
        }

        if (gatt == null) {
            failCentralPath(pathId, "connectGatt returned null")
            throw unavailable("Unable to start GATT connection")
        }

        path.gatt = gatt

        if (request.timeoutMs > 0) {
            path.connectTimeoutRunnable = Runnable {
                val current = centralPaths[pathId] ?: return@Runnable
                if (current.state != BlePathState.READY) {
                    failCentralPath(pathId, "Connection timed out after ${request.timeoutMs}ms")
                    safeDisconnectAndClose(current)
                }
            }
            mainHandler.postDelayed(path.connectTimeoutRunnable!!, request.timeoutMs)
        }

        val requestedMtu = request.androidMtu?.toInt()
        if (requestedMtu != null && requestedMtu > DEFAULT_ATT_MTU) {
            path.mtu = requestedMtu.coerceIn(DEFAULT_ATT_MTU, 517)
        }

        return path.toBlePath()
    }

    override fun disconnect(request: BleDisconnectRequest) {
        when {
            request.pathId.startsWith("central:") -> disconnectCentral(request)
            request.pathId.startsWith("peripheral:") -> disconnectPeripheral(request)
            else -> throw FlutterError("not-found", "Unknown pathId ${request.pathId}")
        }
    }

    override fun send(request: BleSendRequest) {
        when {
            request.pathId.startsWith("central:") -> sendCentral(request)
            request.pathId.startsWith("peripheral:") -> sendPeripheral(request)
            else -> throw FlutterError("not-found", "Unknown pathId ${request.pathId}")
        }
    }

    override fun paths(): List<BlePath> {
        val central = centralPaths.toSortedMap().values.map { it.toBlePath() }
        val peripheral = peripheralPaths.toSortedMap().values.map { it.toBlePath() }
        return central + peripheral
    }

    override fun dispose() {
        cleanup(emitEvents = true)
    }

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            emitAdapterState(adapterState())
            if (adapterState() != BleAdapterState.POWERED_ON) {
                stopScanInternal()
                stopAdvertisingInternal(emitEvents = true)
                centralPaths.values.forEach { path ->
                    path.connectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                    path.connectTimeoutRunnable = null
                    safeDisconnectAndClose(path)
                    path.state = BlePathState.DISCONNECTED
                    path.canSend = false
                    path.subscriptionReady = false
                    path.error = "Bluetooth adapter powered off"
                    emitPath(path.toBlePath())
                }
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            mainHandler.post {
                val pathId = peripheralPathId(device.address)
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        val path = PeripheralPath(
                            pathId = pathId,
                            address = normalizeAddress(device.address),
                            device = device,
                            serviceUuid = advertisedServiceUuid,
                            characteristicUuid = advertisedCharacteristicUuid,
                            state = BlePathState.CONNECTED,
                            error = statusMessageOrNull(status),
                        )
                        peripheralPaths[pathId] = path
                        emitPath(path.toBlePath())
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val path = peripheralPaths[pathId]
                        if (path != null) {
                            path.state = BlePathState.DISCONNECTED
                            path.canSend = false
                            path.subscribed = false
                            path.error = statusMessageOrNull(status)
                            emitPath(path.toBlePath())
                            if (path.forgetOnDisconnect) {
                                peripheralPaths.remove(pathId)
                            }
                        }
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mainHandler.post {
                val path = peripheralPaths[peripheralPathId(device.address)] ?: return@post
                path.mtu = mtu
                emitPath(path.toBlePath())
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != advertisedCharacteristicUuid) {
                mainHandler.post {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        ByteArray(0),
                    )
                }
                return
            }
            val value = characteristic.value ?: ByteArray(0)
            mainHandler.post {
                if (offset > value.size) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        ByteArray(0),
                    )
                    return@post
                }
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value.drop(offset).toByteArray(),
                )
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val charUuid = characteristic.uuid
            mainHandler.post {
                if (charUuid != advertisedCharacteristicUuid) {
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            offset,
                            ByteArray(0),
                        )
                    }
                    return@post
                }

                if (preparedWrite || offset != 0) {
                    logToFlutter(
                        "Rejecting unsupported peripheral write from ${device.address}: " +
                            "preparedWrite=$preparedWrite offset=$offset",
                    )
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            offset,
                            ByteArray(0),
                        )
                    }
                    return@post
                }

                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        ByteArray(0),
                    )
                }

                val pathId = peripheralPathId(device.address)
                ensurePeripheralPath(device)
                emitPayload(
                    BlePayload(
                        pathId = pathId,
                        role = BleRole.PERIPHERAL,
                        value = value,
                        rssi = peripheralPaths[pathId]?.rssi?.toLong() ?: DEFAULT_RSSI.toLong(),
                    ),
                )
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val descriptorUuid = descriptor.uuid
            mainHandler.post {
                if (descriptorUuid != CCCD_UUID) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        ByteArray(0),
                    )
                    return@post
                }
                val subscribed = peripheralPaths[peripheralPathId(device.address)]?.subscribed == true
                val value = if (subscribed) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                if (offset > value.size) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        ByteArray(0),
                    )
                    return@post
                }

                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value.drop(offset).toByteArray(),
                )
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val descriptorUuid = descriptor.uuid
            mainHandler.post {
                if (descriptorUuid != CCCD_UUID || preparedWrite || offset != 0) {
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            offset,
                            ByteArray(0),
                        )
                    }
                    return@post
                }

                val subscribed =
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.contentEquals(value) ||
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE.contentEquals(value)
                val path = ensurePeripheralPath(device)
                path.subscribed = subscribed
                path.canSend = subscribed
                path.state = if (subscribed) BlePathState.READY else BlePathState.CONNECTED
                path.error = null
                emitPath(path.toBlePath())

                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        value,
                    )
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logToFlutter("Notification failed for ${device.address}: status=$status")
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            mainHandler.post {
                if (service.uuid != advertisedServiceUuid) {
                    logToFlutter("Ignoring stale GATT service add callback for ${service.uuid}")
                    return@post
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    logToFlutter("GATT service add failed for ${service.uuid}: status=$status")
                    stopAdvertisingInternal(emitEvents = false)
                    return@post
                }

                val adv = advertiser
                val callback = advertiseCallback
                val settings = pendingAdvertiseSettings
                val data = pendingAdvertiseData
                val scanResponse = pendingScanResponseData
                if (adv == null || callback == null || settings == null || data == null || scanResponse == null) {
                    logToFlutter("Advertising start skipped for ${service.uuid}: pending state is missing")
                    stopAdvertisingInternal(emitEvents = false)
                    return@post
                }

                try {
                    adv.startAdvertising(settings, data, scanResponse, callback)
                    pendingAdvertiseSettings = null
                    pendingAdvertiseData = null
                    pendingScanResponseData = null
                    logToFlutter("Starting advertising for ${service.uuid} after GATT service add")
                } catch (illegalArgumentException: IllegalArgumentException) {
                    logToFlutter("Advertising failed: ${illegalArgumentException.message}")
                    stopAdvertisingInternal(emitEvents = false)
                }
            }
        }
    }

    private fun createCentralGattCallback(pathId: String): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                mainHandler.post {
                    val path = centralPaths[pathId] ?: return@post
                    when {
                        newState == BluetoothProfile.STATE_CONNECTED &&
                            status == BluetoothGatt.GATT_SUCCESS -> {
                            path.gatt = gatt
                            path.device = gatt.device
                            path.state = BlePathState.CONNECTED
                            path.error = null
                            path.canSend = false
                            emitPath(path.toBlePath())

                            val requestedMtu = path.mtu
                            if (requestedMtu > DEFAULT_ATT_MTU) {
                                enqueueGattOp(pathId, GattOp.RequestMtu(requestedMtu))
                            } else {
                                enqueueGattOp(pathId, GattOp.DiscoverServices)
                            }
                        }
                        newState == BluetoothProfile.STATE_DISCONNECTED -> {
                            path.connectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                            path.connectTimeoutRunnable = null
                            path.gatt = null
                            path.characteristic = null
                            path.state = if (status == BluetoothGatt.GATT_SUCCESS) {
                                BlePathState.DISCONNECTED
                            } else {
                                BlePathState.FAILED
                            }
                            path.canSend = false
                            path.subscriptionReady = false
                            path.error = statusMessageOrNull(status)
                            // Drop any queued ops — the gatt is gone.
                            path.pendingOps.clear()
                            path.inFlightOp = null
                            emitPath(path.toBlePath())
                            try {
                                gatt.close()
                            } catch (_: Exception) {
                            }
                            if (path.forgetOnDisconnect) {
                                centralPaths.remove(pathId)
                            }
                        }
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                mainHandler.post {
                    val path = centralPaths[pathId] ?: return@post
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        path.mtu = mtu
                    }
                    finishGattOp(pathId)
                    enqueueGattOp(pathId, GattOp.DiscoverServices)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                mainHandler.post {
                    finishGattOp(pathId)
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failCentralPath(pathId, "Service discovery failed: status=$status")
                        gatt.disconnect()
                        return@post
                    }

                    val path = centralPaths[pathId] ?: return@post
                    val serviceUuid = path.serviceUuid
                    val characteristicUuid = path.characteristicUuid
                    val service = serviceUuid?.let { gatt.getService(it) }
                    val characteristic = service?.let { svc ->
                        characteristicUuid?.let { svc.getCharacteristic(it) }
                    }

                    if (service == null || characteristic == null) {
                        failCentralPath(pathId, "Required GATT service or characteristic not found")
                        gatt.disconnect()
                        return@post
                    }

                    path.characteristic = characteristic
                    if (!characteristic.canWrite()) {
                        failCentralPath(pathId, "Characteristic does not support writes")
                        gatt.disconnect()
                        return@post
                    }

                    if (path.subscribeRequested) {
                        subscribeCentralToNotifications(pathId, gatt, characteristic)
                    } else {
                        markCentralReady(pathId)
                    }
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                mainHandler.post {
                    finishGattOp(pathId)
                    if (descriptor.uuid != CCCD_UUID) return@post
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val path = centralPaths[pathId] ?: return@post
                        path.subscriptionReady = true
                        path.state = BlePathState.SUBSCRIBED
                        path.canSend = path.characteristic?.canWrite() == true
                        path.error = null
                        emitPath(path.toBlePath())
                        markCentralReady(pathId)
                    } else {
                        failCentralPath(pathId, "Descriptor subscription failed: status=$status")
                        gatt.disconnect()
                    }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                @Suppress("DEPRECATION")
                handleCentralCharacteristicChanged(gatt, characteristic, characteristic.value)
            }

            @TargetApi(Build.VERSION_CODES.TIRAMISU)
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                handleCentralCharacteristicChanged(gatt, characteristic, value)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                mainHandler.post {
                    finishGattOp(pathId)
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        logToFlutter("Central write failed for ${gatt.device.address}: status=$status")
                    }
                }
            }

            override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
                mainHandler.post {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val path = centralPaths[pathId] ?: return@post
                        path.rssi = rssi
                        emitPath(path.toBlePath())
                    }
                }
            }
        }
    }

    /// Append a GATT op to the per-path queue and try to start it.
    /// All callers must run on the main handler.
    private fun enqueueGattOp(pathId: String, op: GattOp) {
        val path = centralPaths[pathId] ?: return
        path.pendingOps.addLast(op)
        startNextGattOp(pathId)
    }

    /// Mark the current in-flight op done and dispatch the next one.
    private fun finishGattOp(pathId: String) {
        val path = centralPaths[pathId] ?: return
        path.inFlightOp = null
        startNextGattOp(pathId)
    }

    /// If no op is in flight and the queue has work, run the next one.
    private fun startNextGattOp(pathId: String) {
        val path = centralPaths[pathId] ?: return
        if (path.inFlightOp != null) return
        val gatt = path.gatt ?: return
        val op = path.pendingOps.removeFirstOrNull() ?: return
        path.inFlightOp = op
        val started = when (op) {
            is GattOp.RequestMtu -> {
                gatt.requestMtu(op.mtu)
            }
            GattOp.DiscoverServices -> {
                gatt.discoverServices()
            }
            is GattOp.WriteDescriptor -> {
                writeDescriptorCompat(gatt, op.descriptor, op.value)
            }
            is GattOp.WriteCharacteristic -> {
                writeCharacteristicCompat(gatt, op.characteristic, op.value, op.writeType)
            }
        }
        if (!started) {
            // Couldn't start (busy, missing perms, link gone). Treat as
            // immediate completion so we can drain the queue rather than
            // wedge — and surface an error log for the failed op.
            logToFlutter("GATT op kicked off failed for $pathId: ${op::class.java.simpleName}")
            path.inFlightOp = null
            // Schedule rather than recurse, in case kicking off the next op
            // synchronously fails too — bounded by queue size.
            mainHandler.post { startNextGattOp(pathId) }
        }
    }

    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, writeType) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = writeType
                characteristic.value = value
                gatt.writeCharacteristic(characteristic)
            }
        }
    }

    private fun handleCentralCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        mainHandler.post {
            val pathId = centralPathId(gatt.device.address)
            val path = centralPaths[pathId] ?: return@post
            if (path.characteristicUuid != null && characteristic.uuid != path.characteristicUuid) {
                return@post
            }
            emitPayload(
                BlePayload(
                    pathId = pathId,
                    role = BleRole.CENTRAL,
                    value = value,
                    rssi = path.rssi.toLong(),
                ),
            )
        }
    }

    private fun subscribeCentralToNotifications(
        pathId: String,
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        val notifyValue = when {
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> {
                failCentralPath(pathId, "Characteristic does not support notifications")
                gatt.disconnect()
                return
            }
        }

        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            failCentralPath(pathId, "Unable to enable local characteristic notification")
            gatt.disconnect()
            return
        }

        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            failCentralPath(pathId, "CCCD descriptor not found")
            gatt.disconnect()
            return
        }

        // Queue the CCCD write — it must serialize against any other
        // pending GATT op (e.g. a write-characteristic from the consumer
        // that arrived before subscription completed).
        enqueueGattOp(pathId, GattOp.WriteDescriptor(descriptor, notifyValue))
    }

    private fun markCentralReady(pathId: String) {
        val path = centralPaths[pathId] ?: return
        path.connectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        path.connectTimeoutRunnable = null
        path.state = BlePathState.READY
        path.canSend = path.characteristic?.canWrite() == true
        path.error = null
        emitPath(path.toBlePath())
    }

    private fun failCentralPath(pathId: String, message: String) {
        val path = centralPaths[pathId] ?: return
        path.connectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        path.connectTimeoutRunnable = null
        path.state = BlePathState.FAILED
        path.canSend = false
        path.subscriptionReady = false
        path.error = message
        emitPath(path.toBlePath())
        logToFlutter("Path $pathId failed: $message")
    }

    private fun disconnectCentral(request: BleDisconnectRequest) {
        requireConnectPermission()
        val path = centralPaths[request.pathId] ?: throw FlutterError(
            "not-found",
            "Unknown central pathId ${request.pathId}",
        )
        path.connectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        path.connectTimeoutRunnable = null
        path.forgetOnDisconnect = request.forget
        path.canSend = false
        path.subscriptionReady = false

        // Initiate the disconnect at the GATT layer, then wait for the
        // STATE_DISCONNECTED callback to actually close() the gatt and
        // emit the disconnected path event. Eager close() races with the
        // pending callback and produces sporadic status=133 on Android.
        val gatt = path.gatt
        if (gatt != null) {
            path.state = BlePathState.STALE
            path.error = "Disconnect requested"
            emitPath(path.toBlePath())
            try {
                gatt.disconnect()
            } catch (_: Exception) {
            }
            // The onConnectionStateChange callback will fire with
            // STATE_DISCONNECTED, where we close() and emit the path event.
        } else {
            // No gatt to disconnect — emit + drop synchronously.
            path.state = BlePathState.DISCONNECTED
            emitPath(path.toBlePath())
            if (request.forget) {
                centralPaths.remove(request.pathId)
            }
        }
    }

    private fun disconnectPeripheral(request: BleDisconnectRequest) {
        requireConnectPermission()
        val path = peripheralPaths[request.pathId] ?: throw FlutterError(
            "not-found",
            "Unknown peripheral pathId ${request.pathId}",
        )
        path.forgetOnDisconnect = request.forget
        try {
            gattServer?.cancelConnection(path.device)
        } catch (_: Exception) {
        }
        path.canSend = false
        path.subscribed = false
        path.state = BlePathState.DISCONNECTED
        emitPath(path.toBlePath())
        if (request.forget) {
            peripheralPaths.remove(request.pathId)
        }
    }

    private fun sendCentral(request: BleSendRequest) {
        requireConnectPermission()
        val path = centralPaths[request.pathId] ?: throw FlutterError(
            "not-found",
            "Unknown central pathId ${request.pathId}",
        )
        path.gatt ?: throw unavailable("Central path is not connected")
        val characteristic = path.characteristic
            ?: throw unavailable("Central path characteristic is not ready")
        if (!path.canSend) {
            throw unavailable("Central path is not ready to send")
        }

        val writeType = when (request.writeMode) {
            BleWriteMode.WITHOUT_RESPONSE -> {
                if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0) {
                    throw unavailable("Characteristic does not support write without response")
                }
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            BleWriteMode.WITH_RESPONSE -> {
                if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
                    throw unavailable("Characteristic does not support write with response")
                }
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
        }

        // Don't issue the write directly — it'll silently fail if any other
        // GATT op (subscribe descriptor, MTU, service discovery, prior
        // write) is in flight. Queue it; the queue runner serializes all
        // ops per path.
        mainHandler.post {
            enqueueGattOp(
                request.pathId,
                GattOp.WriteCharacteristic(characteristic, request.value, writeType),
            )
        }
    }

    private fun sendPeripheral(request: BleSendRequest) {
        requireConnectPermission()
        val path = peripheralPaths[request.pathId] ?: throw FlutterError(
            "not-found",
            "Unknown peripheral pathId ${request.pathId}",
        )
        if (!path.subscribed || !path.canSend) {
            throw unavailable("Peripheral path is not subscribed")
        }
        val server = gattServer ?: throw unavailable("GATT server is not available")
        val characteristic = serverCharacteristic
            ?: throw unavailable("Peripheral characteristic is not available")

        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(
                path.device,
                characteristic,
                false,
                request.value,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            synchronized(characteristic) {
                characteristic.value = request.value
                server.notifyCharacteristicChanged(path.device, characteristic, false)
            }
        }

        if (!ok) {
            throw FlutterError("notify-failed", "Unable to notify peripheral characteristic")
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val request = currentScanRequest ?: return
        val record = result.scanRecord
        if (!scanResultMatchesRequest(record, request)) {
            // Diagnostic: log the first time we see each non-matching peer
            // so we can verify scans are running and tell which UUIDs the
            // BLE adapter is delivering. Cheap because each address is
            // logged at most once per scan session via `seenAdvertisements`.
            val address = normalizeAddress(result.device.address)
            if (seenAdvertisements.add("non-match:$address")) {
                val uuids = record?.serviceUuids?.joinToString(",") { it.uuid.toString() }
                    ?: "(none)"
                // Log.d(
                //     TAG,
                //     "Scan saw non-Grassroots peer $address rssi=${result.rssi} " +
                //         "uuids=$uuids",
                // )
            }
            return
        }

        val address = normalizeAddress(result.device.address)
        if (!request.allowDuplicates && !seenAdvertisements.add(address)) return
        // Log.d(
        //     TAG,
        //     "Scan matched Grassroots peer $address rssi=${result.rssi} " +
        //         "uuids=${record?.serviceUuids?.joinToString(",") { it.uuid.toString() }}",
        // )

        val serviceUuids = record?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
        val pathId = centralPathId(address)
        val path = centralPaths[pathId] ?: CentralPath(
            pathId = pathId,
            address = address,
            device = result.device,
            state = BlePathState.DISCOVERED,
        )
        val previousState = path.state
        path.rssi = result.rssi
        path.device = result.device
        if (path.serviceUuid == null) {
            path.serviceUuid = serviceUuids.firstOrNull()?.let { uuid ->
                try {
                    UUID.fromString(uuid)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        }
        if (
            previousState == BlePathState.DISCOVERED ||
            previousState == BlePathState.DISCONNECTED ||
            previousState == BlePathState.FAILED ||
            previousState == BlePathState.STALE
        ) {
            path.state = BlePathState.DISCOVERED
            path.error = null
            path.gatt = null
            path.characteristic = null
            path.canSend = false
            path.subscriptionReady = false
            centralPaths[pathId] = path
            emitPath(path.toBlePath())
        }

        emitAdvertisement(
            BleAdvertisement(
                remoteId = address,
                platformName = safeDeviceName(result.device),
                advertisedName = record?.deviceName,
                serviceUuids = serviceUuids,
                rssi = result.rssi.toLong(),
                connectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    result.isConnectable
                } else {
                    true
                },
                txPower = record?.txPowerLevel?.takeUnless { it == Int.MIN_VALUE }?.toLong(),
                manufacturerData = firstManufacturerData(record),
            ),
        )
    }

    private fun scanResultMatchesRequest(
        record: ScanRecord?,
        request: BleScanRequest,
    ): Boolean {
        val rawPrefix = request.serviceUuidPrefix ?: return true
        if (rawPrefix.isBlank()) return true
        // Compare dash-free hex on both sides — the caller may pass a prefix
        // either way and `UUID.toString()` always emits dashes.
        val prefix = rawPrefix.lowercase(Locale.US).replace("-", "")

        return record?.serviceUuids?.any { parcelUuid ->
            val hex = parcelUuid.uuid.toString().lowercase(Locale.US).replace("-", "")
            hex.startsWith(prefix)
        } == true
    }

    private fun firstManufacturerData(record: ScanRecord?): ByteArray? {
        val data = record?.manufacturerSpecificData ?: return null
        if (data.size() == 0) return null
        // Match iOS layout: [companyId_low, companyId_high, ...payload]
        val id = data.keyAt(0)
        val payload = data.valueAt(0) ?: return null
        val out = ByteArray(payload.size + 2)
        out[0] = (id and 0xFF).toByte()
        out[1] = ((id shr 8) and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 2, payload.size)
        return out
    }

    private fun stopScanInternal() {
        scanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        scanTimeoutRunnable = null
        val callback = scanCallback
        scanCallback = null
        currentScanRequest = null
        if (callback != null) {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
            } catch (_: Exception) {
            }
        }
        if (isScanning) {
            logToFlutter("Scan stopped")
        }
        isScanning = false
    }

    private fun stopAdvertisingInternal(emitEvents: Boolean) {
        val callback = advertiseCallback
        if (callback != null) {
            try {
                advertiser?.stopAdvertising(callback)
            } catch (_: Exception) {
            }
        }
        advertiseCallback = null
        advertiser = null
        pendingAdvertiseSettings = null
        pendingAdvertiseData = null
        pendingScanResponseData = null

        peripheralPaths.values.forEach { path ->
            try {
                gattServer?.cancelConnection(path.device)
            } catch (_: Exception) {
            }
            path.state = BlePathState.DISCONNECTED
            path.canSend = false
            path.subscribed = false
            if (emitEvents) emitPath(path.toBlePath())
        }
        peripheralPaths.clear()

        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        gattServer = null
        serverCharacteristic = null
        advertisedServiceUuid = null
        advertisedCharacteristicUuid = null
    }

    private fun cleanup(emitEvents: Boolean) {
        stopScanInternal()
        stopAdvertisingInternal(emitEvents)
        centralPaths.values.forEach { path ->
            path.connectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            path.connectTimeoutRunnable = null
            safeDisconnectAndClose(path)
            path.state = BlePathState.DISCONNECTED
            path.canSend = false
            path.subscriptionReady = false
            if (emitEvents) emitPath(path.toBlePath())
        }
        centralPaths.clear()
        unregisterAdapterReceiver()
    }

    private fun safeDisconnectAndClose(path: CentralPath) {
        val gatt = path.gatt ?: return
        try {
            gatt.disconnect()
        } catch (_: Exception) {
        }
        try {
            gatt.close()
        } catch (_: Exception) {
        }
        path.gatt = null
        path.characteristic = null
    }

    private fun ensurePeripheralPath(device: BluetoothDevice): PeripheralPath {
        val pathId = peripheralPathId(device.address)
        return peripheralPaths[pathId] ?: PeripheralPath(
            pathId = pathId,
            address = normalizeAddress(device.address),
            device = device,
            serviceUuid = advertisedServiceUuid,
            characteristicUuid = advertisedCharacteristicUuid,
            state = BlePathState.CONNECTED,
        ).also {
            peripheralPaths[pathId] = it
            emitPath(it.toBlePath())
        }
    }

    private fun ensureBluetoothReferences() {
        val context = requireContext()
        if (bluetoothManager == null) {
            bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        }
        bluetoothAdapter = bluetoothManager?.adapter
    }

    private fun ensurePoweredOn() {
        ensureBluetoothReferences()
        when (adapterState()) {
            BleAdapterState.UNSUPPORTED -> throw unsupported("Bluetooth LE is not supported")
            BleAdapterState.UNAUTHORIZED -> throw unauthorized("Bluetooth permission is not granted")
            BleAdapterState.POWERED_OFF -> throw unavailable("Bluetooth adapter is powered off")
            BleAdapterState.UNKNOWN -> throw unavailable("Bluetooth adapter state is unknown")
            BleAdapterState.POWERED_ON -> Unit
        }
    }

    private fun registerAdapterReceiver() {
        if (receiverRegistered) return
        val context = applicationContext ?: return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Bluetooth state-changed is a system broadcast; EXPORTED matches the reference plugin.
            context.registerReceiver(adapterReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(adapterReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterAdapterReceiver() {
        if (!receiverRegistered) return
        try {
            applicationContext?.unregisterReceiver(adapterReceiver)
        } catch (_: Exception) {
        }
        receiverRegistered = false
    }

    private fun emitAdapterState(state: BleAdapterState) {
        mainHandler.post {
            flutterApi?.onAdapterStateChanged(state) { result ->
                result.exceptionOrNull()?.let {
                    Log.w(TAG, "onAdapterStateChanged callback failed", it)
                }
            }
        }
    }

    private fun emitAdvertisement(advertisement: BleAdvertisement) {
        mainHandler.post {
            flutterApi?.onAdvertisement(advertisement) { result ->
                result.exceptionOrNull()?.let {
                    Log.w(TAG, "onAdvertisement callback failed", it)
                }
            }
        }
    }

    private fun emitPath(path: BlePath) {
        mainHandler.post {
            flutterApi?.onPathChanged(path) { result ->
                result.exceptionOrNull()?.let {
                    Log.w(TAG, "onPathChanged callback failed", it)
                }
            }
        }
    }

    private fun emitPayload(payload: BlePayload) {
        mainHandler.post {
            flutterApi?.onPayloadReceived(payload) { result ->
                result.exceptionOrNull()?.let {
                    Log.w(TAG, "onPayloadReceived callback failed", it)
                }
            }
        }
    }

    private fun logToFlutter(message: String) {
        Log.d(TAG, message)
        if (!verboseLogging) return
        mainHandler.post {
            flutterApi?.onLog(message) { result ->
                result.exceptionOrNull()?.let {
                    Log.w(TAG, "onLog callback failed", it)
                }
            }
        }
    }

    private fun CentralPath.toBlePath(): BlePath {
        return BlePath(
            pathId = pathId,
            role = BleRole.CENTRAL,
            state = state,
            platformDeviceId = address,
            serviceUuid = serviceUuid?.toString(),
            characteristicUuid = characteristicUuid?.toString(),
            rssi = rssi.toLong(),
            mtu = mtu.toLong(),
            canSend = canSend,
            error = error,
        )
    }

    private fun PeripheralPath.toBlePath(): BlePath {
        return BlePath(
            pathId = pathId,
            role = BleRole.PERIPHERAL,
            state = state,
            platformDeviceId = address,
            serviceUuid = serviceUuid?.toString(),
            characteristicUuid = characteristicUuid?.toString(),
            rssi = rssi.toLong(),
            mtu = mtu.toLong(),
            canSend = canSend,
            error = error,
        )
    }

    private fun BluetoothGattCharacteristic.canWrite(): Boolean {
        return properties and (
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            ) != 0
    }

    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    private fun safeDeviceName(device: BluetoothDevice): String? {
        return if (hasConnectPermission()) {
            try {
                device.name
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    private fun hasPermission(permission: String): Boolean {
        val context = applicationContext ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun requireConnectPermission() {
        if (!hasConnectPermission()) {
            throw unauthorized("BLUETOOTH_CONNECT permission is required")
        }
    }

    private fun requireScanPermission() {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (!granted) {
            throw unauthorized("Bluetooth scan permission is required")
        }
    }

    private fun requireAdvertisePermission() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
        if (!granted) {
            throw unauthorized("BLUETOOTH_ADVERTISE permission is required")
        }
    }

    private fun requireContext(): Context {
        return applicationContext ?: throw unavailable("Plugin is not attached to an Android context")
    }

    /// Build a [ScanFilter] that matches a service UUID by hex prefix.
    /// `prefix` is the leading hex (no dashes), 1..32 chars. Bytes covered
    /// by the prefix are matched exactly; bytes after are wildcard.
    private fun scanFilterFromPrefix(prefix: String): ScanFilter? {
        val hex = prefix.lowercase(Locale.US).replace("-", "")
        if (hex.isEmpty() || hex.length > 32 || hex.length % 2 != 0) return null
        // Pad both prefix and mask to 32 hex chars (16 bytes).
        val uuidHex = hex.padEnd(32, '0')
        val maskHex = "f".repeat(hex.length).padEnd(32, '0')
        val toUuid: (String) -> UUID = { h ->
            UUID.fromString(
                "${h.substring(0, 8)}-${h.substring(8, 12)}-" +
                    "${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20, 32)}",
            )
        }
        return try {
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(toUuid(uuidHex)), ParcelUuid(toUuid(maskHex)))
                .build()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseUuid(raw: String): UUID {
        val value = raw.trim()
        val expanded = when (value.length) {
            4 -> "0000${value}-0000-1000-8000-00805f9b34fb"
            8 -> "${value}-0000-1000-8000-00805f9b34fb"
            else -> value
        }
        return try {
            UUID.fromString(expanded)
        } catch (illegalArgumentException: IllegalArgumentException) {
            throw FlutterError("invalid-argument", "Invalid UUID: $raw")
        }
    }

    private fun compactUuid(uuid: UUID): String {
        val expanded = uuid.toString()
        return if (expanded.startsWith("0000") &&
            expanded.endsWith("-0000-1000-8000-00805f9b34fb")
        ) {
            expanded.substring(4, 8)
        } else {
            expanded
        }
    }

    private fun normalizeAddress(address: String): String {
        return address.uppercase(Locale.US)
    }

    private fun centralPathId(address: String): String {
        return "central:${normalizeAddress(address)}"
    }

    private fun peripheralPathId(address: String): String {
        return "peripheral:${normalizeAddress(address)}"
    }

    private fun scanModeFromRequest(mode: Int): Int {
        return when (mode) {
            ScanSettings.SCAN_MODE_LOW_POWER,
            ScanSettings.SCAN_MODE_BALANCED,
            ScanSettings.SCAN_MODE_LOW_LATENCY,
            -> mode
            else -> ScanSettings.SCAN_MODE_LOW_LATENCY
        }
    }

    private fun statusMessageOrNull(status: Int): String? {
        return if (status == BluetoothGatt.GATT_SUCCESS) null else "GATT status $status"
    }

    private fun advertiseFailureMessage(errorCode: Int): String {
        return when (errorCode) {
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
            else -> "error $errorCode"
        }
    }

    private fun scanFailureMessage(errorCode: Int): String {
        return when (errorCode) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "already started"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "application registration failed"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "internal error"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
            ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "out of hardware resources"
            ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "scanning too frequently"
            else -> "error $errorCode"
        }
    }

    private fun unsupported(message: String): FlutterError {
        return FlutterError("unsupported", message)
    }

    private fun unavailable(message: String): FlutterError {
        return FlutterError("unavailable", message)
    }

    private fun unauthorized(message: String): FlutterError {
        return FlutterError("unauthorized", message)
    }
}
