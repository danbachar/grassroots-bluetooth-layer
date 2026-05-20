import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    dartPackageName: 'grassroots_bluetooth_layer',
    dartOut: 'lib/src/generated/grassroots_bluetooth_layer.g.dart',
    dartOptions: DartOptions(),
    kotlinOut:
        'android/src/main/kotlin/org/permissionlesstech/grassroots_bluetooth_layer/GrassrootsBluetoothLayer.g.kt',
    kotlinOptions: KotlinOptions(package: 'org.permissionlesstech.grassroots_bluetooth_layer'),
    swiftOut: 'ios/Classes/GrassrootsBluetoothLayer.g.swift',
    swiftOptions: SwiftOptions(),
  ),
)
enum BleRole {
  central,
  peripheral,
}

enum BleAdapterState {
  unknown,
  unsupported,
  unauthorized,
  poweredOff,
  poweredOn,
}

enum BlePathState {
  discovered,
  connecting,
  connected,
  subscribed,
  ready,
  stale,
  disconnected,
  failed,
}

enum BleWriteMode {
  withoutResponse,
  withResponse,
}

class BleInitializeOptions {
  bool showPowerAlert;
  bool restoreState;
  bool verboseLogging;

  BleInitializeOptions({
    this.showPowerAlert = true,
    this.restoreState = false,
    this.verboseLogging = false,
  });
}

class BleAdvertiseRequest {
  String serviceUuid;
  String characteristicUuid;
  String? localName;
  bool includeDeviceName;
  bool bondless;
  int? manufacturerId;
  Uint8List? manufacturerData;

  BleAdvertiseRequest({
    required this.serviceUuid,
    required this.characteristicUuid,
    this.localName,
    this.includeDeviceName = false,
    this.bondless = true,
    this.manufacturerId,
    this.manufacturerData,
  });
}

class BleScanRequest {
  String? serviceUuidPrefix;
  List<String?> serviceUuids;
  int timeoutMs;
  bool allowDuplicates;
  int androidScanMode;

  BleScanRequest({
    this.serviceUuidPrefix,
    this.serviceUuids = const <String?>[],
    this.timeoutMs = 10000,
    this.allowDuplicates = false,
    this.androidScanMode = 2,
  });
}

class BleConnectRequest {
  String remoteId;
  String serviceUuid;
  String characteristicUuid;
  int timeoutMs;
  bool subscribeToNotifications;
  int? androidMtu;

  BleConnectRequest({
    required this.remoteId,
    required this.serviceUuid,
    required this.characteristicUuid,
    this.timeoutMs = 5000,
    this.subscribeToNotifications = true,
    this.androidMtu,
  });
}

class BleSendRequest {
  String pathId;
  Uint8List value;
  BleWriteMode writeMode;

  BleSendRequest({
    required this.pathId,
    required this.value,
    this.writeMode = BleWriteMode.withoutResponse,
  });
}

class BleDisconnectRequest {
  String pathId;
  bool forget;

  BleDisconnectRequest({
    required this.pathId,
    this.forget = true,
  });
}

class BleAdvertisement {
  String remoteId;
  String? platformName;
  String? advertisedName;
  List<String?> serviceUuids;
  int rssi;
  bool connectable;
  int? txPower;
  Uint8List? manufacturerData;

  BleAdvertisement({
    required this.remoteId,
    this.platformName,
    this.advertisedName,
    this.serviceUuids = const <String?>[],
    this.rssi = -100,
    this.connectable = true,
    this.txPower,
    this.manufacturerData,
  });
}

/// A role-tagged BLE link. The `pathId` is the only stable handle the
/// application should use to address a path. It has the form
/// `central:<opaque>` or `peripheral:<opaque>`. The portion after the colon
/// is the platform's view of the remote (CBPeripheral.identifier on iOS, the
/// Bluetooth address on Android) — treat it as opaque, do not parse it, and
/// do not assume it is stable across reconnects or app restarts.
class BlePath {
  String pathId;
  BleRole role;
  BlePathState state;
  String? platformDeviceId;
  String? serviceUuid;
  String? characteristicUuid;
  int? rssi;
  int mtu;
  bool canSend;
  String? error;

  BlePath({
    required this.pathId,
    required this.role,
    required this.state,
    this.platformDeviceId,
    this.serviceUuid,
    this.characteristicUuid,
    this.rssi,
    this.mtu = 23,
    this.canSend = false,
    this.error,
  });
}

class BlePayload {
  String pathId;
  BleRole role;
  Uint8List value;
  int? rssi;

  BlePayload({
    required this.pathId,
    required this.role,
    required this.value,
    this.rssi,
  });
}

@HostApi()
abstract class GrassrootsBluetoothLayerHostApi {
  void initialize(BleInitializeOptions options);

  bool isSupported();

  BleAdapterState adapterState();

  void startAdvertising(BleAdvertiseRequest request);

  void stopAdvertising();

  void startScan(BleScanRequest request);

  void stopScan();

  BlePath connect(BleConnectRequest request);

  void disconnect(BleDisconnectRequest request);

  void send(BleSendRequest request);

  List<BlePath> paths();

  void dispose();
}

@FlutterApi()
abstract class GrassrootsBluetoothLayerFlutterApi {
  void onAdapterStateChanged(BleAdapterState state);

  void onAdvertisement(BleAdvertisement advertisement);

  void onPathChanged(BlePath path);

  void onPayloadReceived(BlePayload payload);

  void onLog(String message);
}
