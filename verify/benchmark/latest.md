# Your Bubble execution benchmark

- device: emulator-5554
- build: 1.2.1 (from YourBubble-1.2.1-debug.apk)
- scope: all 67 tasks
- run: Tue Nov 03 2026 23:01:44 GMT+0530 (India Standard Time)
- result: 63 passed, 2 skipped, 2 failed

| task | phrase | verdict | observed | note |
| --- | --- | --- | --- | --- |
| app.youtube | open youtube | PASS | App / Opened YouTube / done |  |
| app.chrome | open chrome | PASS | App / Opened Chrome / done |  |
| app.gmail | open gmail | PASS | App / Opened Gmail / done |  |
| app.camera | open camera | PASS | App / Opened Camera / done |  |
| app.messages | open messages | PASS | App / Opened Messages / done |  |
| app.settings | open settings | PASS | System / Opened Android settings / done |  |
| app.playstore | open play store | PASS | App / Opened Play Store / done |  |
| app.photos | open photos | PASS | App / Opened Photos / done |  |
| app.maps | open maps | PASS | App / Opened Maps / done |  |
| app.clock | open clock | PASS | App / Opened Clock / done |  |
| app.typo | opem youube | PASS | App / Look up "opem youube" / did you mean YouTube |  |
| app.hindi | यूट्यूब खोलो | PASS | App / Opened YouTube / done |  |
| app.hinglish | kholo chrome | PASS | App / Opened Chrome / done |  |
| app.unknown | open zzzqwerty | PASS | App / Look up "zzzqwerty" / did you mean Safety |  |
| search.web | search the web for weather | PASS | Web / Search for “weather” / awaiting user |  |
| search.lookup | look up weather | PASS | Web / Search for “weather” / awaiting user |  |
| search.url | go to example.com | PASS | Web / https://example.com / awaiting user |  |
| search.youtube | search lofi in youtube | FAIL | Agent / Stopped: Search YouTube for “lofi” / failed \| Search / Search YouTube for "lofi" / requested | expected entry never appeared |
| search.chrome | search weather in chrome | PASS | Agent / Completed: Search Chrome for “weather” / done \| Search / Search Chrome for "weather" / requested |  |
| search.screen | search this screen for weather | PASS | Agent / Completed: Search for “weather” / done |  |
| search.hindi | क्रोम में मौसम खोजो | PASS | Agent / Completed: Search Chrome for “मौसम” / done \| Search / Search Chrome for "मौसम" / requested |  |
| search.hinglish | youtube mein lofi khojo | FAIL | Agent / Stopped: Search YouTube for “lofi” / failed \| Search / Search YouTube for "lofi" / requested | expected entry never appeared |
| screen.scrollDown | scroll down | PASS | Screen / Scrolled down / done |  |
| screen.scrollUp | scroll up | PASS | Screen / Scrolled up / done |  |
| screen.tap | tap battery | PASS | Screen / Tapped Battery / done |  |
| screen.back | go back | PASS | Screen / Went back / done |  |
| screen.recents | open recents | PASS | Screen / Opened recent apps / done |  |
| screen.home | go home | PASS | Screen / Went home / done |  |
| screen.read | read this screen | PASS | Read / Read 344 characters via accessibility / done |  |
| screen.actions | what can i do here | PASS | Actions / Scan this screen / nothing found |  |
| screen.screenshot | take a screenshot | PASS | System / Screenshot requested / requested |  |
| screen.readHindi | स्क्रीन पढ़ो | PASS | Read / Read 344 characters via accessibility / done |  |
| screen.scrollHindi | नीचे स्क्रॉल करो | PASS | Screen / Scrolled down / done |  |
| screen.backHindi | पीछे जाओ | PASS | Screen / Went back / done |  |
| sys.timer | set a timer for 5 minutes | PASS | Timer / Timer for 5 min / requested |  |
| sys.timerHindi | पाँच मिनट का टाइमर लगाओ | PASS | Timer / Timer for 5 min / requested |  |
| sys.alarm | set an alarm for 7:30 am | PASS | Alarm / Alarm at 07:30 / requested |  |
| sys.battery | battery status | PASS | Battery / 100% / done |  |
| sys.batteryHindi | बैटरी बताओ | PASS | Battery / 100% / done |  |
| sys.volumeUp | volume up | PASS | Volume / Media volume up / done |  |
| sys.volumeDown | volume down | PASS | Volume / Media volume down / done |  |
| sys.volumeHindi | आवाज़ बढ़ाओ | PASS | Volume / Media volume up / done |  |
| sys.mute | mute | PASS | Volume / Muted media / done |  |
| sys.unmute | unmute | PASS | Volume / Unmuted media / done |  |
| sys.torchOn | turn on the flashlight | SKIP | System / Flashlight on / done | device lacks the hardware for this task |
| sys.torchOff | turn off the flashlight | SKIP | System / Flashlight off / done | device lacks the hardware for this task |
| sys.dndOn | turn on do not disturb | PASS | Do Not Disturb / On / done |  |
| sys.dndOff | turn off do not disturb | PASS | Do Not Disturb / Off / done |  |
| sys.notifications | read notifications | PASS | Notifications / Read 1 notifications / done |  |
| sys.clearNotifications | clear notifications | PASS | Notifications / Clear notifications / nothing found |  |
| comms.call | call pranav | PASS | Call / Dialer opened for Pranav / awaiting user |  |
| comms.chat | chat with pranav | PASS | Chat / Opened Pranav's chat on Messages / done \| Chat / Open Pranav on Messages / requested |  |
| comms.chatOf | go to chat of pranav | PASS | Chat / Opened Pranav's chat on Messages / done \| Chat / Open Pranav on Messages / requested |  |
| comms.chatHindi | प्रणव से चैट करो | PASS | Chat / Opened Pranav's chat on Messages / done \| Chat / Open Pranav on Messages / requested |  |
| comms.message | message pranav saying I will be late | PASS | Message / Confirm draft to Pranav / awaiting user |  |
| comms.messageHindi | प्रणव को मैसेज भेजो कि मैं देर से आऊंगा | PASS | Message / Confirm draft to Pranav / awaiting user |  |
| comms.email | email test@example.com about lunch | PASS | Email / Draft email / awaiting user |  |
| comms.calendar | create an event for team lunch | PASS | Calendar / New event draft / awaiting user |  |
| comms.maps | navigate to chennai | PASS | Maps / Open "chennai" / awaiting user |  |
| routine.save | save a routine called bench for open youtube | PASS | Routine / Saved bench / done |  |
| routine.run | run routine bench | PASS | Routine / Ran bench / requested |  |
| routine.list | list routines | PASS | Routine / Listed 1 routines / done \| App / Opened YouTube / done |  |
| routine.delete | delete routine bench | PASS | Routine / Delete bench / awaiting user |  |
| follow.repeat | again | PASS | Battery / 100% / done |  |
| follow.copy | copy that | PASS | Clipboard / Copied the last text / copied |  |
| agent.chain | open chrome and search for weather | PASS | Agent / Completed: open chrome and search for weather / done \| Agent / open chrome and search for weather / requested |  |
| agent.youtube | open youtube and search for lofi | PASS | Agent / Completed: open youtube and search for lofi / unverified \| Agent / open youtube and search for lofi / requested |  |

## Skipped by design
- dictation into a caret: needs a human-focused editable field; covered by the chat draft flow and unit tests
- ask/translate screen: needs a configured AI endpoint; the gate is unit-tested
- screen capture / OCR: requires per-session MediaProjection consent by design
- Telegram/Signal/Instagram contact chats: no deterministic deep link, documented unsupported
- placing calls and sending messages: Your Bubble stops at the dialer/draft by design
