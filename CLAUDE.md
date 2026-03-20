# CLAUDE.md

## Project

Capacitor v8 plugin bridging Matrix messaging to web (matrix-js-sdk), Android (Rust SDK/Kotlin), and iOS (Rust SDK/Swift).

## Rules

- Never add random delays (`sleep`, `setTimeout`, `delay`) as a substitute for proper synchronization. Use listeners, callbacks, or awaiting the actual async operation instead.

## Native parity

Any change to the plugin API surface must be mirrored in **all three** implementations:

| Layer | Files |
|---|---|
| TypeScript | `src/definitions.ts`, `src/web.ts` |
| Android | `android/…/CapMatrixPlugin.kt` (call params), `android/…/CapMatrix.kt` (bridge logic) |
| iOS | `ios/…/CapMatrixPlugin.swift` (call params), `ios/…/CapMatrix.swift` (bridge logic) |

Checklist when touching the API:
- **New event field** (e.g. `receiptReceived`): update callback signature in both bridge files and both plugin files.
- **New send/reply/edit option** (e.g. `duration`, `width`, `height`): read the param in both plugin files; wire it through to the bridge when the bridge supports it (mark `// TODO` if not yet).
- **New `DeviceInfo` field** (e.g. `isCrossSigningVerified`): add the field to the device map returned by `getDevices()` in both bridge files.
- **New method** registered in the iOS `pluginMethods` array must also be added to `CapMatrixPlugin.swift` as an `@objc func`.
