# Research: engine language, bindings and on-device ML runtime

Status: research input for the open decision "Engine language, ML runtime" in `docs/decisions.md`. Not a decision yet.
Date: 2026-11-17. All versions and dates below were checked on this date.

Each claim carries a source number from the [Sources](#sources) list. Claims are tagged:

- **[V]** verified: read in a primary source, or measured for this document (see [Measurements made for this document](#measurements-made-for-this-document)).
- **[S]** secondary: from a third-party write-up or a project's own benchmark, not independently checked.
- **[I]** inferred: my reasoning from verified facts. Needs to be confirmed by a benchmark before it is relied on.

---

## 1. Summary and recommendation

| Question | Recommendation | Confidence |
|---|---|---|
| Engine language | **Rust**, one crate graph compiled for `aarch64-linux-android` (and `x86_64-linux-android` for emulators) and for Linux x86_64/arm64 for the evaluation harness. Kotlin owns the IME service, UI and `InputConnection`. | High |
| Bindings | **UniFFI** for the whole engine API (generated, typed Kotlin now, Swift later), under explicit design rules: coarse calls, an engine thread owned by Kotlin, no Rust-to-Kotlin callbacks on the hot path, bulk data as zero-copy bytes. Escape hatch for a measured hot spot: hand-written JNI through the `jni` crate (0.22) with `@CriticalNative`/`@FastNative`, which became public, CTS-tested API exactly at Kern's minSdk 34. | Medium-high: must be confirmed by a per-call measurement on the reference device |
| ML runtime | **ExecuTorch with the XNNPACK CPU backend**, called from Rust behind a small `Model` trait, with a custom (selective) build. LiteRT is the runner-up and must be benchmarked against it on the real swipe and LM models before the decision is locked. | Medium: gated on a bake-off |
| Training-to-device | **PyTorch → `torch.export` → ExecuTorch** (`to_edge_transform_and_lower` with `XnnpackPartitioner`, int8 via the PT2E/torchao XNNPACK quantizer). Search (beam search, CTC collapse, lexicon constraints) stays in Rust, outside the exported graph. | Medium-high |
| User data | Personal data in credential-encrypted (CE) storage only, never touched before first unlock; **SQLite compiled into the engine (`rusqlite`, `bundled`)** for the personal dictionary, learned n-grams and correction history; a versioned, checksummed snapshot for the touch model. No app-level encryption layer in 1.0 (file-based encryption already covers it; Jetpack Security Crypto is deprecated). Excluded from cloud backup by default; device-to-device transfer allowed; explicit export/import in settings. | Medium-high; the cloud-backup choice needs João's call |

Why, in one paragraph each:

**Rust.** The engine is code that sees every keystroke, parses untrusted language packs and model files, and must hit an 8 ms p99 budget. Rust gives C++-class latency and memory control without the memory-safety bug class, which Google measures as the dominant risk in Android native code [V, 19]. The same crates compile unchanged for Android and for the desktop harness through Cargo, with Tier 2 Android targets [V, 17], and later for iOS. Pure Kotlin would make the desktop harness easy (JVM) but gives up memory-mapped compact data structures without bounds-check overhead, SIMD, predictable latency without GC/JIT warm-up, and any iOS path; FlorisBoard contributors moved their dictionary engine to native Rust for exactly the memory reasons [S, 37]. C++ would match Rust on speed but not on safety, testing and build ergonomics.

**UniFFI.** At typing speed the engine is called a few times per keystroke. Even UniFFI's current worst case (about 25 µs per string-carrying call on an Android emulator [S, 9]) is under 0.5% of the 8 ms budget [I]. A fix merged on 2026-11-09 cuts no-op calls on Android from 23.6 µs to 0.45 µs [V, 7], and zero-copy byte slices already exist since 0.32.0 [V, 6]. In exchange, UniFFI removes hand-written JNI signatures (stringly typed, crash-on-mismatch [V, 13]) and gives a Swift binding for free. The remaining costs (JNA dependency, allocation per call, slow callbacks from non-JVM threads [V, 8]) are avoided by design rules, and the `jni` crate stays available for any call that a measurement proves too slow.

**ExecuTorch.** The models are trained in PyTorch, and ExecuTorch runs the `torch.export` graph without converting it to another framework's op set; unsupported ops fall back to portable kernels instead of failing the conversion [V, 25]. Its XNNPACK CPU backend is the recommended Android path and also runs on the host [V, 25], so the harness executes the same runtime and kernels. It is BSD-licensed [V, 23], ships a 9.1 MB arm64 library prebuilt and much less with a selective build [V, measured; V, 24], is in production at Meta scale [V, 24], and has a written API deprecation policy [V, 26]. Its weak points are a C++-only API (the Rust binding is third-party [V, 28]) and a known lack of tied-embedding support in the XNNPACK delegate, which duplicates the embedding table in LM files [V, 24]. LiteRT is a close second (smaller library, official C API and Rust crate) and wins if the bake-off shows it faster on Kern's models; ONNX Runtime is ruled out mainly by size (36 MB arm64 prebuilt), tract by unverified int8 and ARM performance.

---

## 2. Question 1: engine language

### 2.1 Options compared

| Criterion | Rust | C++ | Pure Kotlin |
|---|---|---|---|
| Latency, memory control | Native code, no GC, explicit layout, `mmap` of language packs [I] | Same as Rust [I] | ART GC and JIT/AOT warm-up; compact binary structures via `ByteBuffer` with bounds checks [I] |
| Memory safety | Safe by default; Android reports memory-safety vulnerability density about 1000x lower than its C/C++ code, and Rust changes with a 4x lower rollback rate [V, 19] (the 1000x figure was questioned by commenters [S, 20]) | The bug class that historically dominated Android vulnerabilities [V, 19] | Safe |
| Same code on the desktop harness | `cargo build` for Linux targets; no emulator needed [V, 17] | CMake for both; workable | JVM on desktop; ML runtime would need JVM bindings per OS [I] |
| Future iOS port | Tier 2 Apple targets; UniFFI generates Swift [V, 17; V, 10] | Works, but no binding generator of UniFFI's quality [I] | Only through Kotlin Multiplatform/Native, a different runtime with different performance [I] |
| Contributor friendliness | One toolchain (`rustup`, `cargo test`, `cargo bench`, `cargo fuzz`) [I] | CMake + NDK + sanitizers; more ways to get it wrong [I] | Easiest for Android developers [I] |
| Android build complexity | `cargo-ndk` or Mozilla's `rust-android-gradle` (0.11.0 needs AGP 9) [V, 15; V, 16] | NDK + CMake via AGP's `externalNativeBuild` | None |
| APK size | Static `std`, LTO and `strip`: on the order of 1 MB for an engine of this size [I] | Similar [I] | Smallest [I] |

Prior art: AOSP LatinIME keeps its decoder in C++ behind JNI (`native/jni`) [V, 36b], and HeliBoard and FUTO Keyboard inherit that design (FUTO adds its LM code under `native/jni/src/ggml/`) [S, 35b]; FUTO runs its ~36M-parameter Llama-architecture LM through llama.cpp [S, 35; S, 35b], and a FlorisBoard contributor implemented suggestions as a native Rust engine because of JVM heap limits [S, 37].

### 2.2 The 16 KB page size requirement (verified)

- Google Play requires apps targeting Android 15+ to support 16 KB memory pages on 64-bit devices; the current Android guide says that from **2027-02-01** app updates that don't comply can't be released [V, 1]. The original announcement set 2025-11-01 for all new apps and updates [V, 2]. Kern targets the latest SDK (D2), so it must comply from its first upload.
- NDK r28+ links 16 KB-aligned by default; r27 and lower need `-Wl,-z,max-page-size=16384`. AGP 8.5.1+ zip-aligns uncompressed `.so` files at 16 KB [V, 1].
- RELRO alignment is a second, subtle requirement: the end of `GNU_RELRO` must be 16 KB-aligned or followed by no writable data, otherwise the app segfaults on 16 KB devices [V, 1].
- **Rust specifics.** rustc's Android target specs don't set a page size, so the result depends on the NDK linker's default [S, 3]. Rustup's prebuilt `libstd` **dylib** is 4 KB-aligned, which only matters with `-C prefer-dynamic`; normal static linking is fine [S, 3]. Recommendation: pin NDK r28+ (or newer LTS), also pass `-C link-arg=-Wl,-z,max-page-size=16384` in `.cargo/config.toml` for the Android targets so the result doesn't depend on the NDK default, and make CI fail on any `LOAD` alignment below `0x4000` and on a misaligned RELRO, using `llvm-readelf -lW` and `zipalign -c -P 16 -v 4` [V, 1].
- The three ML runtimes' current prebuilt arm64 libraries are all 16 KB-aligned (`LOAD align 0x4000`) [V, measured].

### 2.3 Build integration on Android

- `cargo-ndk` wraps Cargo with the NDK environment and can write the `jniLibs` layout directly (`cargo ndk -t arm64-v8a -o jniLibs build --release`) [V, 15].
- Mozilla's `rust-android-gradle` runs `cargo build` per target as a Gradle task wired before `merge*JniLibFolders`; 0.11.0 needs AGP 9.0+ and Gradle 9.1+ [V, 16].
- Recommendation [I]: a small typed Gradle convention plugin in `build-logic/` that calls `cargo ndk` for the ABI(s) of the variant and generates the UniFFI Kotlin sources. It keeps the build explicit and avoids depending on a third-party plugin's release cadence; `rust-android-gradle` is the fallback if writing it costs more than expected.
- F-Droid accepts Rust ecosystem dependencies and libraries from trusted Maven repositories, but forbids non-free dependencies such as Play services and prefers building from source [V, 40]. This favors runtimes that can be built from source or come from Maven Central/Google Maven, and rules out the Play services LiteRT runtime.

---

## 3. Question 2: bindings

### 3.1 Per-call overhead, with sources

| Mechanism | Per-call cost | Where measured | Tag |
|---|---|---|---|
| Regular JNI | 115 ns | Nexus 6P (2015 hardware) | [V, 12] |
| `@FastNative` | 35 ns | Nexus 6P | [V, 12] |
| `@CriticalNative` | 25 ns; "almost as cheap as a non-inline call in C/C++" since Android 12 when arguments fit in registers | Nexus 6P / Android docs | [V, 12; V, 13] |
| UniFFI 0.32 over JNA, release build, string argument | 25–28 µs median, 32–40 µs p90 (debug 90 µs) | arm64 Android 15 emulator on M2 Max | [S, 9] |
| Same, breakdown | bare JNA primitive call 51 ns; one `RustCallStatus` allocation 4.0 µs; lowering a 1-char `String` 20 µs | same | [S, 9] |
| UniFFI with the per-thread status buffer (merged 2026-11-09, unreleased) | no-op method 23.6 µs → 0.45 µs; no-op function 18.0 µs → 0.34 µs | arm64 Android emulator, non-debuggable | [V, 7] |
| Same, desktop | call-only 1.98 µs → 64 ns; strings 13.85 → 4.56 µs; records 16.33 → 4.85 µs | M4 Max, JDK 21 | [V, 7] |
| UniFFI callbacks from non-JVM threads | +~17 µs per callback (JNA attach/detach every time) | desktop HotSpot | [V, 8] |

Budget check [I]: a keystroke needs one "touch event in, suggestions out" round trip, maybe two. At today's worst case of ~40 µs p90 that is 0.5% of the 8 ms p99 budget, and below 0.1% after the merged fix ships. Emulator numbers on Apple silicon understate a mid-range phone; the canvist author expects real phones to be slower [S, 9], so the reference-device benchmark (task below) is what decides.

### 3.2 UniFFI

- Mozilla's Rust binding generator; built-in Kotlin, Swift, Python and Ruby; used heavily in Firefox for Android and iOS; MPL-2.0; still pre-1.0, so "more advanced things might break as you upgrade" [V, 10].
- Latest release 0.32.2 (2026-09-22). 0.32.0 made `&[u8]` arguments zero-copy (pointer plus length) and requires a direct `java.nio.ByteBuffer` for them; the unreleased branch adds zero-copy `&mut [u8]`, the per-thread status buffer, and an **experimental JNI-based Kotlin generator** (`uniffi-bindgen-kotlin-jni`) that drops JNA entirely [V, 6; V, 11].
- Callbacks: foreign traits let Kotlin implement a Rust trait (`#[uniffi::export(foreign)]`); arguments are by value only; reference cycles leak; errors need `From<UnexpectedUniFFICallbackError>` [V, 31].
- Cost of JNA itself: `libjnidispatch.so` adds 162 KiB on arm64 plus 218 KiB of classes [S, 9].

Design rules that make UniFFI fit Kern's hot path [I]:

1. **Kotlin owns the engine thread.** A single-threaded executor created in Kotlin calls into Rust synchronously, so no Rust-created thread ever calls back into the JVM (avoids the 17 µs attach/detach [V, 8] and JNI's thread-attachment pitfalls [V, 13]).
2. **Coarse calls.** One call per input event batch and one per suggestion request, never per touch sample or per candidate.
3. **Bulk data as bytes.** Swipe traces cross as one zero-copy `&[u8]` (packed `f32` x, y, t) in a reused direct `ByteBuffer` [V, 6]; suggestion results come back as one record list.
4. **No callback interfaces on the hot path.** Callbacks are fine for cold paths (for example asking Kotlin to open a language pack file).
5. **Measure and gate.** A benchmark in the instrumented test suite records p50/p99 per call on the reference device; above a threshold (proposal: 100 µs p99), that call moves to the escape hatch.

### 3.3 Hand-written JNI and the `jni` crate (escape hatch)

- `jni` 0.22.x (latest 0.22.4, 2026-03-16) is a major safety redesign: `EnvUnowned`/`Env`, runtime-checked local-reference frames, `native_method!` and `bind_java_type!` macros that check signatures at compile time, and `register_native_methods` for explicit registration [V, 14]. Apache-2.0 [V, 41].
- Android recommends `RegisterNatives` from `JNI_OnLoad`, direct `ByteBuffer`s for shared data, and keeping JNI traffic between few threads [V, 13].
- `@FastNative` and `@CriticalNative` became public, CTS-tested API in Android 14 (minSdk 34 for Kern) and require `RegisterNatives`; `@CriticalNative` methods take and return primitives only; neither may run long because GC can't suspend the thread [V, 13]. They fit tiny, bounded calls such as "touch down at (x, y, t)" that append to a native ring buffer [I].

### 3.4 Others considered

- **Diplomat** and **Interoptopus** are listed by UniFFI as alternatives with a C/C++ focus [V, 10]; neither offers a better Kotlin story [I].
- **Java FFM (Panama)** is not available on Android's ART [I].

---

## 4. Question 3: ML inference runtime

Model profile Kern needs (from `docs/product.md`): swipe decoder (encoder over ~50–200 trace points, CTC or attention head), possibly a small neural LM queried incrementally with a KV cache, 5–30M parameters, int8, CPU only (GPU/NPU initialization and delegation overheads don't pay off at this size, and NPU access is vendor-specific [V, 32; V, 24]) [I].

### 4.1 Comparison

| | ExecuTorch 1.6 | LiteRT 2.3 | ONNX Runtime 1.32 | tract 0.23 | candle | burn 0.22 |
|---|---|---|---|---|---|---|
| License | BSD-3-Clause [V, 23] | Apache-2.0 [V, 41] | MIT [V, 41] | MIT/Apache-2.0 [V, 29] | Apache-2.0/MIT [V, 30] | MIT/Apache-2.0 [V, 33] |
| Latest | 1.6.0, 2026-11-12; Maven AAR 1.5.1 [V, 24; V, measured] | 2.3.2, 2026-10-26 [V, 21] | 1.32.0, 2026-11-13 [V, 27] | 0.23.10, 2026-11-05 [V, 29] | n/a | 0.22 [V, 33] |
| arm64 `.so` in official AAR (uncompressed / compressed) | 9.06 MB / 3.28 MB [V, measured] | 5.63 MB / 2.34 MB [V, measured] | 36.3 MB / 13.7 MB [V, measured] | compiled into the engine `.so` | same | same |
| Size with custom build | core runtime ~50 KB without kernels; selective build example 1,248 → 203 KiB on Cortex-M [V, 24] | selective build of op kernels [V, 22] | reduced-op minimal build, ORT format required; one project 25.8 → 2.3 MB [V, 27b; S, 27c] | n/a | n/a | n/a |
| Rust access | third-party `executorch` crate 0.14.0 (2026-11-14, Apache-2.0, one maintainer) [V, 28]; or a first-party C shim | **official** crate `google-ai-edge-litert` 0.2.0 (2026-11-12) over the LiteRT C API; build script downloads prebuilt binaries [V, 21b] | `ort` 2.0.0-rc.15 (still RC), 26.6M downloads [V, 27d] | native | native | native |
| Linux x86_64 for the harness | yes; XNNPACK "always available on host machines"; the Python wheel ships a C++ SDK since 1.5 [V, 25; V, 24] | prebuilt `libLiteRt.so` for linux_x86_64 (11.1 MB) [V, measured; V, 21c] | yes [V, 27] | yes [V, 29] | yes | yes |
| int8 on CPU | PT2E/torchao quantization lowered to XNNPACK [V, 25] | dynamic-range int8 ("4x smaller, 2–3x speedup"), full int8 ("3x+") [V, 22b] | dynamic (recommended for RNN/transformers) and static QDQ; gains on Arm with dot-product [V, 27e] | not documented in README [unverified] | llama.cpp-style quantized types, LLM-oriented [V, 30] | not documented in README [unverified] |
| GPU/NPU | Vulkan, QNN, CoreML backends [V, 24] | CompiledModel GPU (OpenCL/OpenGL, WebGPU) and NPU (Tensor, Qualcomm, MediaTek, Samsung, Intel) [V, 32] | NNAPI EP frozen (NNAPI deprecated in Android 15 [V, 4]; no plans to extend the EP [S, 5]); QNN EP | Metal, CUDA [V, 29] | CUDA, Metal | wgpu, Vulkan, CUDA [V, 33] |
| Notes | Meta paper reports XNNPACK CPU latency "extremely strong" vs LiteRT and ONNX on Galaxy S25 and Pixel 9 Pro XL, but it is Meta's own paper; XNNPACK delegate lacks tied embeddings, duplicating the embedding table [V, 24] | C++ API churn in 2.1.x; v2 Maven `Interpreter` is CPU-only, GPU needs `CompiledModel` [V, 21; V, 32] | biggest by far | pure Rust, used at Sonos for wake word and streaming ASR, nightly ARM benchmarks [V, 29]; no independent comparison with XNNPACK found | "serverless" focus, no Android mention [V, 30] | ONNX import supports "a limited set of ONNX operators" [V, 33] |

Latency reference points for the 5–30M range:

- MobileBERT (25M params) reaches 62 ms on a Pixel 4 at its benchmark sequence length [V, 34]. A 5–10M swipe encoder over ~100 points is a smaller workload; scaled roughly by parameters and sequence length, it lands in the 10–30 ms range on a 2019 flagship in fp32, before int8 [I]. That is inside the 40 ms p95 swipe bar but without much margin on a mid-range 2026 device, so int8 and a measured bake-off are required.
- FUTO Keyboard runs a ~36M-parameter Llama-architecture LM per keystroke with llama.cpp on phones [S, 35; S, 35b], an existence proof for an incremental neural LM at this size [I].

### 4.2 Recommendation: ExecuTorch (XNNPACK), confirmed by a bake-off against LiteRT

Reasons:

1. **No conversion gap.** The deployed graph is the `torch.export` graph; ops XNNPACK doesn't claim fall back to portable kernels instead of failing conversion [V, 25]. The ExecuTorch authors make this "experimentation parity" argument explicitly against conversion-based ONNX and TFLite [V, 24]. LiteRT's converter fails on some ops (its own docs show a transposed convolution failing to lower) [V, 39].
2. **Same runtime in the harness.** The XNNPACK backend runs on host machines [V, 25] and the 1.5+ wheel ships the C++ SDK [V, 24], so the harness links the same runtime version that ships in the APK.
3. **Size and openness.** BSD license [V, 23], 9.1 MB arm64 prebuilt and far smaller with selective build [V, measured; V, 24], buildable from source with CMake, artifacts on Maven Central [V, measured], which F-Droid accepts [V, 40].
4. **Fits the search design.** Dynamic loops are not supported in export, so loops run in application code [V, 25]; Kern wants beam search and lexicon constraints in Rust anyway. Multi-method export (1.5) lets one `.pte` carry `encode` and `decode_step` [V, 24]. Dynamic shapes are supported with explicit bounds [V, 25].
5. **Stability policy.** Stable APIs need a deprecation of at least two minor releases before removal [V, 26].

Conditions and mitigations:

- **Bake-off before locking.** Export the first real swipe model and LM to both `.pte` (ExecuTorch) and `.tflite` (LiteRT), int8, and measure p50/p95/p99 on the reference device and on a low-end device, with 1, 2 and 4 threads, cold and warm. If LiteRT is clearly faster on Kern's models, use LiteRT: its official C API and Rust crate [V, 21b] make that switch cheap as long as the engine only sees a `Model` trait [I].
- **Rust binding.** Prefer a first-party, minimal `extern "C"` shim (load, run method with input/output byte buffers) compiled with the `cc`/`cmake` crate, over the third-party crate, because its bus factor is one [V, 28; I]. Re-evaluate the third-party crate after the spike.
- **Tied embeddings.** Until the XNNPACK delegate supports them [V, 24], an LM with tied input/output embeddings stores the table twice. Either untie them at training time or accept the size; measure.
- **Numeric parity.** XNNPACK selects different kernels on x86_64 and arm64, so results will not be bit-identical between harness and phone [I]. Run the harness on Linux arm64 in CI as well (GitHub's `ubuntu-24.04-arm` runners are free for public repositories [V, 38]) and compare scores, not bits, against the x86 run.

Ruled out for 1.0:

- **ONNX Runtime**: 36 MB arm64 prebuilt [V, measured]; getting it small needs a custom minimal build and the ORT model format [V, 27b]; Rust crate still a release candidate [V, 27d]; its Android accelerator path (NNAPI) is deprecated by Android [V, 4].
- **LiteRT through Google Play services**: smaller APK (~8 MB saved) but a proprietary dependency, and no NPU yet [V, 32]; conflicts with D1 and F-Droid [V, 40].
- **tract**: attractive (pure Rust, one toolchain, same code everywhere) but int8 support and ARM speed relative to XNNPACK are unverified [V, 29]. Worth keeping as a third contestant in the bake-off if a contributor wants to own it.
- **candle, burn**: LLM/GPU-oriented or limited ONNX op coverage, no Android story [V, 30; V, 33].
- **llama.cpp/ggml** (MIT [V, 41]): proven for a keyboard LM by FUTO [S, 35], but each architecture is written by hand in C/C++ and it doesn't cover a CTC swipe encoder without that work [I]. Reconsider only if the LM bake-off shows a decode-speed gap, which the ExecuTorch paper itself reports for single-token decode on one device [V, 24].

---

## 5. Question 4: training-to-device pipeline

| Path | How it works (late 2026) | Pain points |
|---|---|---|
| **ExecuTorch** | `torch.export.export` → `to_edge_transform_and_lower(..., partitioner=[XnnpackPartitioner()])` → `.to_executorch()` → `.pte` [V, 25]. Quantization in PyTorch before lowering (PT2E/torchao) [V, 25; V, 24]. The `.pte` can be run from Python on the host for checks [V, 25]. | Model must be `torch.export`-able (no data-dependent Python control flow; `torch.cond` for branches, no dynamic loops) [V, 25]; dynamic dims need bounds, and larger bounds cost memory [V, 25]. `.pte` schema-version checks since 1.5 [V, 24] mean re-exporting models when the runtime is upgraded across schema changes [I]. |
| **LiteRT (`litert-torch`, formerly `ai-edge-torch`)** | Renamed in Jan 2026; old package frozen at 0.7.2 [S, 39b]. `litert_torch.convert(model, sample_args)` → `.tflite`; built on `torch.export`, with a newer backend replacing Torch XLA [V, 39]. Quantization via PT2E quantizer or the LiteRT Quantizer after conversion; quantizing via converter flags is deprecated [V, 39; V, 22b]. | Lowering to TFLite ops can fail per op; `forward` must take positional tensors; no kwargs [V, 39]. Two quantization stacks in transition [V, 39]. |
| **ONNX** | `torch.onnx.export(..., dynamo=True)` is the default since PyTorch 2.9; TorchScript exporter deprecated; `dynamic_axes` replaced by `dynamic_shapes` [V, 18]. | Recent regressions reported with the new default (e.g. hard-coded batch dimension with buffers) [S, 18b]; QAT semantics may not translate to ONNX quantization [V, 24]. |

Recommendation: the ExecuTorch path, because it has the fewest representation changes between training and device [V, 24; V, 25], and it is the one the evaluation harness can reproduce exactly (same `.pte`, same runtime).

Model-design rules that keep any path painless [I]:

- Export only the network (encoder, decoder step). CTC collapse, beam search, lexicon/trie constraints and LM fusion live in Rust, where they are tested and shared by phone and harness.
- Fixed-rank inputs with bounded dynamic length (trace points, token count); padding plus mask when cheaper.
- Avoid ops with weak XNNPACK coverage; check the delegation report after every export in CI (the partitioner tells which nodes fell back) [V, 25].
- Reproducible export: pinned PyTorch/ExecuTorch versions, export script in the repo, checksum of the `.pte` recorded with the model card (fits D6).
- CTC needs no special support at inference: the model outputs per-frame logits; the CTC loss exists only in training [I].

---

## 6. Question 5: persisting user data

### 6.1 Where

- Android 10+ devices must use file-based encryption (full-disk encryption is not allowed on new devices) [V, 42]. Credential-encrypted (CE) storage is available only after the first unlock; device-encrypted (DE) storage is available during Direct Boot [V, 43].
- A keyboard must work before first unlock (to type the password); AOSP LatinIME is `directBootAware="true"` [V, 36]. Android's guidance: use DE storage only for what Direct Boot needs and keep private user information in CE storage [V, 43].
- Recommendation [I]: language packs and models (not personal) may live in DE storage. **All personal data stays in CE storage.** Before `ACTION_USER_UNLOCKED` / `UserManager.isUserUnlocked()` [V, 43], the engine runs without personalization and records nothing. The same "no learning" mode applies to password fields and `IME_FLAG_NO_PERSONALIZED_LEARNING` (product non-goal).

### 6.2 Format

- **SQLite inside the engine** via `rusqlite` (MIT [V, 41]) with the bundled amalgamation: ACID even after power loss, verified by crash-simulation tests, public domain, under 900 KiB [V, 44]. Bundling means the same SQLite version on the phone and in the harness [I]. Use it for the personal dictionary, learned n-gram counts, the undo-learning record ("reverted correction X→Y"), and per-word language stats. WAL mode, one writer (the engine thread), batched writes off the keystroke path, schema version and forward-only migrations [I].
- **Touch model**: small, fixed-shape parameters (per-key offsets/covariances). A versioned binary snapshot with a checksum, written to a temp file then atomically renamed [I]. If the checksum fails, discard and fall back to the default model; never crash on corrupt learned data [I].
- Keep learned data separate from the shipped language packs, so a pack update never touches user data [I].

### 6.3 Encryption at rest

- File-based encryption already encrypts CE storage with keys tied to the user's credential [V, 42; V, 43].
- Jetpack Security Crypto (`EncryptedFile`, `EncryptedSharedPreferences`) is deprecated as of 1.1.0 with no further releases [V, 45; S, 45b].
- An extra app-level layer (Tink AEAD or SQLCipher with a Keystore-held key) would protect against extraction from an unlocked, compromised device, but a non-exportable Keystore key makes the data unrestorable on another device [S, 45b], which breaks device transfer [I].
- Recommendation: no app-level encryption in 1.0; rely on FBE plus CE-only placement. Keep the OpenRouter key in the Android Keystore as D1 says, and exclude it from every backup/transfer domain, since a wrapped key can't be decrypted elsewhere [S, 45b].

### 6.4 Auto Backup and device-to-device transfer

- Auto Backup uploads up to 25 MB per app to Google Drive, end-to-end encrypted with the lock-screen secret on Android 9+; it runs about nightly when idle and on Wi-Fi, and **the system shuts the app down during backup** [V, 46].
- Android 12+ uses `dataExtractionRules` with separate `<cloud-backup>`, `<device-transfer>` and (Android 16 QPR2+) `<cross-platform-transfer platform="ios">` sections; a missing section means everything except cache/no-backup is included; `disableIfNoEncryptionCapabilities="true"` on `<cloud-backup>` blocks unencrypted cloud backups but does not affect D2D [V, 46]. On some OEMs `allowBackup="false"` disables cloud backup but not D2D [V, 46].
- Recommendation (João's call, because it touches the privacy promise "nothing you type leaves the phone unless you ask") [I]:
  - `<cloud-backup>`: exclude personal data by default. Optionally a user setting to include it, which then also requires `disableIfNoEncryptionCapabilities="true"`.
  - `<device-transfer>`: include the personal dictionary and learned data, exclude caches and the API key.
  - `<cross-platform-transfer>`: declare it explicitly with exclusions until an iOS app exists.
  - Explicit export/import of the personal dictionary in settings, as a documented file format, so the user owns the data independent of Google backup.
- Open test item [I]: whether the nightly backup shutdown can kill the IME process while it is bound, causing a cold start against the 250 ms bar. Test it with `bmgr` on the reference device.

---

## 7. Open risks

1. **UniFFI per-call cost on a real mid-range phone** is only known from emulators [S, 9; V, 7]. Mitigation: benchmark in the first engine spike; escape hatch via `jni` + `@CriticalNative` [V, 13].
2. **UniFFI is pre-1.0 and its JNI backend is experimental** [V, 10; V, 6]. Mitigation: pin versions; keep the generated surface small.
3. **Runtime choice rests on a vendor paper** for the CPU-speed claim [V, 24]. Mitigation: the ExecuTorch-vs-LiteRT bake-off on Kern's own models is a precondition of the decision.
4. **ExecuTorch from Rust** relies on a C++ shim or a single-maintainer crate [V, 28]. Mitigation: a first-party shim of a few functions.
5. **Tied embeddings unsupported in the XNNPACK delegate** inflate LM files [V, 24].
6. **Harness/phone numeric drift** across x86_64 and arm64 kernels [I]. Mitigation: arm64 CI runners [V, 38]; compare metrics with tolerances.
7. **LiteRT API churn** if it wins the bake-off: several breaking C++ API changes during 2.1.x, and its Rust crate downloads binaries from Google Cloud Storage at build time [V, 21; V, 21b]; vendoring from the Maven AAR keeps builds reproducible and F-Droid-compatible [V, 40].
8. **16 KB compliance regressions** from any new native dependency [V, 1]. Mitigation: CI alignment check on every APK/AAB.
9. **Swipe latency margin** on mid-range hardware is estimated, not measured [I]. Mitigation: int8 from the start, thread-count tuning, and a decoder design that does incremental work while the finger moves.
10. **Backup policy vs privacy promise** needs an explicit product decision (section 6.4).

## 8. Suggested next steps

1. Engine spike (Rust): a workspace with `kern-engine` (pure Rust), `kern-ffi` (UniFFI), `kern-harness` (CLI), built with NDK r28+ and the 16 KB CI check.
2. Per-call benchmark of the UniFFI surface on the reference device (p50/p99, allocation per call).
3. Runtime bake-off: one small swipe encoder and one small LM exported to ExecuTorch and LiteRT, int8, measured on device and in the harness on x86_64 and arm64.
4. Then record the decisions in `docs/decisions.md`.

---

## Measurements made for this document

Done on 2026-11-17 without downloading full artifacts: HTTP range requests read only each archive's ZIP central directory (for sizes) and the first deflated block of each arm64 `.so` (for the ELF program headers). Scripts were throwaway and not committed.

| Artifact | arm64 `.so` uncompressed | compressed in AAR | `LOAD` alignment |
|---|---|---|---|
| `org.pytorch:executorch-android:1.5.1` (Maven Central) | `libexecutorch.so` 9,057,072 B | 3,275,540 B | 0x4000 |
| `com.google.ai.edge.litert:litert:2.3.2` (Google Maven) | `libLiteRt.so` 5,629,696 B | 2,337,938 B | 0x4000 |
| `com.microsoft.onnxruntime:onnxruntime-android:1.32.0` (Maven Central) | `libonnxruntime.so` 36,307,784 B (+ JNI 122,192 B) | 13,739,663 B | 0x4000 |
| LiteRT C++ SDK runtime 2.3.0, `linux_x86_64/libLiteRt.so` (GCS, HEAD) | 11,095,568 B | n/a | not checked |
| LiteRT C++ SDK runtime 2.3.0, `android_arm64/libLiteRt.so` (GCS, HEAD) | 5,475,936 B | n/a | not checked |

Whole AARs: ExecuTorch 7.6 MB (arm64 + x86_64), LiteRT 7.6 MB (arm64, armv7, x86_64), ONNX Runtime 59.3 MB (four ABIs). The ExecuTorch AAR library likely includes more than a keyboard needs (portable kernels for all ops, JNI layer); a selective build would be smaller [I].

---

## Sources

1. Android Developers, "Support 16 KB page sizes": https://developer.android.com/guide/practices/page-sizes
2. Android Developers Blog, "Prepare your apps for Google Play's 16 KB page size compatibility requirement" (2025-05): https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html
3. waterui issue #156 (rustup prebuilt libstd dylib 4 KB-aligned; Android target specs set no page size): https://github.com/water-rs/waterui/issues/156 ; search summary of related Rust projects: https://github.com/GregoryConrad/native_toolchain_rust/issues/116
4. Android NDK, Neural Networks API (deprecated in Android 15): https://developer.android.com/ndk/guides/neuralnetworks
5. ONNX Runtime issue #22346 (no plans to extend NNAPI EP): https://github.com/microsoft/onnxruntime/issues/22346
6. UniFFI CHANGELOG: https://raw.githubusercontent.com/mozilla/uniffi-rs/main/CHANGELOG.md
7. UniFFI PR #3056, per-thread RustCallStatus buffer (merged 2026-11-09): https://github.com/mozilla/uniffi-rs/pull/3056
8. UniFFI issue #3011, Kotlin/JNA per-call allocation: https://github.com/mozilla/uniffi-rs/issues/3011
9. canvist PR #87, UniFFI Kotlin overhead on Android emulator: https://github.com/canvist/canvist/pull/87
10. UniFFI README: https://github.com/mozilla/uniffi-rs
11. UniFFI issue #2672, JNI backend: https://github.com/mozilla/uniffi-rs/issues/2672
12. AOSP, ART improvements (JNI transition timings): https://source.android.com/docs/core/runtime/improvements
13. Android Developers, JNI tips (`@FastNative`/`@CriticalNative` public in Android 14, `RegisterNatives`, direct buffers): https://developer.android.com/training/articles/perf-jni
14. jni-rs CHANGELOG: https://github.com/jni-rs/jni-rs/blob/master/CHANGELOG.md
15. cargo-ndk: https://github.com/bbqsrc/cargo-ndk
16. rust-android-gradle: https://github.com/mozilla/rust-android-gradle
17. rustc platform support, Android: https://doc.rust-lang.org/rustc/platform-support/android.html ; all targets: https://doc.rust-lang.org/rustc/platform-support.html
18. PyTorch, torch.export-based ONNX exporter: https://docs.pytorch.org/docs/main/onnx_export.html
18b. PyTorch issue #170172 (dynamo export hard-codes batch dim): https://github.com/pytorch/pytorch/issues/170172 ; forum report: https://discuss.pytorch.org/t/export-pytorch-model-to-onnx-opencv/223818
19. Google Security Blog, "Rust in Android: move fast and fix things" (2025-11): https://blog.google/security/rust-in-android-move-fast-fix-things/
20. LWN discussion of the above: https://lwn.net/Articles/1046397/
21. LiteRT releases: https://github.com/google-ai-edge/LiteRT/releases
21b. LiteRT Rust binding (Cargo.toml, build.rs, wrapper.h over `litert/c` headers): https://github.com/google-ai-edge/LiteRT/tree/main/litert/rust ; crate: https://crates.io/crates/google-ai-edge-litert
21c. LiteRT C++ SDK: https://developers.google.com/edge/litert/next/cpp_sdk
22. LiteRT, reduce binary size: https://ai.google.dev/edge/litert/build/reduce_binary_size
22b. LiteRT, post-training quantization: https://developers.google.com/edge/litert/conversion/tensorflow/quantization/post_training_quantization
23. ExecuTorch LICENSE (BSD): https://github.com/pytorch/executorch/blob/main/LICENSE
24. ExecuTorch releases: https://github.com/pytorch/executorch/releases ; README (50 KB base runtime, production use): https://github.com/pytorch/executorch ; paper "ExecuTorch: A Unified PyTorch Solution to Run AI Models On-Device" (MLSys 2026), sections on selective build, tied embeddings, CPU comparison: https://arxiv.org/abs/2605.08195
25. ExecuTorch, model export and lowering: https://docs.pytorch.org/executorch/stable/using-executorch-export.html
26. ExecuTorch, API life cycle and deprecation policy: https://docs.pytorch.org/executorch/stable/api-life-cycle.html
27. ONNX Runtime releases: https://github.com/microsoft/onnxruntime/releases ; install: https://onnxruntime.ai/docs/install/
27b. ONNX Runtime custom/minimal build: https://onnxruntime.ai/docs/build/custom.html
27c. Third-party reduced build size report (25.8 MB → 2.3 MB): https://github.com/JuanDaGonz18/ConVia/pull/17
27d. `ort` crate: https://crates.io/crates/ort
27e. ONNX Runtime quantization: https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html
28. `executorch` Rust crate (third party): https://crates.io/crates/executorch ; https://github.com/barakugav/executorch-rs
29. tract README: https://github.com/sonos/tract ; versions: https://crates.io/crates/tract-onnx
30. candle README: https://github.com/huggingface/candle
31. UniFFI user guide, foreign traits: https://mozilla.github.io/uniffi-rs/latest/foreign_traits.html
32. Google Developers Blog, "Smaller apps and faster GPU inference with LiteRT in Google Play services" (2026-10-28): https://developers.googleblog.com/smaller-apps-and-faster-gpu-inference-with-litert-in-google-play-services/ ; LiteRT NPU guide: https://developers.google.com/edge/litert/next/npu ; LiteRT for Android: https://developers.google.com/edge/litert/android
33. burn README: https://github.com/tracel-ai/burn
34. Sun et al., "MobileBERT" (62 ms on Pixel 4): https://arxiv.org/abs/2004.02984
35. FUTO Keyboard wiki, Keyboard LM docs (seen through search results; the page did not load directly): https://gitlab.futo.org/keyboard/keyboard-wiki/-/wikis/Keyboard-LM-docs ; llama.cpp issue #8204: https://github.com/ggml-org/llama.cpp/issues/8204
35b. Community FUTO model notes (~36M params): https://github.com/itzune/futo-basque/blob/main/RESEARCH.md
36. AOSP LatinIME manifest (`directBootAware`): https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/java/AndroidManifest.xml
36b. AOSP LatinIME native JNI sources: https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/native/jni/
37. FlorisBoard PR #2970, native Rust NLP engine: https://github.com/florisboard/florisboard/pull/2970
38. GitHub Changelog, arm64 hosted runners GA for public repositories (2025-08-07): https://github.blog/changelog/2025-08-07-arm64-hosted-runners-for-public-repositories-are-now-generally-available/
39. litert-torch PyTorch converter docs: https://github.com/google-ai-edge/litert-torch/blob/main/docs/pytorch_converter/README.md ; overview: https://developers.google.com/edge/litert/conversion/pytorch/overview
39b. `ai-edge-torch` on PyPI (renamed, frozen): https://pypi.org/project/ai-edge-torch/
40. F-Droid Inclusion Policy: https://f-droid.org/docs/Inclusion_Policy/
41. Licenses as reported by the GitHub API for each repository (checked 2026-11-17): google-ai-edge/LiteRT, microsoft/onnxruntime, ggml-org/llama.cpp, rusqlite/rusqlite, jni-rs/jni-rs, mozilla/uniffi-rs, e.g. https://api.github.com/repos/google-ai-edge/LiteRT/license
42. AOSP, Encryption (FBE required for new devices on Android 10+): https://source.android.com/docs/security/features/encryption
43. Android Developers, Direct Boot: https://developer.android.com/privacy-and-security/direct-boot
44. SQLite, About: https://www.sqlite.org/about.html
45. Android API reference, EncryptedSharedPreferences (deprecated in 1.1.0): https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences
45b. OWASP MASTG-KNOW-0036: https://mas.owasp.org/MASTG/knowledge/android/MASVS-STORAGE/MASTG-KNOW-0036/
46. Android Developers, Auto Backup: https://developer.android.com/identity/data/autobackup ; backup security recommendations: https://developer.android.com/privacy-and-security/risks/backup-best-practices
