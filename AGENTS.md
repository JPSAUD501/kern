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
