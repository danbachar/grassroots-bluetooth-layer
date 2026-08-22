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

/// Why the controller refused to start advertising, in terms of what the
/// caller can do about it.
enum BleAdvertiseFailure {
  /// The request is sound but the controller would not take it now — another
  /// app holds the advertising slots, or the stack faulted. A later
  /// `startAdvertising` with the same arguments can succeed.
  transient,

  /// The request cannot succeed as written — the advertisement exceeds the
  /// payload budget, or the controller cannot advertise at all. Repeating the
  /// same `startAdvertising` changes nothing; the arguments have to change.
  terminal,
}

/// Whether this device is broadcasting its advertisement.
///
/// A device that is not advertising is undiscoverable: peers cannot see it,
/// no inbound peripheral leg can form, and nothing about the scan side says
/// so — the radio keeps finding peers while no peer can find it. This state
/// is reported so the application can record it rather than infer it.
/// Whether the controller is actually scanning.
///
/// `startScan` returning says the request was accepted, not that the radio
/// is listening. The distinction matters for the same reason it does for
/// advertising: the application anchors its establishment measurements on
/// the moment the radio is genuinely up, and a stamp taken when the request
/// went in reports intent rather than fact.
class BleScanState {
  /// True while the controller is scanning.
  bool active;

  /// The controller's reason, in plain words (for logs and traces). Set when
  /// [active] is false because a start attempt was refused; null when the
  /// scan stopped because the application asked it to.
  String? reason;

  BleScanState({required this.active, this.reason});
}

class BleAdvertisingState {
  /// True while the controller is broadcasting our advertisement.
  bool active;

  /// Set when [active] is false because a start attempt was refused. Null
  /// when advertising stopped because the application asked it to.
  BleAdvertiseFailure? failure;

  /// The transmit power the controller GRANTED, as the Android level
  /// (0 ultra-low, 1 low, 2 medium, 3 high), or null when advertising is not
  /// active. We always ask for high; the radio is free to answer otherwise,
  /// and a different answer moves every RSSI a peer measures for us. Without
  /// this the trace records the signal and not the setting that produced it.
  int? txPowerLevel;

  /// The controller's reason, in plain words (for logs and traces).
  String? reason;

  BleAdvertisingState({
    required this.active,
    this.failure,
    this.reason,
  });
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
  /// The service UUID placed in the advertisement packet — a discovery /
  /// recognition hint that may rotate over time. It does NOT have to equal the
  /// GATT service the characteristic lives under (see [gattServiceUuid]).
  String serviceUuid;
  String characteristicUuid;

  /// The GATT service UUID under which the characteristic is registered — the
  /// data plane a connected central talks to. When null, defaults to
  /// [serviceUuid] (backward-compatible). Decoupling it from [serviceUuid]
  /// lets the advertised UUID rotate without rebuilding the GATT service or
  /// dropping live peripheral links: a later [startAdvertising] that changes
  /// only [serviceUuid] (same [gattServiceUuid] + [characteristicUuid]) updates
  /// just the advertisement payload.
  String? gattServiceUuid;
  String? localName;
  bool includeDeviceName;
  bool bondless;
  int? manufacturerId;
  Uint8List? manufacturerData;

  BleAdvertiseRequest({
    required this.serviceUuid,
    required this.characteristicUuid,
    this.gattServiceUuid,
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
    required this.rssi,
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

/// One live physical connection (ACL / LL link) to a remote device, with the
/// GATT roles currently riding it. Ground truth from the OS
/// (BluetoothManager's connected-device lists on Android; tracked
/// CBPeripheral/CBCentral objects on iOS) — NOT the plugin's path
/// bookkeeping. One entry per distinct remote address: an address appearing
/// in both role lists is a single shared link carrying both directions
/// (over-ACL attach), while a dual-ACL pair shows up as two entries mapped
/// to the same peer by the app layer.
class BleLinkInfo {
  /// Remote address (MAC on Android, CB identifier UUID on iOS) — matches the
  /// address part of the plugin's pathIds.
  String address;

  /// We hold a GATT *client* on this link (our central leg).
  bool clientRole;

  /// The remote holds a GATT client on our *server* over this link (their
  /// central leg toward us).
  bool serverRole;

  BleLinkInfo({
    required this.address,
    this.clientRole = false,
    this.serverRole = false,
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

  /// Ground-truth snapshot of live physical links (see [BleLinkInfo]).
  /// Diagnostic: lets the app distinguish a shared over-ACL pair (one entry,
  /// both roles) from a dual-ACL pair (two entries for the same peer).
  List<BleLinkInfo> linkSnapshot();

  void dispose();

  /// Cycle the OS Bluetooth adapter — the full stack, radio down and up.
  ///
  /// Only Android 12 and below permit an app to do this; on newer Android
  /// the call returns false and the caller records that the reset did not
  /// happen rather than pretending it did. The adapter-state events carry
  /// the OFF/ON transitions as usual, so the transport re-parks and
  /// restarts through its normal adapter handling.
  bool restartAdapter();
}

@FlutterApi()
abstract class GrassrootsBluetoothLayerFlutterApi {
  void onAdapterStateChanged(BleAdapterState state);

  void onAdvertisement(BleAdvertisement advertisement);

  void onAdvertisingStateChanged(BleAdvertisingState state);

  void onScanStateChanged(BleScanState state);

  void onPathChanged(BlePath path);

  void onPayloadReceived(BlePayload payload);

  void onLog(String message);
}
