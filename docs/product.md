# Kern: product

## Vision

Kern is the keyboard that gets out of the way. You type in any language you speak, mixed in the same sentence, and it understands. You swipe a word and it is the right one. When you want help, rewriting, translating, dictating, it is one deliberate gesture away and shows you exactly what it will change.

Kern is free and open source. It works fully offline. Nothing you type leaves the phone unless you ask for it, and when you do, it goes from your phone straight to the AI provider you chose, with your own key.

## Priorities

In this order. When two of them conflict, the higher one wins.

1. **Incredibly well made.** Fast, correct, never in the way, works in every app.
2. **Modern technology that actually helps.** Every feature has to earn its place by solving something that happens daily.
3. **Beautiful.** Calm, physical, precise. Beauty comes from feel and restraint, not decoration.
4. **Multilingual from day one.** The architecture never assumes a language.
5. **Swipe typing**, as good as the best proprietary keyboards.

## Principles

- **The core is offline and fast.** Typing, correction, prediction and swipe never touch the network. AI features are a layer on top, never in the path of a keystroke.
- **Measure, don't guess.** Every change to the engine or a model is judged by the evaluation harness, per language. If quality can't be measured, it isn't done.
- **The user stays in control.** Corrections are undoable with one backspace. AI output is shown as a preview before it replaces anything. Kern never acts on text the user can't see.
- **Privacy is a feature you can see.** A privacy screen shows every request that left the phone, when, and to whom.
- **Restraint in the UI.** Show what the user expects at that moment and nothing else. One suggestion strip, one contextual action at most.
- **No shortcuts.** No hacks around platform behavior without understanding it, no untyped code, no tests that only exist to exist.

## Quality bars

These are release blockers, measured on a mid-range reference device (to be chosen) and in CI where possible.

| Area | Bar |
|---|---|
| Touch to key feedback | p99 under one frame at the display's refresh rate |
| Keyboard shown (warm) | under 50 ms from `onStartInputView` to first frame |
| Keyboard shown (cold process) | under 250 ms |
| Tap decoding + suggestions | p99 under 8 ms per keystroke, off the main thread's critical path |
| Swipe decoding | p95 under 40 ms after finger lift |
| Memory | resident set under 120 MB with one language pack loaded (target; revisited after measuring) |
| Crash-free sessions | above 99.9% |
| Correction quality | per-language targets set from the first baseline, never regressing |
| App compatibility | full pass on the app matrix (see `docs/compatibility.md`, to be written) |
| Accessibility | full TalkBack support, every key and action labelled |

## Scope of 1.0

**Languages at launch:** English, Portuguese (Brazil and Portugal), Spanish, French, German, Italian. Any number can be enabled at once, with automatic per-word language detection. The engine and data pipeline support any language with a Latin, Cyrillic or Greek script. Other scripts come after 1.0, on purpose, because they need dedicated input methods.

**Typing core**
- Tap typing with a personal touch model that adapts key targets to the user.
- Autocorrection and prediction across all enabled languages at once, without a language switch key.
- Learning from undo: a reverted correction is not repeated.
- Swipe typing in every launch language and layout.
- Space bar cursor control, word-wise delete gesture, one-handed and split layouts.

**Assistance**
- Rewrite, translate, change tone, proofread, with a diff preview, through OpenRouter with the user's own account. An on-device model for these is a post-1.0 goal.
- Voice input: on-device streaming recognition by default, optional cloud transcription and cleanup through OpenRouter.
- Smart clipboard: one-time codes, Pix keys, tracking numbers, addresses, offered at the right moment and expired after a while.
- Semantic emoji search, on-device.

**Look and feel**
- Material You dynamic color as the base, with Kern's own identity on top.
- Tuned haptics, physics-based motion, a GPU-drawn swipe trail.
- Themes with live preview.

## Non-goals

- A chatbot inside the keyboard.
- Reading other apps' content through the accessibility service.
- Any server operated by the Kern project that receives user text.
- Sending anything typed in password fields or incognito mode (`IME_FLAG_NO_PERSONALIZED_LEARNING`) anywhere, or learning from it.
- Supporting old Android versions.

## Competition

| Keyboard | Strength | Gap Kern targets |
|---|---|---|
| Gboard | Best-in-class core, swipe, voice | Closed, sends data to Google, multilingual mixing is limited |
| SwiftKey | Multilingual, prediction | Closed, Microsoft account and cloud features |
| Samsung Keyboard | Preinstalled on Samsung | Closed, weaker correction |
| FUTO Keyboard | Offline, neural swipe, Whisper voice | Source-available, not open source (FUTO Source First License) |
| HeliBoard | Open source (GPL), AOSP-based | Swipe needs a proprietary Google library the user installs |
| FlorisBoard | Open source (Apache), modern codebase | Swipe and prediction still limited |

Kern's position: the only keyboard that is truly open source **and** matches proprietary keyboards on the core, swipe and voice, with AI help that stays under the user's control.
