# Research: the Android IME platform and the Play Store

Status: research, 2026-11-17. This is input for `docs/decisions.md` (D2, and the open decisions on rendering and testing) and for the future `docs/compatibility.md`.

Legend used throughout:

- **[V]** verified. I read it in a primary source on 2026-11-17. Primary sources are the Android API reference (including its "Added in API level" metadata), developer.android.com guides, AOSP source on android.googlesource.com, the source of other keyboards pinned to a commit, issue trackers, and Google Play policy pages.
- **[S]** secondary. It comes from a search summary or a third party, and I did not read it in the primary source.
- **[I]** inference or recommendation by the author of this document.

The tables and lists for question 2 build on a code and issue-tracker survey of LatinIME, HeliBoard, FlorisBoard, AnySoftKeyboard and FUTO Keyboard. The survey used shallow clones at the commits linked below. Where a claim rests on that survey, the code or issue URL is given directly.

---

## 1. Summary and recommendations

### Platform facts that shape everything else

- **The current release is Android 17, API level 37.** `Build.VERSION_CODES_FULL` lists `CINNAMON_BUN` (37), `CINNAMON_BUN_1` (37.1) and `CINNAMON_BUN_2` (37.2) [V] ([VERSION_CODES_FULL](https://developer.android.com/reference/android/os/Build.VERSION_CODES_FULL)).
  - Android 17 went stable on 2026-06-16 [S] ([Android Developers Blog](https://android-developers.googleblog.com/2026/06/Android-17.html), [Wikipedia](https://en.wikipedia.org/wiki/Android_17)).
  - Some APIs in the current reference are tagged 37.2 and are not yet in a stable release, for example `VibrationEffect.Builder` [V] ([VibrationEffect.Builder](https://developer.android.com/reference/android/os/VibrationEffect.Builder)).
- **Since 2026-08-31, Google Play requires new apps and updates to target API 36 or higher** [V] ([Play target API](https://support.google.com/googleplay/android-developer/answer/11926878)).
  - D2 says `targetSdk` follows the latest stable platform, so Kern targets 37. That pulls in the Android 17 targeting changes listed in §2.9.

### Recommendations

1. **Keep `minSdk 34`, but fix the reasoning in D2** (see "Correction to D2" below). [I]

2. **Build the `InputConnection` layer as its own module, designed around "the editor lies".** [I] It should combine:
   - **LatinIME's model of the editor's state.** LatinIME keeps the expected cursor position and caches the text before the cursor. It updates both itself on each edit and reloads them only when an update arrives that it did not cause.
   - **FlorisBoard v0.5's queue of expected states.** Each batch edit pushes the state it should produce. Each `onUpdateSelection` is then reconciled against that queue.
   - **A per-app quirk registry.** It is keyed by package and editor kind. Each quirk has a test and a link to the issue it fixes.

   This is the area where all five existing keyboards still carry open bugs (§3).

3. **Ship an in-app "buggy editor" test bench from the first prototype.** [I] FUTO's bench reproduces seven editor misbehaviors (§3.2). Write our own clean-room version rather than copying theirs, because FUTO's license is not an open-source license [V] ([FUTO repo license metadata](https://github.com/futo-org/android-keyboard)).

4. **Render keys with a custom `View` that draws on a `Canvas`, and use Compose for everything else.** [I] Everything else means the suggestion strip, panels, the clipboard, emoji and settings.
   - This is FUTO's architecture: a `ComposeView` root with the legacy Canvas keyboard view embedded [V] ([FUTO LatinIME.kt#L538](https://github.com/futo-org/android-keyboard/blob/7d61ab4132cfcbb7aa713190d934f0125dc38b03/java/src/org/futo/inputmethod/latin/LatinIME.kt#L538)).
   - FlorisBoard's newest code draws keys in Compose [V] ([ImeKeyButton.kt](https://github.com/florisboard/florisboard/blob/bac346e8a5e7ee7104359cebdbce7d4105d3f0fa/app/src/main/kotlin/dev/patrickgold/florisboard/ime/keyboard3/ui/ImeKeyButton.kt)).
   - Before locking this in, build both as a one-week spike and measure them against the quality bars. "Measure, don't guess" applies. [I]

5. **Haptics: use `View.performHapticFeedback(KEYBOARD_TAP / KEYBOARD_RELEASE)` by default.** [V/I]
   - When it is called from an IME window, the framework applies the system "Keyboard vibration" toggle and the IME-specific intensity [V] ([View.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/View.java), [HapticFeedbackVibrationProvider.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/vibrator/HapticFeedbackVibrationProvider.java)).
   - Offer custom `VibrationEffect.Composition` haptics as an option ("Kern feel"). Gate them per primitive with `Vibrator.arePrimitivesSupported` [V] ([haptics APIs](https://developer.android.com/develop/ui/views/haptics/haptics-apis)).

6. **Do not use `requestUnbufferedDispatch` for taps.** The platform documents that it can *increase* latency and cause jitter [V] ([View reference](https://developer.android.com/reference/android/view/View#requestUnbufferedDispatch(android.view.MotionEvent))). [I] Keep it as a swipe-trail experiment only, together with `androidx.input:input-motionprediction` (1.0.1 stable [V], [Maven metadata](https://dl.google.com/android/maven2/androidx/input/input-motionprediction/maven-metadata.xml)) and possibly `androidx.graphics` front-buffered rendering. Ship it only if measurement shows a gain.

7. **Play Store:**
   - **No `AccessibilityService`.** It brings a declaration form, a disclosure video and policy risk [V] ([Accessibility policy](https://support.google.com/googleplay/android-developer/answer/10964491)), and the product already rules it out.
   - **No `QUERY_ALL_PACKAGES`.** Keyboards are not a permitted use [V] ([QUERY_ALL_PACKAGES policy](https://support.google.com/googleplay/android-developer/answer/10158779)), and an IME automatically sees the app it types into [V] ([package visibility](https://developer.android.com/training/package-visibility/automatic)).
   - **Data safety.** Declare the AI and cloud-voice features as *optional* collection. Typed text and audio leave the device, even though they go straight to OpenRouter and never to us. Declare no sharing, under the user-initiated-transfer exemption. Show an in-app prominent disclosure with affirmative consent before the first cloud request [V for the rules] ([Data safety](https://support.google.com/googleplay/android-developer/answer/10787469), [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)). [I for the mapping to Kern]

8. **Honor `EditorInfo.isWritingToolsEnabled()` (API 36).** Its purpose is to let editors opt out of IMEs that "replace text with generative AI text" [V] ([EditorInfo](https://developer.android.com/reference/android/view/inputmethod/EditorInfo#setWritingToolsEnabled(boolean))). [I] Kern's rewrite, translate and tone features must stay hidden when it is false, next to the password and `IME_FLAG_NO_PERSONALIZED_LEARNING` rules already in `product.md`.

9. **Onboarding: one screen with two live steps, as LatinIME does.**
   - Step one opens the system IME settings. While the user is there, the app polls every 200 ms. As soon as Kern is enabled, it brings the setup screen back to the front.
   - Step two shows the IME picker [V] ([SetupWizardActivity.java](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/java/src/com/android/inputmethod/latin/setup/SetupWizardActivity.java)).
   - On API 34+, check state with `InputMethodManager.getEnabledInputMethodList()` and `getCurrentInputMethodInfo()` (34) instead of parsing `Settings.Secure` [V] ([FlorisBoard InputMethodUtils.kt](https://github.com/florisboard/florisboard/blob/bac346e8a5e7ee7104359cebdbce7d4105d3f0fa/app/src/main/kotlin/dev/patrickgold/florisboard/lib/util/InputMethodUtils.kt#L44)).
   - Before the user enables Kern, explain the frightening system warning they are about to see (§5.2). [I]

10. **Testing:**
    - **Every pull request, free:** run CI on GitHub-hosted Ubuntu runners with emulators (`google_apis` images, *not* ATD, because ATD images remove LatinIME, Chrome and Settings [V], [GMD docs](https://developer.android.com/studio/test/gradle-managed-devices)). Run them against a suite of open-source editor harnesses: EditText, Compose, WebView with textarea, contenteditable and CodeMirror, Flutter, React Native, and Termux.
    - **Before each release, manual plus UI Automator:** run the proprietary apps in the matrix on 3 to 4 owned physical devices.
    - **Measure latency** with Perfetto (FrameTimeline plus the `input` atrace category) and a 240 fps camera. [I, tools V in §6]

### Correction to D2

D2 says: "Android 14 gives IMEs stylus handwriting delegation". The API levels show this is imprecise:

| Capability | API level | Source |
|---|---|---|
| IME-side stylus handwriting (`onStartStylusHandwriting`, `onStylusHandwritingMotionEvent`, `getStylusHandwritingWindow`, `finishStylusHandwriting`) | **33** (Android 13) | [V] [InputMethodService](https://developer.android.com/reference/android/inputmethodservice/InputMethodService#onStartStylusHandwriting()) |
| Handwriting **delegation**. This is an *app-side* API: a placeholder view hands handwriting to a real editor (`View.setHandwritingDelegatorCallback`, `View.setIsHandwritingDelegate`, `InputMethodManager.prepare/acceptStylusHandwritingDelegation`). The IME does nothing special for it. | **34** | [V] [View](https://developer.android.com/reference/android/view/View#setHandwritingDelegatorCallback(java.lang.Runnable)), [IMM](https://developer.android.com/reference/android/view/inputmethod/InputMethodManager#acceptStylusHandwritingDelegation(android.view.View)), [stylus guide](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/stylus-input-in-text-fields) |
| Handwriting **gestures** for IMEs (`InputConnection.performHandwritingGesture`, `previewHandwritingGesture`, `EditorInfo.getSupportedHandwritingGestures`), `replaceText`, `requestTextBoundsInfo`, `onUpdateEditorToolType`, `EditorInfo.getInitialToolType` | **34** | [V] [InputConnection](https://developer.android.com/reference/android/view/inputmethod/InputConnection#performHandwritingGesture(android.view.inputmethod.HandwritingGesture,%20java.util.concurrent.Executor,%20java.util.function.IntConsumer)), [EditorInfo](https://developer.android.com/reference/android/view/inputmethod/EditorInfo#getSupportedHandwritingGestures()) |
| Connectionless handwriting (`onStartConnectionlessStylusHandwriting`), `EditorInfo.isStylusHandwritingEnabled` | **35** | [V] [InputMethodService](https://developer.android.com/reference/android/inputmethodservice/InputMethodService#onStartConnectionlessStylusHandwriting(int,%20android.view.inputmethod.CursorAnchorInfo)) |
| `setStylusHandwritingRegion` | **36** | [V] [InputMethodService](https://developer.android.com/reference/android/inputmethodservice/InputMethodService#setStylusHandwritingRegion(android.graphics.Region)) |

The other two claims in D2 hold:

- AGSL `RuntimeShader` arrived in API 33 [V] ([AGSL guide](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl)).
- Haptic compositions arrived in API 30. `PRIMITIVE_LOW_TICK`, `THUD` and `SPIN` arrived in 31 [V] ([VibrationEffect.Composition](https://developer.android.com/reference/android/os/VibrationEffect.Composition)).

**Suggested wording for D2 [I]:** "API 34 gives IMEs handwriting gestures, `replaceText`, text-bounds queries and editor tool type, plus `getCurrentInputMethodInfo` for setup. Everything older is then available without compatibility branches: stylus handwriting (33), cursor-update filters and `TextAttribute` (33), AGSL (33), haptic compositions (30/31)."

Since Kern has no handwriting in the 1.0 scope, the honest primary reason is "fewer branches and modern `InputConnection` APIs", not handwriting. [I]

---

## 2. IME APIs on Android 14 through 17

### 2.1 Lifecycle essentials

- **Callbacks.** The `InputMethodService` contract is unchanged in shape [V] ([InputMethodService](https://developer.android.com/reference/android/inputmethodservice/InputMethodService)):
  - `onCreate`, then `onCreateInputView` once per window.
  - Then `onStartInput(EditorInfo, restarting)` and `onStartInputView`.
  - Then `onUpdateSelection` / `onUpdateCursorAnchorInfo` while the view is shown.
  - Then `onFinishInputView`, `onFinishInput`, `onWindowHidden`.
- **The IME is a UI context.** `Context.isUiContext()` names `InputMethodService` as an example of one [V] ([Context](https://developer.android.com/reference/android/content/Context#isUiContext())). Jetpack WindowManager's `WindowInfoTracker` accepts any `@UiContext` [V] ([WindowInfoTracker](https://developer.android.com/reference/kotlin/androidx/window/layout/WindowInfoTracker)). So fold and hinge information should be reachable from the service. I did not confirm that fold features are populated for the IME window; check this on a device. [I]
- **Process priority.** While the keyboard is visible, the system binds the IME with `BIND_FOREGROUND_SERVICE | BIND_INCLUDE_CAPABILITIES | BIND_SHOWING_UI` [V] ([InputMethodBindingController.java#L136](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/inputmethod/InputMethodBindingController.java)). This is why an IME can use the microphone while shown, without a foreground service, once `RECORD_AUDIO` is granted. [I]
  - The permission itself must be requested from an Activity. FUTO has a dedicated `MicPermissionActivity` for this [V] ([FUTO MicPermissionActivity.kt](https://github.com/futo-org/android-keyboard/blob/7d61ab4132cfcbb7aa713190d934f0125dc38b03/java/src/org/futo/inputmethod/latin/MicPermissionActivity.kt)).
- **Android 17 memory limits.** Android 17 introduces per-app memory limits based on device RAM, for all apps. Kills show up as `REASON_OTHER` with `"MemoryLimiter:AnonSwap"` [V] ([A17 all-apps changes](https://developer.android.com/about/versions/17/behavior-changes-all)). This makes the 120 MB RSS bar in `product.md` more than a nicety. [I]

### 2.2 Stylus handwriting

See the table in §1. Notes:

- Stylus handwriting is on by default for every `EditText` and for WebView text fields on Android 14+, except password fields [V] ([stylus guide](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/stylus-input-in-text-fields)).
- On a device without a stylus, it can be simulated with `adb shell setprop debug.input.simulate_stylus_with_touch true` on Android 15+ [V] (same source).
- Handwriting is not in Kern 1.0 scope. Don't declare `supportsStylusHandwriting` until it is: an IME that declares it is expected to handle these sessions [I].

### 2.3 Inline autofill suggestions (API 30)

[V] ([IME autofill guide](https://developer.android.com/guide/topics/text/ime-autofill)):

- **Opting in.** Declare `android:supportsInlineSuggestions="true"`, then implement `onCreateInlineSuggestionsRequest` (return the max count plus `InlinePresentationSpec`s) and `onInlineSuggestionsResponse` (inflate each `InlineSuggestion` into an `InlineContentView`).
- **Privacy.** The IME cannot see the suggestion content: "the IME does *not* see what the suggestions are at this stage".
- **Rendering.** Views are rendered by the autofill service, so they don't inherit Kern's look beyond what the presentation spec allows. FUTO renders them inside its Compose action bar [V] ([FUTO UixManager.kt](https://github.com/futo-org/android-keyboard/blob/7d61ab4132cfcbb7aa713190d934f0125dc38b03/java/src/org/futo/inputmethod/latin/uix/UixManager.kt#L697)).
- **Recommendation [I]:** support inline autofill in 1.0, because password managers are a daily need. Place the chips in the suggestion strip, consistent with the "one strip" principle.

### 2.4 `InputConnection` features worth using with minSdk 34

All levels [V] from the [InputConnection reference](https://developer.android.com/reference/android/view/inputmethod/InputConnection):

| API | Level | Use in Kern [I] |
|---|---|---|
| `getSurroundingText(before, after, flags)` | 31 | One call returns text before and after, the selection and its offset. Prefer it over separate `getTextBeforeCursor`/`getTextAfterCursor` calls (fewer IPC round trips). |
| `setImeConsumesInput` | 31 | Used while Kern's own inline editors (search, AI prompt) have focus, so the app's cursor stops blinking. |
| `takeSnapshot()` | 33 | Atomic snapshot of text, selection and composing region. Useful when resynchronizing after a desync. |
| `commitText / setComposingText / setComposingRegion(…, TextAttribute)` | 33 | `TextAttribute` carries extras. API 37 adds `isTextSuggestionSelected`, aimed at CJKV accessibility ([A17 targeting changes](https://developer.android.com/about/versions/17/behavior-changes-17)). |
| `requestCursorUpdates(mode, filter)` with `CURSOR_UPDATE_FILTER_*` | 33/34 | Cheaper cursor-anchor updates. FlorisBoard deliberately omits the filters because they "behave strangely with some editors" ([FlorisBoard ImeEditor.kt#L220](https://github.com/florisboard/florisboard/blob/bac346e8a5e7ee7104359cebdbce7d4105d3f0fa/app/src/main/kotlin/dev/patrickgold/florisboard/ime/keyboard3/ImeEditor.kt#L220)). Test per app before relying on them. |
| `replaceText(start, end, text, cursor, attr)` | 34 | Recorrection and applying AI rewrites in one call. The reference defines it as behaving "like calling `finishComposingText()`, `setSelection(start, end)`, and then `commitText(...)`", and `BaseInputConnection` implements it from 34 [V] ([BaseInputConnection](https://developer.android.com/reference/android/view/inputmethod/BaseInputConnection)). It is one IPC call, not a guarantee of atomic undo in the app. Custom connections (Chrome, Flutter, React Native) need testing. |
| `performHandwritingGesture`, `previewHandwritingGesture`, `requestTextBoundsInfo` | 34 | Not 1.0 (no handwriting). `requestTextBoundsInfo` could later support precise cursor-drag UI. |
| `CursorAnchorInfo.getTextAppearanceInfo`, `getVisibleLineBounds` | 34 | Could match font and size in Kern's inline preview of AI rewrites. [V levels] ([CursorAnchorInfo](https://developer.android.com/reference/android/view/inputmethod/CursorAnchorInfo)) |
| `commitContent` + `EditorInfo.contentMimeTypes` | 25 | GIFs and stickers, if ever. |

### 2.5 `EditorInfo` hints Kern must honor

All [V] from the [EditorInfo reference](https://developer.android.com/reference/android/view/inputmethod/EditorInfo) unless noted:

- **`IME_FLAG_NO_PERSONALIZED_LEARNING` (API 26).** It asks the IME not to update "personalized data such as typing history and personalized language model". The reference also says "the flag is not a guarantee", so apps may not set it in every private mode. Kern should treat password input types the same way, as `product.md` already says.
- **`isWritingToolsEnabled()` (API 36).** Defaults to true. Editors set it to false to opt out of "writing tools, that enable IMEs to replace text with generative AI text". Kern's AI actions must respect it.
- **`getInitialSurroundingText` / `getInitialTextBeforeCursor` (30/31).** Lets Kern show correct suggestions on the very first frame, without an IPC round trip. Don't fully trust them, though: FlorisBoard v0.5 ignored this text after a stale-state bug ([AbstractEditorInstance.kt#L113](https://github.com/florisboard/florisboard/blob/v0.5.2/app/src/main/kotlin/dev/patrickgold/florisboard/ime/editor/AbstractEditorInstance.kt#L113)).
- **`getInitialToolType` (34), `isStylusHandwritingEnabled` (35), `getAutofillId` (36).** For virtual views in Compose and WebView, the autofill ID is the container's unless the provider sets it.
- **Password fields on Android 17.** "Show passwords" is split between touch and physical keyboards. Standard components handle this themselves ([A17 release notes](https://developer.android.com/about/versions/17/release-notes)). It mostly matters to Kern's own text fields. [I]

### 2.6 Predictive back

- **Opting in.** IMEs that register `OnBackInvokedCallback` have their registrations forwarded to the app window by `ImeOnBackInvokedDispatcher`. The system then plays the IME back animation through `ImeBackAnimationController`, which peeks 10% of the keyboard height (`PEEK_FRACTION = 0.1f`) and runs 200 ms after commit and 50 ms after cancel [V] ([ImeOnBackInvokedDispatcher.java](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/window/ImeOnBackInvokedDispatcher.java), [ImeBackAnimationController.java#L53](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/ImeBackAnimationController.java)). The controller's copyright year is 2024, which suggests Android 15. [I]
- **Android 17 targeting change.** For IMEs targeting API 37 with `enableOnBackInvokedCallback="true"`, `BACK_DISPOSITION_ADJUST_NOTHING` now sends back events "directly to the application" [V] ([setBackDisposition](https://developer.android.com/reference/android/inputmethodservice/InputMethodService#setBackDisposition(int))).
- **Android 16 targeting change.** For apps targeting API 36, `onBackPressed` is no longer called and `KEYCODE_BACK` is no longer dispatched [V] ([A16 targeting changes](https://developer.android.com/about/versions/16/behavior-changes-16)).
- **Recommendation [I]:** handle back for Kern's own panels (emoji, clipboard, AI preview) only through `OnBackInvokedCallback`, and never through `onKeyDown(KEYCODE_BACK)`. Let the system own "back closes the keyboard".

### 2.7 Edge-to-edge, insets and the navigation bar

- **Drawing behind the gesture bar.** On gesture navigation, the IME window extends behind the navigation bar, and the framework draws IME navigation buttons (back, IME switcher) on top of it, through `NavigationBarController` [V] ([NavigationBarController.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/inputmethodservice/NavigationBarController.java)).
  - FlorisBoard sets `WindowCompat.setDecorFitsSystemWindows(window, false)` on the IME window and pads for the bar itself [V] ([FlorisImeService.kt#L282](https://github.com/florisboard/florisboard/blob/bac346e8a5e7ee7104359cebdbce7d4105d3f0fa/app/src/main/kotlin/dev/patrickgold/florisboard/FlorisImeService.kt#L282)).
- **Hiding the system IME switcher (API 36).** An IME can hide the system-drawn IME navigation bar with `getWindowInsetsController().hide(captionBar())`. It then receives `onCustomImeSwitcherButtonRequestedVisible(true)` and "must show a button within its UI to switch IMEs" [V] ([InputMethodService](https://developer.android.com/reference/android/inputmethodservice/InputMethodService#onCustomImeSwitcherButtonRequestedVisible(boolean))).
  - **Recommendation [I]:** on API 36+, hide the system bar and draw Kern's own switcher. That gives a seamless background under the gesture handle, which is part of "beautiful". Keep the system bar on 34–35.
- **Translucency.** Blurring the app behind the keyboard is only possible through `Window.setBackgroundBlurRadius` (API 31). Whether it is available depends on the device and the moment (`WindowManager.isCrossWindowBlurEnabled` plus a listener) [V levels] ([Window](https://developer.android.com/reference/android/view/Window#setBackgroundBlurRadius(int)), [WindowManager](https://developer.android.com/reference/android/view/WindowManager#isCrossWindowBlurEnabled())). An IME cannot sample the app's pixels any other way. [I]

### 2.8 Foldables, tablets and floating keyboards

- **How the IME shapes the app's layout.** It reports its footprint in `onComputeInsets(Insets)` [V] ([InputMethodService.Insets](https://developer.android.com/reference/android/inputmethodservice/InputMethodService.Insets)):
  - `contentTopInsets` drives how the app is resized or panned ("any change will cause a full resize/pan").
  - `touchableInsets = TOUCHABLE_INSETS_REGION` plus `touchableRegion` limit where the IME takes touches.
- **Floating or one-handed mode [I].** Set `contentTopInsets` to the window height so the app does not resize. Set the touchable region to the floating panel so taps elsewhere reach the app. Change these values rarely, since every change triggers a relayout of the app.
- **Large screens.** Android 16 and 17 ignore orientation and resizability restrictions on displays of 600 dp or wider for apps targeting 36/37, with no opt-out at 37 [V] ([A17 targeting changes](https://developer.android.com/about/versions/17/behavior-changes-17)). This affects Kern's settings Activity, not the IME window. The keyboard itself must still lay out for any width (split layout). [I]
- **Rotation and the keyboard.** In Android 17, apps no longer get the IME back automatically after an unhandled configuration change such as rotation [V] ([A17 all-apps changes](https://developer.android.com/about/versions/17/behavior-changes-all)). If the keyboard does not come back after rotation, that is app behavior, not a Kern bug. The compatibility matrix should record it as expected. [I]

### 2.9 Other changes in Android 16 and 17 that touch an IME

- **Composition builder and presets.** `VibrationEffect.Builder` and `Preset` arrive in 37.2 [V] ([Builder](https://developer.android.com/reference/android/os/VibrationEffect.Builder)). The haptics guide labels them "Android 16 (26Q4)", which conflicts with the reference's "37.2" [V] ([haptics APIs](https://developer.android.com/develop/ui/views/haptics/haptics-apis)). Trust the reference metadata.
- **Envelope haptics.** `BasicEnvelopeBuilder` arrives in API 36 [V] ([BasicEnvelopeBuilder](https://developer.android.com/reference/android/os/VibrationEffect.BasicEnvelopeBuilder)).
- **New AGSL effects.** `RuntimeColorFilter` and `RuntimeXfermode` arrive in Android 16 [V] ([A16 features](https://developer.android.com/about/versions/16/features)).
- **Encrypted Client Hello.** It is on by default for TLS when the networking library supports it, for apps targeting 37 [V] ([A17 targeting changes](https://developer.android.com/about/versions/17/behavior-changes-17)). This is a small privacy win for the direct calls to OpenRouter. [I]

---

## 3. `InputConnection` compatibility: the hard part

These are the reference commits read (all [V]):

- LatinIME: [`127336e9`](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/127336e9f29d69607eab55982324b210279ae8c5/java/src/com/android/inputmethod/latin/)
- HeliBoard: [`10638635`](https://github.com/HeliBorg/HeliBoard/blob/106386356f0ff5b13ab6617bca4d1cea4d9513e6/app/src/main/java/helium314/keyboard/), GPL-3.0
- FlorisBoard: [`bac346e8`](https://github.com/florisboard/florisboard/blob/bac346e8a5e7ee7104359cebdbce7d4105d3f0fa/app/src/main/kotlin/dev/patrickgold/florisboard/) and [v0.5.2](https://github.com/florisboard/florisboard/blob/v0.5.2/app/src/main/kotlin/dev/patrickgold/florisboard/ime/editor/), Apache-2.0
- AnySoftKeyboard: [`1ee43e7f`](https://github.com/AnySoftKeyboard/AnySoftKeyboard/blob/1ee43e7f037b905e1c762e3beceac1c0178797b6/ime/app/src/main/java/com/anysoftkeyboard/), Apache-2.0
- FUTO: [`7d61ab41`](https://github.com/futo-org/android-keyboard/blob/7d61ab4132cfcbb7aa713190d934f0125dc38b03/java/src/org/futo/inputmethod/latin/), source-first and not OSI

Licenses come from GitHub's repository metadata [V]. HeliBoard is GPL and FUTO is non-free, so code from them can't be copied into an Apache-2.0 Kern, and FUTO's not into any Kern. Take ideas, write the code fresh, and credit them per D3. [I]

### 3.1 Recurring bug classes

**A. Composing text gets duplicated or eaten in JavaScript and web editors and some native apps.**

- **What happens.** The editor rewrites text mid-composition or mishandles `setComposingText` / `setComposingRegion`, and letters or whole words repeat.
- **Where it shows up.** Chrome on keep.google.com, Brave on GitHub search, Firefox on DuckDuckGo and Facebook, Telegram, Instagram web, ChatGPT/Gemini web, and CodeMirror editors (Obsidian, Notion, Logseq). FUTO keeps a tracking table that also notes which other keyboards fail [V] ([FUTO #1519](https://github.com/futo-org/android-keyboard/issues/1519); HeliBoard [#225](https://github.com/HeliBorg/HeliBoard/issues/225), [#662](https://github.com/HeliBorg/HeliBoard/issues/662), [#2408](https://github.com/HeliBorg/HeliBoard/issues/2408)).
- **Workarounds:**
  - **FUTO** emulates composing with `commitText` plus `deleteSurroundingText`, on by default on API 31+ (`InputConnectionInternalComposingWrapper.kt`). It also buffers and merges consecutive calls (`InputConnectionWithBufferingWrapper.kt#L25`) [V].
  - **HeliBoard** adds `NO_SUGGESTIONS` to Firefox web fields (`compat/AppWorkarounds.kt#L9`) and reads back after `setComposingText` (`RichInputConnection.java#L731`) [V].
  - **FlorisBoard k3** only composes in real text fields with autocorrect, and notes that CodeMirror 6 needs composing off (`ime/keyboard3/ImeController.kt#L274`) [V].

**B. Cursor position lies and late `onUpdateSelection` calls.**

- **What happens.** The initial selection is stale, or updates arrive late.
- **Where it shows up.** For example, the first letter comes out lowercase if you type right after sending in WhatsApp, Signal, Discord or Telegram [V] ([HeliBoard #994](https://github.com/HeliBorg/HeliBoard/issues/994), open).
- **Workarounds:**
  - **LatinIME** uses `isBelatedExpectedUpdate` and `tryFixLyingCursorPosition` [V] ([RichInputConnection.java](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/127336e9f29d69607eab55982324b210279ae8c5/java/src/com/android/inputmethod/latin/RichInputConnection.java)).
  - **HeliBoard** uses `tryFixIncorrectCursorPosition`, which falls back to `getExtractedText` [V].
  - **AnySoftKeyboard** treats any selection update within 1.5 s of its own edit as expected (`markExpectingSelectionUpdate`) [V].
  - **FlorisBoard v0.5** rebuilds the selection itself instead of trusting `EditorInfo` [V].

**C. The keyboard's text cache drifts from the field.**

- **What happens.** The app rewrites text: Joplin list markers, a notepad returning stale text, a site that transliterates.
- **Workarounds.** HeliBoard compares with its cache and reloads (`checkTextBeforeCursorConsistency`, `RichInputConnection.java#L542`). LatinIME only does this check in debug builds [V].

**D. `setSelection` does not trigger `onUpdateSelection` (Firefox/GeckoView).**

- **What happens.** Spacebar cursor control jumps word by word instead of letter by letter [V] ([HeliBoard #1139](https://github.com/HeliBorg/HeliBoard/issues/1139)).
- **Related.** Firefox also never sets `TYPE_TEXT_VARIATION_WEB_EDIT_TEXT`. HeliBoard forces it for Firefox, and FUTO checks the package name (`InputAttributes.java#L159`) [V].

**E. Terminals and `TYPE_NULL` fields.**

- **Termux.** It exposes a `BaseInputConnection` that only forwards on `commitText` / `finishComposingText`, maps `deleteSurroundingText(n)` to n `KEYCODE_DEL`, and maps `\n` to `\r` [V] ([TerminalView.java](https://github.com/termux/termux-app/blob/master/terminal-view/src/main/java/com/termux/view/TerminalView.java)). A composing keyboard therefore shows nothing until space [V] ([AnySoftKeyboard #4470](https://github.com/AnySoftKeyboard/AnySoftKeyboard/issues/4470), open).
- **Workarounds:**
  - **FUTO** turns composing off for Termux and other terminals (`getCodeFieldType`, `InputAttributes.java#L377`) [V].
  - **FlorisBoard** treats any `TYPE_NULL` field as raw input and sends key events.
  - **AnySoftKeyboard** detects ConnectBot and JuiceSSH.
  - **LatinIME** has a TODO and does not honor `TYPE_NULL` at all [V].

**F. Editors that expect key events, not text.**

- **Backspace in an empty field.** LatinIME's "HACK for broken apps" still sends a delete when there is nothing to delete (`InputLogic.java`) [V]. React Native infers `onKeyPress` backspace from the InputConnection (`ReactEditTextInputConnectionWrapper`) [S].
- **Digits.** LatinIME sends digits as `KEYCODE_0..9`. HeliBoard does the same after Chrome one-time-code (OTP) fields broke [V] ([HeliBoard #585](https://github.com/HeliBorg/HeliBoard/issues/585)). Chrome OTP backspace is still open in [#2010](https://github.com/HeliBorg/HeliBoard/issues/2010).
- **Enter in Chrome.** Chrome turns `commitText("\n")` into a synthetic ENTER [V] ([ThreadedInputConnection.java](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/content/public/android/java/src/org/chromium/content/browser/input/ThreadedInputConnection.java)).

**G. Paste and shortcuts.**

- **HeliBoard `paste()`.** It commits text when the clip is plain text and otherwise sends `KEYCODE_PASTE`, but Firefox needs Ctrl+V instead. A regression in this code broke Termux, Fennec and ConnectBot [V] ([HeliBoard #2651](https://github.com/HeliBorg/HeliBoard/issues/2651)).
- **Flutter.** Ctrl+Z/Y stopped working in Flutter 3.44 apps [V] ([FUTO #2106](https://github.com/futo-org/android-keyboard/issues/2106), open).

**H. Apps that set contradictory flags in `EditorInfo`.**

- **Pixel launcher.** It sets `IME_FLAG_NO_ENTER_ACTION` but expects Enter to search. HeliBoard and FUTO special-case it [V] (`AppWorkarounds.kt#L26`, [HeliBoard #1989](https://github.com/HeliBorg/HeliBoard/issues/1989)).
- **Google Keep.** It sets both `NO_SUGGESTIONS` and `AUTO_CORRECT`. AnySoftKeyboard resolves the conflict in favor of suggestions (`IMEUtil.java#L191`) [V].

**I. Samsung One UI.**

- **RTL brackets.** On One UI, `commitText("( ")` inserts the mirrored bracket in RTL. FUTO stops merging commits on Samsung for this reason (`canMerge`, `InputConnectionWithBufferingWrapper.kt#L35`) [V].

**J. Slow connections (Chrome/WebView).**

- **Chrome.** Its `ThreadedInputConnection` runs on an IME thread and blocks waiting for the renderer [V] ([ThreadedInputConnection.java](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/content/public/android/java/src/org/chromium/content/browser/input/ThreadedInputConnection.java)).
- **LatinIME.** It times every read, using 1000 ms for a full reload and 200 ms for a partial one. Past those limits it flags the connection as slow for 10 minutes (`detectLaggyConnection`) [V].
- **Implication for Kern [I]:** never read from the `InputConnection` on the path from a key press to visual feedback.

### 3.2 Design patterns worth adopting

1. **An editor model with optimistic updates** (LatinIME `RichInputConnection`) [V]:
   - Kern keeps the expected selection, a cache of committed text before the cursor, and the composing text.
   - It updates them on every write and reloads only on an update it did not expect.
   - Per-app quirks are built from the target app's package (`AppWorkaroundsUtils`).
2. **A queue of expected states** (FlorisBoard v0.5) [V]:
   - Each write runs inside a batch edit and pushes the `EditorContent` it should produce (`AbstractEditorInstance.kt#L95`).
   - `handleSelectionUpdate` pops queue entries until one matches the update. If none matches, it re-reads the field (#L165).
   - This is the cleanest typed design of the five. [I]
3. **A consistency check and reload** (HeliBoard) [V].
4. **A buggy-editor bench** (FUTO) [V] ([buggyeditors/](https://github.com/futo-org/android-keyboard/tree/7d61ab4132cfcbb7aa713190d934f0125dc38b03/java/src/org/futo/inputmethod/latin/uix/settings/pages/buggyeditors)):
   - It has seven switches: `setComposingTextDoesNotCompose`, `composingTextResetsAfter`, `noComposing`, `cursorMovesToEndAndBack`, `doesNotSendCursorUpdates`, `alwaysSendsNoComposing` and `delayCursorUpdates`.
   - It also has a wrapper that logs every `InputConnection` call.
   - Kern should build its own (clean-room) bench and run it in CI. [I]
5. **A composing policy per editor kind [I]:**
   - Native `EditText` and Compose fields: compose normally.
   - Web and contenteditable editors: compose, but verify by reading back, and fall back to commit-only emulation after a detected desync.
   - Terminals and `TYPE_NULL`: never compose; send key events.
   - Make the policy a pure function of `(EditorInfo, package, observed desync history)`, with unit tests.

### 3.3 Proposed app compatibility matrix

**Tiers [I]:**

- **T1** is automated in CI on every pull request, using open-source harness apps on an emulator.
- **T2** is semi-automated with UI Automator on owned physical devices before every release.
- **T3** is a manual checklist before every release.

**Behavior IDs used in the table:**

| ID | Behavior |
|---|---|
| B1 | type a sentence; check auto-cap after send/newline |
| B2 | fast typing at 10+ chars/s with no lost or duplicated characters |
| B3 | autocorrect, then one backspace undoes it |
| B4 | tap mid-word, then recorrect (`setComposingRegion`) |
| B5 | spacebar cursor drag |
| B6 | long-press backspace and word delete |
| B7 | select all, cut, copy, paste |
| B8 | Enter or IME action |
| B9 | emoji and surrogate pairs |
| B10 | rotation and keyboard re-show |
| B11 | the field is cleared by the app (send) |
| B12 | password field: no learning, no suggestions |
| B13 | incognito flag honored |
| B14 | AI rewrite applied via `replaceText` |
| B15 | swipe word commit |

| App | Package | Text stack | Tier | Behaviors | Known traps |
|---|---|---|---|---|---|
| Kern harness: `EditText` | own | TextView | T1 | all | baseline |
| Kern harness: Compose `BasicTextField(TextFieldState)` | own | Compose | T1 | all | — |
| Kern harness: WebView (`textarea`, `contenteditable`, CodeMirror 6, `input[type=tel]` OTP boxes) | own | Chromium | T1 | B1–B11, B14 | A, F, J |
| Kern harness: FUTO-style buggy editors (7 modes) | own | custom IC | T1 | B1–B4, B6 | A, B, C |
| Flutter sample (current stable) | own build | Flutter | T1 | B1–B8, B11 | F, G |
| React Native sample (new architecture) | own build | RN | T1 | B1–B8, B11 | F |
| Termux (F-Droid / GitHub APK) | `com.termux` | `TYPE_NULL` | T1 | B2, B6, B7, B8, plus Tab/Esc/Ctrl | E, G |
| Chrome (address bar, Google search, Keep web, GitHub search, ChatGPT web) | `com.android.chrome` | Chromium | T2 | B1–B11, B14 | A, F, J |
| Firefox | `org.mozilla.firefox` | GeckoView | T2 | B4, B5, B7, B11 | A, D, G |
| Samsung Internet | `com.sec.android.app.sbrowser` | Chromium fork | T2 | B1–B6 | A |
| WhatsApp | `com.whatsapp` | native | T2 | B1, B3, B4, B9, B11 | B |
| Telegram | `org.telegram.messenger` | custom EditText | T2 | B1, B2, B4, B11 | A, B |
| Instagram (DM, comments, captions) | `com.instagram.android` | native / RN | T2 | B1, B2, B4, B11 | A |
| Signal, Discord | `org.thoughtcrime.securesms`, `com.discord` | native / RN | T3 | B1, B11, edit-message caps | B, H |
| Gmail (compose body, To field) | `com.google.android.gm` | WebView body | T2 | B1–B7, B14 | A, J |
| Google Docs | `com.google.android.apps.docs.editors.docs` | custom editor | T2 | B2, B4, B5, B6, B14 | B, C [I] |
| Google Keep (native) | `com.google.android.keep` | native | T3 | B3 | H |
| Samsung Notes, Samsung Messages (One UI) | `com.samsung.android.app.notes`, `com.samsung.android.messaging` | One UI | T3 | B1–B7 | I |
| Pixel launcher search | `com.google.android.apps.nexuslauncher` | native | T3 | B8 | H |
| Obsidian or Notion | `md.obsidian`, `notion.id` | CodeMirror / web | T3 | B2, B4, B11 | A |
| Banking app OTP screen (one local app) | — | varies | T3 | digits, backspace | F |

The package names above follow the apps' Play Store listings and are [I]. Confirm each one before automating it.

---

## 4. Rendering, haptics and touch latency

### 4.1 Compose inside an IME

- **Known setup issue [V].** `InputMethodService` is not a `LifecycleOwner`. Compose looks up the view-tree owners from the window's *decor view*, so they must be installed there or the process crashes with "ViewTreeLifecycleOwner not found".
  - **FlorisBoard's fix.** `LifecycleInputMethodService` implements `LifecycleOwner`, `ViewModelStoreOwner` and `SavedStateRegistryOwner`. It installs them on `window.window.decorView`, sets RESUMED on `onWindowShown` and PAUSED on `onWindowHidden` ([LifecycleInputMethodService.kt](https://github.com/florisboard/florisboard/blob/bac346e8a5e7ee7104359cebdbce7d4105d3f0fa/app/src/main/kotlin/dev/patrickgold/florisboard/ime/lifecycle/LifecycleInputMethodService.kt)).
  - **What happens if the lifecycle is wrong.** A lifecycle that never reaches RESUMED leaves a keyboard that draws once and never updates [S] ([epheos guide](https://epheos.com/library/compose-ime)).
- **What other keyboards do [V]:**
  - **FUTO** puts a `ComposeView` at the root, with `ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed` and no parent composition context. Keys are still drawn by the inherited LatinIME `KeyboardView.onDraw` on a Canvas ([LatinIME.kt#L184](https://github.com/futo-org/android-keyboard/blob/7d61ab4132cfcbb7aa713190d934f0125dc38b03/java/src/org/futo/inputmethod/latin/LatinIME.kt#L184), [KeyboardView.java#L257](https://github.com/futo-org/android-keyboard/blob/7d61ab4132cfcbb7aa713190d934f0125dc38b03/java/src/org/futo/inputmethod/keyboard/KeyboardView.java#L257)).
  - **FlorisBoard k3** draws each key as a composable ([ImeKeyButton.kt](https://github.com/florisboard/florisboard/blob/bac346e8a5e7ee7104359cebdbce7d4105d3f0fa/app/src/main/kotlin/dev/patrickgold/florisboard/ime/keyboard3/ui/ImeKeyButton.kt)).
  - FlorisBoard's tracker has historical reports of slow typing and slow startup ([#689](https://github.com/florisboard/florisboard/issues/689), [#1650](https://github.com/florisboard/florisboard/issues/1650)). They predate the current code, and their root causes were mixed (suggestions, process kills), so they are **not** evidence against Compose [V for the issues, I for the reading].
- **Recommendation [I]:**
  - Draw the **key surface** with one custom `View`. It handles touch directly, draws all keys in `onDraw`, and caches static key layers in a `RenderNode` per layout. Only the pressed-key overlay and the swipe trail are redrawn on each frame.
  - Write all **chrome** in Compose: the suggestion strip, panels and settings.
  - Reasons:
    - The key surface is the one place where per-frame allocation and recomposition must be zero, and where hit-testing must be Kern's own (personal touch model).
    - Everywhere else, Compose's productivity and animation APIs pay off.
    - The split also keeps Compose's cold-start cost off the "keys visible" path, which must be under 250 ms when the process starts cold.
  - Validate with the spike in recommendation 4.

### 4.2 AGSL and effects

- **Shaders.** `RuntimeShader` (API 33) works in a custom View's `Paint`, in Compose's `ShaderBrush`, and as `RenderEffect.createRuntimeShaderEffect` on a view subtree. The guide warns the last is "more expensive than drawing a custom `View`" [V] ([AGSL guide](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl)).
- **Where it fits [I].** Use AGSL for the swipe-trail glow, key-press ripples and theme gradients, drawn inside the key-surface View. Avoid `RenderEffect` on the whole keyboard.

### 4.3 Haptics

- **Framework keyboard haptics [V].**
  - `HapticFeedbackConstants.KEYBOARD_TAP` (= `KEYBOARD_PRESS`) maps to `PRIMITIVE_CLICK` at an OEM-configured fixed amplitude when the device supports it, else to `EFFECT_CLICK`. `KEYBOARD_RELEASE` maps to `PRIMITIVE_TICK` or `EFFECT_TICK` ([HapticFeedbackVibrationProvider.java#L329](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/vibrator/HapticFeedbackVibrationProvider.java)).
  - `View.performHapticFeedback` adds `PRIVATE_FLAG_APPLY_INPUT_METHOD_SETTINGS` when the view's window type is `TYPE_INPUT_METHOD` ([View.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/View.java)). The vibration then uses IME-feedback attributes, which follow the system "Keyboard vibration" setting (`Settings.System.KEYBOARD_VIBRATION_ENABLED`) ([VibrationSettings.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/vibrator/VibrationSettings.java), [Settings.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/Settings.java)).
  - `USAGE_IME_FEEDBACK` is not in the public `VibrationAttributes` reference [V] ([VibrationAttributes](https://developer.android.com/reference/android/os/VibrationAttributes)). A custom `Vibrator.vibrate` from Kern therefore cannot claim the IME usage, and would be governed by the touch intensity instead. [I]
- **Compositions [V].** `VibrationEffect.Composition` has no fallback: one unsupported primitive and "the entire `VibrationEffect.Composition` will fail to play". Primitive support is per device ([haptics APIs](https://developer.android.com/develop/ui/views/haptics/haptics-apis)). CTS only checks that `arePrimitivesSupported` does not crash, so certified devices may support none [S] ([CTS VibratorTest](https://android.googlesource.com/platform/cts/+/ef78465258d/tests/tests/os/src/android/os/cts/VibratorTest.java)).
- **Recommendation [I]:**
  - Default to `performHapticFeedback(KEYBOARD_TAP)` on the key-surface View, at ACTION_DOWN. It respects the user's system setting, needs no `VIBRATE` permission, and is OEM-tuned.
  - Offer "Kern haptics" as an opt-in: compositions of `PRIMITIVE_CLICK`, `LOW_TICK` and `TICK` with tuned scales, only when `areAllPrimitivesSupported` is true. Adopt `VibrationEffect.Builder` presets once 37.2 is stable.
  - Never use raw `createOneShot` buzzes.

### 4.4 Touch latency

- **Input batching.** The docs warn that unbuffered dispatch "will not improve your input latency" for most streams and causes "increased latency, jittery scrolls and inability to take advantage of system resampling" [V] ([View.requestUnbufferedDispatch](https://developer.android.com/reference/android/view/View#requestUnbufferedDispatch(android.view.MotionEvent))). Pre-vsync batching applies to MOVE events. The DOWN that triggers key feedback is not the problem. [I]
- **Low-latency trail.** `androidx.graphics:graphics-core` 1.0.4 (stable, 2025-12-03) provides `CanvasFrontBufferedRenderer`, `GLFrontBufferedRenderer` and `LowLatencyCanvasView` for front-buffered drawing [V] ([graphics release notes](https://developer.android.com/jetpack/androidx/releases/graphics)).
- **Motion prediction.** `androidx.input:input-motionprediction` is at 1.0.1, and the platform `MotionPredictor` arrived in API 34 [V] ([Maven metadata](https://dl.google.com/android/maven2/androidx/input/input-motionprediction/maven-metadata.xml), [MotionPredictor](https://developer.android.com/reference/android/view/MotionPredictor)).
- **Recommendation [I]:**
  - **Key feedback.** Draw the pressed state on the same frame as ACTION_DOWN by invalidating only the key's layer. Fire the haptic before drawing. Commit text after drawing, on the same main-thread turn. Run decoding off the main thread, as the quality bars already require.
  - **Swipe trail.** Try front-buffered rendering plus prediction behind a flag, and keep it only if the camera measurement (§6.3) shows a real gain.
  - **Frame rate.** `View.setRequestedFrameRate` / `REQUESTED_FRAME_RATE_CATEGORY_HIGH` (API 35) can hold high refresh rates during a swipe on devices with adaptive refresh rate [V levels] ([View](https://developer.android.com/reference/android/view/View#setRequestedFrameRate(float))).

---

## 5. Play Store

### 5.1 Policies that apply to a keyboard

- **Data safety [V]** ([Data safety](https://support.google.com/googleplay/android-developer/answer/10787469)):
  - "Collect" means "transmitting data from your app off a user's device", including to third-party servers. Data "only processed locally" need not be disclosed.
  - The user-initiated-transfer exemption applies only to *sharing*, not to collection.
  - Every app must fill in the form and link a privacy policy, even an app that collects nothing.
  - **Mapping for Kern [I]:**
    - Core typing collects nothing.
    - AI rewrite and translate collect "Messages: Other in-app messages" and/or "App activity: Other user-generated content". These are optional, encrypted in transit, not shared (user-initiated), with purpose "App functionality".
    - Cloud voice collects "Audio: Voice or sound recordings", optional.
    - Nothing is collected by Kern's own servers, because there are none. The privacy policy must say so and name OpenRouter as the recipient the user chooses.
- **User Data policy [V]** ([User Data](https://support.google.com/googleplay/android-developer/answer/10144311)):
  - Prominent disclosure and consent are needed when data handling "may not be within the reasonable expectation of the user".
  - The disclosure must be in the app, "displayed in the normal usage", describe the data and its use, and get affirmative consent. Navigating away does not count as consent.
  - Personal data must be transmitted with "modern cryptography (for example, over HTTPS)".
  - **For Kern [I]:** the first time the user turns on an AI or cloud feature, show a disclosure ("Kern sends the selected text to OpenRouter using your key to rewrite it") with Accept and Decline.
- **Accessibility service [V]** ([Accessibility policy](https://support.google.com/googleplay/android-developer/answer/10964491)): it needs a declaration form, an in-app disclosure that can't be bundled with other disclosures, and a video, unless the app is an accessibility tool. Kern's non-goals already exclude it. Keep it that way.
- **`QUERY_ALL_PACKAGES` [V]** ([policy](https://support.google.com/googleplay/android-developer/answer/10158779)): keyboards are not a permitted use, and the permission is refused when a narrower visibility method works. An IME automatically sees "any app that receives input from your app" [V] ([package visibility](https://developer.android.com/training/package-visibility/automatic)). Per-app quirks can therefore read the target's `PackageInfo` without the permission. [I]
- **Microphone and network [V]** ([Permissions policy](https://support.google.com/googleplay/android-developer/answer/9888170)):
  - `RECORD_AUDIO` and `INTERNET` are not on the list of permissions that need a declaration form.
  - Request runtime permissions "in context (via incremental requests)", and only ones "necessary to implement current features".
  - **For Kern [I]:** ask for the microphone the first time the user taps the mic, from a small Activity. Don't ask during onboarding.
  - Google Play has no policy specific to an IME holding `INTERNET` that I could find. User distrust of a keyboard with network access is the real cost. That is a product and communication problem, addressed by the visible privacy screen. [I]
- **Target API [V]:** API 36 or higher since 2026-08-31 for new apps and updates ([Play target API](https://support.google.com/googleplay/android-developer/answer/11926878)).

### 5.2 The system warning when enabling an IME

- **The text.** Android shows this dialog when a user enables a third-party keyboard: "This input method may be able to collect all the text you type, including personal data like passwords and credit card numbers. It comes from the app %1$s." [V] ([SettingsLib strings.xml, `ime_security_warning`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values/strings.xml))
- **Recommendation [I].** Kern cannot change or skip this dialog. The setup screen should say, just before the user goes to Settings, that Android shows this warning for every keyboard, and that Kern's core never connects to the network (link to the privacy screen and the source code).

### 5.3 Onboarding that reduces drop-off

What other keyboards do [V]:

- **LatinIME** has a three-step wizard:
  1. Enable: opens `Settings.ACTION_INPUT_METHOD_SETTINGS`.
  2. Select: `InputMethodManager.showInputMethodPicker()`.
  3. Configure languages.
- While the user is in Settings, LatinIME polls `isThisImeEnabled` every 200 ms, then relaunches the wizard with `FLAG_ACTIVITY_SINGLE_TOP | CLEAR_TOP`. The user never has to press back ([SetupWizardActivity.java](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/java/src/com/android/inputmethod/latin/setup/SetupWizardActivity.java)).
- **FUTO** has the same two steps, plus a warning about direct boot (the keyboard before first unlock) ([Setup.kt](https://github.com/futo-org/android-keyboard/blob/7d61ab4132cfcbb7aa713190d934f0125dc38b03/java/src/org/futo/inputmethod/latin/uix/settings/Setup.kt)).
- **FlorisBoard** observes enabled and selected state as a Compose `State`. On API 34+ it uses `enabledInputMethodList` and `currentInputMethodInfo` ([InputMethodUtils.kt](https://github.com/florisboard/florisboard/blob/bac346e8a5e7ee7104359cebdbce7d4105d3f0fa/app/src/main/kotlin/dev/patrickgold/florisboard/lib/util/InputMethodUtils.kt#L44)).

Recommended flow for Kern [I]:

1. One screen and one primary button that changes label as the state changes: "Turn on Kern", then "Switch to Kern", then "Try it". It comes with one sentence about the system warning (§5.2).
2. Detect that Kern was enabled (poll or `ContentObserver`) and come back to the front automatically.
3. Open the picker immediately. Detect the selection in `onWindowFocusChanged`.
4. End on a "try it" text field in the setup Activity, with the keyboard already up. It shows the result and lets the user pick languages from the suggested system locales.
5. No permission requests, accounts or AI setup during onboarding. Each is offered in context later.
6. Kern must be direct-boot aware (`directBootAware`, device-protected storage for the minimal layout and settings) so it works on the lock screen before first unlock. Otherwise the system falls back to another keyboard there. FUTO warns users about this case.

---

## 6. Testing

### 6.1 Automating IME tests across real apps

- **Switching IMEs from tests.** `adb shell ime enable|set|disable|list|reset` are real commands handled by `InputMethodManagerService`'s shell command [V] ([InputMethodManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/inputmethod/InputMethodManagerService.java)). Tests can enable and select Kern without going through the UI.
- **UI Automator 2.4.** It is now stable (2.4.1 on Maven [V], [metadata](https://dl.google.com/android/maven2/androidx/test/uiautomator/uiautomator/maven-metadata.xml)). It drives other apps from outside their process, offers `startApp`, `onElement { … }` and `windows()` for multi-window, and supports screenshots [V] ([UI Automator guide](https://developer.android.com/training/testing/other-components/ui-automator)).
- **Proposed harness [I]:**
  1. A debug-only, instrumented test hook *inside the Kern process*. It is never in release builds. It lets a test feed key taps or swipe paths into the real touch pipeline (synthetic `MotionEvent`s on the key-surface View), so tests don't depend on screen coordinates.
  2. UI Automator opens the target app and focuses the field.
  3. Assert on two independent readings: Kern's own `getSurroundingText`, and the field text that UI Automator reads through accessibility. They disagree exactly when a desync bug happens.
  4. For editors without accessibility text (Termux), compare a screenshot crop or use the app's own export (`termux` shell history).
- **CI (free) [V]:**
  - Standard GitHub-hosted runners are free in public repositories; larger runners are not ([GitHub Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions)).
  - `ReactiveCircus/android-emulator-runner` runs KVM-accelerated emulators on `ubuntu-latest`, with `google_apis` and `aosp_atd` targets and AVD snapshot caching ([repo](https://github.com/ReactiveCircus/android-emulator-runner)).
  - Gradle Managed Devices can define device groups and shards. Use `swiftshader_indirect` on runners without a GPU. **ATD images remove LatinIME, Chrome and the Settings app**, so they are unsuitable for IME integration tests ([GMD docs](https://developer.android.com/studio/test/gradle-managed-devices)).
- **Real devices (free or cheap) [V]:**
  - Firebase Test Lab's free Spark plan allows 10 virtual and 5 physical test runs per day ([FTL pricing](https://firebase.google.com/docs/test-lab/usage-quotas-pricing)). That is enough for a nightly smoke run on 1–2 physical devices, not for the matrix.
  - Samsung Remote Test Lab is free for Samsung Developer members, with daily credits, and lists One UI 9 / Android 17 devices [S] ([RTL FAQ](https://developer.samsung.com/remotetestlab/docs/2/faq)). It is useful for the One UI rows of the matrix.
- **What to own [I].** One Pixel on the latest Android, one mid-range Samsung (also the likely reference device for the quality bars), and one low-end Android 14 device. Run T2 on them before each release.
- **Proprietary apps on CI [I].** They need a Google account and Play installs on a `google_apis_playstore` image, which is fragile and raises terms-of-service questions. Keep them in T2 on owned devices.

### 6.2 Debugging `InputConnection` traffic

- **Winscope IME traces [S]** ([Winscope adb capture](https://source.android.com/docs/core/graphics/winscope/capture/adb)): they record IME client, service and manager state. On Android 15+ capture them through Perfetto with the `android.inputmethod` data source. Older releases use `adb shell ime tracing start|stop`.
- **Kern's own debug build [I].** Wrap the `InputConnection` in a logging proxy (FUTO does this, §3.2) and attach the log to bug reports. Strip the text content by default.

### 6.3 Measuring input latency on a device

- **Perfetto [V]** ([TraceConfig](https://perfetto.dev/docs/reference/trace-config-proto), [stdlib](https://perfetto.dev/docs/analysis/stdlib-docs), [FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline)):
  - FrameTimeline gives expected-versus-actual present time per frame and needs Android 12+.
  - The `input` atrace category shows InputDispatcher → app delivery.
  - The `android.input.inputevent` data source gives per-event records, but "Input traces can only be taken on debuggable (userdebug/eng) builds".
- **In-process [V levels]** ([FrameMetrics](https://developer.android.com/reference/android/view/FrameMetrics)):
  - `Window.addOnFrameMetricsAvailableListener` (API 24) on the IME window provides `INPUT_HANDLING_DURATION`, `TOTAL_DURATION`, `GPU_DURATION` and `DEADLINE` (31).
  - Pair those with `MotionEvent.getEventTime()` to compute event-to-frame time for every key press in a benchmark build. [I]
- **End to end [V/I].** Touch-to-photon needs external hardware:
  - Google's open-source **WALT** latency tester ([google/walt](https://github.com/google/walt)).
  - Or a 240 fps phone camera filming a finger tap against the key highlight. That gives ~4 ms resolution, enough to check the "p99 under one frame" bar at 60–120 Hz. [I]
- **Two separate measurements [I].** Measure key-to-feedback latency (the IME window only) apart from key-to-text-in-app latency (which includes the app's own redraw). Only the first is fully under Kern's control. Report both.

---

## 7. Open risks

1. **App behavior Kern doesn't control.** Desync bugs in Chrome, Telegram, Flutter and CodeMirror are open in every keyboard's tracker (§3.1). "Full pass on the app matrix" may need per-app exceptions that are documented as app bugs. Decide this bar explicitly before 1.0. [I]
2. **How editors implement `replaceText` (API 34).** The reference specifies it as finish-composing, then select, then commit [V]. Whether the app's undo stack treats a rewrite as one step, and whether custom `InputConnection`s (Chromium, Flutter, React Native) implement it faithfully, is unknown. Test before committing to the "one gesture to undo a rewrite" UX. [I]
3. **The Compose-versus-View decision is unmeasured.** Evidence that Compose is too slow for the key surface is anecdotal and old (§4.1). Without the spike, either choice is a guess. [I]
4. **Haptics fragmentation.** Composition support depends on the device's vibrator HAL, and certification doesn't require it [S]. "Tuned haptics" will feel different on mid-range devices. The reference device must be chosen with this in mind. [I]
5. **Play review of a keyboard with network access and AI.** No IME-specific rule exists, but a reviewer may flag that typed text can go to a third party. Prepare the disclosure screen and the Data safety answers before the first submission, and keep the AI layer optional and off by default. [I]
6. **Android 17 memory limits** could kill Kern under pressure, together with the language models. Measure RSS with all six languages enabled. [V for the limits, I for the risk]
7. **Fold and posture data in the IME.** `WindowInfoTracker` accepts a UI context, and the IME is one, but I did not verify that fold features are reported for IME windows. [I]
8. **Platform API drift.** 37.2 introduces haptics and IME-animation APIs (`setImeShowAnimationDurationOverrideMillis`) that are not yet stable. Revisit at the next quarterly release. [V for the levels]

---

## 8. Sources

Android reference and guides (developer.android.com):

- [InputMethodService](https://developer.android.com/reference/android/inputmethodservice/InputMethodService) · [InputMethodService.Insets](https://developer.android.com/reference/android/inputmethodservice/InputMethodService.Insets) · [InputConnection](https://developer.android.com/reference/android/view/inputmethod/InputConnection) · [EditorInfo](https://developer.android.com/reference/android/view/inputmethod/EditorInfo) · [InputMethodManager](https://developer.android.com/reference/android/view/inputmethod/InputMethodManager) · [CursorAnchorInfo](https://developer.android.com/reference/android/view/inputmethod/CursorAnchorInfo) · [TextAttribute](https://developer.android.com/reference/android/view/inputmethod/TextAttribute) · [InlineSuggestionsRequest](https://developer.android.com/reference/android/view/inputmethod/InlineSuggestionsRequest) · [View](https://developer.android.com/reference/android/view/View) · [Window](https://developer.android.com/reference/android/view/Window) · [WindowManager](https://developer.android.com/reference/android/view/WindowManager) · [FrameMetrics](https://developer.android.com/reference/android/view/FrameMetrics) · [MotionPredictor](https://developer.android.com/reference/android/view/MotionPredictor) · [Context](https://developer.android.com/reference/android/content/Context) · [VibrationEffect.Composition](https://developer.android.com/reference/android/os/VibrationEffect.Composition) · [VibrationEffect.Builder](https://developer.android.com/reference/android/os/VibrationEffect.Builder) · [BasicEnvelopeBuilder](https://developer.android.com/reference/android/os/VibrationEffect.BasicEnvelopeBuilder) · [Vibrator](https://developer.android.com/reference/android/os/Vibrator) · [VibrationAttributes](https://developer.android.com/reference/android/os/VibrationAttributes) · [HapticFeedbackConstants](https://developer.android.com/reference/android/view/HapticFeedbackConstants) · [Build.VERSION_CODES_FULL](https://developer.android.com/reference/android/os/Build.VERSION_CODES_FULL) · [WindowInfoTracker](https://developer.android.com/reference/kotlin/androidx/window/layout/WindowInfoTracker)
- [Stylus input in text fields](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/stylus-input-in-text-fields) · [IME autofill](https://developer.android.com/guide/topics/text/ime-autofill) · [Haptics APIs](https://developer.android.com/develop/ui/views/haptics/haptics-apis) · [AGSL](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl) · [Package visibility: automatic](https://developer.android.com/training/package-visibility/automatic) · [UI Automator](https://developer.android.com/training/testing/other-components/ui-automator) · [Gradle Managed Devices](https://developer.android.com/studio/test/gradle-managed-devices) · [androidx.graphics releases](https://developer.android.com/jetpack/androidx/releases/graphics)
- [Android 16 features](https://developer.android.com/about/versions/16/features) · [Android 16 targeting changes](https://developer.android.com/about/versions/16/behavior-changes-16) · [Android 17 features](https://developer.android.com/about/versions/17/features) · [Android 17 targeting changes](https://developer.android.com/about/versions/17/behavior-changes-17) · [Android 17 all-apps changes](https://developer.android.com/about/versions/17/behavior-changes-all) · [Android 17 release notes](https://developer.android.com/about/versions/17/release-notes)
- Maven metadata: [input-motionprediction](https://dl.google.com/android/maven2/androidx/input/input-motionprediction/maven-metadata.xml) · [uiautomator](https://dl.google.com/android/maven2/androidx/test/uiautomator/uiautomator/maven-metadata.xml)

AOSP source (android.googlesource.com, `main` unless pinned):

- [InputMethodBindingController.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/inputmethod/InputMethodBindingController.java) · [InputMethodManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/inputmethod/InputMethodManagerService.java) · [NavigationBarController.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/inputmethodservice/NavigationBarController.java) · [ImeBackAnimationController.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/ImeBackAnimationController.java) · [ImeOnBackInvokedDispatcher.java](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/window/ImeOnBackInvokedDispatcher.java) · [View.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/View.java) · [HapticFeedbackVibrationProvider.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/vibrator/HapticFeedbackVibrationProvider.java) · [VibrationSettings.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/vibrator/VibrationSettings.java) · [Settings.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/Settings.java) · [SettingsLib strings.xml](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsLib/res/values/strings.xml)
- LatinIME: [RichInputConnection.java](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/127336e9f29d69607eab55982324b210279ae8c5/java/src/com/android/inputmethod/latin/RichInputConnection.java) · [SetupWizardActivity.java](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/java/src/com/android/inputmethod/latin/setup/SetupWizardActivity.java)
- [CTS VibratorTest](https://android.googlesource.com/platform/cts/+/ef78465258d/tests/tests/os/src/android/os/cts/VibratorTest.java) · [Chromium ThreadedInputConnection.java](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/content/public/android/java/src/org/chromium/content/browser/input/ThreadedInputConnection.java)

Other keyboards and apps (pinned commits in §3):

- HeliBoard: [repo](https://github.com/HeliBorg/HeliBoard) · issues [#225](https://github.com/HeliBorg/HeliBoard/issues/225) [#585](https://github.com/HeliBorg/HeliBoard/issues/585) [#662](https://github.com/HeliBorg/HeliBoard/issues/662) [#994](https://github.com/HeliBorg/HeliBoard/issues/994) [#1139](https://github.com/HeliBorg/HeliBoard/issues/1139) [#1989](https://github.com/HeliBorg/HeliBoard/issues/1989) [#2010](https://github.com/HeliBorg/HeliBoard/issues/2010) [#2408](https://github.com/HeliBorg/HeliBoard/issues/2408) [#2651](https://github.com/HeliBorg/HeliBoard/issues/2651)
- FlorisBoard: [LifecycleInputMethodService.kt](https://github.com/florisboard/florisboard/blob/bac346e8a5e7ee7104359cebdbce7d4105d3f0fa/app/src/main/kotlin/dev/patrickgold/florisboard/ime/lifecycle/LifecycleInputMethodService.kt) · [FlorisImeService.kt](https://github.com/florisboard/florisboard/blob/bac346e8a5e7ee7104359cebdbce7d4105d3f0fa/app/src/main/kotlin/dev/patrickgold/florisboard/FlorisImeService.kt) · [InputMethodUtils.kt](https://github.com/florisboard/florisboard/blob/bac346e8a5e7ee7104359cebdbce7d4105d3f0fa/app/src/main/kotlin/dev/patrickgold/florisboard/lib/util/InputMethodUtils.kt) · [ImeEditor.kt](https://github.com/florisboard/florisboard/blob/bac346e8a5e7ee7104359cebdbce7d4105d3f0fa/app/src/main/kotlin/dev/patrickgold/florisboard/ime/keyboard3/ImeEditor.kt) · [v0.5.2 AbstractEditorInstance.kt](https://github.com/florisboard/florisboard/blob/v0.5.2/app/src/main/kotlin/dev/patrickgold/florisboard/ime/editor/AbstractEditorInstance.kt) · issues [#689](https://github.com/florisboard/florisboard/issues/689) [#1650](https://github.com/florisboard/florisboard/issues/1650)
- FUTO Keyboard: [repo](https://github.com/futo-org/android-keyboard) · [LatinIME.kt](https://github.com/futo-org/android-keyboard/blob/7d61ab4132cfcbb7aa713190d934f0125dc38b03/java/src/org/futo/inputmethod/latin/LatinIME.kt) · [UixManager.kt](https://github.com/futo-org/android-keyboard/blob/7d61ab4132cfcbb7aa713190d934f0125dc38b03/java/src/org/futo/inputmethod/latin/uix/UixManager.kt) · [Setup.kt](https://github.com/futo-org/android-keyboard/blob/7d61ab4132cfcbb7aa713190d934f0125dc38b03/java/src/org/futo/inputmethod/latin/uix/settings/Setup.kt) · [buggyeditors/](https://github.com/futo-org/android-keyboard/tree/7d61ab4132cfcbb7aa713190d934f0125dc38b03/java/src/org/futo/inputmethod/latin/uix/settings/pages/buggyeditors) · issues [#1519](https://github.com/futo-org/android-keyboard/issues/1519) [#2106](https://github.com/futo-org/android-keyboard/issues/2106)
- AnySoftKeyboard: [repo](https://github.com/AnySoftKeyboard/AnySoftKeyboard) · issue [#4470](https://github.com/AnySoftKeyboard/AnySoftKeyboard/issues/4470)
- Termux: [TerminalView.java](https://github.com/termux/termux-app/blob/master/terminal-view/src/main/java/com/termux/view/TerminalView.java)

Google Play policy (support.google.com):

- [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469) · [User Data](https://support.google.com/googleplay/android-developer/answer/10144311) · [AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491) · [QUERY_ALL_PACKAGES](https://support.google.com/googleplay/android-developer/answer/10158779) · [Permissions and APIs that access sensitive information](https://support.google.com/googleplay/android-developer/answer/9888170) · [Target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)

Testing and measurement:

- [Firebase Test Lab pricing](https://firebase.google.com/docs/test-lab/usage-quotas-pricing) · [android-emulator-runner](https://github.com/ReactiveCircus/android-emulator-runner) · [GitHub Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions) · [Samsung Remote Test Lab FAQ](https://developer.samsung.com/remotetestlab/docs/2/faq) · [Perfetto TraceConfig](https://perfetto.dev/docs/reference/trace-config-proto) · [Perfetto stdlib](https://perfetto.dev/docs/analysis/stdlib-docs) · [Perfetto FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline) · [Winscope adb capture](https://source.android.com/docs/core/graphics/winscope/capture/adb) · [WALT](https://github.com/google/walt)

Secondary:

- [Android Developers Blog: Android 17 is here](https://android-developers.googleblog.com/2026/06/Android-17.html) · [Wikipedia: Android 17](https://en.wikipedia.org/wiki/Android_17) · [epheos: Compose IME guide](https://epheos.com/library/compose-ime)
