# grassroots_bluetooth_layer

A Flutter plugin that exposes one bondless BLE layer for central scanning/connecting and peripheral advertising/GATT service hosting.

The API treats platform device identifiers as ephemeral path handles. Applications should map paths to stable peer identity only after their own authenticated handshake, such as a Grassroots `ANNOUNCE`.

## What It Provides

- Central scan, connect, subscribe, write, and disconnect.
- Peripheral advertise, GATT service hosting, receive writes, notify subscribed centrals, and disconnect.
- One role-tagged `pathChanges` stream for native BLE link state.
- Bondless GATT attributes by default. Pairing is not required unless the application explicitly requests encrypted attributes.
- Separate `connected`, `subscribed`, and `ready` states so callers do not treat a raw link as sendable too early.

See [doc/architecture.md](doc/architecture.md) for the path-state model.

## Basic Use

```dart
final ble = GrassrootsBluetooth();

await ble.initialize(verboseLogging: true);

ble.pathChanges.listen((path) {
  if (path.state == BlePathState.ready && path.canSend) {
    // path.pathId is the send handle. Bind it to app identity after your handshake.
  }
});

ble.payloads.listen((payload) {
  // payload.role tells whether bytes arrived through our central or peripheral role.
});

await ble.startAdvertising(
  serviceUuid: '84c40316-0871-e5ad-6b75-1e089cd67c1f',
  characteristicUuid: '0000ff01-0000-1000-8000-00805f9b34fb',
);

await ble.startScan(
  serviceUuids: const ['84c40316-0871-e5ad-6b75-1e089cd67c1f'],
  allowDuplicates: true,
);
```

Only one default `GrassrootsBluetooth` instance can own the native callback channel at a time.

## Android Setup

The plugin manifest contributes the BLE permissions it needs. The host app must still request dangerous runtime permissions before calling scan, connect, or advertise:

- Android 12+: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and `BLUETOOTH_ADVERTISE`
- Android 11 and lower: `ACCESS_FINE_LOCATION` for scanning

The plugin declares `android.hardware.bluetooth_le` as `required=false` so apps can decide whether BLE is mandatory.

## iOS Setup

The host app must include `NSBluetoothAlwaysUsageDescription` in `Info.plist`.

For foreground discovery by Android and generic scanners, advertise a 128-bit service UUID without local name or manufacturer data. CoreBluetooth cannot put arbitrary manufacturer data in peripheral advertisements, and adding a local name can force the 128-bit service UUID into iOS overflow advertising where Android scanners cannot see it.

Background iOS advertising has platform limits: service UUIDs may be moved to overflow advertising and are generally discoverable only by iOS scanners that explicitly scan for the same service. Cross-platform discovery should be treated as a foreground feature.

## Testing

`package:grassroots_bluetooth_layer/grassroots_bluetooth_layer.dart` is the public API. `package:grassroots_bluetooth_layer/grassroots_bluetooth_layer_testing.dart` exposes the generated Pigeon host API and fake callbacks for tests.
