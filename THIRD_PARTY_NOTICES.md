# Third-party notices

Kern is GPL-3.0-or-later (see `LICENSE`). It bundles the following third-party work, each under its own license, all compatible with GPLv3.

## Assets

| Work | Where | License |
|---|---|---|
| [Material Symbols](https://github.com/google/material-design-icons) by Google | key icons in `android/app/src/main/res/drawable/ic_key_*.xml` and the placeholder launcher icon | Apache-2.0 |

## Android libraries

| Library | License |
|---|---|
| [AndroidX and Jetpack Compose](https://developer.android.com/jetpack/androidx) | Apache-2.0 |
| [Java Native Access (JNA)](https://github.com/java-native-access/jna) | Apache-2.0 (dual-licensed with LGPL-2.1; Kern uses it under Apache-2.0) |

## Rust crates in the engine library

The engine (`libkern_ffi.so`) links crates under MIT, Apache-2.0, MIT OR Apache-2.0, Unlicense OR MIT, Unicode-3.0 and MPL-2.0 ([UniFFI](https://github.com/mozilla/uniffi-rs), whose source is available at that link). The exact set for a given commit, with each license, is listed by:

```sh
cargo tree -p kern-ffi -e normal --target aarch64-linux-android --prefix none --format '{p} {l}'
```

The app will show the full generated list on an in-app licenses screen before the beta (roadmap M8).
