# Decisions

Each entry records what was decided, why, and what it rules out. Superseded entries stay, marked as such, with a link to what replaced them. Research behind these decisions is in `docs/research/`.

## D1. Fully open source, free, no project-run backend for user text (2026-11-17)

Kern is 100% open source and free. There are no paid tiers. AI features call OpenRouter directly from the phone, billed to the user's own OpenRouter account. No server run by the project ever receives text the user typed.

**Why:** a keyboard sees everything a person types. Trust is the main barrier to adoption, and being auditable end to end, with no server of ours in the loop, is the strongest answer to it.
**Rules out:** analytics SDKs, a Kern account, a proxy that holds an API key, cloud sync run by the project.

## D2. Recent Android only (2026-11-17, reason corrected the same day)

`minSdk` 34 (Android 14). `compileSdk` and `targetSdk` 37 (Android 17, the current stable release). Play has required target 36 or higher since 2026-08-31.

**Why:** fewer version branches, and every modern text API a keyboard uses is available without fallbacks: `replaceText`, text bounds queries, handwriting gestures and finger/stylus detection (34), AGSL shaders (33), haptic compositions (30). Stylus handwriting itself arrived for IMEs in API 33; handwriting delegation (34) is implemented by apps, not keyboards. See `docs/research/android-platform.md` §1.

## D3. Built from scratch (2026-11-17)

Kern does not fork AOSP LatinIME, HeliBoard, FlorisBoard or FUTO Keyboard. Their code and issue trackers are studied, with attribution where an idea is adopted. FUTO's code and model weights are never copied, linked or distilled from: its keyboard and model licenses are not open source, and its `swipe-library` is GPL-3.0.

**Why:** the engine design (multilingual decoding, layout-conditioned swipe, an evaluation harness running the real engine) does not fit any existing codebase without rewriting most of it.

## D4. Language of the codebase and docs (2026-11-17)

Code, comments, commits and public docs are in English, so the project can draw contributors from anywhere. A Portuguese README is provided alongside the English one.

## D5. Launch languages (2026-11-17)

English, Portuguese (BR and PT), Spanish, French, German, Italian. See `docs/product.md`.

**Why:** six languages done to the quality bar beat fifty done poorly. They share a script, so the first models can focus on quality instead of script handling.

## D6. Training runs on desktop-home (2026-11-17)

Model training runs on the home desktop (RTX 3070, 8 GB, Windows). Corpora and checkpoints live there, not on the development laptop. Training is reproducible from scripts in this repository, with pinned data sources (exact revisions) and checksums.

## D7. Engine in Rust, bound to Kotlin with UniFFI (2026-11-17)

The typing engine (layouts and geometry, touch model, tap and swipe decoding, language model queries, personalization, user data) is a Rust workspace. The Android app reaches it through UniFFI-generated Kotlin bindings. The evaluation harness links the same crates on Linux, so what is measured is exactly what ships.

- Native libraries are aligned to 16 KB pages (required by Play for apps targeting the latest SDK). The linker flag is set explicitly and CI fails if any `.so` is not aligned.
- If a hot call measures too slow through UniFFI on the reference device, that one call moves to hand-written JNI with `@CriticalNative`/`@FastNative` (public API since 34). Not before it is measured.

**Why:** C++-class latency without memory-safety bugs, one codebase for the phone and the harness, and a path to iOS later. See `docs/research/engine-runtime.md`.

## D8. On-device ML runtime: ExecuTorch, confirmed by a measured spike (2026-11-17, provisional)

Neural models are trained in PyTorch, exported with `torch.export`, quantized to int8 and run with ExecuTorch on the CPU. Beam search and CTC decoding live in Rust, outside the model. Kern uses its own thin Rust wrapper over ExecuTorch's C++ API rather than a third-party crate.

**Provisional until:** one swipe model and one language model are exported to both ExecuTorch and LiteRT and measured on the reference device (latency, memory, binary size). LiteRT replaces ExecuTorch if it wins clearly. ONNX Runtime is ruled out on size (about 36 MB).

## D9. Swipe: layout-conditioned CTC encoder plus multilingual lexicon search (2026-11-17)

A small CTC encoder (under 1M parameters) takes the swipe trajectory and the keyboard geometry and emits key probabilities. A trie-constrained beam search runs over the union of the enabled languages' lexicons, and Kern's multilingual language model rescores the candidates (the same LM as tap typing).

- **Training data:** swipe.futo.org (MIT, about 1.2M swipes, mostly English) and How We Swipe (MIT, about 109K). Synthetic swipes only as a complement, never as the main source: published results show synthetic-only training performs far worse.
- **Gap:** no open real swipes exist for Portuguese or Italian, and very few for German, French and Spanish. Collecting our own is out of scope for now (D18).
- **Before 1.0:** a legal review of Google's active patents on neural gesture decoding (for example US 9,678,664).

See `docs/research/swipe.md`.

## D10. Tap typing: noisy-channel decoder with per-language n-gram models (2026-11-17)

One decoder does correction, completion and next-word prediction: it combines a spatial touch model (where the finger landed) with a language model, using a beam search over lexicon tries. Each language has its own compact lexicon and quantized n-gram model (about 5 to 10 MB). With several languages enabled, each word is scored by a mixture weighted by a per-word language posterior, so mixed sentences need no switch key.

- A neural model is added later only if the harness shows a gain worth its cost: first as a reranker at word boundaries (2 to 6M parameters), never on the per-keystroke path before it is measured there.
- Personalization needs no retraining: a user lexicon, a personal n-gram mixed with the static one, a block list of corrections the user undid, and adaptive key targets.

See `docs/research/language.md`.

## D11. Language data: only sources whose license allows shipping derived models (2026-11-17)

Shipped language packs are built from: Wikipedia and Wiktionary (CC BY-SA 4.0), Tatoeba (CC BY 2.0 FR), Common Voice sentences (CC0), AOSP word lists (Apache-2.0), Europarl, Common Corpus filtered by per-document license, permissively licensed spelling dictionaries, and synthetic chat-style text generated with Apache-2.0 models. Every pack lists its sources and carries the resulting license (CC BY-SA 4.0 where share-alike sources are used).

- **Excluded:** OpenSubtitles, Reddit, Twitter/X, Stack Exchange, OSCAR. GPL-only dictionaries (German, Italian, possibly PT-PT) are used only to measure coverage, not shipped.
- **Still to check before use:** the Leipzig Corpora license per download and the PT-PT dictionary license.
- Web-crawl datasets: not shipped (D20).

## D12. Voice: on-device streaming ASR as an optional download (2026-11-17, superseded by D19)

- **Default engine:** NVIDIA Nemotron 3.5 ASR Streaming 0.6B (weights under OpenMDW-1.1, a permissive license that is not OSI-approved) run with sherpa-onnx (Apache-2.0). It covers all launch languages including pt-BR and pt-PT, streams words while the user speaks, and outputs punctuation. The int8 model is about 650 MB, so it is downloaded on demand with a pinned revision and checksum, and its license ships with it. Parakeet TDT 0.6B v3 (CC BY 4.0) is evaluated against it.
- **Cleanup on device, always:** spoken punctuation, voice commands such as "scratch that" applied as one undoable edit, per-language filler lists.
- **Cleanup in the cloud, optional:** through OpenRouter, shown as a preview before it replaces anything.
- **Not used:** Gemini Nano through ML Kit. It only serves the foreground app, which a keyboard never is, and lacks Portuguese.

See `docs/research/voice-and-cloud.md`.

## D13. Cloud AI through OpenRouter, private by construction (2026-11-17)

- **Sign-in:** OpenRouter's OAuth PKCE flow returns a key billed to the user's account, stored with the Android Keystore. Pasting a key is the fallback.
- **Every request** sets `provider: { zdr: true, data_collection: "deny" }`. Text requests also exclude providers that retain flagged data for abuse review even under zero retention.
- **Models are data, not code:** a default list (currently `gpt-6-luna` with reasoning off for text, falling back to `claude-haiku-5.5`; `whisper-large-v3-turbo` for transcription) that ships with the app and can be overridden by the user. Updating it without an app release would need a server (D1 rules one out), so the defaults change with releases.
- **Respect the app:** no AI action is offered where `EditorInfo.isWritingToolsEnabled()` is false (API 36), in password fields, or with `IME_FLAG_NO_PERSONALIZED_LEARNING`.
- **Consent:** an in-app disclosure before the first request, and a privacy screen listing every request (time, feature, model, size), stored only on the phone.

## D14. IME structure (2026-11-17)

- **Text field adapter:** the only code that talks to `InputConnection`. It assumes apps often report wrong cursor or text state: it keeps an expected-state model, reconciles it with selection updates, and applies per-app workarounds, each with a test that reproduces the app's behavior.
- **Rendering:** the key area is a custom View drawn with Canvas and RenderNodes; everything else (suggestion strip, panels, settings, onboarding) is Compose. A short spike measures this against an all-Compose key area before it is locked.
- **Haptics:** the system keyboard haptic by default, which honors the user's system setting and needs no permission; tuned compositions as an option.

See `docs/research/android-platform.md`.

## D15. Personal data stays on the phone (2026-11-17)

Personal data (user dictionary, learned corrections, touch model) lives in credential-encrypted storage, so nothing is learned or read before the first unlock. It is stored in SQLite inside the engine, the touch model as a checksummed snapshot. It is excluded from Google cloud backup, included in device-to-device transfer, and can be exported and imported manually.

**Why:** cloud backup would send what the user types to Google without them asking, which breaks the promise of D1.

## D16. Testing (2026-11-17)

- Engine: Rust unit and property tests, plus the evaluation harness, whose scores per language are tracked and must not regress.
- IME: a bench app of misbehaving text fields written for Kern, UI tests on a free emulator matrix in GitHub Actions (full system images, not ATD, which lacks a keyboard, Chrome and Settings), and a manual pass on owned phones for apps that can't run in CI (WhatsApp, Instagram and others).
- Latency: Perfetto traces, plus a 240 fps camera for touch-to-photon checks on the reference device.

## D17. License: GPL-3.0-or-later (2026-11-17)

All code in this repository is GPL-3.0-or-later. Model weights and language packs carry the license their sources require (CC BY-SA 4.0 where share-alike data is used, which is one-way compatible with GPLv3), listed in each pack.

**Why:** a keyboard's value rests on trust. GPL keeps every fork open, so nobody can ship a modified Kern that quietly collects text. It also lets language packs use share-alike data.

## D18. No swipe collection for now (2026-11-17)

Kern does not collect swipes from volunteers, by any means, until this decision is revisited. Swipe for every language is trained on the open data in D9, with layout and lexicon conditioning doing the cross-language work.

**Consequence:** there is no real-swipe test set for Portuguese and Italian, and only a small one for German, French and Spanish. Swipe quality in those languages is measured on synthetic swipes and on the small real sets, and the release notes say so plainly. Revisit before 1.0.

## D19. Only the typing core runs models on the phone (2026-11-17)

Models run locally only where the network can't do the job: tap decoding, correction, prediction and swipe. They run on every keystroke within a few milliseconds, must work offline, and sending every keystroke to a server would break D1. They are small (a few MB per language), not large language models.

Everything generative goes through OpenRouter: rewrite, translate, tone, proofread and dictation cleanup. There is no on-device LLM goal.

**Voice in 1.0** (supersedes the on-device engine in D12): the microphone records, OpenRouter transcribes, and a text model cleans up the result, which is shown before it is inserted. Without an OpenRouter account, the microphone key hands off to the system's voice input. On-device streaming recognition (Nemotron through sherpa-onnx, evaluated in `docs/research/voice-and-cloud.md`) stays a candidate for after 1.0, as an optional download for people who want offline voice.

## D20. No web-crawl text in shipped language packs (2026-11-17)

FineWeb-2, HPLT, mC4 and similar crawls are not used to build anything that ships. Their packaging is licensed, but the text belongs to third parties. Informal coverage comes from synthetic chat-style text generated with Apache-2.0 models (D11). They may be used for evaluation only if a license review allows it.

## D21. Application id `io.github.jpsaud501.kern` (2026-11-17)

The package name and application id is `io.github.jpsaud501.kern`, a namespace João controls through GitHub. It can never change after the first Play Store release.

**Why:** a reverse-domain id needs a domain the project owns. Buying one (kernkeyboard.app and kernkeyboard.com were free on 2026-11-17) is optional and would not change the id.

## D22. Native code for arm64-v8a and x86_64 only (2026-11-17)

The engine is built for arm64-v8a (phones) and x86_64 (emulators and Chromebooks). The app filters every other ABI out of the APK, including the extra ones dependencies such as JNA ship.

**Why:** every phone that runs Android 14 (D2) is 64-bit ARM. 32-bit ABIs would add size and test surface for no user. Without the filter, a device on another ABI would install Kern and crash because the engine is missing.

## Open questions for João

- **Q4. Reference device.** A mid-range phone that all performance bars are measured on, plus which phones João owns for manual testing.
