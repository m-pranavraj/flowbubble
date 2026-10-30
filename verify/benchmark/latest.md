# FlowBubble execution benchmark

- device: emulator-5556
- run: Fri Oct 30 2026 17:00:16 GMT+0530 (India Standard Time)
- result: 39 passed, 2 skipped, 26 failed

| task | phrase | verdict | observed | note |
| --- | --- | --- | --- | --- |
| app.youtube | open youtube | PASS | App / Opened YouTube / done |  |
| app.chrome | open chrome | PASS | App / Opened Chrome / done |  |
| app.gmail | open gmail | FAIL | App / Open Gmail / unverified | expected entry never appeared |
| app.camera | open camera | FAIL | App / Open Camera / unverified | expected entry never appeared |
| app.messages | open messages | PASS | App / Opened Messages / done |  |
| app.settings | open settings | FAIL | System / Opened Android settings / done | expected entry never appeared |
| app.playstore | open play store | FAIL | App / Open Play Store / unverified | expected entry never appeared |
| app.photos | open photos | FAIL | App / Open Photos / unverified | expected entry never appeared |
| app.maps | open maps | FAIL | App / Open Maps / unverified | expected entry never appeared |
| app.clock | open clock | PASS | App / Opened Clock / done |  |
| app.typo | opem youube | FAIL | App / Look up "opem youube" / did you mean YouTube | expected entry never appeared |
| app.hindi | यूट्यूब खोलो | FAIL | App / Open YouTube / unverified | expected entry never appeared |
| app.hinglish | kholo chrome | PASS | App / Opened Chrome / done |  |
| app.unknown | open zzzqwerty | PASS | App / Look up "zzzqwerty" / did you mean Safety |  |
| search.web | search the web for weather | PASS | Web / Search for “weather” / awaiting user |  |
| search.lookup | look up weather | PASS | Web / Search for “weather” / awaiting user |  |
| search.url | go to example.com | PASS | Web / https://example.com / awaiting user |  |
| search.youtube | search lofi in youtube | FAIL | Search / Search YouTube for "lofi" / not found | expected entry never appeared |
| search.chrome | search weather in chrome | PASS | Agent / Completed: Search Chrome for “weather” / done \| Search / Search Chrome for "weather" / requested |  |
| search.screen | search this screen for weather | FAIL | Agent / Completed: Search for “weather” / done | expected entry never appeared |
| search.hindi | क्रोम में मौसम खोजो | PASS | Agent / Completed: Search Chrome for “मौसम” / done \| Search / Search Chrome for "मौसम" / requested |  |
| search.hinglish | youtube mein lofi khojo | FAIL | Search / Search YouTube for "lofi" / unverified | expected entry never appeared |
| screen.scrollDown | scroll down | PASS | Screen / Scrolled down / done |  |
| screen.scrollUp | scroll up | PASS | Screen / Scrolled up / done |  |
| screen.tap | tap battery | PASS | Screen / Tapped Battery / done |  |
| screen.back | go back | FAIL | Screen / Went back / done | expected entry never appeared |
| screen.recents | open recents | PASS | Screen / Opened recent apps / done |  |
| screen.home | go home | FAIL | Screen / Went home / done | expected entry never appeared |
| screen.read | read this screen | PASS | Read / Read 132 characters via accessibility / done |  |
| screen.actions | what can i do here | PASS | Actions / Scan this screen / nothing found |  |
| screen.screenshot | take a screenshot | PASS | System / Screenshot requested / requested |  |
| screen.readHindi | स्क्रीन पढ़ो | FAIL | no new trace entries | expected entry never appeared |
| screen.scrollHindi | नीचे स्क्रॉल करो | PASS | Screen / Scrolled down / done |  |
| screen.backHindi | पीछे जाओ | FAIL | Screen / Went back / done \| Screen / Tapped Battery / done | expected entry never appeared |
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
| sys.notifications | read notifications | PASS | Notifications / Read 2 notifications / done |  |
| sys.clearNotifications | clear notifications | FAIL | no new trace entries | expected entry never appeared |
| comms.call | call pranav | FAIL | Call / Dialer opened for Pranav / awaiting user \| Notifications / Clear notifications / awaiting user | expected foreground com.google.android.dialer |
| comms.chat | chat with pranav | FAIL | no new trace entries | expected entry never appeared |
| comms.chatOf | go to chat of pranav | FAIL | Chat / Opened Messages for Pranav / unverified | expected entry never appeared |
| comms.chatHindi | प्रणव से चैट करो | FAIL | no new trace entries | expected entry never appeared |
| comms.message | message pranav saying I will be late | FAIL | Chat / Opened Messages for Pranav / unverified | expected entry never appeared |
| comms.messageHindi | प्रणव को मैसेज भेजो कि मैं देर से आऊंगा | FAIL | no new trace entries | expected entry never appeared |
| comms.email | email test@example.com about lunch | PASS | Email / Draft email / awaiting user |  |
| comms.calendar | create an event for team lunch | PASS | Calendar / New event draft / awaiting user |  |
| comms.maps | navigate to chennai | PASS | Maps / Open "chennai" / awaiting user |  |
| routine.save | save a routine called bench for open youtube | PASS | Routine / Saved bench / done |  |
| routine.run | run routine bench | FAIL | Routine / Ran bench / requested | expected foreground com.google.android.youtube |
| routine.list | list routines | PASS | Routine / Listed 1 routines / done |  |
| routine.delete | delete routine bench | FAIL | no new trace entries | expected entry never appeared |
| follow.repeat | again | PASS | Battery / 100% / done |  |
| follow.copy | copy that | PASS | Clipboard / Copied the last text / copied \| Read / Read 344 characters via accessibility / done |  |
| agent.chain | open chrome and search for weather | FAIL | Agent / open chrome and search for weather / requested | expected entry never appeared |
| agent.youtube | open youtube and search for lofi | FAIL | Agent / Stopped: open youtube and search for lofi / failed \| Agent / open youtube and search for lofi / requested \| Agent / Completed: open chrome and search for weather / unverified | expected entry never appeared |

## Skipped by design
- dictation into a caret: needs a human-focused editable field; covered by the chat draft flow and unit tests
- ask/translate screen: needs a configured AI endpoint; the gate is unit-tested
- screen capture / OCR: requires per-session MediaProjection consent by design
- Telegram/Signal/Instagram contact chats: no deterministic deep link, documented unsupported
- placing calls and sending messages: FlowBubble stops at the dialer/draft by design
