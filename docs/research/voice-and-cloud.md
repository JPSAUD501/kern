# Research: voice input and the OpenRouter layer

Status: research, not a decision. Researched 2026-11-17. Feeds the "voice stack" item still open in `docs/decisions.md`.

Each claim is tagged:

- **[V]** (verified): read in a primary source (model card, official docs, LICENSE, OpenRouter's live API) on 2026-11-17.
- **[S]** (secondary): taken from a third-party article or benchmark site. Plausible, but not checked against the vendor.
- **[I]** (inferred): my own reasoning from the evidence. It needs a measurement or a device test before we rely on it.

Numbers in brackets like [12] point to the source list at the end.

---

## 1. Summary and recommendation

### On-device speech recognition (default, offline)

**Recommended default: NVIDIA Nemotron 3.5 ASR Streaming 0.6B (multilingual), run with sherpa-onnx.**

- **Licenses.** The weights are OpenMDW-1.1, which is permissive: no field-of-use limits, commercial use allowed, attribution and notice retention when redistributing [V][1][2]. sherpa-onnx is Apache-2.0 [V][5].
- **Languages.** EN, ES, FR, DE, IT and PT are all in its "transcription-ready" tier. Both pt-BR and pt-PT are included [V][1].
- **Streaming and output.** It is a true streaming model with configurable chunks from 80 ms to 1.12 s, and it outputs punctuation and capitalization [V][1].
- **Accuracy.** FLEURS WER at the 560 ms chunk: en 7.99, es 4.26, fr 9.45, de 8.42, it 4.41, pt 5.65 [V][1].
- **Language selection.** sherpa-onnx supports it as an online transducer. The language is set per stream, or detected automatically [V][6].
- **Size.** About 650 MB as int8 [V][6].
- **Precedent.** FUTO already ships Nemotron in its Voice Input app [V][14].

**Alternative to benchmark against it: NVIDIA Parakeet TDT 0.6B v3** (CC-BY-4.0, 25 European languages, punctuation, about 650 MB int8) [V][3][7].

- It is more accurate on FLEURS: en 4.85, es 3.45, fr 5.15, de 5.04, it 3.00, pt 4.76 [V][3]. It is also very fast [V][4].
- It is not a streaming model. sherpa-onnx can only "simulate" streaming, by re-decoding a growing buffer [V][7].
- It was trained on European Portuguese, while most benchmarks use Brazilian Portuguese [V][3].
- **[I]** If the harness shows that Nemotron's partial results are not good enough as final text, the better design is Nemotron for live partials plus a Parakeet re-decode of each finished utterance. Shipping both models costs about 1.3 GB of downloads, so it should be an opt-in "high accuracy" pack, not the default.

**Not recommended as the default:**

| Option | Why not |
|---|---|
| Whisper via whisper.cpp | MIT code and weights are fine [V][8][9]. But the sizes that fit a phone are weak in our languages: Whisper small has French FLEURS WER of 15.0 [V][10]. Whisper is also batch-only. |
| Moonshine | No FR, IT or PT models [V][11]. |
| Kyutai STT | EN and FR only [V][12]. |
| Vosk | Old Kaldi models. Portuguese small WER is 68.92 on CORAAL, and the large PT model is GPL-3.0 [V][13]. |
| Android `createOnDeviceSpeechRecognizer` | The implementation is whatever recognizer the device ships, so it is unavailable or different across devices [V][15][I]. |
| ML Kit GenAI Speech Recognition | Alpha. Advanced mode only runs on flagship chipsets. It is a proprietary Google dependency, which F-Droid forbids [V][16][17]. |

### Cleanup of dictated text

**Recommendation: a layered approach.** Cleanup is never silent: it is shown as a preview, following Kern's principles.

1. **Always, on-device and deterministic.**
   - Spoken punctuation and layout commands per language ("vírgula", "new line", "punto").
   - Explicit voice commands such as "scratch that", "apaga isso", "delete last word". These are recognized from the ASR output and applied as edits, like Gboard's command set [V][43].
   - Light filler removal from a per-language list (uh, um, é…, euh, ähm), measured in the harness.
   - Note that Nemotron and Parakeet already produce punctuation and casing [V][1][3].
2. **Opt-in, cloud.** Full cleanup through OpenRouter with the user's key, in two modes:
   - text-only cleanup of the local transcript, which is the default when the user enables cloud cleanup;
   - cloud transcription plus cleanup, for better accuracy.

   An LLM is needed for implicit self-corrections ("at 2, actually 3" → "at 3"), list formatting and tone. Wispr Flow does exactly this in the cloud [V][29][30].
3. **ML Kit GenAI Proofreading and Rewriting are not viable**, for four reasons:
   - inference is only allowed when the calling app is the top foreground app, and an IME never is [V][18][I];
   - Portuguese is not supported [V][19];
   - the APIs are in beta [V][19];
   - they are a proprietary dependency that F-Droid forbids [V][17].
4. **Post-1.0 research: an on-device cleanup model.** S1-mini proves the concept for English: 0.6B parameters, Apache-2.0 plus a naming term, 462 MB, 94.8% token accuracy [V][20]. No multilingual equivalent exists. A Kern fine-tune of an Apache-2.0 base on desktop-home (decision D6) would be the path. Candidate bases are Qwen3.5-0.8B [V][21] and Gemma 4 E2B, which is now Apache-2.0 [V][22].

### OpenRouter setup

**Auth: OAuth PKCE (S256)**, with no app registration [V][23].

- The user taps "Connect OpenRouter". Kern opens a Custom Tab at `https://openrouter.ai/auth?callback_url=…&code_challenge=…&code_challenge_method=S256&key_label=Kern`.
- Kern then exchanges the code at `POST /api/v1/auth/keys`, which returns a long-lived `sk-or-v1-…` key billed to the user [V][23]. The key is stored with the Android Keystore (decision D1).
- **Callback options:**
  - Documented: `https` URLs and `localhost` URLs on any port. A custom URI scheme is not documented [V][23][24].
  - Use an Android App Link on a static page we control (GitHub Pages plus `assetlinks.json`; no backend).
  - Fall back to "headless" mode, where OpenRouter shows the code and the user pastes it [V][23].
  - Keep "paste a key" as an advanced option.

**Models (prices are from OpenRouter's live API on 2026-11-17 [V][25]):**

- **Cloud transcription:** `openai/whisper-large-v3-turbo`, $0.0002 per minute. All three of its endpoints (Groq, DeepInfra, PrimeIntellect) are ZDR with no safety retention [V][25][26].
  - Quality upgrade: `mistralai/voxtral-mini-transcribe-3-0` at $0.0035 per minute, ZDR on Mistral [V][25][26].
  - Best accuracy on the multilingual leaderboard: `elevenlabs/scribe-v2` (average WER 2.67), about $0.0037 per minute, ZDR [V][4][25][26].
- **Text tasks (rewrite, translate, proofread, cleanup):** `openai/gpt-6-luna` with reasoning off ($0.10/$0.50 per million tokens, ZDR via Azure), with `anthropic/claude-haiku-5.5` as fallback ($0.10/$0.50, ZDR via Bedrock and Vertex) [V][25][26].
  - Luna's non-reasoning time to first answer token is 1.53 s, at about 91 tok/s [S][27].
  - Haiku 5.5 is faster at generation (213+ tok/s) but has no non-reasoning row at Artificial Analysis [S][27].
  - Avoid Google models when strict privacy is on. Their only ZDR-listed endpoints carry "safety retention" [V][26][31].

**Privacy, sent on every request:** `"provider": {"zdr": true, "data_collection": "deny"}` [V][31][32].

- On chat requests, also add `"ignore": ["google-vertex", "google-ai-studio"]`, because `zdr: true` still admits safety-retention endpoints unless the user's account says "ZDR only" [V][31][32].
- Transcription requests ignore `order`, `only` and `ignore` [V][24]. So for transcription, pick models whose endpoints carry no safety retention, as above.
- Do not send attribution headers by default. Without them there are no public rankings [V][33].

### Main risks

1. Real-time factor (RTF) of a 0.6B model on the mid-range reference device. There is no phone measurement anywhere; the desktop figures are RTF 0.16–0.33 [V][6][7][I].
2. Accuracy loss from int8 quantization. One port measured a big WER jump with dynamic int8 [V][34].
3. The Nemotron license history. The repo was earlier under an NVIDIA evaluation license, and OpenMDW-1.1 is not an OSI license.
4. The OAuth callback on Android, since custom schemes are undocumented.
5. Cloud cleanup latency from Brazil.
6. Model churn on OpenRouter.

Details are in [section 7](#7-open-risks).

---

## 2. Question 1: on-device speech recognition

### 2.1 Candidate comparison

WER figures come from each vendor's own card or paper, so normalization differs. Compare within a row group, not across models from different sources. The only same-harness comparison is the Open ASR Leaderboard in [section 2.2](#22-same-harness-comparison-open-asr-leaderboard-multilingual-track).

| Candidate | Code license | Weights license | Launch languages | FLEURS WER: en / es / fr / de / it / pt | Size on device | Streaming | Punctuation and case |
|---|---|---|---|---|---|---|---|
| **Nemotron 3.5 ASR Streaming 0.6B** (June 2026) | sherpa-onnx Apache-2.0 [V][5]; NeMo | OpenMDW-1.1 [V][1][2] | All six; pt-BR and pt-PT [V][1] | 7.99 / 4.26 / 9.45 / 8.42 / 4.41 / 5.65 at 560 ms chunk; 7.91 / 4.11 / 9.03 / 8.31 / 4.25 / 5.48 at 1.12 s [V][1] | int8: encoder 627 MB + decoder 14 MB + joiner 9 MB [V][6] | Yes. Cache-aware, chunks of 80/160/320/560/1120 ms [V][1] | Yes, built in [V][1] |
| **Parakeet TDT 0.6B v3** | sherpa-onnx Apache-2.0; NeMo | CC-BY-4.0 [V][3] | All six (25 European languages; trained on pt-PT) [V][3] | 4.85 / 3.45 / 5.15 / 5.04 / 3.00 / 4.76 [V][3] | int8 encoder about 652 MB [S][7] | No. Offline transducer; sherpa only simulates streaming [V][7] | Yes [V][3] |
| **Canary 1B v2** | NeMo | CC-BY-4.0 [V][35] | All six [V][35] | Leaderboard only (see [section 2.2](#22-same-harness-comparison-open-asr-leaderboard-multilingual-track)) | 978M parameters [V][35]; no sherpa support found [V][5] | No (encoder-decoder) | Yes [V][35] |
| **Whisper small** (244M, whisper.cpp) | MIT [V][9] | MIT [V][8] | All six | 6.1 / 5.6 / 15.0 / 10.2 / 9.8 / 7.3 [V][10] | 466 MiB f16, about 852 MB RAM [V][9] | No. The whisper.cpp stream tool is "a naive example" [V][9] | Yes (Whisper output) |
| **Whisper medium** | MIT | MIT | All six | 4.4 / 3.6 / 8.7 / 6.5 / 5.2 / 5.0 [V][10] | 1.5 GiB, about 2.1 GB RAM [V][9] | No | Yes |
| **Whisper large-v3-turbo** (809M) | MIT | MIT [V][8] | All six | Not published per language for turbo. Leaderboard average across the six languages: 5.41 vs 4.57 for large-v3 [S][36] | Not listed in the README; [I] about 1.6 GB f16 | No | Yes |
| **FUTO ACFT Whisper** (tiny/base/small) | MIT (training repo) [S][37] | Apache-2.0 on Hugging Face [S][37] | Same as the Whisper sizes | Not published | Same as Whisper | No. Tuned for short audio context, which lowers latency [S][37] | Yes |
| **Moonshine v2** | MIT | MIT for streaming models [V][11] | EN, ES, DE only. No FR, IT, PT [V][11] | en 6.65 (medium, leaderboard average); es 4.9 and de 7.5 (small) [V][11] | 34M–245M parameters [V][11] | Yes [V][11] | Not documented |
| **Kyutai STT 1B** | – | CC-BY-4.0 [V][12] | EN, FR only [V][12] | – | 1B parameters [V][12] | Yes, 0.5 s delay [V][12] | Yes [V][12] |
| **Vosk** (Kaldi) | Apache-2.0 [S] | Apache-2.0; big PT model is GPL-3.0 [V][13] | All six | Not FLEURS. Small PT: 68.92 on CORAAL; small FR: 23.95 on Common Voice [V][13] | 31–48 MB small; 1.2–1.9 GB big [V][13] | Yes | No |
| **Qwen3-ASR 0.6B** | – | Apache-2.0 [V][38] | All six (30 languages) [V][38] | en 4.39; 12-language FLEURS average 7.57 [V][38] | 0.9B with the audio encoder [V][38] | Streaming only through vLLM [V][38] | Not documented [V][38] |
| **Android `createOnDeviceSpeechRecognizer`** | Platform API | The device's recognizer (Google on most devices) [I] | Device-dependent | Unknown, not published | System-managed | Partial results; endpointer stops on silence; "not intended for continuous recognition" [V][15] | Device-dependent |

**Real-time factor (RTF).** I found no primary measurement on a mid-range phone for any 0.6B model or for Whisper small and turbo. These are the data points that exist:

- Nemotron English 560 ms, int8, on 1 CPU thread (likely an Apple laptop): RTF 0.16 [V][6].
- Nemotron multilingual ONNX port, CPU: fp32 RTF 0.263 at 320 ms chunks; 0.653 at 80 ms; 0.106 at 1120 ms [S][34].
- Parakeet v3 int8, 2 threads, short clip: RTF 0.325 [V][7]. On an i7-12700KF: about 29.7 times real time [S][7].
- whisper.cpp large-v3 at INT4 on 8 server threads is real time. Medium at INT4 on 4 threads has RTF 0.76 [S][39].

**[I]** A mid-range Cortex-A78-class phone is roughly 2–4 times slower per core than these desktop CPUs. That puts Nemotron int8 at 560 ms chunks on 2–4 threads plausibly under RTF 0.5. Without verification this is a guess, and it must be the first measurement on the reference device. It decides whether the default chunk is 560 ms or 1120 ms.

### 2.2 Same-harness comparison: Open ASR Leaderboard, multilingual track

The track combines CoVoST-2, FLEURS and MLS for DE, FR, IT, ES and PT. The figures below are from the leaderboard paper's Table 4 [V][4]. They are useful as a ranking, not as on-device numbers.

| Model | Open weights | Average | DE | FR | IT | ES | PT | RTFx (GPU) |
|---|---|---|---|---|---|---|---|---|
| ElevenLabs Scribe v2 | No | 2.67 | 2.27 | 3.28 | 2.58 | 2.33 | 2.83 | – |
| Mistral Voxtral Small 24B | Yes | 3.70 | 3.01 | 4.13 | 3.91 | 3.04 | 4.40 | 42 |
| NVIDIA Canary 1B v2 | Yes | 4.60 | 4.10 | 4.83 | 4.88 | 3.25 | 6.33 | 634 |
| OpenAI Whisper large-v3 | Yes | 4.81 | 4.26 | 6.36 | 4.69 | 3.65 | 4.96 | 111 |
| NVIDIA Parakeet TDT 0.6B v3 | Yes | 4.81 | 4.20 | 5.42 | 4.81 | 3.73 | 6.16 | 1720 |
| Qwen3-ASR 1.7B | Yes | 5.11 | 4.12 | 5.74 | 5.61 | 3.87 | 6.29 | 113 |

Nemotron 3.5 streaming is not in that table. Its FLEURS-only numbers on its own card ([section 2.1](#21-candidate-comparison)) put it behind Parakeet v3 by roughly 1–4 WER points depending on the language. That is the price of true streaming [V][1][3].

### 2.3 Why Nemotron over Parakeet as the default

- **Latency perception.** Dictation feels fast when words appear while you speak ([section 5](#5-question-4-what-makes-dictation-feel-great)). Nemotron streams natively at 80 ms to 1.12 s granularity [V][1]. Parakeet in sherpa-onnx re-decodes a growing buffer, which "gets slower as the buffer grows" [V][7].
- **Portuguese.** Nemotron lists pt-BR as transcription-ready [V][1]. Parakeet v3 was trained on pt-PT and has the weakest leaderboard score of the six languages in PT (6.16) [V][3][4].
- **Language handling.** Nemotron takes a per-stream language or `auto` [V][6]. That maps well onto Kern's set of enabled languages: pin the language when only one is enabled, and use `auto` otherwise. **[I]** Auto mode costs about 0–0.6 WER points on FLEURS compared with pinned languages [V][1].
- **Precedent.** FUTO, the most serious offline voice keyboard, already offers Nemotron alongside Whisper [V][14].

### 2.4 Licenses of weights: details

- **OpenMDW-1.1** grants the right "to deal in the Model Materials without restriction", including patent rights [V][2].
  - Conditions: when distributing, keep the agreement and the notices of origin. Rights terminate if you sue over patents or copyright in the Model Materials [V][2].
  - No use restrictions, no copyleft.
  - It is not an OSI-approved license, but it meets "free to use, modify and redistribute".
  - The Nemotron repository previously carried an NVIDIA evaluation license and then the NVIDIA Open Model License. Only the current main branch is OpenMDW-1.1 [S][40]. **Pin the exact Hugging Face revision and ship the license text with the model pack.**
- **CC-BY-4.0** (Parakeet, Canary, Kyutai): attribution is required [V][3][12][35]. Fine.
- **MIT** (Whisper, Moonshine streaming): fine [V][8][11]. The older non-streaming Moonshine models for languages other than English are under a non-commercial "Community" license. Avoid them [V][11].
- **Vosk:** the big Portuguese model is GPL-3.0 [V][13]. That is compatible only if Kern itself goes GPL, and it is not worth it at that quality.

### 2.5 Can an IME rely on Android's on-device SpeechRecognizer?

**No, not as the default.**

- `createOnDeviceSpeechRecognizer` (API 31) throws `UnsupportedOperationException` when `isOnDeviceRecognitionAvailable` is false [V][15].
- Model availability per language must be queried and downloaded through `checkRecognitionSupport` and `triggerModelDownload`. The download "might trigger user interaction" [V][15].
- The class "is not intended to be used for continuous recognition" and must be driven from the main thread [V][15].
- **[I]** The implementation is the device vendor's recognition service: Google's on Play devices, something else or nothing on de-Googled or Huawei devices. So quality, languages and privacy are outside Kern's control and cannot be measured by our harness.
- Possible role: an optional "use system recognizer" engine for users who prefer it. Never the default.

### 2.6 Recording from an IME

- **[I]** The active IME can record audio while the keyboard is shown, without a foreground service. FUTO Voice Input runs its recognizer inside the IME "on the bottom half of the screen in place of the keyboard" [V][14], and voice IMEs ship with only `RECORD_AUDIO` [S][41].
- Microphone foreground services cannot be started from the background on Android 14 [V][42]. That is a reason to keep recording strictly tied to the visible keyboard.
- The `RECORD_AUDIO` permission has to be requested from an Activity, the onboarding screen. This needs a device test on Android 14, 15 and 16.

---

## 3. Question 2: cleanup of dictated text

### 3.1 What cleanup means

- Remove fillers.
- Add punctuation and casing.
- Format numbers, dates and lists.
- Apply explicit commands ("scratch that").
- Resolve implicit self-corrections.

The first two are largely solved by the recommended ASR models [V][1][3]. Explicit commands can be done by pattern matching, as Gboard does [V][43]. Implicit corrections and formatting need a language model [V][30][44].

### 3.2 ML Kit GenAI (Gemini Nano)

| Question | Answer |
|---|---|
| Device availability | Pixel 9 to 11, Galaxy S25 and S26 and others; "Nano 2/3/4" tiers [V][18]. Not supported on unlocked bootloaders [V][19]. |
| Can an IME call it? | **[I] Almost certainly not.** "GenAI API inference is permitted only when the app is the top foreground application". Background use, including a foreground service, returns `BACKGROUND_USE_BLOCKED` [V][18]. While typing, the top app is the app being typed into. Nothing exempts IMEs, which a keyboard project also reports [S][45]. Needs one device test to close it. |
| Languages: Proofreading and Rewriting | English, Japanese, French, German, Italian, Spanish, Korean. **No Portuguese** [V][19]. |
| Input limit | Under 256 tokens; Proofreading has a `VOICE` input type [V][19] |
| Status | Beta, no SLA [V][19] |
| Quotas | Per-app inference quota (`BUSY`) and battery quota [V][18] |
| License | Proprietary Google Play services library. F-Droid forbids it [V][17]. |

**Conclusion: do not build on it.** Even as an optional engine, a voice cleanup feature that fails for PT users and probably fails inside an IME is not "incredibly well made".

### 3.3 A small open on-device model

- **S1-mini** (Superwhisper):
  - fine-tuned from Qwen3-0.6B;
  - Apache-2.0 plus a term to keep the name "S1-mini" by "Superwhisper";
  - Q4_K_M build is 462 MB;
  - "fillers removed, false starts and self-corrections resolved", numbers and dates formatted;
  - 94.8% token accuracy on 7,519 cases;
  - **English only** [V][20].
  - It works per segment, so cross-segment self-corrections fail [S][44].
- **Self-correction is the hard part.** One cleanup fine-tune went from 100% to 57.8% on held-out self-corrections while its loss looked fine [S][44]. The harness must have a dedicated self-correction set per language.
- **Bases for a Kern multilingual fine-tune:**
  - Qwen3.5-0.8B: Apache-2.0, 201 languages [V][21];
  - Gemma 4 E2B: Apache-2.0, 2.3B effective and 5.1B with embeddings, audio input up to 30 s [V][22].
  - Training would run on desktop-home (decision D6).
  - **[I]** A 0.6–0.8B model at Q4 is about 0.5 GB. Together with the 650 MB ASR model, that pushes voice to about 1.2 GB on disk. This belongs after 1.0.
- **Benchmark to borrow:** AASR-Bench has "Filter" (fillers) and "Rephrase" (self-correction) rubrics [S][44].

### 3.4 Recommended pipeline

The cloud step is optional and off by default.

```
mic → VAD/endpointing → Nemotron streaming (partials shown live)
    → final utterance text (punctuated)
    → on-device rules: spoken punctuation, explicit commands, filler list   [always]
    → [cloud cleanup via OpenRouter, if enabled]: preview diff → user accepts
```

---

## 4. Question 3: OpenRouter

### 4.1 Getting a key: OAuth PKCE

The flow, all **[V]** from OpenRouter's OAuth guide [23]:

1. Generate a fresh `code_verifier` and `code_challenge = base64url(sha256(verifier))`.
2. Open `https://openrouter.ai/auth?callback_url=<cb>&code_challenge=<c>&code_challenge_method=S256&key_label=Kern`. Optional parameters: `state`, `workspace_id`, `required_workspace_id`.
3. Exchange the code: `POST https://openrouter.ai/api/v1/auth/keys` with body `{code, code_verifier, code_challenge_method: "S256"}`. The response is `{ key }`.

Details of the flow:

- No app registration [S][46].
- The code is single-use and expires after 10 minutes.
- The key "can spend the user's credits until they revoke it". No expiry or credit-limit parameter exists in the flow.
- OpenRouter recommends storing the key in the OS keychain, never in URLs or logs.
- Error codes: 400 (method mismatch), 403 (bad or expired code), 405 (not POST over HTTPS).
- Deep links to the user's own key and logs: `https://openrouter.ai/keys/<sha256(key)>` and `https://openrouter.ai/logs?api_key_hash=<sha256(key)>`. These fit Kern's privacy screen.

**Callback on Android.** Documented callbacks are `https://` and `http://localhost:<any port>` [V][23][24]. Custom schemes are not documented; the guide only names them as a leak risk [V][23]. Options:

| Option | Assessment |
|---|---|
| **Android App Link** on a static page (GitHub Pages plus `/.well-known/assetlinks.json`) | **Preferred.** No server logic. A verified App Link opens Kern directly. If verification fails, the static page can say "return to Kern" [I]. Unverified HTTPS links open in the browser on Android 12+ [S][47]. PKCE makes a leaked code useless [V][23]. |
| Loopback (`http://localhost:<port>/cb`) served by Kern while the Custom Tab is open | Documented [V][23]. **[I]** Risky: the process may be cached or frozen while the browser is in front. Needs a device test. |
| Headless (no `callback_url`; the user pastes the shown code) | Documented [V][23]. Fallback. |
| Paste an API key | Advanced fallback; keys can carry credit limits set by the user [S][46]. |

### 4.2 Sending audio

- **Dedicated transcription:** `POST /api/v1/audio/transcriptions` [V][24].
  - JSON body `{ model, input_audio: { data: <base64, no data: prefix>, format }, language?, temperature?, response_format? }`. OpenAI-style multipart also works.
  - Formats: wav, mp3, flac, m4a, ogg, webm, aac.
  - 25 MB multipart cap, and the upstream times out at 60 s.
  - `language` is ISO-639-1 and auto-detected if omitted.
  - The response has `text` and `usage.cost`.
  - `provider.zdr` and `data_collection` are honored. `order`, `only` and `ignore` are **not**.
  - Streaming is not documented, so treat transcription as batch.
- **Audio LLMs via chat completions:** `input_audio` content parts with base64 `data` and `format`. URLs are not supported [V][48].
  - This allows one-pass "transcribe and clean" with streamed text output.
  - **[I]** The cheap audio-capable models (the Gemini Flash family) are only ZDR-listed with safety retention [V][26], so this path conflicts with strict privacy.
- **[I] Encoding.** Encode on the phone as 16 kHz mono Ogg/Opus or FLAC to keep uploads small. One minute of 16 kHz PCM WAV is about 1.9 MB.

**Transcription models** (prices from the live API, converted per minute [V][25]; ZDR status from `/api/v1/endpoints/zdr` [V][26]):

| Model | $/min | ZDR endpoints (safety retention?) | Notes |
|---|---|---|---|
| `openai/whisper-large-v3-turbo` | 0.0002 | Groq, DeepInfra, PrimeIntellect (no) | Cheapest. Recommended cloud default. |
| `qwen/qwen3-asr-0.6b` | 0.0002 | – | Not in the ZDR list I checked |
| `openai/whisper-large-v3` | 0.00045 | DeepInfra, Together, Groq (no) | Leaderboard multilingual average 4.81 [V][4] |
| `nvidia/parakeet-tdt-0.6b-v3` | 0.001 | PrimeIntellect, Together (no) | Same model as the on-device alternative |
| `nvidia/nemotron-3.5-asr-streaming-multilingual-0.6b` | 0.0002 | Not in the ZDR list | – |
| `mistralai/voxtral-mini-transcribe-3-0` | 0.0035 | Mistral (no) | – |
| `elevenlabs/scribe-v2` | 0.0037 | ElevenLabs (no) | Best leaderboard multilingual average, 2.67 [V][4] |
| `deepgram/nova-3` | 0.0043 | Deepgram (no) | – |
| `openai/gpt-4o-mini-transcribe`, `openai/gpt-transcribe`, `google/gemini-3.5-transcribe` | Token or per-second priced | **No ZDR endpoint** | Exclude under strict privacy |

### 4.3 Text models for rewrite, translate, proofread and cleanup

Prices are from the live API [V][25]. ZDR status is from [V][26]. Latency is from Artificial Analysis [S][27]. It is measured from their location with their prompts, so it is only a ranking. **Kern must measure from Brazil.**

| Model | $/M in / out | ZDR endpoints (safety retention?) | Latency and speed |
|---|---|---|---|
| `openai/gpt-6-luna` (Sept 2026) | 0.10 / 0.50 | Azure (no). OpenAI first-party is dropped under ZDR [V][31] | Non-reasoning: 1.53 s to first answer token, about 91 tok/s. "Low" reasoning: 2.66 s |
| `anthropic/claude-haiku-5.5` (Oct 2026) | 0.10 / 0.50 | Amazon Bedrock, Google Vertex (no) | "Low" reasoning: 5.27 s to first answer token, 213 tok/s. No non-reasoning row |
| `google/gemini-3.5-flash-lite` | 0.30 / 2.50 (some endpoints 0.15 / 1.25) | Google only, **with safety retention** | 4.68 s, 322 tok/s. Accepts audio |
| `minimax/minimax-m3.1-flash` | 0.075 / 0.30 | Many (no) | Not measured. Multilingual quality unknown |
| `deepseek/deepseek-v4.1-flash` | 0.30 / 1.20 | Many (no) | Not measured |

**[S]** RWS's multilingual benchmark reportedly calls Luna "the surprise budget pick" for multilingual work [49]. I could not load the article to check the numbers.

**Recommendation.** Request `models: ["openai/gpt-6-luna", "anthropic/claude-haiku-5.5"]` with reasoning disabled or set to minimum, and `stream: true`.

- Fallback triggers on any error, including rate limits and downtime, and billing follows the model that answered [V][50].
- **[I] Cost.** A 30-second dictation cleanup (about 400 tokens in and 150 out) costs about $0.0001 on Luna, plus $0.0001 for Whisper turbo transcription. Cost is not a concern; latency is.
- Do not hard-code model IDs as the only truth. Ship a curated default list with each release, and let the user pick from `GET /api/v1/models`, filtered by ZDR availability.

### 4.4 Streaming

- Set `stream: true`. Events are SSE `data:` lines ending with `data: [DONE]` [V][51].
- Lines starting with `:` (for example `: OPENROUTER PROCESSING`) are keep-alive comments and must be skipped [V][51].
- The last chunk carries `usage`, and `finish_reason` appears twice [V][51].
- **Cancellation.** Aborting the connection stops processing and billing on supported providers, which include OpenAI, Azure and Anthropic. It does **not** on AWS Bedrock, Google or Mistral [V][51].
  - **[I]** This matters for "user dismissed the preview": prefer providers that support cancellation.
- **Mid-stream errors** arrive as a chunk with a top-level `error` and `finish_reason: "error"` after a 200 OK. An error can be the only event [V][51][52].

### 4.5 Data policies and per-request enforcement

- **OpenRouter itself:** "we do not retain prompts or responses after processing, except when you explicitly choose to have us store them".
  - Exception: from 2026-10-27, CSAM screening applies to all traffic, including ZDR traffic, and flagged content may be kept [V][31].
- **Three provider tiers:** ZDR, Safety Retention (kept only for abuse prevention, no training, and a request need not be flagged) and Retention+Training. An unclear policy is treated as retain-and-train [V][31].
- **Per request:**
  - `provider.zdr: true` restricts routing to ZDR endpoints. It combines as an OR with account settings, so it can tighten but never loosen them [V][31][32].
  - `provider.data_collection: "deny"` excludes providers that store or train [V][32].
  - **Safety Retention endpoints remain eligible under `zdr: true`** unless the user's account or guardrail is set to "ZDR only". There is no per-request field for this [V][32].
  - **[I]** On chat requests, Kern excludes them with `ignore: ["google-vertex", "google-ai-studio"]`. Slugs must be checked against `/api/v1/models/{id}/endpoints` tags.
- **Google:** from 2026-10-18, Google requires Safety Retention on Vertex (up to 90 days for flagged prompts), so "ZDR only" makes most Google models unavailable [V][31].
- **No fail-open:** with `zdr: true`, if no endpoint qualifies, the request fails rather than falling back to a retaining provider [S][53].
- **Not covered by ZDR:** plugins such as web search. Kern does not use them [V][31].
- **[I]** Since the key belongs to the user, Kern cannot change the user's account settings. It can only send the strict per-request flags and show the user a link to `openrouter.ai/settings/privacy`.

Request template for chat (cleanup and rewrite):

```json
{
  "models": ["openai/gpt-6-luna", "anthropic/claude-haiku-5.5"],
  "stream": true,
  "reasoning": { "effort": "none" },
  "provider": {
    "zdr": true,
    "data_collection": "deny",
    "ignore": ["google-vertex", "google-ai-studio"],
    "sort": "latency"
  },
  "messages": [ ... ]
}
```

**[I]** The exact `reasoning` value shape per model must be checked against the API reference. The model pages list `reasoning` and `reasoning_effort` as accepted [V][25].

### 4.6 Rate limits and error handling

These come from OpenRouter's limits and errors docs [V][54][52].

- Paid models have no platform-level request cap. `:free` models are capped at 20 requests per minute and 50 or 1,000 per day. Kern should not default to `:free` models.
- Extra keys do not raise limits; capacity is governed globally.
- `GET /api/v1/key` returns `limit`, `limit_remaining`, `usage_daily`, `is_free_tier` and more. Use it to show the remaining balance in settings.
- Errors are `{ error: { code, message, metadata } }`.

| Code | Kern behavior |
|---|---|
| 400 | Bug. Log locally. |
| 401 | Key revoked or invalid. Clear the key and ask to reconnect. |
| 402 | No credits. Check `metadata.limit_source`: `openrouter_in_flight_budget` means wait for `Retry-After`; the others mean "add credits". SDKs never retry a 402 automatically. |
| 403 | Moderation or guardrail. Show a neutral message. |
| 408 / 502 | Timeout or model down. Retry once, then fall back. |
| 429 | Honor `Retry-After`, with exponential backoff. |
| 503 | No provider matches the routing. Usually the strict privacy filters: tell the user that no private endpoint is available for this model. |

- Also check the body for `error` even when the status is 200.
- **[I]** Never retry automatically after partial output has been shown. OpenRouter itself only fails over before any token is delivered [V][52].
- **Attribution headers** (`HTTP-Referer`, `X-OpenRouter-Title`) are optional. Without them no public app page or ranking exists [V][33]. **[I]** Default to not sending them, or send them with `X-OpenRouter-App-Visibility: hidden`.

---

## 5. Question 4: what makes dictation feel great

### What the reference products do

**Gboard, advanced voice typing (Pixel):**

- Automatic punctuation as you speak [V][43].
- Commands: "Delete last word", "Clear", "Clear all", "Stop" [V][43].
- Detailed edits ("Delete ___", insert, replace) after a pause of at least half a second, within one or two sentences of the cursor [V][43].
- Spoken text stays on the device except for 'Fix it' and detailed edits [V][43].
- Pixel 8+: "Change John to Johnny". Pixel 9+: "proofread this" and "formalize the text", with "next" / "previous" to cycle versions and "use this" to accept [S][28].

**Wispr Flow:**

- On Android it is a floating bubble over text fields, not a keyboard [S][55].
- It is cloud-only [S][29].
- Smart Formatting does punctuation and lists from "one… two…" [V][30].
- Backtrack: "actually", "scratch that", "never mind", or simply restating, and "Flow removes the whole abandoned attempt". "Only clear self-corrections are removed" [V][30].
- If formatting fails, Flow pastes the unformatted transcript [V][30].
- Reported latency: under 700 ms p99 server-side, but users feel about 1–2 s [S][29].

**FUTO Voice Input and FUTO Keyboard:**

- Fully on-device [V][56].
- The IME voice mode opens in place of the keyboard [V][14].
- A 30-second default limit, with a "long-form" option that may lose quality [S][57].
- Models: Whisper (fine-tuned tiny, base and small), Parakeet and asr4all; Nemotron in Voice Input [V][14][56].

### Design implications for Kern [I]

1. **Show words while speaking.** Streaming partials are the strongest latency signal. This is why the default is a streaming model.
   - The finalized text should replace partials in place, without flicker.
   - The commit at the end of an utterance should land within one chunk (≤ 560 ms) of the speaker stopping.
2. **Keep the keyboard alive during dictation.** Kern is a keyboard, unlike Wispr's bubble or FUTO's panel. Dictation should be a mode of the keyboard, not a replacement screen:
   - a compact mic state in the suggestion strip, with the keys still usable;
   - typing a key pauses dictation and inserts at the cursor;
   - tapping the mic resumes;
   - this "mix voice and typing" is what Gboard's on-device mode allows, and what FUTO's replacement panel does not.
3. **Commands are deterministic and visible.** "scratch that", "apaga isso", "new line" and "send" are matched on-device per language, after a short pause, as Gboard requires [V][43].
   - They act as one undoable edit. One backspace restores the text (Kern principle).
4. **Cloud cleanup is a preview, never a silent replace.** Show the diff in the strip and accept with one tap. If cleanup fails or times out, keep the raw text, as Wispr does [V][30].
5. **No hard time limit.** Use VAD-based segmentation so long dictation keeps working. Nemotron's cache-aware streaming suits this.
6. **Language.** Pin the language when only one is enabled; use `auto` otherwise. Show the detected language subtly, so users can tell why something went wrong.
7. **Privacy screen.** Every cloud request (transcription or cleanup) appears with time, model, provider, ZDR flag and cost from `usage.cost` [V][24], plus a link to the user's OpenRouter logs [V][23].

---

## 6. What to measure next

This is the harness work needed before the decision.

1. Nemotron 3.5 int8 at 560 ms and 1120 ms on the reference device:
   - RTF on 1, 2 and 4 threads;
   - time from end of speech to committed text;
   - resident memory, against the 120 MB budget, which voice will exceed. Voice needs its own budget, loaded only while dictating.
2. WER and character error rate per language on held-out sets, for:
   - Nemotron int8 vs fp16 (quantization loss [S][34]);
   - Parakeet v3 int8;
   - Whisper small (for reference);
   - especially pt-BR and conversational speech, which FLEURS does not cover.
3. Device tests:
   - recording from `InputMethodService` on Android 14, 15 and 16;
   - ML Kit GenAI `BACKGROUND_USE_BLOCKED` from an IME, to close the question;
   - OAuth via App Link and via loopback.
4. Cloud latency from São Paulo:
   - Whisper turbo on Groq, transcription round trip for 5, 15 and 30 s clips;
   - Luna (no reasoning) vs Haiku 5.5, time to first token for cleanup prompts with ZDR flags on.
5. A cleanup quality set per language: fillers, explicit commands, implicit self-corrections, numbers and dates.

## 7. Open risks

1. **On-device performance is unmeasured.** No phone RTF exists for any 0.6B model [I]. If the reference device cannot keep up, the fallbacks are 1120 ms chunks, or Moonshine (EN, ES, DE only) and Whisper base for low-end devices, with worse quality.
2. **Quantization loss.** A dynamic-int8 Nemotron port measured a large WER degradation [S][34]. We need to compare sherpa's int8 against fp16 and possibly ship fp16 for the encoder at about 1.2 GB [S][7].
3. **Download size.** About 650 MB per voice model [V][6]. It must be an optional, resumable download, which needs a download source. **[I]** Hugging Face or GitHub releases are acceptable: they receive no user text.
4. **License drift.** Nemotron's license changed across revisions [S][40]. OpenMDW-1.1 is permissive but not OSI-approved, so pin the revision and record it in `decisions.md`. CC-BY models need visible attribution.
5. **Model churn.** Model IDs on OpenRouter change often: three GPT generations in five months appear in the API listing [V][25]. Defaults must be data, not code.
6. **The privacy settings cannot be fully enforced by Kern.** Safety retention is only excludable at account level or by provider filtering. CSAM screening applies even to ZDR traffic [V][31][32].
7. **OAuth callback.** Custom schemes are undocumented, App Links need a domain the project controls, and loopback may be killed by process freezing [V][23][I].
8. **Proprietary paths.** Any use of ML Kit or Google's recognizer must live in a separate build flavor to stay F-Droid-compatible [V][17].
9. **Pervasive secondary data.** Latency figures (Artificial Analysis) and some leaderboard updates are third-party [S]. They are good enough for shortlisting, not for decisions.

## 8. Sources

1. NVIDIA, nemotron-3.5-asr-streaming-0.6b model card. https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b
2. OpenMDW License 1.1. https://openmdw.ai/license/1-1/
3. NVIDIA, parakeet-tdt-0.6b-v3 model card. https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3
4. Open ASR Leaderboard paper (v4), Table 4, multilingual track. https://arxiv.org/html/2510.06961v4
5. k2-fsa/sherpa-onnx README. https://github.com/k2-fsa/sherpa-onnx
6. sherpa-onnx docs, Nemotron ASR Streaming. https://k2-fsa.github.io/sherpa/onnx/nemo/nemotron-streaming.html ; multilingual support PR and feature request: https://github.com/k2-fsa/sherpa-onnx/issues/3664
7. sherpa-onnx NeMo transducer docs: https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html ; streaming limitation: https://github.com/k2-fsa/sherpa-onnx/issues/2918 ; int8 package sizes: https://huggingface.co/dreambeltech/ailo-parakeet-tdt-sherpa-onnx , https://huggingface.co/ako101/parakeet-tdt-0.6b-v3-sherpa-onnx-fp16/blob/main/README.md ; desktop CPU speed: https://github.com/mil-ad/parakeet-tdt-0.6b-v3-fastapi-openai
8. OpenAI, whisper-large-v3-turbo model card. https://huggingface.co/openai/whisper-large-v3-turbo
9. ggml-org/whisper.cpp README. https://github.com/ggml-org/whisper.cpp
10. Radford et al., "Robust Speech Recognition via Large-Scale Weak Supervision", Table 13 (WER on FLEURS). https://arxiv.org/abs/2212.04356
11. Moonshine available models. https://github.com/moonshine-ai/moonshine/blob/main/docs/models/available-models.md
12. Kyutai, stt-1b-en_fr model card. https://huggingface.co/kyutai/stt-1b-en_fr
13. Vosk models list. https://alphacephei.com/vosk/models
14. futo-org/voice-input README. https://github.com/futo-org/voice-input
15. Android `SpeechRecognizer` reference. https://developer.android.com/reference/android/speech/SpeechRecognizer
16. ML Kit GenAI Speech Recognition. https://developers.google.com/ml-kit/genai/speech-recognition/android
17. F-Droid Inclusion Policy. https://f-droid.org/docs/Inclusion_Policy/
18. ML Kit GenAI overview. https://developers.google.com/ml-kit/genai
19. ML Kit GenAI Proofreading: https://developers.google.com/ml-kit/genai/proofreading/android ; Rewriting: https://developers.google.com/ml-kit/genai/rewriting/android
20. Superwhisper, s1-mini-GGUF model card. https://huggingface.co/superwhisper/s1-mini-GGUF
21. Qwen, Qwen3.5-0.8B model card. https://huggingface.co/Qwen/Qwen3.5-0.8B
22. Google, gemma-4-E2B-it model card. https://huggingface.co/google/gemma-4-E2B-it
23. OpenRouter, OAuth PKCE guide. https://openrouter.ai/docs/guides/overview/auth/oauth
24. OpenRouter, Speech-to-Text guide: https://openrouter.ai/docs/guides/overview/multimodal/stt ; API reference: https://openrouter.ai/docs/api/api-reference/stt/create-transcription ; Python SDK OAuth (callback URL rules): https://openrouter.ai/docs/client-sdks/python/sdks/oauth/README
25. OpenRouter models API, queried 2026-11-17: https://openrouter.ai/api/v1/models and https://openrouter.ai/api/v1/models?output_modalities=transcription ; per-model pages, e.g. https://openrouter.ai/openai/gpt-6-luna , https://openrouter.ai/anthropic/claude-haiku-5.5 ; STT collection: https://openrouter.ai/collections/speech-to-text-models
26. OpenRouter ZDR endpoints API, queried 2026-11-17, including the `safety_retention` field. https://openrouter.ai/api/v1/endpoints/zdr
27. Artificial Analysis: https://artificialanalysis.ai/models/releases/gpt-6-luna , https://artificialanalysis.ai/models/releases/claude-haiku-5-5 , https://artificialanalysis.ai/models/releases/gemini-3-5-flash-lite
28. How-To Geek, Pixel voice typing features. https://www.howtogeek.com/google-pixels-context-aware-voice-typing-better-than-any-keyboard/
29. Wispr Flow reviews and analyses: https://letterly.app/blog/wispr-flow-review/ , https://www.forasoft.com/learn/ai-for-video-engineering/articles-ai/whisper-flow-app-engineering-walkthrough , https://www.androidpolice.com/i-finally-trust-voice-typing-android-not-because-google/
30. Wispr Flow docs, Smart Formatting & Backtrack. https://docs.wisprflow.ai/articles/5373093536-how-do-i-use-smart-formatting-and-backtrack
31. OpenRouter, Zero Data Retention guide: https://openrouter.ai/docs/guides/features/zdr ; provider logging: https://openrouter.ai/docs/guides/privacy/provider-logging
32. OpenRouter, Provider Routing. https://openrouter.ai/docs/guides/routing/provider-selection
33. OpenRouter, App Attribution. https://openrouter.ai/docs/app-attribution
34. codavidgarcia/nemotron-3.5-asr-streaming-onnx (third-party ONNX port, RTF and int8 WER). https://github.com/codavidgarcia/nemotron-3.5-asr-streaming-onnx
35. NVIDIA, canary-1b-v2 model card. https://huggingface.co/nvidia/canary-1b-v2
36. yaps.ai, "Parakeet vs Whisper (2026)". https://www.yaps.ai/blog/parakeet-vs-whisper
37. FUTO whisper-acft: https://github.com/futo-org/whisper-acft ; https://huggingface.co/futo-org/acft-whisper-tiny
38. Qwen, Qwen3-ASR-0.6B model card. https://huggingface.co/Qwen/Qwen3-ASR-0.6B
39. TARQ paper (whisper.cpp thread-limited CPU RTF). https://arxiv.org/pdf/2605.27808
40. Nemotron 3.5 ASR license history (repo commits and mirrors): https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b/blob/22a16aed12c83a96c13be1eef6b93f37a1d73a4c/private_README.md , https://www.modelscope.ai/models/nv-community/nemotron-3.5-asr-streaming-0.6b
41. Example voice IME manifest. https://github.com/arvindg4u/voice-ime-android
42. Android 14, foreground service types and while-in-use restrictions. https://developer.android.com/about/versions/14/changes/fgs-types-required
43. Gboard Help, Use advanced voice typing features. https://support.google.com/gboard/answer/11197787?hl=en
44. Dictation cleanup models and benchmarks: https://betterstack.com/community/guides/ai/s1-mini/ , https://huggingface.co/ReFyneLabs/simplewords-dictation-cleanup-v2 , AASR-Bench https://arxiv.org/pdf/2607.28175
45. wmkeyboard issue on AICore from an IME. https://github.com/wasi-master/wmkeyboard/issues/804
46. OpenRouter, API Authentication. https://openrouter.ai/docs/api_reference/authentication
47. Android App Links behavior on Android 12+ (summary). https://blog.codercops.com/blog/oauth-mobile-redirect-uri-hijacking-2026
48. OpenRouter, Audio input. https://openrouter.ai/docs/guides/overview/multimodal/audio
49. RWS, M-GATE multilingual benchmark (could not load; cited via search snippet). https://www.rws.com/blog/gpt-6-sol-luna-and-claude-opus-5-5-prove-theyre-worth-their-weight-for-multilingual-workflows-on-m-gate/
50. OpenRouter, Model Fallbacks. https://openrouter.ai/docs/guides/routing/model-fallbacks
51. OpenRouter, Streaming. https://openrouter.ai/docs/api/reference/streaming
52. OpenRouter, Errors and Debugging. https://openrouter.ai/docs/api/reference/errors-and-debugging
53. OpenRouter blog, Zero Data Retention. https://openrouter.ai/blog/insights/zero-data-retention/
54. OpenRouter, Limits. https://openrouter.ai/docs/api/reference/limits
55. Wispr Flow for Android. https://wisprflow.ai/android
56. FUTO Voice Input site. https://voiceinput.futo.tech/
57. FUTO Keyboard review (long-form setting). https://privacygear.nl/en/reviews/futo-keyboard-review/
