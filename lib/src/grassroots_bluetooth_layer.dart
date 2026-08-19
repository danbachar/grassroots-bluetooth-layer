import 'dart:async';
import 'dart:typed_data';

import 'package:meta/meta.dart';

import 'generated/grassroots_bluetooth_layer.g.dart';

/// Thin Dart facade over the native unified BLE state machine.
///
/// The package treats platform IDs as ephemeral path handles. Applications
/// should map a [BlePath.pathId] to stable identity only after their own
/// authenticated protocol exchange.
class GrassrootsBluetooth {
  GrassrootsBluetooth({
    GrassrootsBluetoothLayerHostApi? hostApi,
  })  : _hostApi = hostApi ?? GrassrootsBluetoothLayerHostApi(),
        _callbacks = _GrassrootsBluetoothCallbacks(),
        _wireFlutterApi = true {
    if (_activeDefaultInstance) {
      throw StateError(
        'Only one GrassrootsBluetooth instance can own the native callback channel.',
      );
    }
    GrassrootsBluetoothLayerFlutterApi.setup(_callbacks);
    _activeDefaultInstance = true;
  }

  /// Test-only constructor: allows injecting a host API stub and an external
  /// callbacks bridge. Skips the static `GrassrootsBluetoothLayerFlutterApi.setup` so unit
  /// tests don't collide on the singleton channel registration.
  @visibleForTesting
  GrassrootsBluetooth.test({
    required GrassrootsBluetoothLayerHostApi hostApi,
    required GrassrootsBluetoothLayerFlutterApi callbacks,
  })  : _hostApi = hostApi,
        _callbacks = callbacks as _GrassrootsBluetoothCallbacks,
        _wireFlutterApi = false;

  final GrassrootsBluetoothLayerHostApi _hostApi;
  final _GrassrootsBluetoothCallbacks _callbacks;
  final bool _wireFlutterApi;
  static bool _activeDefaultInstance = false;
  bool _disposed = false;

  Stream<BleAdapterState> get adapterStateChanges =>
      _callbacks.adapterStateChanges;

  Stream<BleAdvertisement> get advertisements => _callbacks.advertisements;

  /// Whether this device is broadcasting its advertisement, and why not when
  /// it is not. A device whose advertising was refused keeps scanning and
  /// connecting normally while no peer can discover it, so callers that care
  /// about being reachable should listen here. See [BleAdvertisingState].
  Stream<BleAdvertisingState> get advertisingStateChanges =>
      _callbacks.advertisingStateChanges;

  Stream<BlePath> get pathChanges => _callbacks.pathChanges;

  Stream<BlePayload> get payloads => _callbacks.payloads;

  Stream<String> get logs => _callbacks.logs;

  Future<void> initialize({
    bool showPowerAlert = true,
    bool restoreState = false,
    bool verboseLogging = false,
  }) {
    return _hostApi.initialize(
      BleInitializeOptions(
        showPowerAlert: showPowerAlert,
        restoreState: restoreState,
        verboseLogging: verboseLogging,
      ),
    );
  }

  Future<bool> isSupported() => _hostApi.isSupported();

  Future<BleAdapterState> adapterState() => _hostApi.adapterState();

  /// Start (or update) BLE advertising.
  ///
  /// [serviceUuid] is the UUID placed in the advertisement packet — a
  /// discovery/recognition hint that may rotate. [gattServiceUuid] is the GATT
  /// service the characteristic is registered under (the data plane a connected
  /// central talks to); when null it defaults to [serviceUuid].
  ///
  /// Decoupling them enables non-destructive rotation: calling this again with
  /// a new [serviceUuid] but the same [gattServiceUuid] + [characteristicUuid]
  /// updates only the advertisement payload — the GATT server and every live
  /// peripheral link are preserved.
  Future<void> startAdvertising({
    required String serviceUuid,
    required String characteristicUuid,
    String? gattServiceUuid,
    String? localName,
    bool includeDeviceName = false,
    bool bondless = true,
    int? manufacturerId,
    Uint8List? manufacturerData,
  }) {
    return _hostApi.startAdvertising(
      BleAdvertiseRequest(
        serviceUuid: serviceUuid,
        characteristicUuid: characteristicUuid,
        gattServiceUuid: gattServiceUuid,
        localName: localName,
        includeDeviceName: includeDeviceName,
        bondless: bondless,
        manufacturerId: manufacturerId,
        manufacturerData: manufacturerData,
      ),
    );
  }

  Future<void> stopAdvertising() => _hostApi.stopAdvertising();

  Future<void> startScan({
    String? serviceUuidPrefix,
    List<String> serviceUuids = const [],
    Duration timeout = const Duration(seconds: 10),
    bool allowDuplicates = false,
    int androidScanMode = 2,
  }) {
    return _hostApi.startScan(
      BleScanRequest(
        serviceUuidPrefix: serviceUuidPrefix,
        serviceUuids: serviceUuids,
        timeoutMs: timeout.inMilliseconds,
        allowDuplicates: allowDuplicates,
        androidScanMode: androidScanMode,
      ),
    );
  }

  Future<void> stopScan() => _hostApi.stopScan();

  Future<BlePath> connect({
    required String remoteId,
    required String serviceUuid,
    required String characteristicUuid,
    Duration timeout = const Duration(seconds: 5),
    bool subscribeToNotifications = true,
    int? androidMtu,
  }) {
    return _hostApi.connect(
      BleConnectRequest(
        remoteId: remoteId,
        serviceUuid: serviceUuid,
        characteristicUuid: characteristicUuid,
        timeoutMs: timeout.inMilliseconds,
        subscribeToNotifications: subscribeToNotifications,
        androidMtu: androidMtu,
      ),
    );
  }

  Future<void> disconnect(String pathId, {bool forget = true}) {
    return _hostApi.disconnect(
      BleDisconnectRequest(pathId: pathId, forget: forget),
    );
  }

  Future<void> send(
    String pathId,
    Uint8List value, {
    BleWriteMode writeMode = BleWriteMode.withoutResponse,
  }) {
    return _hostApi.send(
      BleSendRequest(pathId: pathId, value: value, writeMode: writeMode),
    );
  }

  Future<List<BlePath>> paths() async {
    final result = await _hostApi.paths();
    return result.whereType<BlePath>().toList(growable: false);
  }

  /// Ground-truth snapshot of live physical links (one entry per distinct
  /// remote address, with the GATT roles riding it). See [BleLinkInfo].
  Future<List<BleLinkInfo>> linkSnapshot() async {
    final result = await _hostApi.linkSnapshot();
    return result.whereType<BleLinkInfo>().toList(growable: false);
  }

  Future<void> dispose() async {
    if (_disposed) return;
    _disposed = true;
    try {
      await _hostApi.dispose();
    } finally {
      if (_wireFlutterApi) {
        GrassrootsBluetoothLayerFlutterApi.setup(null);
        _activeDefaultInstance = false;
      }
      await _callbacks.close();
    }
  }
}

/// Test-only handle to drive synthetic events through a [GrassrootsBluetooth.test]
/// instance. Construct one, pass it to [GrassrootsBluetooth.test], then call its
/// `pushXxx` methods to simulate plugin events.
@visibleForTesting
class FakeGrassrootsBluetoothCallbacks extends _GrassrootsBluetoothCallbacks {
  FakeGrassrootsBluetoothCallbacks();

  void pushAdapterState(BleAdapterState state) => onAdapterStateChanged(state);
  void pushAdvertisement(BleAdvertisement adv) => onAdvertisement(adv);
  void pushAdvertisingState(BleAdvertisingState state) =>
      onAdvertisingStateChanged(state);
  void pushPath(BlePath path) => onPathChanged(path);
  void pushPayload(BlePayload payload) => onPayloadReceived(payload);
}

class _GrassrootsBluetoothCallbacks extends GrassrootsBluetoothLayerFlutterApi {
  final _adapterStateController = StreamController<BleAdapterState>.broadcast();
  final _advertisementController =
      StreamController<BleAdvertisement>.broadcast();
  final _advertisingStateController =
      StreamController<BleAdvertisingState>.broadcast();
  final _pathController = StreamController<BlePath>.broadcast();
  final _payloadController = StreamController<BlePayload>.broadcast();
  final _logController = StreamController<String>.broadcast();

  Stream<BleAdapterState> get adapterStateChanges =>
      _adapterStateController.stream;

  Stream<BleAdvertisement> get advertisements =>
      _advertisementController.stream;

  Stream<BleAdvertisingState> get advertisingStateChanges =>
      _advertisingStateController.stream;

  Stream<BlePath> get pathChanges => _pathController.stream;

  Stream<BlePayload> get payloads => _payloadController.stream;

  Stream<String> get logs => _logController.stream;

  @override
  void onAdapterStateChanged(BleAdapterState state) {
    _adapterStateController.add(state);
  }

  @override
  void onAdvertisement(BleAdvertisement advertisement) {
    _advertisementController.add(advertisement);
  }

  @override
  void onAdvertisingStateChanged(BleAdvertisingState state) {
    _advertisingStateController.add(state);
  }

  @override
  void onPathChanged(BlePath path) {
    _pathController.add(path);
  }

  @override
  void onPayloadReceived(BlePayload payload) {
    _payloadController.add(payload);
  }

  @override
  void onLog(String message) {
    _logController.add(message);
  }

  Future<void> close() async {
    await _adapterStateController.close();
    await _advertisementController.close();
    await _advertisingStateController.close();
    await _pathController.close();
    await _payloadController.close();
    await _logController.close();
  }
}
