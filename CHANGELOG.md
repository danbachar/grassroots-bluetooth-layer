## 0.2.0

### Breaking

- `BlePath.rssi` and `BlePayload.rssi` are now nullable (`int?` / `Long?` / `Int64?`). `null` represents "no measurement available" — previously the layer leaked a `-100` sentinel. Consumers that did `path.rssi.toLong()` must add a null-safe operator.

### Added

- Central paths on both platforms now actively poll RSSI every 10 seconds so the value stays current after connection, not only at scan time. Android wires the poll into the per-path GATT op queue (started at `markCentralPathReady`, using `readRemoteRssi()`); iOS uses a per-path `Timer` driving `CBPeripheral.readRSSI()`. Both are torn down on disconnect / fail / dispose.
- `decodeGattStatus(status: Int)` translates Android GATT status codes (8, 19, 22, 62, 133, …) into human-readable names in every `onConnectionStateChange` log line.
- `describeDisconnectError(_:)` does the equivalent for iOS, decoding `CBError.Code` (connectionTimeout, peripheralDisconnected, connectionLimitReached, …) in `didFailToConnect` and `didDisconnectPeripheral` logs.
- Connection-state observability on both platforms: previous state, new state, decoded status, plus Android-side `pendingOps` count and in-flight op type, and iOS-side peripheral `didSubscribeTo` / `didUnsubscribeFrom` (the only iOS signal that a remote central went away).
- `grassrootsIosLocalName` (`'grs-ios'`) constant, exported from both `grassroots_bluetooth_layer` and `grassroots_bluetooth_layer_testing`. iOS peripherals now advertise this fixed local name in the scan response alongside the 128-bit service UUID, surfaced to scanners as `BleAdvertisement.advertisedName`. It is a platform marker (not an identity): peers use it to yield the first central dial to the iOS side, which can only open the first ACL link of a pair. Identity still travels exclusively in the signed post-connection ANNOUNCE.

### Changed

- iOS advertising now carries the short fixed `grs-ios` local name in addition to the service UUID; previously the local name was omitted entirely. The caller-supplied `localName` is still ignored on iOS (substituted with the marker) so the 128-bit service UUID stays in the primary advertise packet where external scanners can see it. Backgrounded iOS still drops the name and moves the UUID to the overflow area.
- Central-connection diagnostics on iOS: added log lines at connect issue / already-connected, connection timeout (cancelling with no `didConnect` in the window), and `didConnect` (LL link up).

### Fixed

- iOS devices saw zero nearby Grassroots peers in BLE scans, which silently broke cross-platform discovery from the iOS side. The scan filter was rejecting every Grassroots advertisement because `CBUUID.uuidString` returns dashed 128-bit UUIDs (`84c40316-0871-e5ad-…`) while callers pass compact-hex prefixes (`84c403160871e5ad`); `hasPrefix` therefore always returned false. Both sides of the comparison are now normalized to dashless lowercase hex.
- iOS `didDiscover` rejection log no longer floods the console with every nearby BLE device — only logs rejections whose advertised UUIDs at least look like Grassroots (prefix-matched), so genuine filter mismatches stay visible.
- Scan results carrying non-real RSSI sentinel values are now dropped at the platform boundary on both Android and iOS, so consumers no longer receive placeholder measurements; each dropped result is logged for diagnostics.

### Packaging

- Removed a redundant `.pubignore` that shadowed `.gitignore` during `flutter pub publish`. It only re-listed `.dart_tool/` and `pubspec.lock` (both already in `.gitignore`) while disabling `.gitignore`'s build-output exclusions, so the publish archive was bundling `build/` artifacts (13 MB). With it gone, pub falls back to `.gitignore` and the archive is 47 KB.

## 0.1.0

- Initial unified BLE central/peripheral plugin scaffold.
- Defines a Pigeon API centered on role-tagged BLE paths rather than stable device identities.
- Adds Android and iOS native central/peripheral implementations.
- Starts Android advertising only after the GATT service is confirmed.
- Cleans up Android advertising and scan failure state.
- Declares Android BLE permissions in the plugin manifest.
- Enforces a single default Dart facade instance for the global native callback channel.
- Documents iOS foreground/background advertising limits for cross-platform discovery.
