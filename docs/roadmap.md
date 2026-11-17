# Roadmap

Milestones are ordered by dependency. Each has exit criteria that are checked, not estimated: a milestone is done when every criterion is met and shown (a test, a harness score, a trace, a recording). Dates are not set until M1 gives real measurements to plan from.

## M0. Foundations

The skeleton everything else grows on, with the quality gates in place from the first commit.

- Rust workspace for the engine, Android app module, UniFFI bridge, Gradle build that compiles the Rust crates for arm64 and x86_64.
- A minimal IME: shows a QWERTY layout, commits letters, space, backspace and enter through the text field adapter. No prediction yet.
- The bench app of misbehaving text fields, with its first cases.
- CI: Rust format, clippy and tests, Android lint and Kotlin compiler with warnings as errors, 16 KB alignment check, emulator UI tests that type into the bench app.

**Exit:** a debug build installs on the emulator and on a physical phone, types correctly into the bench app and Chrome, and CI is green.

## M1. Measurement

Nothing about quality is decided without this.

- Evaluation harness: replays text through a realistic tap noise model and through recorded or synthetic swipes, runs the real engine, reports per language: word error rate after correction, false correction rate, keystroke savings, top-1 and top-3 accuracy, latency.
- Data pipeline v0 on desktop-home: pinned sources from D11, lexicons and frequency lists for the six languages, held-out test sets.
- Rendering spike (D14) and runtime spike (D8) measured on the reference device.

**Exit:** a baseline report per language committed to the repo, D8 and D14 confirmed or revised.

## M2. Tap typing core

- Touch model, noisy-channel decoder, per-language n-gram models, multilingual mixture with per-word language detection.
- Suggestion strip, autocorrection with one-backspace undo, capitalization and punctuation rules per language, diacritic restoration.

**Exit:** harness scores beat the M1 baseline in every launch language and meet the latency bars in `docs/product.md` on the reference device.

## M3. Swipe

- Training pipeline for the layout-conditioned CTC encoder, export, int8 quantization.
- Decoder in Rust over the union of lexicons, rescored by the language model.
- Swipe trail drawn on the GPU.
- Test sets for every launch language: the real swipes available (D9), plus synthetic swipes where none exist (D18).

**Exit:** top-1 accuracy on the English test split at or above the best published open result; per-language targets set from the first measurements and met; p95 decode under 40 ms on the reference device.

## M4. Personalization, layouts and feel

- User dictionary, learning from undo, adaptive key targets, personal n-gram.
- Layouts for every launch language (QWERTY, AZERTY, QWERTZ, Spanish), number and symbol pages, one-handed and split modes, floating keyboard on tablets and foldables.
- Theme system with Material You dynamic color, tuned haptics, motion, sounds.
- Full TalkBack support.

**Exit:** personalization measurably improves harness scores on simulated users; accessibility audit passes; design review by João.

## M5. AI assistance

- OpenRouter sign-in (OAuth PKCE), key storage, default model list.
- Rewrite, translate, tone, proofread with diff preview.
- Consent flow and privacy screen.

**Exit:** every request verified (in a test against a mock server) to carry the zero-retention settings; no AI action reachable where D13 forbids it.

## M6. Voice

- Recording, OpenRouter transcription and cleanup, preview before insertion (D19).
- Hand-off to the system's voice input when there is no OpenRouter account.

**Exit:** word error rate and end-to-end latency (stop speaking to text shown) measured in every launch language and accepted.

## M7. Smart features

- Smart clipboard (one-time codes, Pix keys, tracking numbers, addresses) with expiry.
- Semantic emoji search on device.

**Exit:** each feature has a measured precision on a labelled test set and no false offers in password or incognito fields.

## M8. Beta

- Full compatibility matrix pass (documented exceptions only where the app itself is at fault and every keyboard fails).
- Onboarding, store listing, Data safety form, F-Droid metadata.
- In-app open source licenses screen, generated at build time from the Gradle and Cargo dependency graphs, replacing the hand-kept list in `THIRD_PARTY_NOTICES.md`.
- Closed testing track on Play.

**Exit:** crash-free sessions above 99.9% across the beta, no open release-blocking issues.

## M9. 1.0
