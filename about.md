# Your Bubble — the whole project, end to end

What exists today, what does not, what is worth improving in what exists, and what could be built
next. Everything here is stated against the code at `1.2.0`, not against a plan. Each claim names a
file so it can be checked rather than trusted.

[feature.md](feature.md) is the per-feature proof ledger — it tags every capability as seen on a device,
covered by tests but never watched on a device, or built and not wired. This file is the strategy layer
above it; where the two disagree about what a feature costs, feature.md is the one that checked.

## 1. The build as it ships

| | |
| --- | --- |
| Name | **Your Bubble**. It shipped as FlowBubble through 1.0.1, the last tag on this repository; the rename landed after it and 1.2.0 is the first release under the new name. `applicationId` stays `com.flowbubble.app` on purpose, because changing it would make Android treat the app as a different product and drop every permission a user already granted. See "Name and identity" in `README.md` |
| Version | 1.2.0 (`versionCode` 5), `app/build.gradle.kts:27` |
| Languages / UI | Kotlin. Jetpack Compose for the app screens, hand-built `WindowManager` overlay views for the companion, transcript pill and panels |
| SDK floor | `minSdk` 26, `compileSdk`/`targetSdk` 35 |
| Source | 83 Kotlin files, 26,618 lines in `app/src/main`; 38 files, 6,643 lines in `app/src/test`; 1 file in `app/src/debug` |
| Unit tests | 529 tests in 38 suites, 0 failures, 0 skipped — measured from the JUnit XML after the 1.2.0 build. There is no `app/src/androidTest` and no Robolectric, so nothing is verified inside an Android runtime by the suite |
| Command surface | 40 `Command` variants in `voice/CommandParser.kt:22`, each with a real handler in `commands/CommandExecutor.kt` |
| Manifest permissions | 13 declared: `RECORD_AUDIO`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `POST_NOTIFICATIONS`, `READ_CONTACTS`, `MODIFY_AUDIO_SETTINGS`, `RECEIVE_BOOT_COMPLETED`, `INTERNET`, `VIBRATE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and `com.android.alarm.permission.SET_ALARM` for automation alarms. No camera, no storage, no location, no telephony, no SMS |
| Service-level permissions | 3, all Android-enforced and user-granted outside the manifest: `BIND_ACCESSIBILITY_SERVICE`, `BIND_NOTIFICATION_LISTENER_SERVICE`, `BIND_QUICK_SETTINGS_TILE` |
| Third-party libraries | 12 plus the Compose BOM — Compose UI/foundation/material3, activity, core-ktx, lifecycle, coroutines, `androidx.security:security-crypto`, ML Kit text-recognition, ML Kit GenAI speech-recognition, and `com.google.ai.edge.litertlm:litertlm-android:0.16.1` for on-device inference. JUnit is the only test dependency; no DI, no database, no networking or image library |
| Release artifact | `YourBubble-1.2.0-release.apk`, V2-signed with the Your Bubble key. It is 102,868,227 bytes (~103 MB), up from 52,233,764 bytes (`FlowBubble-1.0.1-release.apk`) in 1.0.1, and the difference is native code: 88 MB of it is `.so` files. `libmlkit_google_ocr_pipeline.so` ships for all four ABIs; `liblitertlm_jni.so` only for arm64-v8a and x86_64 (21.5 MB and 25.6 MB), so a 32-bit phone carries the OCR runtime but not on-device inference. No model file is inside the APK |
| Distribution | GitHub Releases only. Not a Play listing — Play restricts general-purpose automation through the Accessibility API |

## 2. What is actually there

### 2.1 The pipeline, in order

```
bubble held / wake phrase
  -> SpeechController (one recognizer turn, live partials into the transcript pill)
     -> SystemSpeechEngine (platform recognizer) or LocalSpeechEngine (ML Kit on-device)
  -> TextProcessor (filler + repeat removal, punctuation, casing, Devanagari normalisation)
  -> CommandParser (regex grammar -> 40 typed Commands)
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
  timers and alarms, taps by label, ordinal and pointer, scroll and scroll-until, back/home/recents/
  cancel, dictation, inline edits, notifications, DND with duration, media, flashlight, volume,
  battery, email, calendar, chat opening, agent chains, watch-the-screen instructions, routines,
  follow-ups.
- `voice/PhraseResolver.kt` — aliases, phonetic resculpting and "did you mean" for app names, so
  "instagam" and "you tube" still land.
- `voice/Devanagari.kt` + `voice/HindiCommands.kt` — Hindi and Hinglish enter the same grammar
  ("यूट्यूब खोलो", "youtube mein lofi khojo"), including spoken Devanagari numerals.
- `voice/ActionVerbStripTest.kt` covers the verb-stripping rules; the 1.0.1 release exists because
  "message hi" used to type `messagehi`.
- `voice/HandsFree.kt:1` — the wake phrase matches in Latin script but the command is sliced from the
  **original** words, so a Hindi wake ("हैलो बबल") leaves Devanagari verbs intact for the parser
  instead of receiving a transliterated fragment it cannot act on.

### 2.3 Executing it and proving it worked

- `commands/CommandExecutor.kt` — 2,896 lines. Every command's effect and its verification.
- `access/FlowAccessibilityService.kt` — focused-field reads, text insertion at the caret,
  selection handling, label and ordinal taps, gestures (long press, swipe), scroll, window-change
  pulse used to wait for screens.
- `agent/LocalPlanner.kt` — deterministic 2–8 step plans for the common chains;
  `agent/LlmPlanner.kt` optionally proposes steps for unfamiliar screens.
- `agent/AgentRunner.kt` — runs a plan step by step, observes between steps, stops on the first step
  it cannot confirm, and always offers Stop.
- `core/HistoryStore.kt` — the last 100 summaries (`MAX = 100`, line 18), on-device only.
- `agent/SafetyPolicy.kt` — the gates: confirmation required for sends, risky taps and destructive
  actions; no silent approval on timeout.
- `commands/ContactResolver.kt` — names to E.164 numbers, and the gate when contacts are missing.

Nothing reports success on "I fired an intent". The check is "did the app come to the front", "is my
text in that field", "did the screen change".

### 2.4 Screen intelligence, including continuous watching

- `capture/ScreenCaptureService.kt` — MediaProjection, consent per session, frames in memory only.
- `capture/ScreenSession.kt` (497 lines) — the frame stream is now **watched continuously while a
  task runs**, not sampled on demand. Every frame is fingerprinted on a coarse grid and the expensive
  work — recognising text in pixels — only runs when the fingerprint moved, and then only over the
  band that moved, so a busy animation cannot drive OCR into a loop. Without screen access it still
  works on the accessibility tree alone at 420 ms ticks; that is the "good analysis without AI" path
  and it needs no key. Nothing outlives a stop: bitmaps are recycled, the worker is cancelled, and if
  the session asked for access then stopping releases the projection.
- `vision/LiveScreenModel.kt` (731 lines) — the ordered live target list "the blue one" and "that
  chat at the top" resolve against. Anything not provably one target is asked back rather than tapped.
- `vision/ScreenFusion.kt` — accessibility nodes fused with ML Kit OCR, so controls an app hides
  from accessibility are still found.
- `vision/ScreenModels.kt` — one model of the screen shared by read, ask, translate and tap paths.
- `vision/SmartActions.kt` — "what can I do here": phones, emails, links, addresses → buttons.
  Nothing runs until a tap, and Call only opens the prefilled dialer.

### 2.5 Voice in, voice out, hands free

- `voice/SystemSpeechEngine.kt` and `voice/LocalSpeechEngine.kt` behind one `TranscriptionEngine`
  seam, chosen per session (`EngineChoice`, default `AUTO`): the platform recognizer, or ML Kit
  GenAI on-device speech recognition.
- `voice/HandsFreeTest.kt` and the loop in `service/OverlayService.kt` — "Hello Bubble" opens one
  turn, outcomes are spoken, confirmations answered "yes"/"no"/"haan"/"nahi". Silence and timeouts
  are never approval. Listening stops when the screen is off.
- `voice/Speaker.kt` — TTS for outcomes and prompts, and `BubbleView.setSpeaking` so the character
  visibly talks rather than the app going quiet mid-sentence.
- Off by default, with its own switch in Settings and in the bubble menu.

### 2.6 The companion, and the design system behind it

- `ui/theme/Theme.kt` (101 lines) — `object Ink`, the single palette the whole app draws from:
  Void/Obsidian/Onyx/Smoke/Veil for the near-black plum ground, Lavender and LavenderDeep for the
  idle and off states, Burgundy/BurgundyDeep/Crimson for on-states and danger, Jade for "granted",
  Amber for "needs you". Every screen, chip, toggle and card reads from here.
- `overlay/companion/CompanionState.kt` (707 lines) — a pure model of
  **state × mood × stage × amplitude → visual numbers**, with every channel clamped. Five states
  (`Idle`, `Listening`, `Thinking`, `Happy`, `Worried`), five moods (`Neutral`, `Happy`, `Curious`,
  `Sleepy`, `Surprised`), four growth stages that change eye scale, body scale, spark count, halo and
  when feet appear. No Android types, so it is unit-testable on a JVM.
- `overlay/companion/CompanionRenderer.kt` (508 lines) — the Canvas drawing of that model. It
  allocates nothing per frame, which is the only reason an always-on animated overlay is affordable.
- `overlay/BubbleView.kt` (349 lines) — the window, the gestures and the six callbacks
  (`setState`, `setAmplitude`, `setMood`, `setStage`, `setSpeaking`, drag), and the `State → pose`
  map at line 167. The launcher icon is the same character (`eb8ebc3`), so the thing floating over
  your apps is the thing on your home screen.
- `service/OverlayService.kt` — `BUBBLE_SIZE_DP = 96f` owns the overlay window size and the drag
  clamp reads the same constant, because a mismatch lets the character be sized one thing and clamped
  as another. `resetBubble()` sets the one mood with a real signal: curious while the screen is being
  watched, neutral the moment watching stops. There is deliberately no mood timer.
- `ui/Components.kt`, `ui/MainScreens.kt`, `ui/SettingsHistoryScreens.kt`, `ui/AiModelsCard.kt` —
  Home / Setup / Settings / History rebuilt on `Ink`: gradient chips, segmented permission meter,
  `FlowToggle` (lavender off, burgundy→crimson on), and the transcript pill restyled to the same
  palette (`#F0120C1C` ground, lavender hairline).
- `overlay/OverlayPanel.kt` (950 lines) — the card that appears over another app: body, actions,
  progress, consent gates and the transcript pill, all on `Ink`. Two things learned the hard way live
  in it. Its `TextView.letterSpacing` values are in **em**, not dp — the Compose `tracking` beside them
  is, and 1.6 written into an em field renders as the garbled, overlapping letterforms that made the
  card look broken; every tracked value in the file is now 0.02–0.16. And a card that asks nothing of
  the user closes itself (`autoDismissMs`, e.g. the copy-only "Nowhere to type" card at 12 s), because
  a card is its own overlay window and its touchable region swallows every gesture under it until it
  goes.
- `core/DndRevert.kt` + `core/DndRevertReceiver.kt` — a timed DND switch reverts itself even across
  an app restart or a device reboot.
- `core/RoutineStore.kt` — up to 20 spoken routines, stored on-device, replayed through the same
  confirmation gates.

### 2.7 Models and providers

- `voice/AiProviders.kt` — pure logic: **12 hosted provider presets** (OpenAI, Groq, OpenRouter,
  Google Gemini, Mistral, DeepSeek, Together AI, xAI Grok, Cerebras, Ollama and LM Studio for a model on
  your own machine, and a generic "Custom endpoint") plus `on-device` as a thirteenth picker entry. There
  is no SambaNova and no Hugging Face preset — an earlier draft of this file claimed both, and reading
  `AiProviders.all` disproves it. Base-URL
  normalisation (scheme inferred, `/chat/completions` appended once, never twice) and the rules that
  make a profile valid. A hosted endpoint needs a key and plain `http://` to it is refused; an address
  on your own machine or LAN needs no key at all. `resolve(settings, role)` is the single answer to
  "which model handles this job", and it never substitutes a different provider for a role whose
  assigned model has stopped working — a broken assignment turns that job off instead of sending your
  text to another company.
- `core/LocalModelStore.kt` — the on-device catalog and installer. Six specs: FunctionGemma
  270M (284 MB), Qwen3 0.6B (614 MB), Gemma 4 E2B (2.59 GB), Gemma 4 E4B (3.66 GB), Gemma 3n E2B
  (3.66 GB, **license-gated so it cannot be downloaded** — it is listed and refused with the reason) and
  EmbeddingGemma 2 270M (165 MB, embeddings only, so it is deliberately not assignable to a chat role).
  Sizes are the repositories' real `Content-Length` values, RAM floors follow
  Google's LiteRT-LM performance table, and each spec carries an `honestNote` saying what the model is
  bad at. Nothing downloads without a button that already printed the size. **No model has actually run
  on a device here** — the download path and `voice/LocalLlmEngine.kt` are real code with a real call
  site (`LlmClient.kt:162`), but nobody has watched a token come out of a local model on this machine.
- `voice/LocalLlmEngine.kt` (194 lines) — real inference, not a stub: builds a LiteRT-LM `Engine` from
  the model path, initialises it, opens one `Conversation` per request, and releases the resident
  model when settings change. `LlmClient` routes to it through the same role resolution as a hosted
  profile, so "on this phone" is a first-class answer rather than a special case.
- `core/AiStore.kt` — profiles in ordinary preferences, keys in an `EncryptedSharedPreferences` file
  sealed by the Android Keystore, and a visible warning instead of a silent downgrade when a device
  cannot open its keystore. A 1.0.1-era single endpoint+key configuration migrates into one default
  profile exactly once, and the legacy keys are then removed.
- `ui/AiModelsCard.kt` (928 lines) — the whole AI surface in one card: add, edit, delete and rank
  profiles, a **Test** round trip per profile that reports the latency or the provider's own error with
  the key masked out, a model picker for each job, the local-model installer, and the two AI switches.
- Six jobs are assignable: dictation cleanup, inline edit, summarise, answer-about-screen, translate
  and free-form plan. `voice/LlmClient.kt` takes the role at every call site, so the model that
  answers is decided in one place and named in every consent panel.
- Every request carries `User-Agent: YourBubble/<versionName>`, read from the single place the version
  is declared.

### 2.8 Automations — running without a hand on the bubble

The subsystem that acts when nobody is holding the bubble, so it is the subsystem carrying the most
guards. Full proof ledger in [feature.md](feature.md) §6; the shape of it here.

- `core/AutomationStore.kt` — a rule is a name, a trigger, up to 8 steps, an enable flag, and a small
  last-run record. One serialized record per rule in the `flowbubble_automations` preferences file,
  20 rules maximum. Writes are committed synchronously on purpose: an async `apply()` still in flight
  when the process dies would lose the rule *and* the alarm behind it, which is exactly the state where
  a user believes something is scheduled and it is not.
- `agent/AutomationEngine.kt` — pure logic, no Android: trigger matching, the next-run maths (a time
  already passed today rolls to tomorrow), `unreplayableSteps` and `stepNeedsConfirmation`. The
  confirmation answer is **derived from the steps every single time**, never read back from a stored
  boolean, so a rule hand-edited on disk cannot mark itself safe.
- `service/AutomationReceiver.kt` — the only Android-touching half. It arms the next time trigger with
  `AlarmManager.setWindow` and a five-minute window, deliberately without `SCHEDULE_EXACT_ALARM`, which
  is why a 06:12 rule was observed firing at 06:17. It re-arms on `BOOT_COMPLETED`, and its `sweep`
  asks `decide` for every rule and gets one of three answers: run, ask first, or blocked.
- Feeds for the other three triggers: `FlowAccessibilityService.kt:75` reports window changes into
  `notifyAppOpened`, `FlowNotificationService.kt:63` pokes `notifyNotificationArrived`, and
  `OverlayService.kt:837` hands `notifyPhrase` the recognised transcript.
- Safety, stated as invariants rather than intentions: the bubble off-switch stops an in-flight rule and
  the polling but *re-arms* the schedule rather than cancelling it; a rule needing confirmation raises
  an ask and records "Needs your OK", never a silent yes; and `AutomationReceiver.kt:177` guarantees only
  a rule's own name and kind reach logcat — never a phrase, screen text, or notification body.
- `ui/AutomationsScreen.kt` — list with plain-language summaries, per-rule switch, last-run line, delete
  confirmation, and an editor whose amber and red notices are computed from the draft as you type. The
  kill-switch notice appears on the list itself when the bubble is off, so a schedule that will not fire
  never looks like a schedule that will.

### 2.9 Verification tooling (not shipped in the APK)

- `scripts/benchmark.mjs` — 67 frozen spoken tasks against a real device, judged by the product's own
  history entries and foreground state. It refuses to score anything until it has proved the
  accessibility connection is live.
- `scripts/release-check.mjs` — 6 checks that a release build works, because a release build has no
  debug receiver and the benchmark cannot drive it.
- `scripts/provider-check.mjs` — drives the Settings screen through the real UI and reads back what a
  stand-in server actually received: the path, the absence of an Authorization header for a local
  model, the presence of a bearer token for one that has a key, the user agent, the model per job, and
  the masked 401. It also greps the app's own storage for the key to prove it is not sitting in plain
  text, and it proves the end-to-end loop by speaking a dictation transcript into a real text field and
  checking that the answer comes back in that field whole. Before it touches the UI it asserts the
  endpoint is reachable *from the app's own uid*, and while it runs it watches the card window's
  touchable rectangle in `dumpsys window` — because a card a dump cannot see still blocks every tap
  under it. It derives the expected user agent and install hint from `versionName` in Gradle, so it
  cannot go stale on the next release.
- `scripts/mock-openai.mjs` — that stand-in endpoint, in its own process. It has to be a separate
  process, because `provider-check.mjs` drives the device with `execFileSync` and sleeps on
  `Atomics.wait`, so its event loop never yields and an in-process
  `http.createServer().listen()` never finishes binding.
  Six runs reported "no request arrived" against a port nothing had opened. It writes one JSON line per
  request to `verify/provider/requests.jsonl`, which the blocking parent can read.
- `.github/workflows/ci.yml` and `.github/workflows/release.yml` — CI on PR and `main`; a `v*` tag
  builds and refuses to publish unless `apksigner` verifies what it built and the tag matches
  `versionName`.

## 3. What is not there

### 3.1 Product gaps

1. **It can ask, but only from one place.** `Command.Clarify` exists (`voice/CommandParser.kt:90`), is
   produced when a target is ambiguous (`commands/CommandExecutor.kt:1023`), is handled by a choice
   panel (`:1050`) and is routed as its own capability (`agent/CapabilityRouter.kt:125`). What is still
   missing is breadth: the agent planner and the automation sweep never produce a clarification, so an
   ambiguous *step* inside a multi-task chain still falls through instead of asking.
2. **No long missions.** The planner runs 2–8 steps in one live session. There is no durable plan that
   survives process death, waits for something to happen, or resumes later.
3. **No cross-app ordinals.** "Tap the third result" works on the current screen. "Open the third
   chat" has no resolver that knows what a chat list item is in an arbitrary app; the live target list
   covers what is on screen now, not what is three scrolls away.
4. **No IME and no assistant role.** It cannot be set as the default assistant, so it does not answer
   "Hey Google", it does not appear in the app's own text field suggestions, and other apps cannot
   hand it work through `App Actions` or shortcuts.
5. **Contacts are resolved or refused, not disambiguated.** Two numbers for one name is a stop, not a
   "which one?" panel.
6. **One language pair.** Hindi/Hinglish share the English grammar; there is no Tamil, Telugu, Bengali,
   Marathi or Arabic surface, and no tested code-switching inside one sentence.
7. **No sync, no export, no history search.** History is 100 rolling entries on one device, unreadable
   by anything else. There is no export and no way to search what was done last week.
8. **Triggers exist; per-app behaviour does not.** Automations give four trigger kinds — a time, an app
   opening, a notification matching, a phrase (`agent/AutomationEngine.kt`) — but only the time trigger
   has been watched firing on a device. Nothing remembers "in WhatsApp do this, in Gmail do that":
   `agent/AppPlaybooks.kt` is the catalog that would hold that knowledge and only its `blockedBy()`
   lookup is called, with every entry still `provenOnDevice = false`. There is also no geofence or
   battery/state trigger, and no calendar-aware condition.
9. **The companion reacts to one thing.** It has five moods and four stages in the model, but only one
   mood (`Curious`, while the screen is watched) and the default stage are actually driven by a signal
   today. The rest of the state space is drawn and tested but not yet fed.
10. **No on-device speech-to-text of the user's own voice.** `LocalSpeechEngine` is ML Kit GenAI, which
    still depends on Google's speech stack; a fully offline recognizer is not in the build.

### 3.2 Verification gaps — the important one

1. **Real speech has never been tested.** Every device result in this repo comes from transcripts
   injected through the debug receiver. The microphone-to-recognizer hop is unexercised, because the
   test image has no recognizer language pack (`SodaSpeechRecognizer … error 13`). Hands-free,
   barge-in and the wake phrase are therefore *verified as code paths, not as audio in, action out*.
2. **One device, one image.** API 35, x86_64, `google_apis_playstore`. No physical phone, no API 26 /
   29 / 31 / 33 matrix, no low-RAM device, no OEM skin (Xiaomi/Samsung kill background services
   differently), no tablet or fold. That matters more than it used to: the local model catalog is
   gated on RAM figures this emulator does not represent.
3. **The recorded benchmark run is red, and red for a harness reason.** `verify/benchmark/latest.md`
   (2026-11-03 05:46) reads **0 passed, 0 skipped, 67 failed**, and every single row says "no new trace
   entries". Sixty-seven consecutive failures with an identical cause is not sixty-seven product defects;
   it is the injection or the trace read failing for the whole run. It is re-run against the shipping
   build, and a run like that must never be published as if it were a measurement. When a healthy run
   does land, its own floor is **4 of 67 tasks permanently blocked on this image** (its YouTube opens on
   an "Update your app" dialog with no search field) and **2 skipped** (no flash unit), so the real
   denominator for any score quoted from this device is 61 of 65.
4. **No instrumentation tests.** `app/src/androidTest` does not exist. All tests are JVM tests on pure
   logic; every line that touches `AccessibilityNodeInfo`, a window or a system service is proven only
   end-to-end by the benchmark.
5. **The release channel was checked, not driven.** It cannot execute a spoken command on a release
   build without a real microphone working.
6. **The continuous watcher has never been proven on a real screen sequence.** `ScreenSession` and
   `LiveScreenModel` are unit-tested on synthetic frames and diffs; the pointer grammar ("the blue
   one") resolving against a live app has not been driven end to end.
7. **No local model has been run on this emulator.** The catalog, installer, size gates and RAM verdicts
   are tested; actual LiteRT-LM inference has not been executed on the test image, which is x86_64 with
   no GPU delegate and not the hardware these models are for.

### 3.3 Engineering gaps

1. **R8 is off** — `isMinifyEnabled = false` in `app/build.gradle.kts`. That, plus the bundled native
   runtimes, is why the APK is ~101 MB for a 22k-line app.
2. **Key storage has an unencrypted fallback** — `core/AiStore.kt:20`. Keys normally live in an
   `EncryptedSharedPreferences` file sealed by the Android Keystore, but if a device cannot open its
   keystore the app writes them to ordinary preferences and only *says so* on the Settings screen.
   The warning is honest; the fallback is still plaintext.
3. **Two god-objects.** `CommandExecutor.kt` (2,896 lines) and `OverlayService.kt` (1,411) hold command
   dispatch, verification, UI state and service lifecycle at once. Neither is unit-testable as it is.
4. **The parser's word lists are hand-tuned.** `bodyOpeners` and `vagueRecipients` are enumerated
   English sets. They will mis-route the next phrasing that is not in them, and there is no metric
   telling us so. The 1.1.0 merge found exactly this class of bug by test, not by eye: the new pointer
   grammar was swallowing ordinal taps that every release since 1.0.0 had device-verified.
5. **The provider registry is a table, not a probe.** `voice/AiProviders.kt` suggests one model id per
   provider. When a provider renames a model the app still offers the old id and only the **Test**
   button tells you; nothing asks the endpoint for its real `/models` list the way every other client
   does.
6. **The benchmark report does not identify the build under test.** `latest.md` records device, time and
   score, not the APK name, version or commit — so a report cannot be tied to the artifact it scored.
7. **No failure taxonomy.** A device-blocked task and a product regression print the same `FAIL`, which
   is how the YouTube wall was able to hide a real accessibility-readiness bug for a whole run.
8. **The UI-driving harnesses are fragile against this image.** `uiautomator dump` intermittently
   returns a null root or "could not get idle state", and a failed dump leaves the *previous* screen in
   the file it was writing, so `provider-check.mjs`, `benchmark.mjs` and `release-check.mjs` can act on
   a stale tree; all three now delete the file before dumping and retry. Six failure modes are
   invisible by design, and each one cost a run to find:
   - a needle that does not match a label reads as a product failure rather than a harness bug;
   - the emulator can boot with no route to the workstation (`10.0.2.2` answers "Network is
     unreachable", so every network-backed check fails quietly until wifi is cycled and DHCP
     re-leases);
   - `am start` returns before the activity has drawn, so the first dump after a screen change can
     still show the screen before it — every tap the harness then makes is aimed at nodes that are not
     on the device, and one bad read cascades into twelve failures. `showSettings()` now polls until
     the card it is about to drive is really on screen;
   - **a stand-in server inside the harness was never listening.** `provider-check.mjs` drives the
     device entirely with `execFileSync` and sleeps on `Atomics.wait`, so its event loop never yields
     and `http.createServer().listen()` never completes its bind: every "no request arrived" in six
     runs was the app knocking on a port nothing had opened, reported as a product result. The endpoint
     is now a second process (`scripts/mock-openai.mjs`) that writes each request to a JSON-lines file
     the blocking parent can read, and the run asserts reachability from the app's own uid before it
     touches the UI;
   - **a scroll that scrolls nothing.** One run printed five identical screen dumps across twenty-four
     scroll attempts: the column had stopped moving, but nothing in the script could tell a frozen
     picture from a screen that genuinely lacks the button, so five checks failed against it. Each
     scroll now compares the tree before and after, dismisses a keyboard that is eating the gesture,
     wakes the display, and prints that the device froze instead of letting the findings imply the app
     is missing controls.
   - **the app's own card, which no dump can see.** `uiautomator dump` walks the *active* window only,
     so a companion card — its own `TYPE_APPLICATION_OVERLAY` window — never appears in any tree the
     harness reads, while its touchable rectangle does: `dumpsys window` showed
     `com.flowbubble.app, touchableRegion=SkRegion((0,1308,1080,2132))`. Every swipe and every tap
     below y≈1308 belonged to that invisible card, so a run that left one up watched its own gestures
     land nowhere and reported the app as missing controls. The frozen column was this, and the earlier
     sentence in this list saying the overlay was *not* the cause was wrong: it was written from a swipe
     that moved the screen while a card happened to be closed. `provider-check.mjs` now reads the card's
     rectangle out of the window list and waits for it to go, and the product side stops the card from
     being permanent — an informational card closes itself.
   Two matching rules were also wrong in ways that looked like product bugs. Substring matching cannot
   tell a per-role menu option ("Dictation mock · mock-dictation") from the stored profile row above it
   ("Dictation mock"), and it picks the row — so the assignment check tapped a card body. And because
   the editor replaces the card's add button in place, one step leaving it open hides the button from
   every later step, which scrolls to the end of Settings hunting for it; `openModelEditor()` now reads
   its own state as it goes and dismisses an open editor. Also: two runs must not share a device, because
   they write the same dump path and one run's delete wipes the other's fresh tree, which shows up as
   "nothing readable" in both.
9. **No crash reporting, no analytics, deliberately** — and no local crash log either, so a user
   reporting "it stopped working" leaves nothing to read.
10. **Signing is workstation-only.** The release key is not in CI secrets, so tag pushes build without
    publishing; releases are made by hand from the machine that holds the keystore.
11. **The AAB keeps its default name.** `app-release.aab`, not `YourBubble-1.1.0-release.aab`: AGP 8.13
    produces the bundle in `PerModuleBundleTask`, which is not an archive task and exposes no
    `archiveFileName`, so there is no clean rename hook. The APK renames properly.
12. **Every screen read happens on the app's main thread, and the app ANRs under load.**
    `OverlayService.kt:117` builds its coroutine scope on `Dispatchers.Main.immediate`, so the
    accessibility tree walks that agent steps depend on — `visibleNodes`, `findFocus`, window roots,
    each a separate trip to the framework — run on the thread that draws the UI and answers input.
    The twelfth provider run hit it: `ANR in Window{…MainActivity}. Reason: Input dispatching timed
    out (Waited 5003ms for KeyEvent)`, and the screen went to "Your Bubble isn't responding" mid-run,
    turning one check red for a reason that had nothing to do with providers. This is not new to the
    build under test — `/data/anr/` on this emulator holds traces from earlier runs — and the stack
    that names the blocking frame could not be read, because this image refuses `adb root`, refuses
    `debuggerd -b`, and keeps `/data/anr` mode 0600 as system. So the mechanism is documented, the
    exact frame is not. Same release, same thread: the wrong-window focus lookup that made dictation
    answer "nowhere to type" while a field *was* focused (`focusedNode()` consulted only
    `rootInActiveWindow`, which is briefly our own overlay, and an overlay cannot hold input focus) is
    fixed — application windows are now searched topmost after the active root comes up empty — but
    the fix was deliberately made lazy, searching other windows only on the failure path, because
    widening it eagerly would have bought a correct answer by making the main thread slower still.
    Until the reads move off `Main`, this class of defect is live, and the fix sits on the same thread
    as the problem.

## 4. What can be improved in what already exists

Ordered by value per hour. Sizes: S ≈ under a day, M ≈ a few days, L ≈ a week or more.

1. **Give the harness needles a single source of truth** (S) — the labels `provider-check.mjs` taps for
   ("Add a model", "Which model answers what", the role labels) are duplicated as literals in two
   languages. Export them from one Kotlin object and have the script read them, so a rename in the app
   breaks the harness loudly instead of failing a product check quietly.
2. **Offer the endpoint's real model list** (M) — when a profile is edited, `GET /models` on the same
   base URL and populate the model picker from it, keeping the typed value as the answer for servers
   that do not implement the endpoint. This removes the only place where a provider rename breaks the
   app.
3. **Refuse instead of downgrading key storage** (S) — if the keystore cannot be opened, stop storing
   keys and tell the user to use a local model or fix the device, rather than writing plaintext to
   ordinary preferences behind a warning line.
4. **Turn on R8 and resource shrinking** (M) — measure the APK before and after, keep ML Kit's and
   LiteRT-LM's rules, and re-run the full benchmark after, because minification can break
   reflection-driven code.
5. **Prove the three non-time automation triggers on device** (S) — `Command.Clarify` and the
   app-opened / notification-matches / phrase triggers are all built and unit-tested
   (`agent/AutomationEngine.kt`, with real feeds at `FlowAccessibilityService.kt:75`,
   `FlowNotificationService.kt:63` and `OverlayService.kt:837`), but only the time trigger has been
   watched firing end to end. Each one needs the same treatment: arm it, fire it, and keep the log line.
6. **Feed the companion more real signals** (S/M) — `State.READY` and its `Happy` pose exist but no
   call site ever reaches them; moods for "just finished", "needs you to confirm" and "idle for a long
   while" are each one line at an existing transition. Stage progression needs a defined, honest rule
   (or it should be removed rather than left decorative).
7. **Split `CommandExecutor` by capability** (L) — app/text/screen/system/comms handlers with the
   verification helpers extracted, so each is testable against a fake bridge. Same for
   `OverlayService` (panel state out of the service).
8. **Add instrumentation tests** (L) — `app/src/androidTest` for the accessibility bridge and overlay:
   assert text really lands in a field, a labelled tap hits the node, a gesture changes the screen.
   This is the layer the JVM suite cannot reach and the benchmark only samples.
9. **Make the benchmark identify its build and classify failures** (M) — record APK name, `versionName`,
   commit and device fingerprint in `latest.md`/`latest.json`, and add verdicts `PASS / FAIL /
   BLOCKED(device) / SKIP(no hardware)` so a score is comparable across images and a device wall can
   never mask a regression.
10. **Give the harness an opt-in task set per image** (S) — a `device:` tag on the YouTube and flashlight
    tasks so a run on a different device does not repeat "these 6 will fail everywhere".
11. **Harden the LLM path** (M) — timeout, one bounded retry, a size cap already at 6,000 chars but no
    token budget, and an explicit "the model said nothing usable" outcome instead of a generic failure.
    For local models, add a measured first-token latency per device class rather than the catalog's
    RAM floor alone.
12. **Surface what a failed command should do next** (M) — failures currently describe themselves well
    in the trace and offer nothing actionable except opening the missing switch. Add "try by label",
    "scroll and retry", "grant X" as one-tap follow-ups.
13. **Local crash log** (S) — a rotating, redacted last-N-lines file in app storage with a "share
    diagnostics" action that strips screen text and shows what it will send first.
14. **Per-app coverage report** (M) — a hidden screen that dumps, for the foreground app, how many
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
| **`OrdinalTargetResolver`** | "Open the third chat", "the second invoice", "the message from yesterday" across a scroll, not just on the current viewport | Needs a per-category item model rather than the current label-and-index tap; `LiveScreenModel` already holds the ordered list to extend |
| **Continuous watching with conditions** | "Watch this page and tell me when the price drops" as a standing request rather than a held session | `ScreenSession` already diffs frames and publishes revisions; what is missing is a durable predicate and a scheduler |
| **On-device embedding index of history** | "What did I do yesterday", "find the screen where I saw the warranty page" | EmbeddingGemma 2 270M is already in the catalog at 165 MB. Directly conflicts with today's no-persistence stance, so it must be opt-in with a one-tap wipe |
| **Disambiguation panels** | Two numbers for one name becomes a choice, not a stop; also for two apps that can do a thing | Small, and it removes a large share of dead ends |
| **Real multilingual + code-switching** | Same grammar for more Indic languages, and "traffic ka status bata" understood as one sentence | The Devanagari layer is already factored out; the hard part is recognizer language coverage |

### 5.3 Voice quality

- Streaming ASR with word-level timestamps, so an edit like "change *tomorrow* to Friday" targets the
  word rather than the whole field.
- Barge-in during TTS with proper audio-focus and echo handling, so a spoken "no" interrupts a prompt.
- Speaker binding: enrol one voice, refuse commands from anybody else — hands-free on a shared desk is
  unsafe today.
- Confidence-aware routing: below a threshold, it asks or confirms instead of executing the best guess.
- Prosody-aware dictation: punctuation from intonation rather than spoken "full stop".

### 5.4 Safety features that unlock the ambitious ones

- Biometric or PIN gate before a mission may send, tap or spend.
- Per-mission budget: "you may spend up to ₹200 and tap up to 6 things", enforced in `SafetyPolicy`.
- An audit view of every action a mission took, replayable, so autonomy is reviewable after the fact.
- Kill-switch propagation: today the bubble off-switch stops overlay, mic and capture; a mission
  engine must also stop scheduled work, and that has to be tested, not assumed.

## 6. How the claims in this file were checked

- **529 JVM unit tests, 0 failures, 0 skipped** across 38 suites on the current tree, measured from
  `app/build/test-results/testDebugUnitTest/*.xml` after the 1.2.0 build rather than from a Gradle
  summary line.
- **Automations were followed end to end on device.** One rule created in the UI ("Movie night" style
  draft, step `open the clock app`) was read back out of `flowbubble_automations.xml` with
  `at=06:12, daily=true, windowMinutes=15`, seen armed in `dumpsys alarm` as
  `RTC_WAKEUP … tag=*walarm*:com.flowbubble.app.action.AUTOMATIONS_RUN origWhen=2026-11-03 06:12:00
  window=+5m`, observed firing (`Persist test dispatched 1 steps`), observed executing (the Clock app
  became `mCurrentFocus`), recorded (`lastResult="Started TIME"`,
  `lastTriggerKey=time:2026-11-03T06:12`) and re-armed for the next day. The same pass confirmed the
  kill-switch notice renders when the bubble is off, and that a plain app-open step raises neither the
  amber nor the red editor notice — first with a JVM test printing the parse, then by typing it on the
  device.
- **The floating companion has been seen on device.** The 96 dp overlay window is drawn at
  `(802,1488) 252×252` over Messages, its window titles are "Your Bubble" and "Your Bubble
  transcript", and the `Listening` pose renders with the transcript pill above it.
- **The rename was proven to be an upgrade, not a reinstall.** `install -r -t` moved the tagged 1.0.1
  build onto the current versionName with
  `firstInstallTime` unchanged, so overlay, microphone, accessibility and notification-listener grants
  and the signing identity survived.
- **Accessibility binds under the new label** — `dumpsys accessibility` reports
  `Bound services: {Service[label=Your Bubble voice access, …]}`.
- **The provider surface was driven on device** — the AI models card, the 13-entry provider dropdown
  and the profile editor were captured on the emulator; see §3.2 for what the automated provider
  harness does and does not yet prove.
- **Not proven anywhere in this file:** anything that depends on a real microphone, and any local
  model actually running. See §3.2.

## 7. Non-negotiables for all of the above

Anything merged into Your Bubble must keep these, which is the product's whole claim to be trusted:

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
- No model download without an explicit user action that already showed the size.
- Signing material stays out of git.
