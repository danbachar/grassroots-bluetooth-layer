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

/// The largest ATT MTU the specification allows. A central asks for this and
/// the two controllers settle on whatever they can both carry, so a pair ends
/// at its own ceiling rather than at a number this library picked for it.
private const val MAX_ATT_MTU = 517
private const val RSSI_POLL_INTERVAL_MS = 10_000L
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

    /**
     * The UUID the GATT data service is registered under (the data plane a
     * connected central talks to). Decoupled from [advertisedServiceUuid], which
     * is the rotating discovery beacon; keeping this stable lets the advertised
     * UUID rotate without tearing down the GATT server or dropping peripheral
     * links.
     */
    private var gattServiceUuid: UUID? = null

    // Last advertise settings + scan response, retained so a non-destructive
    // beacon refresh can restart the advertiser without rebuilding them (the
    // `pending*` copies are consumed once in onServiceAdded).
    private var lastAdvertiseSettings: AdvertiseSettings? = null
    private var lastScanResponseData: AdvertiseData? = null

    // The advertising state Dart was last told about, so repeated teardowns
    // (adapter off, dispose, a stop with nothing running) do not restate it.
    private var reportedAdvertisingState: BleAdvertisingState? = null

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
        var rssi: Int? = null,
        var mtu: Int = DEFAULT_ATT_MTU,
        /// The MTU to ASK for on this link, kept apart from [mtu], which holds
        /// only what a negotiation has actually agreed.
        var requestedMtu: Int = MAX_ATT_MTU,
        var canSend: Boolean = false,
        var subscribeRequested: Boolean = true,
        var subscriptionReady: Boolean = false,
        var connectTimeoutRunnable: Runnable? = null,
        var rssiPollRunnable: Runnable? = null,
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
        object ReadRemoteRssi : GattOp()
        data class RequestMtu(val mtu: Int) : GattOp()
    }

    /**
     * An ATT MTU reported for a peripheral link whose path does not exist yet.
     *
     * The server learns the MTU from the remote central's exchange, which is
     * not ordered against our own STATE_CONNECTED callback. When the MTU
     * arrives first the path is not in [peripheralPaths] yet, and dropping the
     * value there leaves that link on the 23-byte ATT default for its whole
     * lifetime -- every write large enough to matter is then refused by the
     * caller, so the link is up and carries nothing. Entries are keyed by path
     * id and cleared on disconnect, so a value can never be applied to a later
     * connection that renegotiated its own.
     */
    private val pendingPeripheralMtu = mutableMapOf<String, Int>()

    private data class PeripheralPath(
        val pathId: String,
        val address: String,
        val device: BluetoothDevice,
        var serviceUuid: UUID? = null,
        var characteristicUuid: UUID? = null,
        var state: BlePathState = BlePathState.CONNECTED,
        var rssi: Int? = null,
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
        // The next isolate to attach holds no advertising state, so nothing it
        // is told may be dropped as a repeat.
        reportedAdvertisingState = null
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

        val context = requireContext()
        val manager = bluetoothManager ?: throw unavailable("Bluetooth manager is unavailable")
        val adapter = bluetoothAdapter ?: throw unsupported("Bluetooth adapter is unavailable")
        val serviceUuid = parseUuid(request.serviceUuid)
        val characteristicUuid = parseUuid(request.characteristicUuid)
        val newGattServiceUuid = parseUuid(request.gattServiceUuid ?: request.serviceUuid)

        if (!adapter.isMultipleAdvertisementSupported) {
            throw unsupported("Bluetooth LE advertising is not supported on this device")
        }

        // Non-destructive rotation: when the GATT data service (its UUID and the
        // characteristic) is unchanged and already registered, only the
        // advertised beacon changed. Refresh just the advertiser and keep the
        // GATT server and every live peripheral link intact. (The advertiser was
        // acquired by the prior full advertise and is still live here.)
        if (gattServer != null &&
            gattServiceUuid == newGattServiceUuid &&
            advertisedCharacteristicUuid == characteristicUuid
        ) {
            logToFlutter(
                "Advertised serviceUuid changed, GATT service $newGattServiceUuid " +
                    "unchanged — refreshing advertisement only (no service rebuild)."
            )
            refreshAdvertisementOnly(serviceUuid)
            return
        }

        stopAdvertisingInternal(emitEvents = true)

        // Acquire the advertiser AFTER stopAdvertisingInternal — it nulls the
        // `advertiser` field, and onServiceAdded needs it non-null to actually
        // start advertising once the GATT service is added.
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

        val service = BluetoothGattService(newGattServiceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
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
                val self = this
                mainHandler.post {
                    if (advertiseCallback !== self) return@post
                    logToFlutter("Advertising started for $serviceUuid")
                    emitAdvertisingState(BleAdvertisingState(active = true))
                }
            }

            override fun onStartFailure(errorCode: Int) {
                resolveAdvertiseStartFailure(this, errorCode)
            }
        }

        gattServer = manager.openGattServer(context, gattServerCallback)
            ?: throw unavailable("Unable to open Bluetooth GATT server")
        serverCharacteristic = characteristic
        advertisedServiceUuid = serviceUuid
        advertisedCharacteristicUuid = characteristicUuid
        gattServiceUuid = newGattServiceUuid
        advertiseCallback = callback
        pendingAdvertiseSettings = settings
        pendingAdvertiseData = advertiseData
        pendingScanResponseData = scanResponseData
        lastAdvertiseSettings = settings
        lastScanResponseData = scanResponseData

        val added = gattServer?.addService(service) ?: false
        if (!added) {
            stopAdvertisingInternal(emitEvents = false)
            throw unavailable("Unable to add GATT service")
        }
        logToFlutter("GATT service add requested for $serviceUuid")
    }

    /**
     * Restart only the advertiser with a new advertised service UUID, leaving
     * the open GATT server and every live peripheral link untouched. Used for
     * non-destructive rotation of the discovery beacon.
     */
    private fun refreshAdvertisementOnly(serviceUuid: UUID) {
        val adv = advertiser ?: return
        val settings = lastAdvertiseSettings ?: return
        val scanResponse = lastScanResponseData ?: return

        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(serviceUuid))
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()

        advertiseCallback?.let {
            try {
                adv.stopAdvertising(it)
            } catch (_: Exception) {
            }
        }

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                val self = this
                mainHandler.post {
                    if (advertiseCallback !== self) return@post
                    logToFlutter("Advertising refreshed for $serviceUuid")
                    emitAdvertisingState(BleAdvertisingState(active = true))
                }
            }

            override fun onStartFailure(errorCode: Int) {
                resolveAdvertiseStartFailure(this, errorCode)
            }
        }

        advertisedServiceUuid = serviceUuid
        advertiseCallback = callback
        pendingAdvertiseData = advertiseData
        try {
            adv.startAdvertising(settings, advertiseData, scanResponse, callback)
        } catch (illegalArgumentException: IllegalArgumentException) {
            logToFlutter("Advertising refresh failed: ${illegalArgumentException.message}")
            emitAdvertisingState(
                BleAdvertisingState(
                    active = false,
                    failure = BleAdvertiseFailure.TERMINAL,
                    reason = illegalArgumentException.message ?: "rejected advertise data",
                )
            )
        }
    }

    /** What an advertise start refusal says about the request. */
    private enum class AdvertiseRefusal {
        /** The controller is already broadcasting our advertisement. */
        ALREADY_ACTIVE,

        /** The request is sound; the controller would not take it now. */
        TRANSIENT,

        /** The request cannot succeed as written. */
        TERMINAL,
    }

    /**
     * The controller refuses an advertise start for reasons that differ in what
     * the caller can do about them, so the reason decides what survives.
     *
     * `ALREADY_STARTED` is not a failure: the radio is broadcasting our
     * advertisement, which is the state the caller asked for.
     *
     * `TOO_MANY_ADVERTISERS` means another app (Google Play services' Nearby
     * stack is the usual one) holds the controller's advertising sets, and
     * `INTERNAL_ERROR` is a stack fault. Both leave the request itself valid,
     * so a later start can succeed and the peripheral stack stays up.
     *
     * `DATA_TOO_LARGE` and `FEATURE_UNSUPPORTED` describe our own request or
     * the hardware, and no later attempt with the same arguments changes
     * either. A code this plugin does not name is read the same way, since
     * nothing establishes it as recoverable.
     */
    private fun classifyAdvertiseRefusal(errorCode: Int): AdvertiseRefusal {
        return when (errorCode) {
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> AdvertiseRefusal.ALREADY_ACTIVE
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS,
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> AdvertiseRefusal.TRANSIENT
            else -> AdvertiseRefusal.TERMINAL
        }
    }

    /**
     * Act on a refused advertise start, and tell Dart whether this device is
     * advertising.
     *
     * Both advertise callbacks — the one that starts a freshly built GATT
     * service and the one that refreshes only the beacon — route here, so a
     * refusal means the same thing whichever raised it.
     *
     * A transient refusal keeps the advertiser and the open GATT server, so a
     * later start finds the service registered and only has to reach the
     * radio. A terminal one tears the peripheral stack down.
     *
     * The event matters as much as the classification: a device whose
     * advertising was refused keeps scanning and connecting normally while no
     * peer can discover it, and nothing else above the plugin reports that.
     */
    private fun resolveAdvertiseStartFailure(source: AdvertiseCallback, errorCode: Int) {
        mainHandler.post {
            if (advertiseCallback !== source) return@post
            val reason = advertiseFailureMessage(errorCode)
            when (classifyAdvertiseRefusal(errorCode)) {
                AdvertiseRefusal.ALREADY_ACTIVE -> {
                    logToFlutter("Advertising start returned: $reason")
                    emitAdvertisingState(BleAdvertisingState(active = true))
                }

                AdvertiseRefusal.TRANSIENT -> {
                    logToFlutter(
                        "Advertising refused: $reason — this device is undiscoverable; " +
                            "advertiser and GATT server kept for a later start",
                    )
                    emitAdvertisingState(
                        BleAdvertisingState(
                            active = false,
                            failure = BleAdvertiseFailure.TRANSIENT,
                            reason = reason,
                        )
                    )
                }

                AdvertiseRefusal.TERMINAL -> {
                    logToFlutter("Advertising failed: $reason")
                    // Report the reason before the teardown, which otherwise
                    // reports the same stop without one.
                    emitAdvertisingState(
                        BleAdvertisingState(
                            active = false,
                            failure = BleAdvertiseFailure.TERMINAL,
                            reason = reason,
                        )
                    )
                    stopAdvertisingInternal(emitEvents = false)
                }
            }
        }
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
            rssi = existing?.rssi,
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
                }
            }
            mainHandler.postDelayed(path.connectTimeoutRunnable!!, request.timeoutMs)
        }

        // Record what to ask for. `mtu` stays at the default until a
        // negotiation answers: writes are sized from it, and a value we have
        // merely requested is not one the link has agreed to carry.
        val requestedMtu = request.androidMtu?.toInt()
        path.requestedMtu =
            (requestedMtu ?: MAX_ATT_MTU).coerceIn(DEFAULT_ATT_MTU + 1, MAX_ATT_MTU)

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

    override fun linkSnapshot(): List<BleLinkInfo> {
        // Ground truth from the OS, not our path bookkeeping: the stack's
        // per-profile connected-device lists. One entry per distinct remote
        // address = one live ACL. An address in BOTH lists is a single shared
        // link carrying both GATT directions (over-ACL); a dual-ACL pair
        // surfaces as two different addresses that the app maps to one peer.
        val manager = bluetoothManager ?: return emptyList()
        val clientAddrs = try {
            manager.getConnectedDevices(BluetoothProfile.GATT).map { it.address }
        } catch (e: Exception) {
            emptyList()
        }
        val serverAddrs = try {
            manager.getConnectedDevices(BluetoothProfile.GATT_SERVER).map { it.address }
        } catch (e: Exception) {
            emptyList()
        }
        return (clientAddrs.toSet() + serverAddrs.toSet()).sorted().map { addr ->
            BleLinkInfo(
                address = addr,
                clientRole = clientAddrs.contains(addr),
                serverRole = serverAddrs.contains(addr),
            )
        }
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
                    stopCentralRssiPoll(path)
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

    /// GATT-server response that survives the respond-after-disconnect race.
    /// The peer can drop the link between the request callback and our
    /// (main-handler-posted) response; on modern Android sendResponse then
    /// just returns false, but Android 8.x's Bluetooth process throws an NPE
    /// that propagates back through the binder and would kill the whole app.
    /// The race is unavoidable, so the response must be crash-proof.
    private fun safeSendResponse(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray,
    ) {
        try {
            gattServer?.sendResponse(device, requestId, status, offset, value)
        } catch (e: Exception) {
            logToFlutter(
                "sendResponse to ${device.address} failed (peer likely gone): $e",
            )
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            mainHandler.post {
                val pathId = peripheralPathId(device.address)
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        val previousState = peripheralPaths[pathId]?.state
                        logToFlutter(
                            "BLE peripheral onConnectionStateChange: pathId=$pathId " +
                                "addr=${device.address} previousState=$previousState " +
                                "newState=CONNECTED ${decodeGattStatus(status)}"
                        )
                        val existing = peripheralPaths[pathId]
                        // A repeated CONNECTED on a link that is already up must
                        // not reset the negotiated MTU: it is exchanged once per
                        // connection and never again, so a fresh object here
                        // pins the link at the ATT default for good. A path that
                        // was DISCONNECTED is a new connection, which does
                        // renegotiate, and starts at the default correctly.
                        val carriedMtu = if (existing != null &&
                            existing.state != BlePathState.DISCONNECTED
                        ) {
                            existing.mtu
                        } else {
                            pendingPeripheralMtu.remove(pathId) ?: DEFAULT_ATT_MTU
                        }
                        val path = PeripheralPath(
                            pathId = pathId,
                            address = normalizeAddress(device.address),
                            device = device,
                            serviceUuid = gattServiceUuid,
                            characteristicUuid = advertisedCharacteristicUuid,
                            state = BlePathState.CONNECTED,
                            mtu = carriedMtu,
                            subscribed = existing?.subscribed ?: false,
                            error = statusMessageOrNull(status),
                        )
                        peripheralPaths[pathId] = path
                        emitPath(path.toBlePath())
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val path = peripheralPaths[pathId]
                        logToFlutter(
                            "BLE peripheral onConnectionStateChange: pathId=$pathId " +
                                "addr=${device.address} previousState=${path?.state} " +
                                "subscribed=${path?.subscribed} " +
                                "newState=DISCONNECTED ${decodeGattStatus(status)}"
                        )
                        // The next connection negotiates its own MTU, so
                        // nothing learned about this one may outlive it.
                        pendingPeripheralMtu.remove(pathId)
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
                    else -> {
                        logToFlutter(
                            "BLE peripheral onConnectionStateChange: pathId=$pathId " +
                                "addr=${device.address} newState=$newState " +
                                "${decodeGattStatus(status)} (intermediate)"
                        )
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mainHandler.post {
                val pathId = peripheralPathId(device.address)
                val path = peripheralPaths[pathId]
                if (path == null) {
                    // Ahead of our own CONNECTED callback: hold it until the
                    // path exists rather than losing the only report we get.
                    pendingPeripheralMtu[pathId] = mtu
                    logToFlutter(
                        "BLE peripheral onMtuChanged before path exists: " +
                            "pathId=$pathId mtu=$mtu (held)"
                    )
                    return@post
                }
                path.mtu = mtu
                logToFlutter(
                    "BLE peripheral onMtuChanged: pathId=$pathId mtu=$mtu"
                )
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
                    safeSendResponse(
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
                    safeSendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        ByteArray(0),
                    )
                    return@post
                }
                safeSendResponse(
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
                        safeSendResponse(
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
                        safeSendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            offset,
                            ByteArray(0),
                        )
                    }
                    return@post
                }

                // An ATT write PDU is one opcode byte, two handle bytes and
                // the value, and the whole PDU has to fit the MTU — so a value
                // of this length having arrived proves the link carries at
                // least value.size + 3. (The send side subtracts those same
                // three bytes; they are added here because what is known is
                // the value, not the PDU.) The peripheral needs that evidence:
                // its only direct report is the GATT server's MTU callback,
                // which the OS does not always deliver, and a path left on the
                // 23-byte default has 20 usable bytes — under the header of a
                // single packet — so it refuses everything it sends while the
                // link reports healthy. Raise only: where the callback did
                // speak, the value it gave is the true one.
                val provenMtu = value.size + 3
                peripheralPaths[peripheralPathId(device.address)]?.let { known ->
                    if (provenMtu > known.mtu) {
                        logToFlutter(
                            "BLE peripheral mtu raised by an inbound write: " +
                                "pathId=${known.pathId} ${known.mtu} -> $provenMtu",
                        )
                        known.mtu = provenMtu
                        emitPath(known.toBlePath())
                    }
                }

                if (responseNeeded) {
                    safeSendResponse(
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
                        rssi = peripheralPaths[pathId]?.rssi?.toLong(),
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
                    safeSendResponse(
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
                    safeSendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        offset,
                        ByteArray(0),
                    )
                    return@post
                }

                safeSendResponse(
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
                        safeSendResponse(
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
                    safeSendResponse(
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
                if (service.uuid != gattServiceUuid) {
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
                    // Release the controller's slot for this connection before
                    // anything else can return early.
                    //
                    // disconnect() tears the link down; only close() gives back
                    // the client interface and its transport control block. The
                    // stack has a small, fixed number of those — far smaller on
                    // older controllers — and once they are gone every
                    // connectGatt comes back as the generic status 133, which
                    // produces more of these callbacks and so exhausts them
                    // faster. A callback for a path we no longer track is
                    // exactly the case that must still close: the object is
                    // ours either way, and nothing else will ever release it.
                    if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        try {
                            gatt.close()
                        } catch (_: Exception) {
                        }
                    }
                    val path = centralPaths[pathId] ?: return@post
                    when {
                        newState == BluetoothProfile.STATE_CONNECTED &&
                            status == BluetoothGatt.GATT_SUCCESS -> {
                            val previousState = path.state
                            logToFlutter(
                                "BLE central onConnectionStateChange: pathId=$pathId " +
                                    "addr=${path.address} previousState=$previousState " +
                                    "newState=CONNECTED ${decodeGattStatus(status)}"
                            )
                            path.gatt = gatt
                            path.device = gatt.device
                            path.state = BlePathState.CONNECTED
                            path.error = null
                            path.canSend = false
                            emitPath(path.toBlePath())

                            // Negotiate the MTU as the first thing on a new
                            // link, unconditionally and before discovery.
                            //
                            // This was gated on `path.mtu` already exceeding
                            // the default — the same field a negotiated value
                            // lands in — so a path arriving here at the default
                            // skipped the exchange and went straight to
                            // discovery. Nothing raised it afterwards: the
                            // central kept the default, and the peer's GATT
                            // server was never called back, because no
                            // negotiation had happened at all. Both ends were
                            // left with 20 usable bytes, under the header of a
                            // single packet, on a link that looked healthy and
                            // could carry nothing.
                            // Before anything reads this peer's services:
                            // discovery must see what the peer has now, not
                            // what it had under a previous rotation slot.
                            clearGattCache(gatt)
                            enqueueGattOp(pathId, GattOp.RequestMtu(path.requestedMtu))
                        }
                        newState == BluetoothProfile.STATE_DISCONNECTED -> {
                            val previousState = path.state
                            // High-value diagnostic — log BEFORE mutating
                            // path state so we capture what we were doing
                            // when the link dropped (CONNECTING / CONNECTED
                            // / SUBSCRIBED / READY all imply different
                            // root causes).
                            logToFlutter(
                                "BLE central onConnectionStateChange: pathId=$pathId " +
                                    "addr=${path.address} previousState=$previousState " +
                                    "newState=DISCONNECTED ${decodeGattStatus(status)} " +
                                    "pendingOps=${path.pendingOps.size} " +
                                    "inFlightOp=${path.inFlightOp?.javaClass?.simpleName ?: "none"}"
                            )
                            path.connectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                            path.connectTimeoutRunnable = null
                            stopCentralRssiPoll(path)
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
                            if (path.forgetOnDisconnect) {
                                centralPaths.remove(pathId)
                            }
                        }
                        else -> {
                            // Surface other transient state changes (STATE_CONNECTING,
                            // STATE_DISCONNECTING) so partial-link issues are visible.
                            logToFlutter(
                                "BLE central onConnectionStateChange: pathId=$pathId " +
                                    "addr=${path.address} newState=$newState " +
                                    "${decodeGattStatus(status)} (intermediate)"
                            )
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
                    finishGattOp(pathId)
                    val path = centralPaths[pathId] ?: return@post
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        path.rssi = rssi
                        emitPath(path.toBlePath())
                    }
                    scheduleCentralRssiPoll(pathId)
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
            GattOp.ReadRemoteRssi -> {
                gatt.readRemoteRssi()
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
            if (op === GattOp.ReadRemoteRssi) {
                scheduleCentralRssiPoll(pathId)
            }
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
                    rssi = path.rssi?.toLong(),
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
        requestCentralRssi(pathId)
    }

    private fun failCentralPath(pathId: String, message: String) {
        val path = centralPaths[pathId] ?: return
        path.connectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        path.connectTimeoutRunnable = null
        stopCentralRssiPoll(path)
        path.state = BlePathState.FAILED
        path.canSend = false
        path.subscriptionReady = false
        path.error = message
        // A path that failed AFTER the link came up -- discovery, the
        // characteristic lookup, the CCCD write -- is still holding a
        // connection, and the stack has a small fixed number of those. Nothing
        // else releases it: the peer is not going to drop a link it considers
        // healthy, and the DISCONNECTED callback that closes elsewhere never
        // arrives. Release it here, where every failure converges.
        safeDisconnectAndClose(path)
        emitPath(path.toBlePath())
        logToFlutter("Path $pathId failed: $message")
    }

    private fun requestCentralRssi(pathId: String) {
        val path = centralPaths[pathId] ?: return
        if (!path.canPollRssi()) return
        if (path.inFlightOp === GattOp.ReadRemoteRssi ||
            path.pendingOps.any { it === GattOp.ReadRemoteRssi }
        ) {
            return
        }
        enqueueGattOp(pathId, GattOp.ReadRemoteRssi)
    }

    private fun scheduleCentralRssiPoll(pathId: String) {
        val path = centralPaths[pathId] ?: return
        stopCentralRssiPoll(path)
        if (!path.canPollRssi()) return
        val runnable = Runnable { requestCentralRssi(pathId) }
        path.rssiPollRunnable = runnable
        mainHandler.postDelayed(runnable, RSSI_POLL_INTERVAL_MS)
    }

    private fun stopCentralRssiPoll(path: CentralPath) {
        path.rssiPollRunnable?.let { mainHandler.removeCallbacks(it) }
        path.rssiPollRunnable = null
    }

    private fun CentralPath.canPollRssi(): Boolean {
        return gatt != null &&
            state != BlePathState.DISCONNECTED &&
            state != BlePathState.FAILED &&
            state != BlePathState.STALE
    }

    private fun disconnectCentral(request: BleDisconnectRequest) {
        requireConnectPermission()
        val path = centralPaths[request.pathId] ?: throw FlutterError(
            "not-found",
            "Unknown central pathId ${request.pathId}",
        )
        path.connectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        path.connectTimeoutRunnable = null
        stopCentralRssiPoll(path)
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
                // Close whatever this path still holds before losing the only
                // reference to it: once the entry is gone nothing can give the
                // controller its slot back.
                safeDisconnectAndClose(path)
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
        // Real BLE RSSI is always negative dBm (typically -30 to -100).
        // Some Android firmwares emit `result.rssi >= 0` for scan-result
        // cache replays or transient hardware states where the radio has
        // not actually measured this advertisement. Drop these entirely:
        // we cannot trust ANY field of a ScanResult whose RSSI is bogus,
        // and the next real-measurement tick will deliver a usable one.
        if (result.rssi >= 0) {
            // Diagnostic — confirms how often we drop a result and from
            // which remote. Real-pair drops here imply the radio is
            // delivering cached/sentinel measurements for that peer.
            logToFlutter(
                "Dropped scan result with non-real RSSI: " +
                    "addr=${result.device.address} rssi=${result.rssi}",
            )
            return
        }

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
        // Every path that takes the advertiser down runs through here,
        // including the GATT-service-add failures that reach no caller, so
        // this is where Dart hears that the device stopped advertising. A
        // refusal has already reported itself, with its reason; only a stop
        // from a state Dart believes is advertising is news.
        if (reportedAdvertisingState?.active == true) {
            emitAdvertisingState(BleAdvertisingState(active = false))
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
        gattServiceUuid = null
        lastAdvertiseSettings = null
        lastScanResponseData = null
    }

    private fun cleanup(emitEvents: Boolean) {
        stopScanInternal()
        stopAdvertisingInternal(emitEvents)
        centralPaths.values.forEach { path ->
            path.connectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            path.connectTimeoutRunnable = null
            stopCentralRssiPoll(path)
            safeDisconnectAndClose(path)
            path.state = BlePathState.DISCONNECTED
            path.canSend = false
            path.subscriptionReady = false
            if (emitEvents) emitPath(path.toBlePath())
        }
        centralPaths.clear()
        unregisterAdapterReceiver()
    }

    /**
     * Drop Android's cached attribute table for this peer.
     *
     * The stack remembers a device's services and handles by address and
     * reuses them on reconnect instead of rediscovering. This project's GATT
     * service UUID is derived from the peer's public key and rotates with its
     * advertisement, so a remembered table routinely names a service the peer
     * no longer exposes: discovery then finds nothing, the path is failed and
     * disconnected, and the redial that follows reads the same stale entry
     * again. Clearing it costs one rediscovery and makes every connection read
     * what the peer actually has.
     *
     * There is no public API for this — `refresh()` is hidden, and its only
     * effect is to drop that cache. Absent or refused, the connection still
     * works from whatever is cached, so a failure here is logged and ignored.
     */
    private fun clearGattCache(gatt: BluetoothGatt): Boolean {
        return try {
            val cleared = BluetoothGatt::class.java
                .getMethod("refresh")
                .invoke(gatt) as? Boolean ?: false
            if (!cleared) logToFlutter("GATT cache clear refused for ${gatt.device?.address}")
            cleared
        } catch (e: Exception) {
            logToFlutter("GATT cache clear unavailable: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun safeDisconnectAndClose(path: CentralPath) {
        stopCentralRssiPoll(path)
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
            serviceUuid = gattServiceUuid,
            characteristicUuid = advertisedCharacteristicUuid,
            state = BlePathState.CONNECTED,
            mtu = pendingPeripheralMtu.remove(pathId) ?: DEFAULT_ATT_MTU,
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

    /**
     * Pigeon FlutterApi callbacks reply with a `channel-error` [FlutterError]
     * when no Dart isolate is listening on the channel. This is expected and
     * transient during hot restart: native scan/GATT callbacks keep firing while
     * the old isolate is gone and the new one hasn't re-registered its handlers
     * yet, so a single scan can emit dozens of these per second. Swallow that
     * specific case (it self-heals once Dart re-attaches) while still surfacing
     * any genuine callback failure.
     */
    private fun handleFlutterCallbackResult(label: String, result: Result<Unit>) {
        val error = result.exceptionOrNull() ?: return
        if (error is FlutterError && error.code == "channel-error") return
        Log.w(TAG, "$label callback failed", error)
    }

    private fun emitAdapterState(state: BleAdapterState) {
        mainHandler.post {
            flutterApi?.onAdapterStateChanged(state) {
                handleFlutterCallbackResult("onAdapterStateChanged", it)
            }
        }
    }

    private fun emitAdvertisement(advertisement: BleAdvertisement) {
        mainHandler.post {
            flutterApi?.onAdvertisement(advertisement) {
                handleFlutterCallbackResult("onAdvertisement", it)
            }
        }
    }

    /**
     * Tell Dart whether this device is advertising. Repeats of the state Dart
     * already holds are dropped, so a teardown that runs over an already-torn-
     * down advertiser stays quiet.
     *
     * Called on the main thread only — the advertise callbacks arrive on a
     * binder thread and hop before they get here, which keeps
     * [reportedAdvertisingState] to one thread.
     */
    private fun emitAdvertisingState(state: BleAdvertisingState) {
        if (reportedAdvertisingState == state) return
        reportedAdvertisingState = state
        mainHandler.post {
            flutterApi?.onAdvertisingStateChanged(state) {
                handleFlutterCallbackResult("onAdvertisingStateChanged", it)
            }
        }
    }

    private fun emitPath(path: BlePath) {
        mainHandler.post {
            flutterApi?.onPathChanged(path) {
                handleFlutterCallbackResult("onPathChanged", it)
            }
        }
    }

    private fun emitPayload(payload: BlePayload) {
        mainHandler.post {
            flutterApi?.onPayloadReceived(payload) {
                handleFlutterCallbackResult("onPayloadReceived", it)
            }
        }
    }

    private fun logToFlutter(message: String) {
        Log.d(TAG, message)
        if (!verboseLogging) return
        mainHandler.post {
            flutterApi?.onLog(message) {
                handleFlutterCallbackResult("onLog", it)
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
            rssi = rssi?.toLong(),
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
            rssi = rssi?.toLong(),
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
        return if (status == BluetoothGatt.GATT_SUCCESS) null else decodeGattStatus(status)
    }

    /// Decode a GATT / HCI status code to a human-readable name. Common
    /// disconnect reasons are not all exposed by the public `BluetoothGatt`
    /// class; values are taken from `gatt_api.h` / `hci_status.h` in AOSP.
    /// Always returns "GATT status N (NAME)" so the raw code stays
    /// inspectable when triaging logs.
    private fun decodeGattStatus(status: Int): String {
        val name = when (status) {
            0 -> "SUCCESS"
            1 -> "GATT_INVALID_HANDLE"
            2 -> "GATT_READ_NOT_PERMIT"
            3 -> "GATT_WRITE_NOT_PERMIT"
            5 -> "GATT_INSUF_AUTHENTICATION"
            6 -> "GATT_REQ_NOT_SUPPORTED"
            7 -> "GATT_INVALID_OFFSET"
            8 -> "GATT_CONN_TIMEOUT (supervision timeout)"
            13 -> "GATT_INVALID_ATTR_LEN"
            15 -> "GATT_INSUF_ENCRYPTION"
            19 -> "GATT_CONN_TERMINATE_PEER_USER (remote disconnected)"
            20 -> "GATT_CONN_TERMINATE_LOCAL_HOST"
            22 -> "GATT_CONN_TERMINATE_LOCAL_HOST (we called disconnect)"
            34 -> "GATT_CONN_LMP_TIMEOUT"
            62 -> "GATT_CONN_FAIL_ESTABLISH (link couldn't be established)"
            133 -> "GATT_ERROR generic"
            143 -> "GATT_CONN_CANCEL"
            256 -> "GATT_CONN_CANCEL"
            else -> "UNKNOWN"
        }
        return "GATT status $status ($name)"
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
