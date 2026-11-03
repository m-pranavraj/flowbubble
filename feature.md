# Your Bubble — every feature, and how each one was proved

This is the capability catalogue for the build that ships. [README.md](README.md) tells you how to
build and use the app; [about.md](about.md) is the strategy document that also says what *could* be
built. This file answers a narrower question: **for each feature, does the code exist, is it wired to
something the user can actually reach, and has anyone watched it work on a real device?**

Those are three different claims, and this product has been harmed by conflating them, so every line
below carries one of three proof tags.

| Tag | Means |
|---|---|
| **DEVICE** | Watched working on `emulator-5554` (Android 15 / API 35) in this build. A screenshot, log line, `dumpsys` entry, or file on disk backs it. |
| **TESTED** | Runs in the shipped code path and is covered by unit tests that pass, but nobody has watched it happen on a device in this build. |
| **WIRED, NEVER RUN** | Reachable from a real user action and correct in tests, but the heavy dependency (a multi-gigabyte model, a live provider) has never been present, so it has never produced an output on this machine. |
| **BUILT, NOT WIRED** | The file and its tests exist; no reachable user action invokes the part being described. Listed in §9 so nobody reads a class name as a feature. |

Two things are never claimed anywhere in this file: that a real human voice was spoken into this
build, and that it works on a phone. Every DEVICE line was earned on an emulator with an injected
transcript.

## The build this describes

| | |
|---|---|
| Version | `1.2.0`, versionCode `5` — `app/build.gradle.kts:27-28` |
| Package id | `com.flowbubble.app` (unchanged on purpose; see "Name and identity" in the README) |
| SDKs | minSdk 26, targetSdk 35, Java 17 bytecode |
| Kotlin source shipped | 83 files, 26,620 lines under `app/src/main/java` |
| Unit tests | 38 files, 6,664 lines, **532 tests, 0 failures, 0 skipped** (measured from `app/build/test-results/testDebugUnitTest/*.xml`) |
| Debug-only source | 1 file — `app/src/debug/java/com/flowbubble/app/DebugSimulateReceiver.kt`, guarded by `BuildConfig.DEBUG` and absent from the release APK |
| Manifest permissions requested | 13 `uses-permission` entries (12 `android.permission.*` plus `com.android.alarm.permission.SET_ALARM`) |
| Artifacts | `YourBubble-1.2.0-release.apk` (102,868,227 bytes), `YourBubble-1.2.0-debug.apk` (106,777,318 bytes) |
| Instrumented tests | **none.** There is no `app/src/androidTest`, and no Robolectric. Nothing here is verified inside a runtime by the test suite. |

---

## 1. The bubble itself

**DEVICE** — A floating, draggable overlay bubble above every app except Android Settings. It is a
`TYPE_APPLICATION_OVERLAY` window owned by `service/OverlayService.kt`, drawn by `overlay/BubbleView.kt`.
Turning it on starts a microphone-type foreground service; turning it off tears the windows down.

**DEVICE** — Hold-to-talk: press and hold the bubble, release to finish. Live transcript appears in
`overlay/OverlayPanel.kt`, and the ongoing notification carries a Stop action.

**DEVICE** — Tap the bubble for the options menu (panel actions, hands-free switch, turn-the-bubble-off).
The tap also triggers a haptic and a "startled" reaction from the character.

**TESTED** — The Quick Settings tile (`service/FlowTileService.kt`) toggles the same switch as the app.
On Android 14+ it uses the tile's required visibility handling.

**DEVICE** — The kill switch. Switching the bubble off removes every overlay window, stops the
microphone, ends MediaProjection, and stops all automations. This is one boolean in `OverlayService`
and everything else checks it; it is the reason the app can promise "nothing listens when it is off".

> Platform limit, not a defect: on the API 35 emulator image Android force-hides third-party overlay
> windows while Settings is in the foreground (`mForceHideNonSystemOverlayWindow=true` in
> `dumpsys window`). The bubble is invisible inside Settings, still works, and draws normally over
> every other app tested.

## 2. Speech in

**DEVICE (injected)** — Transcription, then cleanup: filler and repeated words removed, punctuation and
casing applied, inserted at the caret of the focused field. **The audio path is not verified** — this
emulator image has no working speech recognizer language pack, so every transcript below was injected
through the debug receiver. Everything after the transcript is real.

**TESTED** — Two engines behind `voice/TranscriptionEngine.kt`: on-device ML Kit text recognition, and
the system recognizer, with an "Automatic" mode that picks one and says which it picked. Engine choice
is a Settings card; the model's download state for the selected language is shown honestly (ready /
installing / not available / failed) with a **Check again** probe and a **Download** button. Your Bubble
never starts that download by itself — the button is the consent and the size is shown first.

**DEVICE** — The raw ML Kit failure codes no longer leak into the UI. `voice/LocalSpeechModel.kt:98`
maps each documented `FeatureStatus` to a human sentence; anything unknown becomes "not available on
this device" rather than the number `0`, which is what used to appear in Settings.

**TESTED** — `voice/PhraseResolver.kt` re-scores a heard phrase against installed app labels and known
aliases with a typo distance, because ML Kit returns no confidence and no alternatives. A clear match
acts, a near miss asks "did you mean YouTube?", and a phrase with nowhere to go is kept as text rather
than silently dropped. `voice/VocabularyProvider.kt` caches the label vocabulary.

**TESTED** — Misheard app names that arrive as dictation are rescued: free text with no focused field is
offered as an app to open instead of reported as a failed insertion.

**DEVICE** — Hindi and Hinglish. `voice/HindiCommands.kt` rewrites the documented verb forms into the
English grammar *before* parsing, so a Hindi phrase enters the same safety gates and verification as an
English one; `voice/Devanagari.kt` transliterates names for contact matching. A spoken message body or
search query stays in the script it was spoken in. This is a fixed pattern table, not Hindi NLU —
anything outside the table stays dictation.

**DEVICE (injected)** — The wake phrase **"Hello Bubble"**, then a command, with no touch at all. The
hands-free loop lives in `OverlayService`; listening pauses when the screen is off, so the microphone
does. It is off by default with its own switch in Settings and in the bubble menu. **The wake phrase
itself has only ever been proven with injected transcripts** — "hearing 'Hello Bubble' through a real
microphone" is unverified on this machine and cannot be verified here.

**DEVICE** — Voice out. Outcomes and confirmation prompts are spoken through the TTS speaker, and a
prompt can be answered "yes" or "no". **Silence never approves anything** — a prompt that times out
stays unanswered; there is no auto-yes path in the code.

## 3. Understanding what was said

**TESTED** — One parser, `voice/CommandParser.kt`, producing a closed set of **40 command variants**,
each with a real handler in `commands/CommandExecutor.kt`. There are no stubs: the list is

```
OpenApp  SetTimer  SetAlarm  Call  SendMessage  OpenChat  Navigate  Tap  Scroll
Back  Home  Recents  ReadScreen  OpenSettings  Dnd  TypeText  Screenshot
ReadNotifications  ClearNotifications  OpenUrl  WebSearch  Search  AgentTask
AskScreen  TranslateScreen  MediaToggle  FollowUp  SaveRoutine  RunRoutine
DeleteRoutine  ListRoutines  Flashlight  Volume  BatteryStatus  ComposeEmail
NewEvent  ScreenActions  Watch  Point  Clarify
```

`Watch` is continuous screen watching (§4). `Point` and `Clarify` are the "which one did you mean"
path — the assistant asks instead of guessing.

**TESTED** — Alias and phonetic app resolution (`commands/AppAliases.kt`) with foreground verification:
the app that was asked for must actually come to the front before the action is reported as done.

**TESTED** — `agent/CapabilityRouter.kt` is the single place that decides whether a command is *ready*,
*gated*, or *unsupported* before anything runs, and a gate names the exact system switch and offers a
button that opens that Settings screen. The panel, the history entry, and the prompt all read the same
decision, so a half-configured action cannot report success.

**TESTED** — `agent/Outcome.kt` gives one vocabulary for results — completed / partial / awaiting user /
unsupported / failed — and agent chains carry a step-by-step execution trace. A chain is only reported
complete when every step has evidence the system observed; steps Android never confirms are listed as
"not confirmed by the system".

## 4. Screen intelligence

**DEVICE** — On capture consent, `capture/ScreenCaptureService.kt` holds a MediaProjection session. The
system consent dialog is per-session and cannot be pre-accepted. **Frames stay in memory and are never
written to disk.** Apps that set `FLAG_SECURE` produce black frames and the app says so.

**DEVICE** — `vision/` fuses the accessibility node tree with ML Kit OCR into one screen model, so a
control accessibility hides but that is visible on screen can still be found and tapped. Reading the
screen, answering a question about it, and translating it all use that fused model. On Android 14+ the
system may default to sharing one app; cross-app vision needs "Entire screen" and the app cannot choose
for you.

**DEVICE** — Continuous watching: "keep watching this screen" / "watch until it's done" / "stop
watching" (`capture/ScreenSession.kt`, driven by `Command.Watch`). While a watch is on, the screen is
re-read on change and the reactive agent loop can act on what appears — the case behind "scroll down"
or "click that specific chat" mid-task. The bubble wears a *curious* expression while a watch is live
(`OverlayService.kt:1295`), so the user can always see whether the eyes are open.

**DEVICE** — Scroll-to-find: "scroll down until you see References", "scroll until you see Checkout and
tap it". One scroll at a time, re-reading between scrolls, stopping when the label appears (and tapping
only when asked), and reporting honestly when the screen stops changing or the label never appears.
Default cap is six scrolls.

**DEVICE** — Smart screen actions: "what can I do here" scans visible text for phone numbers, emails,
links, and street addresses (`vision/SmartActions.kt`) and offers Call / Email / Open / Map buttons.
Nothing runs until a button is tapped, and Call only opens the prefilled dialer.

**TESTED** — `vision/LiveScreenModel.kt` and `vision/ScreenModels.kt` build the model the agent and the
answering path consume, and are unit tested without Android.

## 5. Getting it done in other apps

**DEVICE** — Open any installed app; go back / home / recents; open Android Settings; take a screenshot
(API 30+; the system saves it and Your Bubble cannot confirm the file).

**DEVICE** — Tap by visible label or by ordinal ("tap the second result"), scroll, long-press, side
swipe, and open the overflow menu through the accessibility service
(`access/FlowAccessibilityService.kt`). `access/SpokenText.kt` folds labels so `&`, `-`, punctuation,
and Devanagari marks still match what the user said.

**DEVICE** — Type into the focused field; copy / select-all / set-selection (API 28+). Gestures are
dispatched, and Android accepts a gesture before the app reacts, so a gesture is only claimed as done
when the screen actually changed.

**DEVICE** — Generic in-app search with verification: it needs a visible field or a control labelled
"search", waits for the field to actually appear after tapping a search control, and reports failure
rather than typing into whatever happens to be focused. Also web search, and "search in <app>".

**DEVICE** — Short multi-step agent tasks: "open Chrome then search for weather", "open Settings and
search for display". A deterministic local planner handles common chains; an optional AI planner can
propose `{open, tap, type, scroll, back, wait, ask}` JSON for unfamiliar screens, which is validated and
run through the same gates. The progress panel always offers Stop.

This shape regressed inside the 1.2.0 swarm and the frozen benchmark is what caught it, in two halves.
First, the reactive loop matched the open-app pattern against the *whole sentence* before it split the
chain, so "open chrome and search for weather" produced a goal meaning *wait until an app named
"chrome and search for weather" is in the foreground*. `agent/LocalPlanner.kt` now reads a chain's finish
line from its last clause, and returns no finish line at all when that clause has nothing observable to
check. Second, "no finish line" was not respected: `agent/AgentRunner.kt` substituted a GENERIC
placeholder goal, which has no branch in the decision function, so the chain fell through to *"I've done
the steps I could plan for … and I can't see a way to check the rest"* and a failure verdict — Chrome was
open, the search had run, and the panel said NOT DONE. With nothing observable to wait for, the planned
steps are the task and the verdict comes from their evidence, which is the rule the linear path already
used. Three unit tests pin the parsing half; the verdict half has no test because nothing in the JVM
suite can reach a screen, so it is proven on device: `agent.chain` went FAIL → PASS on `emulator-5554`,
and the 2026-11-01 run scored it as passed, which is what proves 1.2.0 broke it rather than that it
always worked.

**DEVICE** — Deterministic chat opening: "go to chat of Pranav", "WhatsApp Arjun". Contacts resolve as
people with every saved number attached (mobile first); an exact name is never listed twice; several
matches ask you to choose. Android has no "find this person's chat" API, so the app uses each app's
published deep link and then requires **two independent facts** before reporting success: the chat app
came to the front *and* the person's name is visible in it. WhatsApp, WhatsApp Business and the SMS
composer are covered; Telegram-without-a-username, Signal and Instagram are reported as unsupported
rather than guessed at.

**DEVICE** — Message drafts never send. Your Bubble resolves the contact, shows the exact text, and
opens the composer with an E.164 number. **The user presses Send.**

**DEVICE** — OS controls with read-back: flashlight (`core/TorchController.kt`, state observed through
Android's torch callback; devices that accept without reporting get "requested", not "success"), media
volume (`core/VolumeController.kt`; **one step per request** by default, `CommandParser.kt:64`), mute,
set-to-percent, battery. Media key dispatch requires API 31 (`CommandExecutor.kt:1733` checks it).

**DEVICE** — Do Not Disturb with an optional duration, reverted automatically even if the app restarts
or the device reboots (a stored deadline is re-armed on start).

**DEVICE** — Timers and alarms through the standard clock intents; the panel says the request was sent,
not that the clock displayed anything. Email drafts and calendar events open their editors pre-filled
and both panels state plainly that you press Send or Save yourself.

**DEVICE** — Notifications: read what arrived while the listener was connected (last 40, in memory, never
history) and, after a confirmation, dismiss only those same recorded notifications.

**DEVICE** — Voice routines: "save this as a routine called morning brief" then "run routine morning
brief". Stored on-device in `core/RoutineStore.kt`, capped at 20, listed in Settings with a delete
confirmation, and replayed through the normal confirmation gates.

**DEVICE** — Conversational follow-ups: after a dictation or a screen reading, "make it shorter", "make
this professional", "turn that into bullet points", "translate that to Spanish" reuse the text just
produced; "undo that", "do that again", "copy that" act on the last step. `core/LastActionMemory.kt`
lives in RAM only and is cleared when the bubble turns off.

## 6. Automations — scheduled, condition-triggered runs

The one subsystem that acts *without* the user holding the bubble, so it is the subsystem with the most
guards. Everything here is **DEVICE**-proven for the time trigger, end to end, in this build.

**UI**: Settings/Home navigation → **Automations** (`ui/AutomationsScreen.kt`,
`MainActivity.SCREEN_AUTOMATIONS`). Rules are listed with a plain-language summary ("Every day at
06:12"), a per-rule switch, a last-run line, an editor card, and a delete confirmation.

**DEVICE** — A rule is a name (40 chars), a trigger, and up to 8 steps (`AutomationEngine.MAX_STEPS`)
that are replayed through the *same* `CommandParser` and the *same* safety gates as a spoken command.
Persisted in `core/AutomationStore.kt` as one record per rule in the SharedPreferences file
`flowbubble_automations`, capped at 20 rules. A rule the user just saved is committed synchronously —
an async `apply()` still in flight when the process dies would lose the rule and its alarm together.

**DEVICE** — Time trigger, proved all the way through:

```
create "Movie night" in the UI          → validation passes, editor shows no warning
save                                     → flowbubble_automations.xml contains at=06:12, daily=true,
                                           windowMinutes=15, step "open the clock app"
arm                                      → dumpsys alarm: RTC_WAKEUP
                                           tag=*walarm*:com.flowbubble.app.action.AUTOMATIONS_RUN
                                           origWhen=2026-11-03 06:12:00 window=+5m
fire                                     → Persist test dispatched 1 steps
                                           Persist test [TIME] ran TIME
execute                                  → the Clock app became mCurrentFocus
record                                   → lastResult="Started TIME",
                                           lastTriggerKey=time:2026-11-03T06:12
re-arm                                   → origWhen=2026-11-04 06:12:00
```

The UI shows "Runs on its own" and "Started TIME · 3 Nov · 06:17" afterwards.

**What "inexact" means here, honestly**: `service/AutomationReceiver.kt` uses `AlarmManager.setWindow`
and deliberately does **not** request `SCHEDULE_EXACT_ALARM`. So a 06:12 rule fired at 06:17. A rule is
a "sometime around then", never a guarantee — which is the right trade for an app that should not be
holding an exact-alarm permission for convenience.

**Wired, unit tested, not device-proven** — the other three trigger kinds in
`agent/AutomationEngine.kt` (`TIME`, `APP_OPENED`, `NOTIFICATION_MATCHES`, `PHRASE`). Each has a real
feed: `FlowAccessibilityService.kt:75` reports window changes into `notifyAppOpened`, `FlowNotificationService.kt:63`
pokes `notifyNotificationArrived`, and `OverlayService.kt:837` feeds `notifyPhrase` with the recognised
command. The matching logic is tested on the JVM. Nobody has watched an app-open or notification rule
fire on a device yet, so treat those three as *plumbing built, proof owed*.

**DEVICE** — The two safety banners in the editor, and the reason they can disagree:

- An amber notice lists steps a saved rule *cannot replay* at all (saves, deletes, listing, follow-ups),
  because they need the conversation that produced them. `AutomationEngine.unreplayableSteps`.
- A red notice appears when a step needs your OK before it runs — send, call, point, clear
  notifications, delete a routine, a tap the safety layer does not rate SAFE, or any free-form agent
  task. `AutomationEngine.stepNeedsConfirmation`, derived from the steps rather than trusted from a
  stored flag, so a rule edited on disk cannot talk its way out of confirmation.
- They are different questions. A `FollowUp` step is unreplayable but does not need confirmation, so a
  rule can show one banner and not the other and both are correct.
- **DEVICE**: "open the clock app" shows *neither* banner. Proven twice — first with a JVM test that
  printed the parse (`OpenApp(query=clock)`, needsConfirmation=false, unreplayable=[]), then by typing
  the same step on the device and watching both banners stay away. A plain app-open is SAFE; earlier
  reports of phantom warnings came from a mangled draft with a blank step, not from the rule engine.

**DEVICE** — An automation that needs confirmation does not silently run: `AutomationDecision.Ask`
records "Needs your OK" and raises the ask; `AutomationReceiver.kt:177` guarantees that only the rule's
own name and kind ever reach logcat — never a phrase, a screen, or a notification body.

**DEVICE** — The bubble switch is a global kill switch for automations too. With the bubble off, an
in-flight rule stops, nothing keeps polling, and the screen says so; the schedule is re-armed rather
than cancelled when a wake finds the switch off, so turning the bubble back on later does not silently
lose tomorrow's run.

**DEVICE** — `BOOT_COMPLETED` re-arms the plan after a reboot, and `MainActivity.onResume` re-arms after
any rule edit.

## 7. The companion character

**DEVICE** — The bubble is a rendered 3D baby, not a vector blob: seven 384×384 stills in
`app/src/main/res/drawable-nodpi/` (`companion_idle`, `_listening`, `_thinking`, `_happy`, `_worried`,
`_sleepy`, `_surprised`), decoded once and cached off the draw path by
`overlay/companion/CompanionSprites.kt`, with a feathered circular rim so the disc never reads as a
pasted square on a white page. `tools/companion-sprites.py` builds them from source renders; a pose
that fails to decode falls back to the vector character, so a broken asset costs looks and never costs
the bubble.

**DEVICE** — The motion is procedural, and it is real animation rather than a slideshow: bob, breath,
blinks, gaze drift, sway, ring pulse, amplitude reacting to microphone level, and a poke on tap. All of
it is computed in `overlay/companion/CompanionState.kt` — pure Kotlin, deterministic functions of a
clock the caller supplies, no Android types — and only drawn by `CompanionRenderer.kt`. That split is
why the character's whole personality is unit-testable on a JVM.

**DEVICE** — Idle, listening, thinking, worried, and the startled poke on tap are all reachable and
were seen on device.

**BUILT, NOT WIRED** — The *happy* face never appears: `BubbleView.kt:173` maps `State.READY` to it,
but nothing ever sets `State.READY`, so a finished task shows the resting face instead of celebrating.
The *sleepy* face never appears either: `CompanionLook.moodNamed` (`CompanionState.kt:212`) parses
`"sleepy"` from a persisted preference string that no screen writes. Both are one small wiring change
away, not a rendering change.

**BUILT, NOT WIRED** — `BubbleView.setStage` exists (expression intensity, 1–4) but no caller changes
the stage, so the character always performs at its default.

**DEVICE** — The launcher icon is the same character (`tools/launcher-icon.py` →
`drawable-nodpi/ic_launcher_foreground.png`, replacing the vector foreground that was deleted), kept
inside the 66 dp adaptive safe zone. **Not finished**: the adaptive icon's third layer,
`drawable/ic_launcher_monochrome.xml`, is still the hand-drawn vector of the same face from `eb8ebc3` —
right character, different medium. Under a themed icon Android tints that layer and the app reads as a
line drawing; everywhere else it is rendered art.

## 8. Models and providers

**TESTED** — `voice/AiProviders.kt` is the registry: **12 hosted presets** — OpenAI, Groq, OpenRouter,
Google Gemini, Mistral, DeepSeek, Together AI, xAI Grok, Cerebras, Ollama (local), LM Studio (local),
and "Custom endpoint" — plus `on-device` as a thirteenth picker entry. `about.md` previously claimed
SambaNova and Hugging Face existed here; they do not, and that claim is corrected.

**TESTED** — Multiple named profiles, each assignable to any of the jobs: dictation cleanup, inline
edits, summarising, answering about the screen, translating, and free-form planning (`AiRole`).
`Settings → AI models` (`ui/AiModelsCard.kt`). A hosted endpoint without a key is refused; a keyless
endpoint is allowed only for an address on your own machine or network; plain `http://` to a hosted
provider is refused before anything leaves the app.

**TESTED** — Per-profile **Test** button: one tiny round trip ("reply with exactly OK"), reporting
latency or the provider's own error with the status code and the key masked. A job assigned to a
profile that has stopped working turns that job off rather than quietly routing your text to a
different company.

**DEVICE** — The settings UI, the profile storage, the encrypted key file, and the validation rules
were exercised on the device against a local mock OpenAI-compatible endpoint
(`scripts/mock-openai.mjs`); request shapes are recorded in `verify/provider/requests.jsonl`. Keys are
in a Keystore-backed preference file, shown as last-four, and never reach a log or the history.

**WIRED, NEVER RUN** — **No local model has actually run on this machine.** The call path is real: a
profile marked on-device routes `voice/LlmClient.kt:162` into `voice/LocalLlmEngine.complete(...)`, and
`LlmClient.kt:102-106` releases the loaded model when the profile changes so two profiles cannot share
one resident weight file. What has never happened here is a download followed by a token coming out.
`core/LocalModelStore.kt` carries a 6-model catalog —
`functiongemma-270m` (284 MB), `qwen3-0.6b` (614 MB), `gemma-4-e2b` (2.59 GB), `gemma-4-e4b` (3.66 GB),
`gemma-3n-e2b` (3.66 GB, license-gated so it cannot be downloaded), and
`embeddinggemma-2-text-270m` (165 MB, embeddings only, so not assignable to a chat role) — with a size
disclosed before any download, a resumed partial file, an exact-length check, and a RAM floor for
low-memory devices. The download and inference code is written; nobody has pressed the button on a
device and watched a token come out. Treat "runs a model on your phone" as a promise in this build, not
a demonstration.

**Not in this build** — `sherpa-onnx` appears zero times in the repository. The on-device STT path is
ML Kit text recognition, not sherpa. The original plan named sherpa; the shipped code does not use it.

## 9. Built, not wired — the honest list

Each row below compiles and is unit tested, and the **specific member named is reachable by nothing the
user can do**. Some of these files are genuinely used elsewhere; that is stated per row, so the section
reads as a list of unwired *capabilities* rather than a list of dead files.

| Code | What it would do | What is missing |
|---|---|---|
| `agent/AppPlaybooks.kt` | A curated per-app catalog — the "book a table, buy a ticket, open a show" knowledge layer, with known blockers per app | Only `blockedBy()` is called (`AgentRunner.kt:332`). No planner, search, or automation reads a playbook, and every entry still carries `provenOnDevice = false`. |
| `agent/ActionContract.kt` | Per-action contracts: fresh-confirmation tokens, an unnamed-recipient rule, an outcome note, "how was this proven" | `requiresFreshConfirmation`, `unnamedRecipientMatch`, `provenBy` and `outcomeNote` are never read by `AgentRunner`, which consumes only `id`, `tier`, `mayRun`, `requiresConfirmation`, `target`, `fallbackMessage`. Because no call site ever populates the recipient, "an unnamed recipient is CRITICAL" is unreachable in practice. |
| `agent/CriticalPolicy.kt` | Classify a send-to-person or a label as critical | Reachable for taps and field labels through `SafetyPolicy.kt:65,103`; the `classifySendToPerson` branch (`SafetyPolicy.kt:133`) has no populated recipient to act on. |
| `agent/TaskStateMachine.kt` | A guarded lifecycle for a task | Nine events are driven; `pause`, `resume`, `reset` and `legalEvents` are never called, so a task cannot be paused or resumed. |
| `agent/TaskContext.kt` | Facts carried across steps, and handoff between apps | Only `record()` and `close()` are used. `fact`, `facts`, `handoff`, `viewFor` and `expire` have no callers, so there is no cross-app fact handoff. |
| `service/AutomationReceiver.kt` | Refuse to run while the screen shows an authentication challenge | `awaitingAuthentication` is hardcoded `false`. The guard is written and never armed. |
| The happy and sleepy faces, `BubbleView.setStage` | See §7 | See §7. |

## 10. Safety invariants, stated so they can be checked

These are not features; they are the rules every feature above is inside. Each was verified by reading
the enforcement point, and the ones with a user-visible consequence have device evidence.

- Never bypasses a lock screen, an authentication prompt, or a CAPTCHA.
- Never reads or writes a password or payment field; OCR is skipped while a password field is focused,
  and labels that name credentials are blocked.
- Messages, destructive actions, purchases and risky taps require a confirmation, and nothing
  auto-sends. A confirmation that times out is not a yes.
- On-screen text and notification content are treated as untrusted input, not as instructions.
- No raw audio, screen text, notification body, or credential goes into a log or into history.
  Automations additionally log only a rule's name and kind.
- The bubble switch kills the overlay, the microphone, MediaProjection and every automation.
- MediaProjection stays consent-per-session; frames stay in memory.
- AI features send only the visible text the user explicitly approved — never audio, never a screenshot
  image — and the panel names the destination host and the character count first.
- No silent multi-hundred-megabyte model download: the size is shown, and the button is the consent.
- Anything unsupported or unconfirmed is reported as such. "Requested" is not spelled "done".

## 11. Verification tooling (not shipped in the APK)

| Tool | What it does | State |
|---|---|---|
| `scripts/benchmark.mjs` | Frozen 67-task device execution benchmark; injects a phrase, waits for the trace entry, records the verdict | **63 passed, 2 skipped, 2 failed** on the shipping build (`verify/benchmark/latest.md`, 2026-11-03 10:24). Both failures are the YouTube wall below, not product defects. An earlier run the same morning read *0 passed, 67 failed* with every row saying "no new trace entries" — a harness/injection failure, which is why a run like that was re-taken instead of published. |
| `scripts/provider-check.mjs` | Proves the settings/profile/validation paths against a local OpenAI-compatible mock | Evidence in `verify/provider/` |
| `scripts/mock-openai.mjs` | The local mock endpoint itself | used by the above |
| `scripts/release-check.mjs` | Checks the release gate before tagging | part of the release flow |
| `verify/*.png` | Device screenshots backing the DEVICE claims in this file | on disk |
| `app/src/test/` | 532 JVM unit tests, 0 failures | green at build time |

## 12. What this build cannot do

- **It is not a Google Play app.** Google Play's Accessibility API policy prohibits distributing a
  general-purpose automation tool that uses accessibility, and this product's core *is*
  autonomous accessibility. Sideloading is the supported channel. Anyone deciding to publish should
  re-read the current policy first; this is a distribution blocker, not a code bug.
- No real microphone audio has ever been processed on this machine, and no real speech has ever been
  recognised. Everything downstream of a transcript is real; the transcript itself is injected.
- No local model has run on a device, so keyless on-device AI is code and UI, not a demo.
- **Two of the 67 benchmark tasks can never pass on this emulator, and the reason is YouTube.** The
  image ships YouTube 19.17.42, which opens on a modal "Update your app" with no dismiss control — BACK
  exits the app rather than closing it — and `dumpsys account` lists no Google account, so Play cannot
  update it. There is genuinely no search field behind that dialog, so `search.youtube` and
  `search.hinglish` fail on every run here (`verify/yt_update_modal.png`). The product's answer is the
  honest one ("NOT DONE / I don't see a search field or a search button on this screen"), which is
  exactly what the panel says. **A third row, `agent.youtube`, prints PASS and should not be read as
  proof** — its own trace ends `Completed: open youtube and search for lofi / unverified`, so the
  harness accepted a step the app itself declined to vouch for. Any score quoted from this device has a
  floor of 2 skipped (no flash unit) and 2 device-blocked, making the real ceiling 65 of 67 — and 64 of
  67 if a row that admits it is unverified is not counted as seen.
- Booking, ticketing, and "buy anything in any app" are **not** implemented. The playbook layer that
  would carry that knowledge exists but is unwired (§9), no playbook has `provenOnDevice`, and there is
  no purchasing path. What exists today is the generic agent plus scroll-to-find plus tap-by-label,
  which can be *directed* at such a screen by a user but has not been proven there.
- No assistant role, no `VoiceInteractionService`, no custom IME.
- R8/minification is not enabled, so release size is uncompressed-by-obfuscation size.
- Threading debt that is real and not hidden: `service/OverlayService.kt:117` dispatches on
  `Dispatchers.Main.immediate`, so accessibility walks run on the main thread and the app can ANR
  under harness load. Observed; not fixed.
- The emulator AVD is still named `FlowBubble_API35` and the AAB keeps the default `app-release.aab`
  filename — both are cosmetic leftovers of the rename.

---

*Every DEVICE line in this file was produced on `emulator-5554` with the 1.2.0 sources. The evidence is
in `verify/` and in the run logs of the session that wrote it; where a claim rests on a log line or a
`dumpsys` entry, the entry is quoted so it can be re-checked against the file rather than against this
document's memory of it.*
