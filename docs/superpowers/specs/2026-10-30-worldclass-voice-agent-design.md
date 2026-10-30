# FlowBubble world-class voice and any-app control — design

Date: 2026-10-30
Status: approved for implementation (engine choice: ML Kit GenAI on-device speech + toolchain upgrade; in-app search: generic only)

## Goal

Make FlowBubble usable as a primary input method on any Android app: open any app by voice including mispronounced names, search inside any app, drive any app through a verified agent, and transcribe accurately with no network dependency once a local model is installed.

## Non-goals

No root, no custom IME, no wake word, no assistant role, no curated per-app deep-link table, no cloud STT. "Perfect understanding" is not a property any recognizer has; the goal is measurably better recognition plus loud honesty when it is not sure.

## Existing state this builds on

- `commands/AppResolver.kt` matches launcher labels by exact / prefix / substring only, and `CommandExecutor.openApp` reports "Opened X" without checking that X reached the foreground.
- `voice/SpeechController.kt` requests partials and three alternates but uses neither the alternates nor any confidence, and sets no biasing context.
- `access/FlowAccessibilityService.kt` can tap by label/ordinal, scroll, press back/home/recents, and tap at a point, but has no long-press, no horizontal swipe, no overflow-menu or dialog primitive.
- `agent/LocalPlanner.kt` and `agent/AgentRunner.kt` already run an observe-then-act-then-verify loop with a bounded step budget; new actions slot into it.

## Component decisions

### 1. Toolchain upgrade (blocking)

`com.google.mlkit:genai-speech-recognition:1.0.0-alpha2` classes carry Kotlin metadata `mv = 2.4`. Kotlin 2.0.21 cannot read them. Upgrade Kotlin and the Compose compiler plugin to 2.4.21, AGP 8.7.3 to 8.13.2, Gradle 8.9 to 8.13. The build and all 83 existing unit tests must be green before any feature work starts.

### 2. `voice/TranscriptionEngine`

Interface over the existing `SpeechController.Listener` shape: `start`, `stop`, `cancel`, `close`, `describe`.

- `SystemSpeechEngine` wraps today's `SpeechRecognizer`. Always available, unchanged behaviour.
- `LocalSpeechEngine` drives ML Kit `SpeechRecognizer.startRecognition(request): Flow<SpeechRecognizerResponse>` with an `AudioSource` fed by `AudioRecord` at 16 kHz mono PCM. `PartialTextResponse` feeds the live transcript pill, `FinalTextResponse` completes the utterance, `AudioLevelResponse` drives the glow. `warmup()` runs when the model finishes installing.
- `ModelManager` exposes `checkStatus()`, the `download(): Flow<DownloadStatus>` progress, and install/cancel to Settings. Nothing downloads without a tap.
- Routing: local engine when its model is installed and the capability is available, otherwise system. If the active engine errors mid-utterance, the retry uses the other engine and the panel says which one was used. ML Kit needs Google Play Services; on a device without it the app stays on the system engine and says so.

### 3. Understanding layer (no engine gives us biasing)

ML Kit exposes no confidence, no n-best list, and no hotword API, so correction happens after transcription.

- `voice/VocabularyProvider` builds an in-memory lexicon from launcher app labels, contact names (only when the permission is granted), routine names, and command keywords.
- `voice/PhraseResolver` tries the existing `CommandParser` first. When a phrase does not parse, it generates candidates by scoring tokens with Levenshtein distance and a dependency-free phonetic normalizer against the lexicon, and returns one of `Resolved`, `Suggest(candidates)`, or `Unknown(reason)`.
- `OverlayService` shows a "Did you mean" panel for `Suggest` with up to three buttons, and re-prompts for `Unknown`. An unanswered suggestion is never treated as a yes, and an unparsable phrase is never silently dictated.

### 4. Opening any app, verified

`AppResolver` gains a nickname/alias table, text normalization, and the same Levenshtein plus phonetic scoring used by the resolver, matching against label, activity label, and package segment. After launch, `CommandExecutor` polls the accessibility service's current package for up to about 2.5 seconds; a package that reaches the foreground is reported as opened, one that does not is reported as unverified. A name that matches nothing offers a Play Store search rather than a web search.

### 5. Searching inside any app, generic

No curated deep links. Find the app's own search affordance through accessibility: focus an editable node whose hints look like search, otherwise tap a node labelled Search, type, submit with the IME action, then verify the screen text changed. If none of that works, report it and offer a web search.

### 6. Handling any app

New accessibility primitives: long-press by label and at a point, directional swipe including horizontal, open overflow menu, clear focused field, select-all, copy, paste, and dismiss a dialog by its visible button label. Each becomes an `AgentAction`, is parsed by `LocalPlanner`, is offered to `LlmPlanner` within the fixed vocabulary, and is executed by `AgentRunner` with the same verification as existing steps.

`SafetyPolicy` classifies allow, deny, install, pay, send, delete, and permission-dialog dismissals as confirmation-required. FlowBubble never taps a permission grant on the user's behalf, never touches password fields, and never sends a message, email, or payment.

## Testing

Unit tests for `PhraseResolver` (fuzzy and phonetic cases, reject cases), `VocabularyProvider`, `AppResolver` alias and fuzzy matching, engine routing decisions, new parser routes, and `SafetyPolicy` on dismiss and allow labels. Device tests on the API 35 emulator for model install, local dictation, a deliberately mangled app name, open-app foreground verification, Play Store fallback, in-app search, and a long-press plus overflow agent step, with regression of every previously verified feature.

## Known risks

- The ML Kit artifact is an alpha and its model download requires Google Play Services; if the emulator cannot install the model, the local path is verified only as far as its fallback and the honest reporting, and that is reported rather than glossed over.
- Upgrading Kotlin and AGP can surface unrelated build breakage; the upgrade is verified green before features are added so any later failure is attributable.
- Model size and download behaviour are not documented by the SDK; the UI shows whatever `DownloadStatus` reports instead of a promised number.
