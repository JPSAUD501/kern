# Kern: guide for contributors and agents

Kern is an open source Android keyboard. Read `docs/product.md` (what and why), `docs/decisions.md` (what is settled) and `docs/roadmap.md` (what comes next) before changing anything. The research behind the decisions is in `docs/research/`.

## Rules

- **Quality first.** Follow the priorities in `docs/product.md`. A feature that misses its quality bar does not ship.
- **No hacks.** Understand a platform behavior before working around it. Every workaround for a specific app lives in the text field adapter and has a test that reproduces the app's behavior.
- **Strict typing.** No `Any`/`any` escapes, no unchecked casts, no stringly-typed state. Model states with sealed types and enums.
- **Measure engine changes.** Any change to decoding, models or data runs through the evaluation harness, and per-language scores must not regress.
- **Tests that matter.** Test behavior that can break. No tests that only assert something was removed.
- **Licenses are hard constraints.** Never copy code or data from a source whose license is not compatible, and never use FUTO's code or model weights (see D3). Every data source is pinned and listed with its license.
- **Privacy.** Nothing typed is logged, persisted outside the engine's user data, or sent anywhere except by an explicit AI action that follows D13.
- **Decisions are recorded.** A change that contradicts `docs/decisions.md` updates it in the same change, with the reason.

## Conventions

- English for code, comments, commits and docs.
- Commits: imperative summary line, body explaining why.

## Layout

- `engine/kern-core`: the typing engine in Rust, platform-independent. Layouts are JSON in `engine/kern-core/layouts`.
- `engine/kern-ffi`: the engine's Kotlin API, generated with UniFFI.
- `android/engine`: Android library that builds the Rust engine with cargo-ndk and the bindings with UniFFI (Gradle plugin in `build-logic`).
- `android/app`: the keyboard app (input method service, keyboard view, setup screen).
- `android/bench`: test app of text fields and the UI tests that type into it with the installed keyboard.

## Build and test

Needs JDK 21, the Android SDK with the NDK version in `gradle/libs.versions.toml`, rustup (the toolchain in `rust-toolchain.toml` installs itself) and `cargo install cargo-ndk --version 4.1.2 --locked`. CI (`.github/workflows/ci.yml`) runs the same commands.

```sh
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked

./gradlew :app:lintDebug :bench:lintDebug :app:assembleDebug :app:assembleRelease
scripts/check-native-alignment.sh android/app/build/outputs/apk/*/*.apk

# UI tests, with an emulator or phone connected and its language set to English:
./gradlew :app:installDebug :bench:connectedDebugAndroidTest
```

Kotlin and Android lint warnings fail the build.
