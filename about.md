# FlowBubble — the whole project, end to end

What exists today, what does not, what is worth improving in what exists, and what could be built
next. Everything here is stated against the code at `v1.0.2` (commit `75feebd`), not against a plan.

## 1. The build as it ships

| | |
| --- | --- |
| Version | 1.0.2 (`versionCode` 3), `app/build.gradle.kts:24` |
| Languages / UI | Kotlin, Jetpack Compose for the app screens, hand-built `WindowManager` overlay views for the bubble and panels |
| SDK floor | `minSdk` 26, `compileSdk`/`targetSdk` 35 |
| Source | 65 Kotlin files, 15,321 lines in `app/src/main`, 2,744 lines in 23 files in `app/src/test` |
| Command surface | 37 `Command` variants in `voice/CommandParser.kt:21` |
| Manifest permissions | 12 declared: `RECORD_AUDIO`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `POST_NOTIFICATIONS`, `READ_CONTACTS`, `MODIFY_AUDIO_SETTINGS`, `RECEIVE_BOOT_COMPLETED`, `INTERNET`, `VIBRATE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. No camera, no storage, no location, no telephony, no SMS |
| Service-level permissions | 3, all Android-enforced and user-granted outside the manifest: `BIND_ACCESSIBILITY_SERVICE`, `BIND_NOTIFICATION_LISTENER_SERVICE`, `BIND_QUICK_SETTINGS_TILE` |
| Third-party libraries | 11 plus the Compose BOM — Compose UI/foundation/material3, activity, core-ktx, lifecycle, coroutines, `androidx.security:security-crypto` for the key store, ML Kit text-recognition and ML Kit GenAI speech-recognition. JUnit is the only test dependency; no DI, no database, no networking or image library |
| Release artifact | `FlowBubble-1.0.2-release.apk`, 53,004,004 bytes, V2-signed with the FlowBubble key; a 28,226,954-byte release AAB for Play |
| Distribution | GitHub Releases only. Not a Play listing — Play restricts general-purpose automation through the Accessibility API |

## 2. What is actually there

### 2.1 The pipeline, in order

```
bubble held / wake phrase
  -> SpeechController (one recognizer turn, live partials into the transcript pill)
     -> SystemSpeechEngine (platform recognizer) or LocalSpeechEngine (ML Kit on-device)
  -> TextProcessor (filler + repeat removal, punctuation, casing, Devanagari normalisation)
  -> CommandParser (regex grammar -> 37 typed Commands)
  -> CapabilityRouter  (the single place that decides: run / dictate / edit / ask-for-permission)
  -> CommandExecutor | AgentRunner | FollowUp | RoutineStore
  -> FlowAccessibilityService (read node, insert text, gesture, tap) or an Intent / system service
  -> verify the effect actually happened
  -> HistoryStore entry the user can read ("what it did, in its words, plus status")
```

`agent/CapabilityRouter.kt` is deliberate: there is exactly one decision point about which route a
transcript takes, so a claim about routing can be read off one file and unit-tested in isolation.

### 2.2 Understanding what the user said

- `voice/CommandParser.kt` — the whole spoken grammar: apps, search (web, in-app, current screen),
  timers and alarms, taps by label and ordinal, scroll and scroll-until, back/home/recents/cancel,
  dictation, inline edits, notifications, DND with duration, media, flashlight, volume, battery,
  email, calendar, chat opening, agent chains, routines, follow-ups.
- `voice/PhraseResolver.kt` — aliases, phonetic resculpting and "did you mean" for app names, so
  "instagam" and "you tube" still land.
- `voice/Devanagari.kt` + `voice/HindiCommands.kt` — Hindi and Hinglish enter the same grammar
  ("यूट्यूब खोलो", "youtube mein lofi khojo"), including spoken Devanagari numerals.
- `voice/ActionVerbStripTest.kt` covers the verb-stripping rules; the 1.0.1 release exists because
  "message hi" used to type `messagehi`.

### 2.3 Executing it and proving it worked

- `commands/CommandExecutor.kt` — 2,712 lines. Every command's effect and its verification.
- `access/FlowAccessibilityService.kt` — focused-field reads, text insertion at the caret,
  selection handling, label and ordinal taps, gestures (long press, swipe), scroll, window-change
  pulse used to wait for screens.
- `agent/LocalPlanner.kt` — deterministic 2–8 step plans (`MAX_STEPS = 8`, line 33) for the common
  chains; `agent/LlmPlanner.kt` optionally proposes steps for unfamiliar screens.
- `agent/AgentRunner.kt` — runs a plan step by step, observes between steps, stops on the first step
  it cannot confirm, and always offers Stop.
- `core/HistoryStore.kt` — the last 100 summaries (`MAX = 100`, line 18), on-device only.
- `agent/SafetyPolicy.kt` — the gates: confirmation required for sends, risky taps and destructive
  actions; no silent approval on timeout.
- `commands/ContactResolver.kt` — names to E.164 numbers, and the gate when contacts are missing.

Nothing reports success on "I fired an intent". The check is "did the app come to the front", "is my
text in that field", "did the screen change".

### 2.4 Screen intelligence

- `capture/ScreenCaptureService.kt` — MediaProjection, consent per session, frames in memory only.
- `vision/ScreenFusion.kt` — accessibility nodes fused with ML Kit OCR, so controls an app hides
  from accessibility are still found.
- `vision/ScreenModels.kt` — one model of the screen shared by read, ask, translate and tap paths.
- `vision/SmartActions.kt` — "what can I do here": phones, emails, links, addresses → buttons.
  Nothing runs until a tap, and Call only opens the prefilled dialer.

### 2.5 Voice in, voice out, hands free

- `voice/SystemSpeechEngine.kt` and `voice/LocalSpeechEngine.kt` behind one `TranscriptionEngine`
  seam, chosen per session (`EngineChoice`, default `AUTO`): the platform recognizer, or ML Kit
  GenAI on-device speech recognition. The on-device path is already wired, which is what makes an
  offline story possible later.
- `voice/HandsFreeTest.kt` and the loop in `service/OverlayService.kt` — "Hello Bubble" opens one
  turn, outcomes are spoken, confirmations answered "yes"/"no"/"haan"/"nahi". Silence and timeouts
  are never approval. Listening stops when the screen is off.
- `voice/Speaker.kt` — TTS for outcomes and prompts.
- Off by default, with its own switch in Settings and in the bubble menu.

### 2.6 Surface and controls

- `overlay/BubbleView.kt` — hold-to-talk, drag, tap-for-menu, state animation.
- `overlay/OverlayPanel.kt` — transcript pill, result cards, confirmation panels, close.
- `ui/MainScreens.kt`, `ui/SettingsHistoryScreens.kt` — Home / Setup / Settings / History, the
  Quick Settings tile, per-capability status and the exact screen that opens a missing switch.
- `core/DndRevert.kt` + `core/DndRevertReceiver.kt` — a timed DND switch reverts itself even across
  an app restart or a device reboot.
- `core/RoutineStore.kt` — up to 20 spoken routines, stored on-device, replayed through the same
  confirmation gates.

### 2.7 Models and providers

- `voice/AiProviders.kt` — 247 lines of pure logic: the provider registry (Groq, OpenRouter, Gemini,
  Mistral, DeepSeek, Together, xAI, Cerebras, OpenAI, plus Ollama and LM Studio for a model on your
  own machine), base-URL normalisation (scheme inferred, `/chat/completions` appended once, never
  twice) and the rules that make a profile valid. A hosted endpoint needs a key and plain `http://`
  to it is refused; an address on your own machine or LAN needs no key at all.
  `resolve(settings, role)` is the single answer to "which model handles this job", and it never
  substitutes a different provider for a role whose assigned model has stopped working — a broken
  assignment turns that job off instead of sending your text to another company.
- `core/AiStore.kt` — profiles in ordinary preferences, keys in an `EncryptedSharedPreferences` file
  sealed by the Android Keystore, and a visible warning instead of a silent downgrade when a device
  cannot open its keystore. A 1.0.1-era single endpoint+key configuration migrates into one default
  profile exactly once, and the legacy keys are then removed.
- `ui/AiModelsCard.kt` — the whole AI surface in one card: add, edit, delete and rank profiles, a
  **Test** round trip per profile that reports the latency or the provider's own error with the key
  masked out, a model picker for each job, and the two AI switches.
- Six jobs are assignable: dictation cleanup, inline edit, summarise, answer-about-screen, translate
  and free-form plan. `voice/LlmClient.kt` takes the role at every call site, so the model that
  answers is decided in one place and named in every consent panel.

### 2.8 Verification tooling (not shipped in the APK)

- `scripts/benchmark.mjs` — 67 frozen spoken tasks against a real device, judged by the product's own
  history entries and foreground state. It refuses to score anything until it has proved the
  accessibility connection is live.
- `scripts/release-check.mjs` — 6 checks that a release build works, because a release build has no
  debug receiver and the benchmark cannot drive it.
- `scripts/provider-check.mjs` — stands up a mock OpenAI-compatible server on the workstation, drives
  the Settings screen through the real UI, and reads back what the server actually received: the path,
  the absence of an Authorization header for a local model, the presence of a bearer token for one
  that has a key, the user agent, the model per job, and the masked 401. It also greps the app's own
  storage for the key to prove it is not sitting in plain text.
- `.github/workflows/ci.yml` and `.github/workflows/release.yml` — CI on PR and `main`; a `v*` tag
  builds and refuses to publish unless `apksigner` verifies what it built and the tag matches
  `versionName`.

## 3. What is not there

### 3.1 Product gaps

1. **It cannot ask.** The `Command` sealed interface has no clarification outcome. An ambiguous
   phrase falls through to dictation instead of asking "to whom?". This is rule 7 of the PRD and it is
   still unimplemented; two regression tests now pin the safe half of it.
2. **No long missions.** The planner runs 2–8 steps in one live session. There is no durable plan that
   survives process death, waits for something to happen, or resumes later.
3. **No continuous screen session.** Screen reading is on demand. There is no `ScreenSessionManager`
   that holds a frame stream, diffs it, and only then runs OCR on the changed region.
4. **No cross-app ordinals.** "Tap the third result" works on the current screen. "Open the third
   chat" has no resolver that knows what a chat list item is in an arbitrary app.
5. **No IME and no assistant role.** It cannot be set as the default assistant, so it does not answer
   "Hey Google", it does not appear in the app's own text field suggestions, and other apps cannot
   hand it work through `App Actions` or shortcuts.
6. **Contacts are resolved or refused, not disambiguated.** Two numbers for one name is a stop, not a
   "which one?" panel.
7. **One language pair.** Hindi/Hinglish share the English grammar; there is no Tamil, Telugu, Bengali,
   Marathi or Arabic surface, and no tested code-switching inside one sentence.
8. **No sync, no export, no history search.** History is 100 rolling entries on one device, unreadable
   by anything else. There is no export and no way to search what was done last week.
9. **No per-app behaviour.** Nothing remembers "in WhatsApp do this, in Gmail do that", and there are
   no triggers ("when I get home and a message arrives…").

### 3.2 Verification gaps — the important one

1. **Real speech has never been tested.** Every device result in this repo comes from transcripts
   injected through the debug receiver. The microphone-to-recognizer hop is unexercised, because the
   test image has no recognizer language pack (`SodaSpeechRecognizer … error 13`). Hands-free,
   barge-in and the wake phrase are therefore *verified as code paths, not as audio in, action out*.
2. **One device, one image.** API 35, x86_64, `google_apis_playstore`. No physical phone, no API 26 /
   29 / 31 / 33 matrix, no low-RAM device, no OEM skin (Xiaomi/Samsung kill background services
   differently), no tablet or fold.
3. **4 of 67 tasks are permanently blocked on that image** (its YouTube opens on an "Update your app"
   dialog with no search field) and 2 are skipped (no flash unit). So the real denominator is 61 of 65.
4. **No instrumentation tests.** `app/src/androidTest` does not exist. All 262 tests are JVM tests on
   pure logic; every line that touches `AccessibilityNodeInfo`, a window or a system service is proven
   only end-to-end by the benchmark.
5. **The release channel was checked, not driven.** 6/6 checks pass (installed version, no debug
   receiver, accessibility bound, bubble on from Setup, overlay window drawn over the launcher). It
   cannot execute a spoken command on a release build without a real microphone working.

### 3.3 Engineering gaps

1. **R8 is off** — `isMinifyEnabled = false` at `app/build.gradle.kts:41`. That, plus a bundled OCR
   model, is why the APK is 52 MB for a 14k-line app.
2. **Key storage has an unencrypted fallback** — `core/AiStore.kt:20`. Keys normally live in an
   `EncryptedSharedPreferences` file sealed by the Android Keystore, but if a device cannot open its
   keystore the app writes them to ordinary preferences and only *says so* on the Settings screen.
   The warning is honest; the fallback is still plaintext.
3. **Two god-objects.** `CommandExecutor.kt` (2,712 lines) and `OverlayService.kt` (1,389) hold command
   dispatch, verification, UI state and service lifecycle at once. Neither is unit-testable as it is.
4. **The parser's word lists are hand-tuned.** `bodyOpeners` and `vagueRecipients` are enumerated
   English sets. They will mis-route the next phrasing that is not in them, and there is no metric
   telling us so.
5. **The provider registry is a table, not a probe.** `voice/AiProviders.kt` suggests one model id per
   provider. When a provider renames a model the app still offers the old id and only the **Test**
   button tells you; nothing asks the endpoint for its real `/models` list the way every other client
   does.
6. **The benchmark report does not identify the build under test.** `latest.md` records device, time and
   score, not the APK name, version or commit — so a report cannot be tied to the artifact it scored.
7. **No failure taxonomy.** A device-blocked task and a product regression print the same `FAIL`, which
   is how the YouTube wall was able to hide a real accessibility-readiness bug for a whole run.
8. **No crash reporting, no analytics, deliberately** — and no local crash log either, so a user
   reporting "it stopped working" leaves nothing to read.
9. **Signing is workstation-only.** The release key is not in CI secrets, so tag pushes build without
   publishing; releases are made by hand from the machine that holds the keystore.

## 4. What can be improved in what already exists

Ordered by value per hour. Sizes: S ≈ under a day, M ≈ a few days, L ≈ a week or more.

1. **Offer the endpoint's real model list** (M) — when a profile is edited, `GET /models` on the same
   base URL and populate the model picker from it, keeping the typed value as the answer for servers
   that do not implement the endpoint. This removes the only place where a provider rename breaks the
   app.
2. **Refuse instead of downgrading key storage** (S) — if the keystore cannot be opened, stop storing
   keys and tell the user to use a local model or fix the device, rather than writing plaintext to
   ordinary preferences behind a warning line.
3. **Turn on R8 and resource shrinking** (M) — measure the APK before and after, keep ML Kit's rules,
   and re-run the full benchmark after, because minification can break reflection-driven code.
4. **Add an `ask` outcome to the parser** (M) — a `Command.Clarify(question, candidates)` variant, a
   panel that offers the candidates, and a trace entry saying it asked. This closes the one PRD rule
   still open and makes every ambiguous phrasing recoverable instead of wrong.
5. **Split `CommandExecutor` by capability** (L) — app/text/screen/system/comms handlers with the
   verification helpers extracted, so each is testable against a fake bridge. Same for
   `OverlayService` (panel state out of the service).
6. **Add instrumentation tests** (L) — `app/src/androidTest` for the accessibility bridge and overlay:
   assert text really lands in a field, a labelled tap hits the node, a gesture changes the screen.
   This is the layer the JVM suite cannot reach and the benchmark only samples.
7. **Make the benchmark identify its build and classify failures** (M) — record APK name, `versionName`,
   commit and device fingerprint in `latest.md`/`latest.json`, and add verdicts `PASS / FAIL /
   BLOCKED(device) / SKIP(no hardware)` so a score is comparable across images and a device wall can
   never mask a regression.
8. **Give the harness an opt-in task set per image** (S) — a `device:` tag on the YouTube and flashlight
   tasks so a run on a different device does not repeat "these 6 will fail everywhere".
9. **Harden the LLM path** (M) — timeout, one bounded retry, a size cap already at 6,000 chars but no
   token budget, and an explicit "the model said nothing usable" outcome instead of a generic failure.
10. **Surface what a failed command should do next** (M) — failures currently describe themselves well
    in the trace and offer nothing actionable except opening the missing switch. Add "try by label",
    "scroll and retry", "grant X" as one-tap follow-ups.
11. **Local crash log** (S) — a rotating, redacted last-N-lines file in app storage with a "share
    diagnostics" action that strips screen text and shows what it will send first.
12. **Per-app coverage report** (M) — a hidden screen that dumps, for the foreground app, how many
    nodes expose text, how many expose content descriptions, and what OCR added. It turns "this app
    doesn't work" into a measurable statement.

## 5. Advanced features that could be built next

Grouped by the mechanism that makes them possible, with what each would actually add.

### 5.1 Becoming part of the system rather than an app on top of it

| Feature | Mechanism | What it buys | Size / risk |
| --- | --- | --- | --- |
| Default assistant, answers "Hey Google" | `VoiceInteractionService` + `Settings.Secure.ASSISTENT_FOR_WORK` + `SetAssistantStrategy` | No bubble to hold, no wake phrase to train; works from the lock screen the way Google's assistant does | L · high risk: needs an app the user *chooses* as assistant, which forces a role-request flow and heavy policy review |
| True always-on hotword on low power | `<activity-intent>` Device Voice Interaction + `SttService`, model downloaded per locale | Wake listening without holding the microphone open, so hands-free stops costing battery | L · only works for enrolled short phrases, not free commands |
| Other apps hand it work | `App Actions` / `shortcuts.xml`, `SearchIntents`, `Wear` tiles, `Car` `AppFocus` | "Order another coffee" resolves inside the coffee app instead of automating its UI | M · needs third-party apps to declare intents |
| Text-field suggestions from inside the keyboard | `InputMethodService` (a real IME) | Edit-as-you-type with no overlay, no hold, in any field, including other people's apps | L · a second distribution problem: IME review is stricter |
| Reply to a notification by voice | `NotificationListenerService` `RemoteInput` | "Reply to that: ten minutes" without opening anything | S/M · must keep the never-send gate |
| Form filling | `AutofillService` | Dictate an address into any signup | M · **sensitive**: touches credentials, so it needs its own consent and a hard rule against password fields |
| Screen context for other apps | `ContentCaptureService` | Cross-session recall of what appeared on screen | L · the most invasive permission in Android; only with explicit per-app opt-in and on-device storage |

### 5.2 Understanding and memory

| Feature | What it buys | Notes |
| --- | --- | --- |
| **Mission Engine** | A plan that survives process death and app restarts, checkpoints after each verified step, waits for a condition ("tell me when the download finishes"), and resumes without starting over | `WorkManager` + a serialized plan in app storage. The verification model already fits: a step is done when the system confirms it |
| **Live `ScreenSessionManager`** | Continuous observation with change detection; OCR only the changed region; a "call me when the price drops" that is not polling the network | Frames must stay in memory and the consent model stays per session |
| **`OrdinalTargetResolver`** | "Open the third chat", "the second invoice", "the message from yesterday" | Needs a per-category item model rather than the current label-and-index tap |
| **On-device LLM** | Parse, plan and summarise with no network at all — the privacy story becomes provable, and offline works | ML Kit GenAI / Gemini Nano availability is device-dependent; needs a tested fallback ladder and honest "this device can't" |
| **On-device embedding index of history** | "What did I do yesterday", "find the screen where I saw the warranty page" | Directly conflicts with today's no-persistence stance, so it must be opt-in with a one-tap wipe |
| **Disambiguation panels** | Two numbers for one name becomes a choice, not a stop; also for two apps that can do a thing | Small, and it removes a large share of dead ends |
| **Real multilingual + code-switching** | Same grammar for more Indic languages, and "traffic ka status bata" understood as one sentence | The Devanagari layer is already factored out; the hard part is recognizer language coverage |

### 5.3 Voice quality

- Streaming ASR with word-level timestamps, so an edit like "change *tomorrow* to Friday" targets the
  word rather than the whole field.
- Barge-in during TTS with proper audio-focus and echo handling, so a spoken "no" interrupts a prompt.
- Speaker binding: enrol one voice, refuse commands from anybody else — hands-free on a shared desk is
  unsafe today.
- Confidence-aware routing: below a threshold, it asks or confirms instead of executing the best guess.
- Proody-aware dictation: punctuation from intonation rather than spoken "full stop".

### 5.4 Safety features that unlock the ambitious ones

- Biometric or PIN gate before a mission may send, tap or spend.
- Per-mission budget: "you may spend up to ₹200 and tap up to 6 things", enforced in `SafetyPolicy`.
- An audit view of every action a mission took, replayable, so autonomy is reviewable after the fact.
- Kill-switch propagation: today the bubble off-switch stops overlay, mic and capture; a mission
  engine must also stop scheduled work, and that has to be tested, not assumed.

## 6. How the claims in this file were checked

- **240 JVM unit tests, 0 failures**, on a clean build of `6a63c0c`.
- **67-task device benchmark: 61 passed, 2 skipped, 4 failed.** The 4 failures are all one emulator
  defect and each is recorded by the product as a failure rather than as a success. `verify/benchmark/latest.md`.
- **Release channel: 6/6 checks** on the signed APK via `node scripts/release-check.mjs`.
- **Artifact provenance:** the published asset round-trips byte-identical to `b3e8af638e…78fe80f`, and a
  clean rebuild matches it on 523 of 524 zip entries; the only difference is
  `META-INF/version-control-info.textproto`, i.e. which commit built it.
- **CI green** on `main` and on the `v1.0.1` tag build.
- **Not proven anywhere in this file:** anything that depends on a real microphone. See §3.2.

## 7. Non-negotiables for all of the above

Anything merged into FlowBubble must keep these, which is the product's whole claim to be trusted:

- Never bypass a lock screen, authentication or a CAPTCHA.
- Never read or write passwords or payment credentials; skip OCR while a password field is focused and
  block credential labels.
- Messages, drafts, purchases and destructive or risky taps always need confirmation, and a
  confirmation that times out is never a silent yes.
- On-screen text and notification content are untrusted input, never instructions.
- No raw audio, screen text, notification content or credentials in logs or history.
- The bubble off-switch is a global kill switch for overlay, microphone and screen capture.
- Never claim success without verification; report unsupported and unconfirmed actions honestly.
- The debug transcript receiver stays in `src/debug` only; MediaProjection stays consent-per-session;
  frames stay in memory.
- Signing material stays out of git.
