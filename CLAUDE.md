# CLAUDE.md

## Project

Capacitor v8 plugin bridging Matrix messaging to web (matrix-js-sdk), Android (Rust SDK/Kotlin), and iOS (Rust SDK/Swift).

## Rules

- Never add random delays (`sleep`, `setTimeout`, `delay`) as a substitute for proper synchronization. Use listeners, callbacks, or awaiting the actual async operation instead.
