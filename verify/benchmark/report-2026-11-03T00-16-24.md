# Your Bubble execution benchmark

- device: emulator-5554
- run: Tue Nov 03 2026 05:46:24 GMT+0530 (India Standard Time)
- result: 0 passed, 0 skipped, 67 failed

| task | phrase | verdict | observed | note |
| --- | --- | --- | --- | --- |
| app.youtube | open youtube | FAIL | no new trace entries | expected entry never appeared |
| app.chrome | open chrome | FAIL | no new trace entries | expected entry never appeared |
| app.gmail | open gmail | FAIL | no new trace entries | expected entry never appeared |
| app.camera | open camera | FAIL | no new trace entries | expected entry never appeared |
| app.messages | open messages | FAIL | no new trace entries | expected entry never appeared |
| app.settings | open settings | FAIL | no new trace entries | expected entry never appeared |
| app.playstore | open play store | FAIL | no new trace entries | expected entry never appeared |
| app.photos | open photos | FAIL | no new trace entries | expected entry never appeared |
| app.maps | open maps | FAIL | no new trace entries | expected entry never appeared |
| app.clock | open clock | FAIL | no new trace entries | expected entry never appeared |
| app.typo | opem youube | FAIL | no new trace entries | expected entry never appeared |
| app.hindi | यूट्यूब खोलो | FAIL | no new trace entries | expected entry never appeared |
| app.hinglish | kholo chrome | FAIL | no new trace entries | expected entry never appeared |
| app.unknown | open zzzqwerty | FAIL | no new trace entries | expected entry never appeared |
| search.web | search the web for weather | FAIL | no new trace entries | expected entry never appeared |
| search.lookup | look up weather | FAIL | no new trace entries | expected entry never appeared |
| search.url | go to example.com | FAIL | no new trace entries | expected entry never appeared |
| search.youtube | search lofi in youtube | FAIL | no new trace entries | expected entry never appeared |
| search.chrome | search weather in chrome | FAIL | no new trace entries | expected entry never appeared |
| search.screen | search this screen for weather | FAIL | no new trace entries | expected entry never appeared |
| search.hindi | क्रोम में मौसम खोजो | FAIL | no new trace entries | expected entry never appeared |
| search.hinglish | youtube mein lofi khojo | FAIL | no new trace entries | expected entry never appeared |
| screen.scrollDown | scroll down | FAIL | no new trace entries | expected entry never appeared |
| screen.scrollUp | scroll up | FAIL | no new trace entries | expected entry never appeared |
| screen.tap | tap battery | FAIL | no new trace entries | expected entry never appeared |
| screen.back | go back | FAIL | no new trace entries | expected entry never appeared |
| screen.recents | open recents | FAIL | no new trace entries | expected entry never appeared |
| screen.home | go home | FAIL | no new trace entries | expected entry never appeared |
| screen.read | read this screen | FAIL | no new trace entries | expected entry never appeared |
| screen.actions | what can i do here | FAIL | no new trace entries | expected entry never appeared |
| screen.screenshot | take a screenshot | FAIL | no new trace entries | expected entry never appeared |
| screen.readHindi | स्क्रीन पढ़ो | FAIL | no new trace entries | expected entry never appeared |
| screen.scrollHindi | नीचे स्क्रॉल करो | FAIL | no new trace entries | expected entry never appeared |
| screen.backHindi | पीछे जाओ | FAIL | no new trace entries | expected entry never appeared |
| sys.timer | set a timer for 5 minutes | FAIL | no new trace entries | expected entry never appeared |
| sys.timerHindi | पाँच मिनट का टाइमर लगाओ | FAIL | no new trace entries | expected entry never appeared |
| sys.alarm | set an alarm for 7:30 am | FAIL | no new trace entries | expected entry never appeared |
| sys.battery | battery status | FAIL | no new trace entries | expected entry never appeared |
| sys.batteryHindi | बैटरी बताओ | FAIL | no new trace entries | expected entry never appeared |
| sys.volumeUp | volume up | FAIL | no new trace entries | expected entry never appeared |
| sys.volumeDown | volume down | FAIL | no new trace entries | expected entry never appeared |
| sys.volumeHindi | आवाज़ बढ़ाओ | FAIL | no new trace entries | expected entry never appeared |
| sys.mute | mute | FAIL | no new trace entries | expected entry never appeared |
| sys.unmute | unmute | FAIL | no new trace entries | expected entry never appeared |
| sys.torchOn | turn on the flashlight | FAIL | no new trace entries | expected entry never appeared |
| sys.torchOff | turn off the flashlight | FAIL | no new trace entries | expected entry never appeared |
| sys.dndOn | turn on do not disturb | FAIL | no new trace entries | expected entry never appeared |
| sys.dndOff | turn off do not disturb | FAIL | no new trace entries | expected entry never appeared |
| sys.notifications | read notifications | FAIL | no new trace entries | expected entry never appeared |
| sys.clearNotifications | clear notifications | FAIL | no new trace entries | expected entry never appeared |
| comms.call | call pranav | FAIL | no new trace entries | expected entry never appeared |
| comms.chat | chat with pranav | FAIL | no new trace entries | expected entry never appeared |
| comms.chatOf | go to chat of pranav | FAIL | no new trace entries | expected entry never appeared |
| comms.chatHindi | प्रणव से चैट करो | FAIL | no new trace entries | expected entry never appeared |
| comms.message | message pranav saying I will be late | FAIL | no new trace entries | expected entry never appeared |
| comms.messageHindi | प्रणव को मैसेज भेजो कि मैं देर से आऊंगा | FAIL | no new trace entries | expected entry never appeared |
| comms.email | email test@example.com about lunch | FAIL | no new trace entries | expected entry never appeared |
| comms.calendar | create an event for team lunch | FAIL | no new trace entries | expected entry never appeared |
| comms.maps | navigate to chennai | FAIL | no new trace entries | expected entry never appeared |
| routine.save | save a routine called bench for open youtube | FAIL | no new trace entries | expected entry never appeared |
| routine.run | run routine bench | FAIL | no new trace entries | expected entry never appeared |
| routine.list | list routines | FAIL | no new trace entries | expected entry never appeared |
| routine.delete | delete routine bench | FAIL | no new trace entries | expected entry never appeared |
| follow.repeat | again | FAIL | no new trace entries | expected entry never appeared |
| follow.copy | copy that | FAIL | no new trace entries | expected entry never appeared |
| agent.chain | open chrome and search for weather | FAIL | no new trace entries | expected entry never appeared |
| agent.youtube | open youtube and search for lofi | FAIL | no new trace entries | expected entry never appeared |

## Skipped by design
- dictation into a caret: needs a human-focused editable field; covered by the chat draft flow and unit tests
- ask/translate screen: needs a configured AI endpoint; the gate is unit-tested
- screen capture / OCR: requires per-session MediaProjection consent by design
- Telegram/Signal/Instagram contact chats: no deterministic deep link, documented unsupported
- placing calls and sending messages: Your Bubble stops at the dialer/draft by design
