# Grassroots BLE Architecture

`grassroots_bluetooth_layer` owns BLE path state, not peer identity.

Platform IDs are useful only as short-lived handles:

- Android central and peripheral paths are keyed by Bluetooth addresses, which may rotate.
- iOS central paths are keyed by `CBPeripheral.identifier`.
- iOS peripheral paths are keyed by `CBCentral.identifier`.

The application must treat those values as transport paths and bind them to stable identity only after an authenticated protocol exchange.

## Event Model

The plugin emits one role-tagged path stream:

- `discovered`: fresh advertisement, not a connection.
- `connecting`: central dial is in progress.
- `connected`: the underlying BLE link exists.
- `subscribed`: the peer subscribed to our notify characteristic.
- `ready`: the path can send bytes.
- `stale`: the native layer believes the path is suspect.
- `disconnected`: the path closed.
- `failed`: a dial or operation failed.

Peripheral raw connection and subscription readiness are distinct. A raw Android GATT connection is not sendable until the central subscribes to the characteristic. Darwin has no raw peripheral connection callback, so the first observable peripheral path is subscription.

## Bondless Defaults

Advertising creates one primary service and one characteristic with:

- read
- write
- write without response
- notify

The plugin uses plain readable/writeable permissions when `bondless` is true. Encrypted permissions are intentionally absent from the first API because Grassroots authenticates at the packet layer and should not trigger OS pairing prompts.

## Reconnection

Reconnect policy should use the latest advertisement plus app identity hints. Cached `pathId` and `platformDeviceId` values are not stable enough to be long-lived peer identifiers.
