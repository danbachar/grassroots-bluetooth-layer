import CoreBluetooth
import Flutter
import Foundation
import UIKit

// Pigeon 17.3.0's generated Swift code uses `Result<Void, FlutterError>`,
// which requires `FlutterError` to conform to `Error`. The generator
// doesn't emit this conformance, so we add it here. (Flutter's
// `FlutterError` is an `NSObject` subclass; declaring conformance is
// safe because Swift's `Error` protocol has no required members.)
extension FlutterError: Error {}

public class GrassrootsBluetoothPlugin: NSObject, FlutterPlugin {
  public static func register(with registrar: FlutterPluginRegistrar) {
    let messenger = registrar.messenger()
    let api = GrassrootsBluetoothDarwin(binaryMessenger: messenger)
    GrassrootsBluetoothLayerHostApiSetup.setUp(binaryMessenger: messenger, api: api)
  }
}

private struct CentralPathState {
  let pathId: String
  let platformDeviceId: String
  let peripheral: CBPeripheral
  var serviceUuid: CBUUID?
  var characteristicUuid: CBUUID?
  var characteristic: CBCharacteristic?
  var state: BlePathState
  var rssi: Int64?
  var mtu: Int64
  var notificationsRequested: Bool
  var isSubscribed: Bool
  var error: String?
}

private struct PeripheralPathState {
  let pathId: String
  let platformDeviceId: String
  let central: CBCentral
  var serviceUuid: CBUUID?
  var characteristicUuid: CBUUID?
  var state: BlePathState
  var rssi: Int64?
  var mtu: Int64
  var isSubscribed: Bool
  var error: String?
}

private struct PendingCentralWrite {
  let value: Data
  let characteristic: CBCharacteristic
}

private struct PendingPeripheralUpdate {
  let pathId: String
  let value: Data
  let central: CBCentral
}

private final class GrassrootsBluetoothDarwin: NSObject, GrassrootsBluetoothLayerHostApi {
  private static let centralRestoreIdentifier = "org.permissionlesstech.grassroots_bluetooth_layer.central"
  private static let peripheralRestoreIdentifier = "org.permissionlesstech.grassroots_bluetooth_layer.peripheral"
  private static let pendingQueueCap = 256

  /// Polling interval for `CBPeripheral.readRSSI()` on each live central
  /// path. Mirrors the Android side's `RSSI_POLL_INTERVAL_MS`. 10 seconds
  /// is a deliberate trade-off between radio cost and freshness — once a
  /// peer is connected we don't see new advertisements from them, so this
  /// poll is the only thing that keeps the central-side RSSI current.
  private static let rssiPollInterval: TimeInterval = 10.0

  private let flutterApi: GrassrootsBluetoothLayerFlutterApi

  private var centralManager: CBCentralManager?
  private var peripheralManager: CBPeripheralManager?
  private var advertisedCharacteristic: CBMutableCharacteristic?
  private var advertisedServiceUuid: CBUUID?
  private var advertisedCharacteristicUuid: CBUUID?
  private var advertiseRequest: BleAdvertiseRequest?
  private var advertisementData: [String: Any]?
  private var advertiseGeneration: UInt64 = 0
  private var scanRequest: BleScanRequest?
  private var scanTimer: Timer?
  private var connectTimers: [String: Timer] = [:]
  /// Per-pathId repeating timer that polls `CBPeripheral.readRSSI()` on
  /// each ready central path. Without this, the central-side RSSI stays
  /// frozen at whatever value the most recent `didDiscover` reported, and
  /// goes stale as soon as the peer is connected.
  private var rssiPollTimers: [String: Timer] = [:]
  private var knownPeripherals: [String: CBPeripheral] = [:]
  private var centralPaths: [String: CentralPathState] = [:]
  private var peripheralPaths: [String: PeripheralPathState] = [:]
  private var pendingCentralWrites: [String: [PendingCentralWrite]] = [:]
  private var pendingPeripheralUpdates: [PendingPeripheralUpdate] = []
  private var lastAdapterState: BleAdapterState = .unknown
  private var verboseLogging = false
  private var initialized = false

  init(binaryMessenger: FlutterBinaryMessenger) {
    self.flutterApi = GrassrootsBluetoothLayerFlutterApi(binaryMessenger: binaryMessenger)
    super.init()
  }

  func initialize(options: BleInitializeOptions) throws {
    verboseLogging = options.verboseLogging
    initialized = true
    ensureManagers(options: options)
    emitAdapterStateIfChanged(force: true)
  }

  func isSupported() throws -> Bool {
    let state = try adapterState()
    return state != .unsupported
  }

  func adapterState() throws -> BleAdapterState {
    if let centralManager = centralManager {
      return mapState(centralManager.state)
    }
    if let peripheralManager = peripheralManager {
      return mapState(peripheralManager.state)
    }
    return .unknown
  }

  func startAdvertising(request: BleAdvertiseRequest) throws {
    ensureManagers()
    advertiseRequest = request
    advertiseGeneration &+= 1

    guard let peripheralManager = peripheralManager else {
      throw flutterError("notInitialized", "BLE peripheral manager is not initialized.")
    }

    advertisedServiceUuid = CBUUID(string: request.serviceUuid)
    advertisedCharacteristicUuid = CBUUID(string: request.characteristicUuid)

    log("startAdvertising requested: serviceUuid=\(request.serviceUuid) localName=\(request.localName ?? "<nil>")")
    log("peripheralManager.state=\(describe(peripheralManager.state)) authorization=\(describePeripheralAuthorization())")

    if peripheralManager.state == .poweredOn {
      configurePeripheralService()
    } else {
      log("Deferring advertising until peripheral manager is powered on.")
    }
  }

  private func describe(_ state: CBManagerState) -> String {
    switch state {
    case .poweredOn: return "poweredOn"
    case .poweredOff: return "poweredOff"
    case .resetting: return "resetting"
    case .unauthorized: return "unauthorized"
    case .unsupported: return "unsupported"
    case .unknown: return "unknown"
    @unknown default: return "@unknown(\(state.rawValue))"
    }
  }

  private func describePeripheralAuthorization() -> String {
    #if os(iOS)
      if #available(iOS 13.1, *) {
        switch CBPeripheralManager.authorization {
        case .allowedAlways: return "allowedAlways"
        case .denied: return "denied"
        case .restricted: return "restricted"
        case .notDetermined: return "notDetermined"
        @unknown default: return "@unknown(\(CBPeripheralManager.authorization.rawValue))"
        }
      }
      if #available(iOS 13.0, *) {
        switch CBPeripheralManager.authorizationStatus() {
        case .authorized: return "authorized"
        case .denied: return "denied"
        case .restricted: return "restricted"
        case .notDetermined: return "notDetermined"
        @unknown default: return "@unknown(\(CBPeripheralManager.authorizationStatus().rawValue))"
        }
      }
    #endif
    return "notRequired"
  }

  func stopAdvertising() throws {
    advertiseGeneration &+= 1
    advertiseRequest = nil
    advertisementData = nil
    advertisedCharacteristic = nil
    advertisedServiceUuid = nil
    advertisedCharacteristicUuid = nil
    pendingPeripheralUpdates.removeAll()
    peripheralManager?.stopAdvertising()
    peripheralManager?.removeAllServices()

    for pathId in Array(peripheralPaths.keys) {
      markPeripheralPath(pathId: pathId, state: .disconnected, canKeep: false, error: nil)
    }
  }

  func startScan(request: BleScanRequest) throws {
    ensureManagers()
    guard let centralManager = centralManager else {
      throw flutterError("notInitialized", "BLE central manager is not initialized.")
    }

    scanTimer?.invalidate()
    scanTimer = nil
    scanRequest = request

    if centralManager.state == .poweredOn {
      startScanIfReady()
    } else {
      log("Deferring scan until central manager is powered on (currentState=\(describe(centralManager.state))).")
    }

    if request.timeoutMs > 0 {
      scanTimer = Timer.scheduledTimer(withTimeInterval: TimeInterval(request.timeoutMs) / 1000.0, repeats: false) { [weak self] _ in
        guard let self = self else { return }
        try? self.stopScan()
      }
    }
  }

  func stopScan() throws {
    scanTimer?.invalidate()
    scanTimer = nil
    scanRequest = nil
    centralManager?.stopScan()
    log("Scan stopped")
  }

  func connect(request: BleConnectRequest) throws -> BlePath {
    ensureManagers()
    guard let centralManager = centralManager else {
      throw flutterError("notInitialized", "BLE central manager is not initialized.")
    }
    guard centralManager.state == .poweredOn else {
      throw flutterError("adapterOff", "Bluetooth must be powered on to connect.")
    }

    guard let peripheral = lookupPeripheral(remoteId: request.remoteId) else {
      throw flutterError("notFound", "Peripheral not found for remoteId \(request.remoteId).")
    }

    let pathId = Self.centralPathId(for: peripheral)
    knownPeripherals[peripheral.identifier.uuidString] = peripheral
    peripheral.delegate = self

    let serviceUuid = CBUUID(string: request.serviceUuid)
    let characteristicUuid = CBUUID(string: request.characteristicUuid)
    let current = centralPaths[pathId]
    centralPaths[pathId] = CentralPathState(
      pathId: pathId,
      platformDeviceId: peripheral.identifier.uuidString,
      peripheral: peripheral,
      serviceUuid: serviceUuid,
      characteristicUuid: characteristicUuid,
      characteristic: current?.characteristic,
      state: peripheral.state == .connected ? .connected : .connecting,
      rssi: current?.rssi,
      mtu: current?.mtu ?? 23,
      notificationsRequested: request.subscribeToNotifications,
      isSubscribed: current?.isSubscribed ?? false,
      error: nil
    )
    emitPath(path(forCentralPathId: pathId))

    if peripheral.state == .connected {
      peripheral.discoverServices([serviceUuid])
    } else {
      centralManager.connect(peripheral, options: [
        CBConnectPeripheralOptionNotifyOnConnectionKey: true,
        CBConnectPeripheralOptionNotifyOnDisconnectionKey: true,
        CBConnectPeripheralOptionNotifyOnNotificationKey: true,
      ])
    }

    scheduleConnectTimeout(pathId: pathId, timeoutMs: request.timeoutMs)
    return path(forCentralPathId: pathId)
  }

  func disconnect(request: BleDisconnectRequest) throws {
    if request.pathId.hasPrefix("central:") {
      guard let path = centralPaths[request.pathId] else { return }
      connectTimers[request.pathId]?.invalidate()
      connectTimers.removeValue(forKey: request.pathId)
      stopCentralRssiPoll(pathId: request.pathId)
      pendingCentralWrites.removeValue(forKey: request.pathId)
      centralManager?.cancelPeripheralConnection(path.peripheral)
      markCentralPath(pathId: request.pathId, state: .disconnected, canKeep: !request.forget, error: nil)
      return
    }

    if request.pathId.hasPrefix("peripheral:") {
      pendingPeripheralUpdates.removeAll { $0.pathId == request.pathId }
      markPeripheralPath(pathId: request.pathId, state: .disconnected, canKeep: !request.forget, error: nil)
    }
  }

  func send(request: BleSendRequest) throws {
    if request.pathId.hasPrefix("central:") {
      try sendToPeripheral(request: request)
      return
    }
    if request.pathId.hasPrefix("peripheral:") {
      try sendToCentral(request: request)
      return
    }
    throw flutterError("invalidPath", "Unknown BLE path id \(request.pathId).")
  }

  func paths() throws -> [BlePath] {
    let central = centralPaths.keys.sorted().map { path(forCentralPathId: $0) }
    let peripheral = peripheralPaths.keys.sorted().map { path(forPeripheralPathId: $0) }
    return central + peripheral
  }

  func dispose() throws {
    scanTimer?.invalidate()
    scanTimer = nil
    connectTimers.values.forEach { $0.invalidate() }
    connectTimers.removeAll()
    rssiPollTimers.values.forEach { $0.invalidate() }
    rssiPollTimers.removeAll()

    for pathId in Array(centralPaths.keys) {
      if let path = centralPaths[pathId] {
        centralManager?.cancelPeripheralConnection(path.peripheral)
      }
      markCentralPath(pathId: pathId, state: .disconnected, canKeep: false, error: nil)
    }
    for pathId in Array(peripheralPaths.keys) {
      markPeripheralPath(pathId: pathId, state: .disconnected, canKeep: false, error: nil)
    }

    centralManager?.stopScan()
    peripheralManager?.stopAdvertising()
    peripheralManager?.removeAllServices()
    centralManager?.delegate = nil
    peripheralManager?.delegate = nil

    centralManager = nil
    peripheralManager = nil
    advertisedCharacteristic = nil
    advertisedServiceUuid = nil
    advertisedCharacteristicUuid = nil
    advertiseRequest = nil
    advertisementData = nil
    scanRequest = nil
    knownPeripherals.removeAll()
    centralPaths.removeAll()
    peripheralPaths.removeAll()
    pendingCentralWrites.removeAll()
    pendingPeripheralUpdates.removeAll()
    initialized = false
    lastAdapterState = .unknown
  }

  private func ensureManagers(options: BleInitializeOptions? = nil) {
    if centralManager != nil && peripheralManager != nil { return }

    let showPowerAlert = options?.showPowerAlert ?? true
    let restoreState = options?.restoreState ?? false

    var centralOptions: [String: Any] = [
      CBCentralManagerOptionShowPowerAlertKey: showPowerAlert
    ]
    var peripheralOptions: [String: Any] = [
      CBPeripheralManagerOptionShowPowerAlertKey: showPowerAlert
    ]

    if restoreState {
      centralOptions[CBCentralManagerOptionRestoreIdentifierKey] = Self.centralRestoreIdentifier
      peripheralOptions[CBPeripheralManagerOptionRestoreIdentifierKey] = Self.peripheralRestoreIdentifier
    }

    if centralManager == nil {
      centralManager = CBCentralManager(delegate: self, queue: nil, options: centralOptions)
    }
    if peripheralManager == nil {
      peripheralManager = CBPeripheralManager(delegate: self, queue: nil, options: peripheralOptions)
    }
  }

  private func configurePeripheralService() {
    guard let peripheralManager = peripheralManager,
          let request = advertiseRequest,
          let serviceUuid = advertisedServiceUuid,
          let characteristicUuid = advertisedCharacteristicUuid else {
      log("configurePeripheralService: missing prerequisites (manager=\(peripheralManager != nil) request=\(advertiseRequest != nil))")
      return
    }

    peripheralManager.stopAdvertising()
    peripheralManager.removeAllServices()
    pendingPeripheralUpdates.removeAll()
    for pathId in Array(peripheralPaths.keys) {
      markPeripheralPath(pathId: pathId, state: .disconnected, canKeep: false, error: nil)
    }

    let permissions: CBAttributePermissions = request.bondless
      ? [.readable, .writeable]
      : [.readEncryptionRequired, .writeEncryptionRequired]
    let characteristic = CBMutableCharacteristic(
      type: characteristicUuid,
      properties: [.notify, .write, .writeWithoutResponse, .read],
      value: nil,
      permissions: permissions
    )

    let service = CBMutableService(type: serviceUuid, primary: true)
    service.characteristics = [characteristic]
    advertisedCharacteristic = characteristic

    log("Adding GATT service \(serviceUuid.uuidString) characteristic \(characteristicUuid.uuidString)")
    peripheralManager.add(service)
  }

  private func buildAdvertisementData() -> [String: Any] {
    guard let request = advertiseRequest,
          let serviceUuid = advertisedServiceUuid else { return [:] }

    // iOS deliberately encodes 128-bit service UUIDs in a private "overflow"
    // area when the primary advertise packet (31 bytes) is full. The
    // overflow area is decodable only by other iOS apps that pre-register
    // the exact UUID with `scanForPeripherals(withServices:)`. Including a
    // local name (15+ bytes) on top of a 128-bit UUID overflows the packet
    // and effectively makes the broadcast invisible to external scanners
    // (Android, generic BLE explorers). Grassroots carries identity in the
    // post-connection ANNOUNCE, so we omit local name + manufacturer data
    // from the iOS advertisement to keep the UUID in the primary packet.
    let data: [String: Any] = [
      CBAdvertisementDataServiceUUIDsKey: [serviceUuid]
    ]

    if let localName = request.localName, !localName.isEmpty {
      log("Ignoring localName='\(localName)' on iOS — keeping 128-bit service UUID in the primary advertise packet so external scanners can see it.")
    }
    if request.manufacturerData != nil || request.manufacturerId != nil {
      log("iOS peripheral advertising does not support manufacturer data via CoreBluetooth; ignored.")
    }

    return data
  }

  private func startScanIfReady() {
    guard let centralManager = centralManager,
          let request = scanRequest,
          centralManager.state == .poweredOn else { return }

    centralManager.stopScan()

    let requestedServices = request.serviceUuids.compactMap { value -> CBUUID? in
      guard let value = value, !value.isEmpty else { return nil }
      return CBUUID(string: value)
    }

    let services = requestedServices.isEmpty ? nil : requestedServices
    let options: [String: Any] = [
      CBCentralManagerScanOptionAllowDuplicatesKey: request.allowDuplicates
    ]
    centralManager.scanForPeripherals(withServices: services, options: options)
  }

  private func lookupPeripheral(remoteId: String) -> CBPeripheral? {
    if let peripheral = knownPeripherals[remoteId] {
      return peripheral
    }
    guard let uuid = UUID(uuidString: remoteId) else {
      return nil
    }
    let peripherals = centralManager?.retrievePeripherals(withIdentifiers: [uuid]) ?? []
    if let peripheral = peripherals.first {
      knownPeripherals[peripheral.identifier.uuidString] = peripheral
      return peripheral
    }
    return nil
  }

  private func sendToPeripheral(request: BleSendRequest) throws {
    guard let path = centralPaths[request.pathId] else {
      throw flutterError("notFound", "Central path \(request.pathId) is not known.")
    }
    guard path.peripheral.state == .connected else {
      throw flutterError("notConnected", "Peripheral \(path.platformDeviceId) is not connected.")
    }
    guard let characteristic = path.characteristic else {
      throw flutterError("notReady", "Path \(request.pathId) has no discovered characteristic.")
    }

    let value = request.value.data
    let writeType: CBCharacteristicWriteType = request.writeMode == .withoutResponse ? .withoutResponse : .withResponse
    let properties = characteristic.properties
    if writeType == .withoutResponse && !properties.contains(.writeWithoutResponse) {
      throw flutterError("unsupportedWriteMode", "Characteristic does not support writeWithoutResponse.")
    }
    if writeType == .withResponse && !properties.contains(.write) {
      throw flutterError("unsupportedWriteMode", "Characteristic does not support write with response.")
    }

    let maxLength = path.peripheral.maximumWriteValueLength(for: writeType)
    if value.count > maxLength {
      throw flutterError("valueTooLarge", "Value length \(value.count) exceeds iOS write limit \(maxLength).")
    }

    if writeType == .withoutResponse && !path.peripheral.canSendWriteWithoutResponse {
      try enqueueCentralWrite(pathId: request.pathId, value: value, characteristic: characteristic)
      updateCentralCanSend(pathId: request.pathId)
      return
    }

    path.peripheral.writeValue(value, for: characteristic, type: writeType)
    updateCentralCanSend(pathId: request.pathId)
  }

  private func sendToCentral(request: BleSendRequest) throws {
    guard let path = peripheralPaths[request.pathId] else {
      throw flutterError("notFound", "Peripheral path \(request.pathId) is not known.")
    }
    guard path.isSubscribed else {
      throw flutterError("notReady", "Central \(path.platformDeviceId) is not subscribed.")
    }
    guard let peripheralManager = peripheralManager,
          let characteristic = advertisedCharacteristic else {
      throw flutterError("notAdvertising", "Peripheral characteristic is not available.")
    }

    let value = request.value.data
    let maxLength = path.central.maximumUpdateValueLength
    if value.count > maxLength {
      throw flutterError("valueTooLarge", "Value length \(value.count) exceeds iOS notify limit \(maxLength).")
    }

    let didSend = peripheralManager.updateValue(value, for: characteristic, onSubscribedCentrals: [path.central])
    if !didSend {
      try enqueuePeripheralUpdate(PendingPeripheralUpdate(pathId: request.pathId, value: value, central: path.central))
    }
    updatePeripheralCanSend(pathId: request.pathId)
  }

  private func enqueueCentralWrite(pathId: String, value: Data, characteristic: CBCharacteristic) throws {
    var queue = pendingCentralWrites[pathId] ?? []
    if queue.count >= Self.pendingQueueCap {
      throw flutterError("writeQueueFull", "Central write queue is full for \(pathId).")
    }
    queue.append(PendingCentralWrite(value: value, characteristic: characteristic))
    pendingCentralWrites[pathId] = queue
  }

  private func drainCentralWrites(for peripheral: CBPeripheral) {
    let pathId = Self.centralPathId(for: peripheral)
    guard var queue = pendingCentralWrites[pathId], !queue.isEmpty else {
      updateCentralCanSend(pathId: pathId)
      return
    }

    while peripheral.canSendWriteWithoutResponse && !queue.isEmpty {
      let next = queue.removeFirst()
      peripheral.writeValue(next.value, for: next.characteristic, type: .withoutResponse)
    }

    pendingCentralWrites[pathId] = queue.isEmpty ? nil : queue
    updateCentralCanSend(pathId: pathId)
  }

  private func enqueuePeripheralUpdate(_ update: PendingPeripheralUpdate) throws {
    if pendingPeripheralUpdates.count >= Self.pendingQueueCap {
      throw flutterError("notifyQueueFull", "Peripheral notify queue is full.")
    }
    pendingPeripheralUpdates.append(update)
  }

  private func drainPeripheralUpdates() {
    guard let peripheralManager = peripheralManager,
          let characteristic = advertisedCharacteristic,
          !pendingPeripheralUpdates.isEmpty else { return }

    while !pendingPeripheralUpdates.isEmpty {
      let next = pendingPeripheralUpdates.removeFirst()
      guard let path = peripheralPaths[next.pathId], path.isSubscribed else {
        continue
      }
      let didSend = peripheralManager.updateValue(next.value, for: characteristic, onSubscribedCentrals: [next.central])
      if !didSend {
        pendingPeripheralUpdates.insert(next, at: 0)
        break
      }
    }

    for pathId in Array(peripheralPaths.keys) {
      updatePeripheralCanSend(pathId: pathId)
    }
  }

  private func scheduleConnectTimeout(pathId: String, timeoutMs: Int64) {
    connectTimers[pathId]?.invalidate()
    connectTimers.removeValue(forKey: pathId)
    guard timeoutMs > 0 else { return }

    connectTimers[pathId] = Timer.scheduledTimer(withTimeInterval: TimeInterval(timeoutMs) / 1000.0, repeats: false) { [weak self] _ in
      guard let self = self,
            let path = self.centralPaths[pathId],
            path.state != .ready else { return }

      self.centralManager?.cancelPeripheralConnection(path.peripheral)
      self.pendingCentralWrites.removeValue(forKey: pathId)
      self.markCentralPath(pathId: pathId, state: .failed, canKeep: true, error: "Connection timed out.")
    }
  }

  private func finishConnectIfReady(pathId: String) {
    guard var path = centralPaths[pathId], path.characteristic != nil else { return }
    if path.notificationsRequested && !path.isSubscribed {
      return
    }
    path.state = .ready
    path.error = nil
    path.mtu = centralMtu(for: path.peripheral)
    centralPaths[pathId] = path
    connectTimers[pathId]?.invalidate()
    connectTimers.removeValue(forKey: pathId)
    emitPath(self.path(forCentralPathId: pathId))
    // Now that the central path is sendable, start polling RSSI. The
    // first read fires immediately so the displayed value transitions
    // from scan-time RSSI to live RSSI without a 10 s gap.
    startCentralRssiPoll(pathId: pathId)
  }

  /// Start (or restart) periodic `readRSSI()` polling on the given central
  /// path. Safe to call multiple times — any existing timer for the same
  /// pathId is invalidated first.
  private func startCentralRssiPoll(pathId: String) {
    guard let path = centralPaths[pathId] else { return }
    stopCentralRssiPoll(pathId: pathId)
    path.peripheral.readRSSI()
    rssiPollTimers[pathId] = Timer.scheduledTimer(
      withTimeInterval: Self.rssiPollInterval,
      repeats: true
    ) { [weak self] timer in
      guard let self = self else {
        timer.invalidate()
        return
      }
      guard let path = self.centralPaths[pathId] else {
        timer.invalidate()
        self.rssiPollTimers.removeValue(forKey: pathId)
        return
      }
      // Only poll while the central path is usable; if it has gone
      // disconnected or failed, stop the timer entirely.
      guard path.state == .ready
        || path.state == .connected
        || path.state == .subscribed else {
        timer.invalidate()
        self.rssiPollTimers.removeValue(forKey: pathId)
        return
      }
      path.peripheral.readRSSI()
    }
  }

  private func stopCentralRssiPoll(pathId: String) {
    rssiPollTimers[pathId]?.invalidate()
    rssiPollTimers.removeValue(forKey: pathId)
  }

  private func markCentralPath(pathId: String, state: BlePathState, canKeep: Bool, error: String?) {
    guard var path = centralPaths[pathId] else { return }
    path.state = state
    path.error = error
    if state == .disconnected || state == .failed {
      path.isSubscribed = false
      path.characteristic = nil
      // The link is no longer usable for readRSSI — stop the poll loop
      // so we don't keep firing reads against a dead peripheral.
      stopCentralRssiPoll(pathId: pathId)
    }
    centralPaths[pathId] = path
    emitPath(self.path(forCentralPathId: pathId))
    if !canKeep {
      centralPaths.removeValue(forKey: pathId)
    }
  }

  private func markPeripheralPath(pathId: String, state: BlePathState, canKeep: Bool, error: String?) {
    guard var path = peripheralPaths[pathId] else { return }
    path.state = state
    path.error = error
    if state == .disconnected || state == .failed {
      path.isSubscribed = false
    }
    peripheralPaths[pathId] = path
    emitPath(self.path(forPeripheralPathId: pathId))
    if !canKeep {
      peripheralPaths.removeValue(forKey: pathId)
    }
  }

  private func updateCentralCanSend(pathId: String) {
    guard centralPaths[pathId] != nil else { return }
    emitPath(path(forCentralPathId: pathId))
  }

  private func updatePeripheralCanSend(pathId: String) {
    guard peripheralPaths[pathId] != nil else { return }
    emitPath(path(forPeripheralPathId: pathId))
  }

  private func upsertPeripheralPath(for central: CBCentral, state: BlePathState, subscribed: Bool, error: String? = nil) {
    let pathId = Self.peripheralPathId(for: central)
    let existing = peripheralPaths[pathId]
    let isSubscribed = subscribed || (existing?.isSubscribed ?? false)
    peripheralPaths[pathId] = PeripheralPathState(
      pathId: pathId,
      platformDeviceId: central.identifier.uuidString,
      central: central,
      serviceUuid: advertisedServiceUuid,
      characteristicUuid: advertisedCharacteristicUuid,
      state: state,
      rssi: existing?.rssi,
      mtu: max(23, Int64(central.maximumUpdateValueLength + 3)),
      isSubscribed: isSubscribed,
      error: error
    )
    emitPath(path(forPeripheralPathId: pathId))
  }

  private func path(forCentralPathId pathId: String) -> BlePath {
    guard let path = centralPaths[pathId] else {
      return emptyPath(pathId: pathId, role: .central, state: .disconnected)
    }
    return BlePath(
      pathId: path.pathId,
      role: .central,
      state: path.state,
      platformDeviceId: path.platformDeviceId,
      serviceUuid: path.serviceUuid?.uuidString,
      characteristicUuid: path.characteristicUuid?.uuidString,
      rssi: path.rssi,
      mtu: centralMtu(for: path.peripheral),
      canSend: centralCanSend(path),
      error: path.error
    )
  }

  private func path(forPeripheralPathId pathId: String) -> BlePath {
    guard let path = peripheralPaths[pathId] else {
      return emptyPath(pathId: pathId, role: .peripheral, state: .disconnected)
    }
    return BlePath(
      pathId: path.pathId,
      role: .peripheral,
      state: path.state,
      platformDeviceId: path.platformDeviceId,
      serviceUuid: path.serviceUuid?.uuidString,
      characteristicUuid: path.characteristicUuid?.uuidString,
      rssi: path.rssi,
      mtu: path.mtu,
      canSend: peripheralCanSend(path),
      error: path.error
    )
  }

  private func emptyPath(pathId: String, role: BleRole, state: BlePathState) -> BlePath {
    BlePath(
      pathId: pathId,
      role: role,
      state: state,
      platformDeviceId: nil,
      serviceUuid: nil,
      characteristicUuid: nil,
      rssi: nil,
      mtu: 23,
      canSend: false,
      error: nil
    )
  }

  private func centralCanSend(_ path: CentralPathState) -> Bool {
    guard path.state != .disconnected,
          path.state != .failed,
          let characteristic = path.characteristic else { return false }
    if path.notificationsRequested && !path.isSubscribed {
      return false
    }
    if characteristic.properties.contains(.write) {
      return true
    }
    if characteristic.properties.contains(.writeWithoutResponse) {
      return path.peripheral.canSendWriteWithoutResponse
    }
    return false
  }

  private func peripheralCanSend(_ path: PeripheralPathState) -> Bool {
    guard path.state != .disconnected,
          path.state != .failed,
          path.isSubscribed,
          advertisedCharacteristic != nil else { return false }
    return !pendingPeripheralUpdates.contains { $0.pathId == path.pathId }
  }

  private func centralMtu(for peripheral: CBPeripheral) -> Int64 {
    if peripheral.state != .connected {
      return 23
    }
    return max(23, Int64(peripheral.maximumWriteValueLength(for: .withoutResponse) + 3))
  }

  private func advertisementMatchesScanRequest(_ advertisementData: [String: Any]) -> Bool {
    guard let request = scanRequest else { return false }

    let advertisedServices = advertisementServiceUuids(from: advertisementData)
    let lowerServices = advertisedServices.map { $0.lowercased() }
    // `CBUUID.uuidString` returns 128-bit UUIDs in dashed form
    // ("84c40316-0871-e5ad-…"), while callers typically pass `serviceUuidPrefix`
    // as compact hex ("84c403160871e5ad"). Normalize both to dashless hex so
    // `hasPrefix` works regardless of how the caller wrote the prefix.
    let dashlessLowerServices = lowerServices.map {
      $0.replacingOccurrences(of: "-", with: "")
    }

    if let prefixRaw = request.serviceUuidPrefix?.lowercased(), !prefixRaw.isEmpty {
      let prefix = prefixRaw.replacingOccurrences(of: "-", with: "")
      guard dashlessLowerServices.contains(where: { $0.hasPrefix(prefix) }) else {
        return false
      }
    }

    let requestedServices = request.serviceUuids.compactMap { $0?.lowercased() }
    if !requestedServices.isEmpty {
      guard requestedServices.contains(where: { lowerServices.contains($0) }) else {
        return false
      }
    }

    return true
  }

  private func advertisementServiceUuids(from advertisementData: [String: Any]) -> [String] {
    var services: [CBUUID] = []
    if let values = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] {
      services.append(contentsOf: values)
    }
    if let values = advertisementData[CBAdvertisementDataOverflowServiceUUIDsKey] as? [CBUUID] {
      services.append(contentsOf: values)
    }
    if let values = advertisementData[CBAdvertisementDataSolicitedServiceUUIDsKey] as? [CBUUID] {
      services.append(contentsOf: values)
    }
    return services.map { $0.uuidString }
  }

  private func mapState(_ state: CBManagerState) -> BleAdapterState {
    switch state {
    case .poweredOn:
      return .poweredOn
    case .poweredOff:
      return .poweredOff
    case .unauthorized:
      return .unauthorized
    case .unsupported:
      return .unsupported
    case .resetting, .unknown:
      return .unknown
    @unknown default:
      return .unknown
    }
  }

  private func emitAdapterStateIfChanged(force: Bool = false) {
    let state = (try? adapterState()) ?? .unknown
    guard force || state != lastAdapterState else { return }
    lastAdapterState = state
    flutterApi.onAdapterStateChanged(state: state) { _ in }

    if state != .poweredOn {
      for pathId in Array(centralPaths.keys) {
        markCentralPath(pathId: pathId, state: .disconnected, canKeep: true, error: nil)
      }
      for pathId in Array(peripheralPaths.keys) {
        markPeripheralPath(pathId: pathId, state: .disconnected, canKeep: true, error: nil)
      }
      pendingCentralWrites.removeAll()
      pendingPeripheralUpdates.removeAll()
    }
  }

  private func emitPath(_ path: BlePath) {
    flutterApi.onPathChanged(path: path) { _ in }
  }

  private func emitPayload(pathId: String, role: BleRole, value: Data, rssi: Int64?) {
    let payload = BlePayload(
      pathId: pathId,
      role: role,
      value: FlutterStandardTypedData(bytes: value),
      rssi: rssi
    )
    flutterApi.onPayloadReceived(payload: payload) { _ in }
  }

  private func log(_ message: String) {
    // Mirror Android: always print to the OS log (visible in `flutter run`
    // and Console.app) so diagnostic events aren't silently dropped when
    // verboseLogging is off. Only forward to Flutter when the consumer
    // explicitly opted in.
    NSLog("[GrassrootsBluetoothPlugin] %@", message)
    guard verboseLogging else { return }
    flutterApi.onLog(message: message) { _ in }
  }

  /// Decode a CoreBluetooth disconnect/connect error into a stable, raw
  /// string that includes both the CBError numeric code and Apple's
  /// localizedDescription. The raw code is what we need to triage drops;
  /// the localized description is what humans recognise.
  private func describeDisconnectError(_ error: Error?) -> String {
    guard let error = error else { return "error=nil (clean disconnect)" }
    let ns = error as NSError
    let name: String
    if ns.domain == CBErrorDomain, let code = CBError.Code(rawValue: ns.code) {
      switch code {
      case .unknown: name = "CBError.unknown"
      case .invalidParameters: name = "CBError.invalidParameters"
      case .invalidHandle: name = "CBError.invalidHandle"
      case .notConnected: name = "CBError.notConnected"
      case .outOfSpace: name = "CBError.outOfSpace"
      case .operationCancelled: name = "CBError.operationCancelled"
      case .connectionTimeout: name = "CBError.connectionTimeout (supervision timeout)"
      case .peripheralDisconnected: name = "CBError.peripheralDisconnected (remote side dropped link)"
      case .uuidNotAllowed: name = "CBError.uuidNotAllowed"
      case .alreadyAdvertising: name = "CBError.alreadyAdvertising"
      case .connectionFailed: name = "CBError.connectionFailed"
      case .connectionLimitReached: name = "CBError.connectionLimitReached"
      case .unkownDevice: name = "CBError.unknownDevice"
      case .operationNotSupported: name = "CBError.operationNotSupported"
      case .peerRemovedPairingInformation: name = "CBError.peerRemovedPairingInformation"
      case .encryptionTimedOut: name = "CBError.encryptionTimedOut"
      case .tooManyLEPairedDevices: name = "CBError.tooManyLEPairedDevices"
      @unknown default: name = "CBError.unknown(\(ns.code))"
      }
    } else {
      name = "\(ns.domain)"
    }
    return "error=\(name) code=\(ns.code) desc=\(ns.localizedDescription)"
  }

  private func flutterError(_ code: String, _ message: String) -> FlutterError {
    FlutterError(code: code, message: message, details: nil)
  }

  private static func centralPathId(for peripheral: CBPeripheral) -> String {
    "central:\(peripheral.identifier.uuidString)"
  }

  private static func peripheralPathId(for central: CBCentral) -> String {
    "peripheral:\(central.identifier.uuidString)"
  }
}

extension GrassrootsBluetoothDarwin: CBCentralManagerDelegate {
  func centralManagerDidUpdateState(_ central: CBCentralManager) {
    emitAdapterStateIfChanged()
    if central.state == .poweredOn {
      startScanIfReady()
    } else {
      central.stopScan()
    }
  }

  /// Full state-restoration contract for the central role.
  /// CoreBluetooth invokes this exactly once after the system relaunches us
  /// for a BLE event we registered to receive in the background. The dict
  /// contains everything the system kept on our behalf:
  ///   - `RestoredStatePeripheralsKey`: peripherals that were connected or
  ///     being connected at the moment of suspension.
  ///   - `RestoredStateScanServicesKey`: services we were scanning for.
  ///   - `RestoredStateScanOptionsKey`: scan options (e.g. allowDuplicates).
  ///
  /// The system has already re-attached the underlying scan + each peripheral
  /// connection — we must NOT re-issue `scanForPeripherals` / `connect`.
  /// What we MUST do: re-populate our in-process tables so subsequent
  /// delegate callbacks (didDiscover, didConnect, didUpdateValue, …) find
  /// the path entries they expect, and emit path-changed events so the
  /// Dart consumer's Redux mirror reflects the restored topology.
  func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
    log("Central willRestoreState: keys=\(dict.keys.sorted())")

    // Reconstruct scanRequest from the system-provided service list +
    // options so `advertisementMatchesScanRequest` keeps filtering and
    // `seenAdvertisements`-style state has somewhere to live.
    if let scanServices =
      dict[CBCentralManagerRestoredStateScanServicesKey] as? [CBUUID]
    {
      let scanOptions =
        dict[CBCentralManagerRestoredStateScanOptionsKey] as? [String: Any] ?? [:]
      let allowDuplicates =
        (scanOptions[CBCentralManagerScanOptionAllowDuplicatesKey] as? NSNumber)?.boolValue ?? false
      scanRequest = BleScanRequest(
        serviceUuidPrefix: nil,
        serviceUuids: scanServices.map { Optional($0.uuidString) },
        timeoutMs: 0,
        allowDuplicates: allowDuplicates,
        androidScanMode: 2
      )
      log("Restored scanRequest: services=\(scanServices.map { $0.uuidString }) allowDuplicates=\(allowDuplicates)")
    }

    // Re-attach delegates and re-populate centralPaths for every restored
    // peripheral. The system tells us their current state via
    // `peripheral.state`; we project that into our path lifecycle.
    let peripherals =
      dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
    for peripheral in peripherals {
      peripheral.delegate = self
      knownPeripherals[peripheral.identifier.uuidString] = peripheral
      let pathId = Self.centralPathId(for: peripheral)
      let mappedState: BlePathState
      switch peripheral.state {
      case .connected: mappedState = .connected
      case .connecting: mappedState = .connecting
      case .disconnecting: mappedState = .stale
      case .disconnected: mappedState = .disconnected
      @unknown default: mappedState = .connecting
      }
      centralPaths[pathId] = CentralPathState(
        pathId: pathId,
        platformDeviceId: peripheral.identifier.uuidString,
        peripheral: peripheral,
        serviceUuid: centralPaths[pathId]?.serviceUuid,
        characteristicUuid: centralPaths[pathId]?.characteristicUuid,
        characteristic: centralPaths[pathId]?.characteristic,
        state: mappedState,
        rssi: centralPaths[pathId]?.rssi,
        mtu: centralMtu(for: peripheral),
        notificationsRequested: centralPaths[pathId]?.notificationsRequested ?? true,
        isSubscribed: centralPaths[pathId]?.isSubscribed ?? false,
        error: nil
      )
      emitPath(path(forCentralPathId: pathId))
    }
    log("Restored \(peripherals.count) central paths")
  }

  func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
    // Apple uses RSSI = 127 (NSNotFound) to signal "value cannot be read"
    // when CoreBluetooth replays a peripheral discovery without a fresh
    // hardware measurement (e.g. cache replays after a brief scan
    // suspension). Real BLE RSSI is always negative dBm. Drop the
    // discovery entirely — the next scan tick with a real measurement
    // will deliver a usable advertisement.
    if RSSI.intValue >= 0 {
      // Diagnostic — confirms how often we drop a discovery and from
      // which peripheral. Real-pair drops here imply CoreBluetooth is
      // replaying without a fresh measurement for that peer.
      log("Dropped discovery with non-real RSSI: "
        + "remoteId=\(peripheral.identifier.uuidString) rssi=\(RSSI.intValue)")
      return
    }

    let remoteId = peripheral.identifier.uuidString
    let rawServiceUuids = advertisementServiceUuids(from: advertisementData)
    let advertisedName = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name ?? "<unnamed>"

    if !advertisementMatchesScanRequest(advertisementData) {
      // Only log rejections from devices that *look like* Grassroots peers —
      // i.e. they advertise at least one service UUID matching our prefix.
      // Without this gate every nearby AirPod / watch / beacon produces a
      // line, drowning the actual diagnostic signal. The interesting case
      // is "advertisement has Grassroots-shaped UUID but our exact-match
      // filter still rejected it"; everything else is environmental noise.
      let prefix = (scanRequest?.serviceUuidPrefix?.lowercased() ?? "")
        .replacingOccurrences(of: "-", with: "")
      let looksGrassroots = !prefix.isEmpty
        && rawServiceUuids.contains(where: {
          $0.lowercased()
            .replacingOccurrences(of: "-", with: "")
            .hasPrefix(prefix)
        })
      if looksGrassroots {
        log("Scan: rejected advertisement remoteId=\(remoteId) name=\(advertisedName) "
          + "rssi=\(RSSI.int64Value) services=[\(rawServiceUuids.joined(separator: ","))] "
          + "(prefix=\(scanRequest?.serviceUuidPrefix ?? "<none>"))")
      }
      return
    }

    knownPeripherals[remoteId] = peripheral

    let serviceUuids = advertisementServiceUuids(from: advertisementData)
    let localName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
    let txPower = advertisementData[CBAdvertisementDataTxPowerLevelKey] as? NSNumber
    let manufacturerData = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data
    let connectable = (advertisementData[CBAdvertisementDataIsConnectable] as? NSNumber)?.boolValue ?? true

    let advertisement = BleAdvertisement(
      remoteId: remoteId,
      platformName: peripheral.name,
      advertisedName: localName,
      serviceUuids: serviceUuids.map { Optional($0) },
      rssi: RSSI.int64Value,
      connectable: connectable,
      txPower: txPower?.int64Value,
      manufacturerData: manufacturerData.map { FlutterStandardTypedData(bytes: $0) }
    )
    flutterApi.onAdvertisement(advertisement: advertisement) { _ in }

    let pathId = Self.centralPathId(for: peripheral)
    if centralPaths[pathId] == nil {
      centralPaths[pathId] = CentralPathState(
        pathId: pathId,
        platformDeviceId: remoteId,
        peripheral: peripheral,
        serviceUuid: serviceUuids.first.map { CBUUID(string: $0) },
        characteristicUuid: nil,
        characteristic: nil,
        state: .discovered,
        rssi: RSSI.int64Value,
        mtu: 23,
        notificationsRequested: false,
        isSubscribed: false,
        error: nil
      )
    } else {
      centralPaths[pathId]?.rssi = RSSI.int64Value
    }
    emitPath(path(forCentralPathId: pathId))
  }

  func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
    let pathId = Self.centralPathId(for: peripheral)
    if var path = centralPaths[pathId] {
      path.state = .connected
      path.error = nil
      path.mtu = centralMtu(for: peripheral)
      centralPaths[pathId] = path
      emitPath(self.path(forCentralPathId: pathId))

      if let serviceUuid = path.serviceUuid {
        peripheral.discoverServices([serviceUuid])
      } else {
        peripheral.discoverServices(nil)
      }
    }
  }

  func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
    let pathId = Self.centralPathId(for: peripheral)
    let previousState = centralPaths[pathId]?.state
    log(
      "BLE central didDisconnectPeripheral: pathId=\(pathId) " +
      "uuid=\(peripheral.identifier.uuidString) previousState=\(previousState.map { String(describing: $0) } ?? "nil") " +
      "\(describeDisconnectError(error))"
    )
    pendingCentralWrites.removeValue(forKey: pathId)
    connectTimers[pathId]?.invalidate()
    connectTimers.removeValue(forKey: pathId)
    markCentralPath(pathId: pathId, state: .disconnected, canKeep: true, error: error?.localizedDescription)
  }

  func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
    let pathId = Self.centralPathId(for: peripheral)
    let previousState = centralPaths[pathId]?.state
    log(
      "BLE central didFailToConnect: pathId=\(pathId) " +
      "uuid=\(peripheral.identifier.uuidString) previousState=\(previousState.map { String(describing: $0) } ?? "nil") " +
      "\(describeDisconnectError(error))"
    )
    pendingCentralWrites.removeValue(forKey: pathId)
    connectTimers[pathId]?.invalidate()
    connectTimers.removeValue(forKey: pathId)
    markCentralPath(pathId: pathId, state: .failed, canKeep: true, error: error?.localizedDescription)
  }
}

extension GrassrootsBluetoothDarwin: CBPeripheralDelegate {
  func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
    let pathId = Self.centralPathId(for: peripheral)
    guard var path = centralPaths[pathId] else { return }
    if let error = error {
      markCentralPath(pathId: pathId, state: .failed, canKeep: true, error: error.localizedDescription)
      return
    }

    guard let services = peripheral.services, !services.isEmpty else {
      markCentralPath(pathId: pathId, state: .failed, canKeep: true, error: "No services discovered.")
      return
    }

    let service: CBService?
    if let requested = path.serviceUuid {
      service = services.first { $0.uuid == requested }
    } else {
      service = services.first
      path.serviceUuid = service?.uuid
      centralPaths[pathId] = path
    }

    guard let service = service else {
      markCentralPath(pathId: pathId, state: .failed, canKeep: true, error: "Requested service not found.")
      return
    }

    if let characteristicUuid = path.characteristicUuid {
      peripheral.discoverCharacteristics([characteristicUuid], for: service)
    } else {
      peripheral.discoverCharacteristics(nil, for: service)
    }
  }

  func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
    let pathId = Self.centralPathId(for: peripheral)
    guard var path = centralPaths[pathId] else { return }
    if let error = error {
      markCentralPath(pathId: pathId, state: .failed, canKeep: true, error: error.localizedDescription)
      return
    }

    let characteristic: CBCharacteristic?
    if let requested = path.characteristicUuid {
      characteristic = service.characteristics?.first { $0.uuid == requested }
    } else {
      characteristic = service.characteristics?.first
    }

    guard let characteristic = characteristic else {
      markCentralPath(pathId: pathId, state: .failed, canKeep: true, error: "Requested characteristic not found.")
      return
    }

    path.serviceUuid = service.uuid
    path.characteristicUuid = characteristic.uuid
    path.characteristic = characteristic
    path.state = .connected
    path.error = nil
    path.mtu = centralMtu(for: peripheral)
    centralPaths[pathId] = path
    emitPath(self.path(forCentralPathId: pathId))

    if path.notificationsRequested {
      guard characteristic.properties.contains(.notify) || characteristic.properties.contains(.indicate) else {
        markCentralPath(pathId: pathId, state: .failed, canKeep: true, error: "Characteristic does not support notifications.")
        return
      }
      peripheral.setNotifyValue(true, for: characteristic)
    } else {
      finishConnectIfReady(pathId: pathId)
    }
  }

  func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
    let pathId = Self.centralPathId(for: peripheral)
    guard var path = centralPaths[pathId] else { return }
    if let error = error {
      markCentralPath(pathId: pathId, state: .failed, canKeep: true, error: error.localizedDescription)
      return
    }

    path.isSubscribed = characteristic.isNotifying
    path.state = characteristic.isNotifying ? .subscribed : .connected
    path.error = nil
    centralPaths[pathId] = path
    emitPath(self.path(forCentralPathId: pathId))
    finishConnectIfReady(pathId: pathId)
  }

  func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
    let pathId = Self.centralPathId(for: peripheral)
    if let error = error {
      markCentralPath(pathId: pathId, state: .failed, canKeep: true, error: error.localizedDescription)
      return
    }
    guard let value = characteristic.value, !value.isEmpty else { return }
    emitPayload(pathId: pathId, role: .central, value: value, rssi: centralPaths[pathId]?.rssi)
  }

  func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
    let pathId = Self.centralPathId(for: peripheral)
    if let error = error {
      markCentralPath(pathId: pathId, state: .failed, canKeep: true, error: error.localizedDescription)
    } else {
      updateCentralCanSend(pathId: pathId)
    }
  }

  func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
    drainCentralWrites(for: peripheral)
  }

  /// CoreBluetooth's response to `peripheral.readRSSI()`. We use this both
  /// from the periodic `rssiPollTimers` and as the first read kicked off
  /// immediately when a central path becomes ready. On success it updates
  /// the cached path RSSI and emits a path-changed event so the Dart side
  /// (and the app's UI) see the fresh measurement.
  func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) {
    let pathId = Self.centralPathId(for: peripheral)
    guard var path = centralPaths[pathId] else { return }
    if error != nil {
      // Don't mark the path as failed on a single read error — RSSI reads
      // can transiently fail on a busy link. Just log to NSLog and let
      // the timer fire again on the next cycle.
      log("readRSSI error for \(pathId): \(error?.localizedDescription ?? "<nil>")")
      return
    }
    path.rssi = RSSI.int64Value
    centralPaths[pathId] = path
    emitPath(self.path(forCentralPathId: pathId))
  }

  func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
    let pathId = Self.centralPathId(for: peripheral)
    guard let path = centralPaths[pathId],
          let serviceUuid = path.serviceUuid,
          invalidatedServices.contains(where: { $0.uuid == serviceUuid }) else { return }
    peripheral.discoverServices([serviceUuid])
  }
}

extension GrassrootsBluetoothDarwin: CBPeripheralManagerDelegate {
  func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
    log("peripheralManagerDidUpdateState → \(describe(peripheral.state)) " +
        "authorization=\(describePeripheralAuthorization())")
    emitAdapterStateIfChanged()
    if peripheral.state == .poweredOn {
      if advertiseRequest != nil {
        log("peripheralManager poweredOn — running deferred configurePeripheralService")
        configurePeripheralService()
      } else {
        log("peripheralManager poweredOn — no advertise request pending")
      }
    } else {
      peripheral.stopAdvertising()
      advertisedCharacteristic = nil
      pendingPeripheralUpdates.removeAll()
      for pathId in Array(peripheralPaths.keys) {
        markPeripheralPath(pathId: pathId, state: .disconnected, canKeep: true, error: nil)
      }
    }
  }

  /// Full state-restoration contract for the peripheral role.
  /// Invoked exactly once after the system relaunches us. Dict contents:
  ///   - `RestoredStateServicesKey`: services we had registered on the
  ///     GATT server (with their characteristics + subscribed centrals).
  ///   - `RestoredStateAdvertisementDataKey`: advertisement data dict if
  ///     we were advertising. Presence implies the system has resumed
  ///     advertising on our behalf.
  ///
  /// We rebuild every piece of state needed for the rest of the plugin to
  /// behave as if startAdvertising had just been called by the consumer:
  /// `advertisedServiceUuid`, `advertisedCharacteristicUuid`,
  /// `advertisedCharacteristic`, `advertisementData`, `advertiseRequest`.
  /// We also re-emit peripheral path events for every central listed as
  /// subscribed in the restored characteristic so the Dart Redux mirror
  /// knows about live subscribers.
  func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
    log("Peripheral willRestoreState: keys=\(dict.keys.sorted())")

    let services =
      dict[CBPeripheralManagerRestoredStateServicesKey] as? [CBMutableService] ?? []
    let advData =
      dict[CBPeripheralManagerRestoredStateAdvertisementDataKey] as? [String: Any]

    // Pick a primary service to use as the advertised one. Prefer a service
    // whose UUID is referenced by the advertisement data; fall back to the
    // first restored service if not.
    let advertisedUuids =
      (advData?[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID]) ?? []
    let pickedService = services.first { advertisedUuids.contains($0.uuid) }
      ?? services.first

    if let service = pickedService {
      advertisedServiceUuid = service.uuid
      // Pick our well-known notify+write characteristic if present; else the
      // first writable one in the service.
      let restoredChar: CBMutableCharacteristic? =
        service.characteristics?.compactMap({ $0 as? CBMutableCharacteristic })
          .first(where: { $0.properties.contains(.notify) || $0.properties.contains(.write) })
      if let restoredChar = restoredChar {
        advertisedCharacteristic = restoredChar
        advertisedCharacteristicUuid = restoredChar.uuid
      }
      log("Restored advertised service \(service.uuid.uuidString) characteristic \(advertisedCharacteristicUuid?.uuidString ?? "<nil>")")
    }

    if let advData = advData {
      advertisementData = advData
      // Reconstruct an advertiseRequest so callers querying state see a
      // self-consistent picture. iOS doesn't restore localName/bondless
      // semantics — we set them to the conservative defaults the Grassroots
      // consumer uses.
      if let serviceUuid = advertisedServiceUuid,
         let characteristicUuid = advertisedCharacteristicUuid {
        advertiseRequest = BleAdvertiseRequest(
          serviceUuid: serviceUuid.uuidString,
          characteristicUuid: characteristicUuid.uuidString,
          localName: nil,
          includeDeviceName: false,
          bondless: true,
          manufacturerId: nil,
          manufacturerData: nil
        )
      }
      log("Restored advertisementData: keys=\(advData.keys.sorted())")
    }

    // Re-emit peripheral path events for any central marked subscribed on
    // the restored characteristic so the Dart consumer's `_paths` map and
    // Redux discovered-peripheral state include them. (CoreBluetooth lists
    // active subscribers via `subscribedCentrals`.)
    if let chr = advertisedCharacteristic {
      for central in chr.subscribedCentrals ?? [] {
        upsertPeripheralPath(for: central, state: .ready, subscribed: true)
        log("Restored subscribed central \(central.identifier.uuidString)")
      }
    }
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
    if let error = error {
      log("Failed to add BLE service \(service.uuid.uuidString): \(error.localizedDescription)")
      if service.uuid == advertisedServiceUuid {
        advertisementData = nil
        advertisedCharacteristic = nil
        pendingPeripheralUpdates.removeAll()
        peripheral.stopAdvertising()
        peripheral.removeAllServices()
      }
      return
    }
    guard advertiseRequest != nil,
          service.uuid == advertisedServiceUuid else {
      log("Ignoring stale didAdd for service \(service.uuid.uuidString)")
      return
    }
    let data = buildAdvertisementData()
    guard !data.isEmpty else {
      log("Not starting advertising for service \(service.uuid.uuidString): advertisement data is empty.")
      return
    }
    advertisementData = data
    log("Service added: \(service.uuid.uuidString) — calling startAdvertising with keys=\(Array(data.keys))")
    peripheral.startAdvertising(data)
  }

  func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
    if let error = error {
      log("Failed to start advertising: \(error.localizedDescription)")
      return
    }
    log("Started BLE advertising. isAdvertising=\(peripheral.isAdvertising)")
    scheduleAdvertiseHealthCheck(generation: advertiseGeneration)
  }

  /// iOS sometimes returns `didStartAdvertising` with no error but stops
  /// transmitting shortly afterward (foreground→background transition,
  /// system pressure, radio contention, etc.). Poll the manager every 5s
  /// to surface the silent stop.
  private func scheduleAdvertiseHealthCheck(generation: UInt64) {
    DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
      guard let self = self,
            let peripheral = self.peripheralManager,
            self.advertiseRequest != nil,
            self.advertiseGeneration == generation else { return }
      guard peripheral.state == .poweredOn else { return }
      if peripheral.isAdvertising {
        self.scheduleAdvertiseHealthCheck(generation: generation)
      } else {
        self.log("[advertise-health] iOS silently stopped advertising — re-issuing.")
        self.restartAdvertisingIfPossible()
      }
    }
  }

  private func restartAdvertisingIfPossible() {
    guard let peripheral = peripheralManager,
          peripheral.state == .poweredOn,
          advertiseRequest != nil,
          advertisedCharacteristic != nil,
          advertisedServiceUuid != nil else {
      configurePeripheralService()
      return
    }

    let data = buildAdvertisementData()
    guard !data.isEmpty else {
      log("Not restarting advertising: advertisement data is empty.")
      configurePeripheralService()
      return
    }
    advertisementData = data
    peripheral.startAdvertising(data)
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
    guard characteristic.uuid == advertisedCharacteristicUuid else { return }
    let pathId = Self.peripheralPathId(for: central)
    let previousState = peripheralPaths[pathId]?.state
    log(
      "BLE peripheral didSubscribeTo: pathId=\(pathId) " +
      "uuid=\(central.identifier.uuidString) previousState=\(previousState.map { String(describing: $0) } ?? "nil") " +
      "maxUpdateValueLength=\(central.maximumUpdateValueLength)"
    )
    upsertPeripheralPath(for: central, state: .subscribed, subscribed: true)
    if var path = peripheralPaths[pathId] {
      path.state = .ready
      peripheralPaths[path.pathId] = path
      emitPath(self.path(forPeripheralPathId: path.pathId))
    }
    drainPeripheralUpdates()
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
    guard characteristic.uuid == advertisedCharacteristicUuid else { return }
    let pathId = Self.peripheralPathId(for: central)
    let previousState = peripheralPaths[pathId]?.state
    // On iOS the peripheral never sees an explicit "central disconnected"
    // callback — `didUnsubscribeFrom` is the only signal that the link
    // is gone. Log it explicitly so the trail is symmetric with Android.
    log(
      "BLE peripheral didUnsubscribeFrom: pathId=\(pathId) " +
      "uuid=\(central.identifier.uuidString) previousState=\(previousState.map { String(describing: $0) } ?? "nil") " +
      "(treating as disconnect)"
    )
    pendingPeripheralUpdates.removeAll { $0.pathId == pathId }
    markPeripheralPath(pathId: pathId, state: .disconnected, canKeep: true, error: nil)
  }

  func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
    drainPeripheralUpdates()
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
    guard request.characteristic.uuid == advertisedCharacteristicUuid else {
      peripheral.respond(to: request, withResult: .requestNotSupported)
      return
    }
    request.value = Data()
    peripheral.respond(to: request, withResult: .success)
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
    let validRequests = requests.filter { request in
      request.characteristic.uuid == advertisedCharacteristicUuid
    }
    for request in requests where request.characteristic.uuid != advertisedCharacteristicUuid {
      peripheral.respond(to: request, withResult: .requestNotSupported)
    }
    guard !validRequests.isEmpty else {
      return
    }

    for request in validRequests {
      peripheral.respond(to: request, withResult: .success)
    }

    let grouped = Dictionary(grouping: validRequests, by: { $0.central.identifier.uuidString })
    for (_, group) in grouped {
      guard let first = group.first else { continue }
      let central = first.central
      let pathId = Self.peripheralPathId(for: central)
      let subscribed = peripheralPaths[pathId]?.isSubscribed ?? false
      upsertPeripheralPath(for: central, state: subscribed ? .ready : .connected, subscribed: subscribed)

      let sorted = group.sorted { $0.offset < $1.offset }
      var combined = Data()
      for request in sorted {
        guard let value = request.value else { continue }
        let end = request.offset + value.count
        if combined.count < end {
          combined.append(Data(repeating: 0, count: end - combined.count))
        }
        combined.replaceSubrange(request.offset..<end, with: value)
      }
      if !combined.isEmpty {
        emitPayload(pathId: pathId, role: .peripheral, value: combined, rssi: nil)
      }
    }
  }
}
