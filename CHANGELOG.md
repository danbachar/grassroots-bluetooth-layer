## 0.1.0

- Initial unified BLE central/peripheral plugin scaffold.
- Defines a Pigeon API centered on role-tagged BLE paths rather than stable device identities.
- Adds Android and iOS native central/peripheral implementations.
- Starts Android advertising only after the GATT service is confirmed.
- Cleans up Android advertising and scan failure state.
- Declares Android BLE permissions in the plugin manifest.
- Enforces a single default Dart facade instance for the global native callback channel.
- Documents iOS foreground/background advertising limits for cross-platform discovery.
