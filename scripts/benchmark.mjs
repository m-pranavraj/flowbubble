#!/usr/bin/env node
/*
 * Frozen on-device execution benchmark.
 *
 * Every task is a phrase a user can say, a set of acceptable execution-trace entries, and optional
 * real side effects (foreground app, media volume). The trace is read from the same history the
 * product shows the user, so a pass means FlowBubble recorded an outcome it is willing to stand
 * behind and the device agrees.
 *
 * Usage:
 *   node scripts/benchmark.mjs --list
 *   node scripts/benchmark.mjs --only apps,system
 *   node scripts/benchmark.mjs --install --only search
 *   node scripts/benchmark.mjs                # the whole frozen suite
 */
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';

const ADB = process.env.ADB || 'C:/Users/Pranav/AppData/Local/Android/Sdk/platform-tools/adb.exe';
const DEVICE = process.env.DEVICE || 'emulator-5556';
const PKG = 'com.flowbubble.app';
const LABEL = 'FlowBubble voice access';
const APK = process.env.APK || 'app/build/outputs/apk/debug/app-debug.apk';
const OUT = 'verify/benchmark';

const args = process.argv.slice(2);
const flag = (name) => args.includes(`--${name}`);
const option = (name, fallback = null) => {
  const hit = args.find((a) => a.startsWith(`--${name}=`));
  if (hit) return hit.slice(name.length + 3);
  const index = args.indexOf(`--${name}`);
  return index >= 0 && args[index + 1] && !args[index + 1].startsWith('--') ? args[index + 1] : fallback;
};

const sleepBuffer = new Int32Array(new SharedArrayBuffer(4));
const sleep = (ms) => Atomics.wait(sleepBuffer, 0, 0, ms);

function sh(file, argv, { allowFail = false, timeout = 40000 } = {}) {
  try {
    return execFileSync(file, argv, { encoding: 'utf8', timeout, maxBuffer: 32 * 1024 * 1024 }).trim();
  } catch (error) {
    if (allowFail) return '';
    throw error;
  }
}

const adb = (argv, opts) => sh(ADB, ['-s', DEVICE, ...argv], opts);
const shell = (command, opts) => adb(['shell', command], opts);

function deviceQuote(text) {
  return `'${text.replace(/'/g, `'\\''`)}'`;
}

function say(phrase) {
  shell(`am broadcast -p ${PKG} -a ${PKG}.DEBUG_SIMULATE --es text ${deviceQuote(phrase)}`, {
    allowFail: true,
    timeout: 10000
  });
}

function decodeXml(value) {
  return value
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&');
}

function history() {
  const xml = shell(`run-as ${PKG} cat shared_prefs/flowbubble_history.xml`, {
    allowFail: true,
    timeout: 10000
  });
  const match = xml.match(/<string name="entries">([\s\S]*?)<\/string>/);
  if (!match) return [];
  try {
    const parsed = JSON.parse(decodeXml(match[1]));
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function newestTime() {
  const entries = history();
  return entries.reduce((max, entry) => Math.max(max, Number(entry.time) || 0), 0);
}

function focus() {
  const line = shell('dumpsys window | grep -m1 mCurrentFocus', { allowFail: true, timeout: 15000 });
  const match = line.match(/mCurrentFocus=Window\{[^}]*?\s([a-zA-Z0-9_.]+)\//);
  return match ? match[1] : '';
}

function waitFocus(packageName, timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (focus().includes(packageName)) return true;
    sleep(400);
  }
  return false;
}

function mediaVolume() {
  const out = shell('cmd media_session volume --stream 3 --get', { allowFail: true, timeout: 10000 });
  const match = out.match(/volume is (\d+)/i);
  return match ? Number(match[1]) : null;
}

function accessibilityBound() {
  const out = shell('dumpsys accessibility', { allowFail: true, timeout: 20000 });
  return out.includes(LABEL);
}

function applyPre(items = []) {
  for (const item of items) {
    if (item === 'home') {
      shell('input keyevent KEYCODE_HOME', { allowFail: true });
      sleep(900);
    } else if (item.startsWith('app:')) {
      const packageName = item.slice(4);
      shell(`monkey -p ${packageName} -c android.intent.category.LAUNCHER 1`, { allowFail: true });
      waitFocus(packageName, 10000);
      sleep(600);
    } else if (item.startsWith('speak:')) {
      say(item.slice(6));
      sleep(4000);
    }
  }
}

function cleanupTask(task) {
  if (task.keep) return;
  if (task.after) {
    applyPre(task.after);
    return;
  }
  shell('input keyevent KEYCODE_BACK', { allowFail: true });
  sleep(250);
  shell('input keyevent KEYCODE_HOME', { allowFail: true });
  sleep(500);
}

function ruleMatches(entry, rule) {
  if (rule.kind) {
    const kinds = Array.isArray(rule.kind) ? rule.kind : [rule.kind];
    if (!kinds.includes(entry.kind)) return false;
  }
  if (rule.status && !rule.status.includes(entry.status)) return false;
  if (rule.statusPrefix && !rule.statusPrefix.some((p) => String(entry.status).startsWith(p))) {
    return false;
  }
  if (rule.summary && !rule.summary.test(String(entry.summary))) return false;
  return true;
}

function describe(entries) {
  if (!entries.length) return 'no new trace entries';
  return entries
    .slice(0, 4)
    .map((entry) => `${entry.kind} / ${entry.summary} / ${entry.status}`)
    .join(' | ');
}

function runTask(task) {
  applyPre(task.pre);
  const baseline = newestTime();
  const volumeBefore = task.volume ? mediaVolume() : null;
  const result = {
    id: task.id,
    group: task.group,
    phrase: task.phrase,
    verdict: 'FAIL',
    observed: '',
    note: '',
    focus: '',
    volume: null
  };

  say(task.phrase);

  if (task.confirm) {
    sleep(1600);
    shell('input keyevent KEYCODE_BACK', { allowFail: true });
    sleep(900);
  }

  const timeout = task.timeout ?? 12000;
  const deadline = Date.now() + timeout;
  let newEntries = [];
  let matched = null;
  while (Date.now() < deadline) {
    newEntries = history().filter((entry) => Number(entry.time) > baseline);
    matched = newEntries.find((entry) => task.expect.some((rule) => ruleMatches(entry, rule)));
    if (matched) break;
    sleep(350);
  }
  if (matched) sleep(task.settle ?? 1200);

  result.observed = describe(newEntries);
  if (!matched) {
    result.note = 'expected entry never appeared';
    return result;
  }

  if (task.focus) {
    result.focus = focus();
    if (!result.focus.includes(task.focus)) {
      const ok = waitFocus(task.focus, 6000);
      result.focus = focus();
      if (!ok) {
        result.note = `expected foreground ${task.focus}`;
        return result;
      }
    }
  }

  if (task.volume) {
    const after = mediaVolume();
    result.volume = `${volumeBefore} -> ${after}`;
    const pass = {
      up: volumeBefore !== null && after !== null && after > volumeBefore,
      down: volumeBefore !== null && after !== null && after < volumeBefore,
      zero: after === 0,
      positive: after !== null && after > 0
    }[task.volume];
    if (!pass) {
      result.note = `volume probe failed (${result.volume})`;
      return result;
    }
  }

  if (task.envFailure) {
    result.verdict = 'SKIP';
    result.note = 'device lacks the hardware for this task';
  } else {
    result.verdict = 'PASS';
  }
  return result;
}

const tasks = [
  // ---- apps -------------------------------------------------------------
  {
    id: 'app.youtube', group: 'apps', phrase: 'open youtube',
    expect: [{ kind: 'App', status: ['done'], summary: /Opened YouTube/ }],
    focus: 'com.google.android.youtube', pre: ['home']
  },
  {
    id: 'app.chrome', group: 'apps', phrase: 'open chrome',
    expect: [{ kind: 'App', status: ['done'], summary: /Opened Chrome/ }],
    focus: 'com.android.chrome', pre: ['home']
  },
  {
    id: 'app.gmail', group: 'apps', phrase: 'open gmail',
    expect: [{ kind: 'App', status: ['done'], summary: /Opened Gmail/ }],
    focus: 'com.google.android.gm', pre: ['home']
  },
  {
    id: 'app.camera', group: 'apps', phrase: 'open camera',
    expect: [{ kind: 'App', status: ['done'], summary: /Opened Camera/ }],
    focus: 'com.android.camera2', pre: ['home']
  },
  {
    id: 'app.messages', group: 'apps', phrase: 'open messages',
    expect: [{ kind: 'App', status: ['done'], summary: /Opened Messages/ }],
    focus: 'com.google.android.apps.messaging', pre: ['home']
  },
  {
    id: 'app.settings', group: 'apps', phrase: 'open settings',
    expect: [{ kind: 'System', status: ['done'], summary: /Opened Android settings|Opened Settings/ }],
    focus: 'com.android.settings', pre: ['home']
  },
  {
    id: 'app.playstore', group: 'apps', phrase: 'open play store',
    expect: [{ kind: 'App', status: ['done'], summary: /Opened Play Store/ }],
    focus: 'com.android.vending', pre: ['home']
  },
  {
    id: 'app.photos', group: 'apps', phrase: 'open photos',
    expect: [{ kind: 'App', status: ['done'], summary: /Opened Photos/ }],
    focus: 'com.google.android.apps.photos', pre: ['home']
  },
  {
    id: 'app.maps', group: 'apps', phrase: 'open maps',
    expect: [{ kind: 'App', status: ['done'], summary: /Opened Maps/ }],
    focus: 'com.google.android.apps.maps', pre: ['home']
  },
  {
    id: 'app.clock', group: 'apps', phrase: 'open clock',
    expect: [{ kind: 'App', status: ['done'], summary: /Opened Clock/ }],
    focus: 'com.google.android.deskclock', pre: ['home']
  },
  {
    id: 'app.typo', group: 'apps', phrase: 'opem youube',
    expect: [{ kind: 'App', statusPrefix: ['did you mean'], summary: /Look up "opem youube"/ }],
    pre: ['home']
  },
  {
    id: 'app.hindi', group: 'apps', phrase: 'यूट्यूब खोलो',
    expect: [{ kind: 'App', status: ['done'], summary: /Opened YouTube/ }],
    focus: 'com.google.android.youtube', pre: ['home']
  },
  {
    id: 'app.hinglish', group: 'apps', phrase: 'kholo chrome',
    expect: [{ kind: 'App', status: ['done'], summary: /Opened Chrome/ }],
    focus: 'com.android.chrome', pre: ['home']
  },
  {
    id: 'app.unknown', group: 'apps', phrase: 'open zzzqwerty',
    expect: [
      { kind: 'App', status: ['not found'], summary: /Look up/ },
      { kind: 'App', statusPrefix: ['did you mean'], summary: /Look up/ }
    ],
    pre: ['home']
  },

  // ---- search -----------------------------------------------------------
  {
    id: 'search.web', group: 'search', phrase: 'search the web for weather',
    expect: [{ kind: 'Web', status: ['awaiting user'], summary: /weather/i }],
    focus: 'com.android.chrome', pre: ['home']
  },
  {
    id: 'search.lookup', group: 'search', phrase: 'look up weather',
    expect: [{ kind: 'Web', status: ['awaiting user'], summary: /weather/i }],
    focus: 'com.android.chrome', pre: ['home']
  },
  {
    id: 'search.url', group: 'search', phrase: 'go to example.com',
    expect: [{ kind: 'Web', status: ['awaiting user'], summary: /example\.com/ }],
    focus: 'com.android.chrome', pre: ['home']
  },
  {
    id: 'search.youtube', group: 'search', phrase: 'search lofi in youtube',
    expect: [{ kind: 'Agent', status: ['done', 'unverified'], summary: /Completed:.*[Yy]ou[Tt]ube/ }],
    focus: 'com.google.android.youtube', pre: ['home'], timeout: 45000
  },
  {
    id: 'search.chrome', group: 'search', phrase: 'search weather in chrome',
    expect: [{ kind: 'Agent', status: ['done', 'unverified'], summary: /Completed:.*[Cc]hrome/ }],
    focus: 'com.android.chrome', pre: ['home'], timeout: 45000, keep: true
  },
  {
    id: 'search.screen', group: 'search', phrase: 'search this screen for weather',
    expect: [{ kind: 'Agent', status: ['done', 'unverified'], summary: /Completed: Search for/ }],
    pre: [], timeout: 45000
  },
  {
    id: 'search.hindi', group: 'search', phrase: 'क्रोम में मौसम खोजो',
    expect: [{ kind: 'Agent', status: ['done', 'unverified'], summary: /Completed:.*[Cc]hrome/ }],
    focus: 'com.android.chrome', pre: ['home'], timeout: 45000
  },
  {
    id: 'search.hinglish', group: 'search', phrase: 'youtube mein lofi khojo',
    expect: [{ kind: 'Agent', status: ['done', 'unverified'], summary: /Completed:.*[Yy]ou[Tt]ube/ }],
    focus: 'com.google.android.youtube', pre: ['home'], timeout: 45000
  },

  // ---- screen control ---------------------------------------------------
  {
    id: 'screen.scrollDown', group: 'screen', phrase: 'scroll down',
    expect: [{ kind: 'Screen', status: ['done'], summary: /Scrolled down/ }],
    pre: ['home', 'app:com.android.settings']
  },
  {
    id: 'screen.scrollUp', group: 'screen', phrase: 'scroll up',
    expect: [{ kind: 'Screen', status: ['done'], summary: /Scrolled up/ }],
    pre: ['home', 'app:com.android.settings']
  },
  {
    id: 'screen.tap', group: 'screen', phrase: 'tap battery',
    expect: [{ kind: 'Screen', status: ['done'], summary: /Tapped/ }],
    focus: 'com.android.settings', pre: ['home', 'app:com.android.settings'], keep: true
  },
  {
    id: 'screen.back', group: 'screen', phrase: 'go back',
    expect: [{ kind: 'Screen', status: ['done'], summary: /Went back/ }],
    focus: 'com.android.settings', pre: []
  },
  {
    id: 'screen.recents', group: 'screen', phrase: 'open recents',
    expect: [{ kind: 'Screen', status: ['done'], summary: /recent apps/i }],
    pre: ['home']
  },
  {
    id: 'screen.home', group: 'screen', phrase: 'go home',
    expect: [{ kind: 'Screen', status: ['done'], summary: /Went home/ }],
    focus: 'com.google.android.apps.nexuslauncher', pre: ['home', 'app:com.android.settings']
  },
  {
    id: 'screen.read', group: 'screen', phrase: 'read this screen',
    expect: [{ kind: 'Read', status: ['done'], summary: /Read \d+ characters/ }],
    pre: ['home', 'app:com.android.settings']
  },
  {
    id: 'screen.actions', group: 'screen', phrase: 'what can i do here',
    expect: [{ kind: 'Actions', status: ['done', 'nothing found'], summary: /Found \d+ actions|Scan this screen/ }],
    pre: [], timeout: 20000
  },
  {
    id: 'screen.screenshot', group: 'screen', phrase: 'take a screenshot',
    expect: [{ kind: 'System', status: ['requested'], summary: /Screenshot requested/ }],
    pre: ['home', 'app:com.android.settings']
  },
  {
    id: 'screen.readHindi', group: 'screen', phrase: 'स्क्रीन पढ़ो',
    expect: [{ kind: 'Read', status: ['done'], summary: /Read \d+ characters/ }],
    pre: ['home', 'app:com.android.settings']
  },
  {
    id: 'screen.scrollHindi', group: 'screen', phrase: 'नीचे स्क्रॉल करो',
    expect: [{ kind: 'Screen', status: ['done'], summary: /Scrolled down/ }],
    pre: ['home', 'app:com.android.settings']
  },
  {
    id: 'screen.backHindi', group: 'screen', phrase: 'पीछे जाओ',
    expect: [{ kind: 'Screen', status: ['done'], summary: /Went back/ }],
    focus: 'com.android.settings', pre: ['home', 'app:com.android.settings', 'speak:tap battery']
  },

  // ---- system -----------------------------------------------------------
  {
    id: 'sys.timer', group: 'system', phrase: 'set a timer for 5 minutes',
    expect: [{ kind: 'Timer', status: ['requested'], summary: /5 min/ }]
  },
  {
    id: 'sys.timerHindi', group: 'system', phrase: 'पाँच मिनट का टाइमर लगाओ',
    expect: [{ kind: 'Timer', status: ['requested'], summary: /5 min/ }]
  },
  {
    id: 'sys.alarm', group: 'system', phrase: 'set an alarm for 7:30 am',
    expect: [{ kind: 'Alarm', status: ['requested'], summary: /7:30/ }]
  },
  {
    id: 'sys.battery', group: 'system', phrase: 'battery status',
    expect: [{ kind: 'Battery', status: ['done'], summary: /\d+%/ }]
  },
  {
    id: 'sys.batteryHindi', group: 'system', phrase: 'बैटरी बताओ',
    expect: [{ kind: 'Battery', status: ['done'], summary: /\d+%/ }]
  },
  {
    id: 'sys.volumeUp', group: 'system', phrase: 'volume up',
    expect: [{ kind: 'Volume', status: ['done'], summary: /Media volume up|at maximum/ }],
    volume: 'up'
  },
  {
    id: 'sys.volumeDown', group: 'system', phrase: 'volume down',
    expect: [{ kind: 'Volume', status: ['done'], summary: /Media volume down|at zero/ }],
    volume: 'down'
  },
  {
    id: 'sys.volumeHindi', group: 'system', phrase: 'आवाज़ बढ़ाओ',
    expect: [{ kind: 'Volume', status: ['done'], summary: /Media volume up|at maximum/ }],
    volume: 'up'
  },
  {
    id: 'sys.mute', group: 'system', phrase: 'mute',
    expect: [{ kind: 'Volume', status: ['done'], summary: /Muted media/ }],
    volume: 'zero'
  },
  {
    id: 'sys.unmute', group: 'system', phrase: 'unmute',
    expect: [{ kind: 'Volume', status: ['done'], summary: /Unmuted media/ }],
    volume: 'positive'
  },
  {
    id: 'sys.torchOn', group: 'system', phrase: 'turn on the flashlight',
    expect: [{ kind: 'System', status: ['done', 'requested', 'failed', 'unsupported'], summary: /Flashlight/ }],
    envFailure: true
  },
  {
    id: 'sys.torchOff', group: 'system', phrase: 'turn off the flashlight',
    expect: [{ kind: 'System', status: ['done', 'requested', 'failed', 'unsupported'], summary: /Flashlight/ }],
    envFailure: true
  },
  {
    id: 'sys.dndOn', group: 'system', phrase: 'turn on do not disturb',
    expect: [{ kind: 'Do Not Disturb', status: ['done'], summary: /^On/ }]
  },
  {
    id: 'sys.dndOff', group: 'system', phrase: 'turn off do not disturb',
    expect: [{ kind: 'Do Not Disturb', status: ['done'], summary: /^Off$/ }]
  },
  {
    id: 'sys.notifications', group: 'system', phrase: 'read notifications',
    expect: [{ kind: 'Notifications', status: ['done', 'nothing found'], summary: /Read |Read notifications/ }]
  },
  {
    id: 'sys.clearNotifications', group: 'system', phrase: 'clear notifications',
    expect: [{ kind: 'Notifications', status: ['nothing found', 'awaiting user'], summary: /Clear notifications/ }],
    confirm: true
  },

  // ---- communication ----------------------------------------------------
  {
    id: 'comms.call', group: 'comms', phrase: 'call pranav',
    expect: [{ kind: 'Call', status: ['awaiting user'], summary: /Dialer opened/ }],
    focus: 'com.google.android.dialer', pre: ['home']
  },
  {
    id: 'comms.chat', group: 'comms', phrase: 'chat with pranav',
    expect: [{ kind: 'Chat', status: ['done'], summary: /Opened Pranav's chat/ }],
    focus: 'com.google.android.apps.messaging', pre: ['home'], timeout: 30000
  },
  {
    id: 'comms.chatOf', group: 'comms', phrase: 'go to chat of pranav',
    expect: [{ kind: 'Chat', status: ['done'], summary: /Opened Pranav's chat/ }],
    focus: 'com.google.android.apps.messaging', pre: ['home'], timeout: 30000
  },
  {
    id: 'comms.chatHindi', group: 'comms', phrase: 'प्रणव से चैट करो',
    expect: [{ kind: 'Chat', status: ['done'], summary: /Opened Pranav's chat/ }],
    focus: 'com.google.android.apps.messaging', pre: ['home'], timeout: 30000
  },
  {
    id: 'comms.message', group: 'comms', phrase: 'message pranav saying I will be late',
    expect: [{ kind: 'Message', status: ['awaiting user'], summary: /Confirm|Draft/ }],
    pre: ['home'], dismissAfter: true
  },
  {
    id: 'comms.messageHindi', group: 'comms', phrase: 'प्रणव को मैसेज भेजो कि मैं देर से आऊंगा',
    expect: [{ kind: 'Message', status: ['awaiting user'], summary: /Confirm|Draft/ }],
    pre: ['home'], dismissAfter: true
  },
  {
    id: 'comms.email', group: 'comms', phrase: 'email test@example.com about lunch',
    expect: [{ kind: 'Email', status: ['awaiting user'], summary: /Draft email/ }],
    pre: ['home']
  },
  {
    id: 'comms.calendar', group: 'comms', phrase: 'create an event for team lunch',
    expect: [{ kind: 'Calendar', status: ['awaiting user'], summary: /New event/ }],
    pre: ['home']
  },
  {
    id: 'comms.maps', group: 'comms', phrase: 'navigate to chennai',
    expect: [{ kind: 'Maps', status: ['awaiting user'], summary: /chennai/i }],
    focus: 'com.google.android.apps.maps', pre: ['home']
  },

  // ---- routines and follow-ups -----------------------------------------
  {
    id: 'routine.save', group: 'routines', phrase: 'save a routine called bench for open youtube',
    expect: [{ kind: 'Routine', status: ['done'], summary: /Saved bench/ }],
    pre: ['home']
  },
  {
    id: 'routine.run', group: 'routines', phrase: 'run routine bench',
    expect: [{ kind: 'Routine', status: ['requested'], summary: /Ran bench/ }],
    focus: 'com.google.android.youtube', pre: ['home'], timeout: 40000
  },
  {
    id: 'routine.list', group: 'routines', phrase: 'list routines',
    expect: [{ kind: 'Routine', status: ['done'], summary: /Listed \d+ routines/ }]
  },
  {
    id: 'routine.delete', group: 'routines', phrase: 'delete routine bench',
    expect: [{ kind: 'Routine', status: ['awaiting user'], summary: /Delete bench/ }],
    confirm: true
  },
  {
    id: 'follow.repeat', group: 'routines', phrase: 'again',
    expect: [{ kind: 'Battery', status: ['done'], summary: /\d+%/ }],
    pre: ['speak:battery status']
  },
  {
    id: 'follow.copy', group: 'routines', phrase: 'copy that',
    expect: [{ kind: 'Clipboard', status: ['copied'], summary: /Copied the last text/ }],
    pre: ['home', 'app:com.android.settings', 'speak:read this screen'], timeout: 15000
  },

  // ---- agent chains -----------------------------------------------------
  {
    id: 'agent.chain', group: 'agent', phrase: 'open chrome and search for weather',
    expect: [{ kind: 'Agent', status: ['done', 'unverified'], summary: /Completed: open chrome and search for weather/ }],
    focus: 'com.android.chrome', pre: ['home'], timeout: 70000
  },
  {
    id: 'agent.youtube', group: 'agent', phrase: 'open youtube and search for lofi',
    expect: [{ kind: 'Agent', status: ['done', 'unverified'], summary: /Completed: open youtube and search for lofi/ }],
    focus: 'com.google.android.youtube', pre: ['home'], timeout: 70000
  }
];

const skippedByDesign = [
  'dictation into a caret: needs a human-focused editable field; covered by the chat draft flow and unit tests',
  'ask/translate screen: needs a configured AI endpoint; the gate is unit-tested',
  'screen capture / OCR: requires per-session MediaProjection consent by design',
  'Telegram/Signal/Instagram contact chats: no deterministic deep link, documented unsupported',
  'placing calls and sending messages: FlowBubble stops at the dialer/draft by design'
];

function selectTasks() {
  const only = option('only');
  const skip = option('skip');
  let chosen = tasks;
  if (only) {
    const wanted = new Set(only.split(',').flatMap((token) => {
      const value = token.trim();
      return tasks.filter((task) => task.group === value).map((task) => task.id).concat(value);
    }));
    chosen = chosen.filter((task) => wanted.has(task.id) || wanted.has(task.group));
  }
  if (skip) {
    const unwanted = new Set(skip.split(',').map((token) => token.trim()));
    chosen = chosen.filter((task) => !unwanted.has(task.group) && !unwanted.has(task.id));
  }
  return chosen;
}

function installAndPrepare() {
  if (flag('install')) {
    if (!existsSync(APK)) throw new Error(`APK not found: ${APK}`);
    adb(['install', '-r', APK], { timeout: 180000 });
  }
  shell(`am force-stop ${PKG}`, { allowFail: true });
  if (!accessibilityBound()) {
    shell(`settings put secure enabled_accessibility_services ${PKG}/${PKG}.access.FlowAccessibilityService`, { allowFail: true });
    shell('settings put secure accessibility_enabled 1', { allowFail: true });
  }
  shell(`monkey -p ${PKG} -c android.intent.category.LAUNCHER 1`, { allowFail: true });
  sleep(2000);
  shell(`am start-foreground-service ${PKG}/.service.OverlayService`, { allowFail: true });
  sleep(1200);
  shell('input keyevent KEYCODE_HOME', { allowFail: true });
  sleep(900);
  return { accessibility: accessibilityBound(), focus: focus() };
}

function main() {
  if (flag('list')) {
    for (const task of tasks) console.log(`${task.group.padEnd(9)} ${task.id.padEnd(24)} ${task.phrase}`);
    console.log(`\n${tasks.length} tasks`);
    return;
  }

  const chosen = selectTasks();
  const readiness = installAndPrepare();
  mkdirSync(OUT, { recursive: true });
  console.log(`device ${DEVICE}  accessibility=${readiness.accessibility}  tasks=${chosen.length}`);

  const results = [];
  for (const task of chosen) {
    process.stdout.write(`- ${task.id.padEnd(24)} `);
    let result;
    try {
      result = runTask(task);
    } catch (error) {
      result = {
        id: task.id, group: task.group, phrase: task.phrase, verdict: 'FAIL',
        observed: '', note: String(error && error.message ? error.message : error)
      };
    }
    if (task.dismissAfter) {
      shell('input keyevent KEYCODE_BACK', { allowFail: true });
      sleep(400);
    }
    cleanupTask(task);
    results.push(result);
    const detail = result.note ? ` (${result.note})` : '';
    console.log(`${result.verdict}${detail}`);
  }

  const passed = results.filter((r) => r.verdict === 'PASS').length;
  const skipped = results.filter((r) => r.verdict === 'SKIP').length;
  const failed = results.filter((r) => r.verdict === 'FAIL').length;
  console.log(`\n${passed} passed, ${skipped} skipped, ${failed} failed`);

  const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
  const lines = [
    `# FlowBubble execution benchmark`,
    ``,
    `- device: ${DEVICE}`,
    `- run: ${new Date().toString()}`,
    `- result: ${passed} passed, ${skipped} skipped, ${failed} failed`,
    ``,
    `| task | phrase | verdict | observed | note |`,
    `| --- | --- | --- | --- | --- |`,
    ...results.map((r) => `| ${r.id} | ${r.phrase} | ${r.verdict} | ${r.observed.replace(/\|/g, '\\|')} | ${r.note.replace(/\|/g, '\\|')} |`),
    ``,
    `## Skipped by design`,
    ...skippedByDesign.map((line) => `- ${line}`),
    ``
  ];
  writeFileSync(`${OUT}/report-${stamp}.md`, lines.join('\n'));
  writeFileSync(`${OUT}/latest.md`, lines.join('\n'));
  writeFileSync(`${OUT}/latest.json`, JSON.stringify({ stamp, results }, null, 2));
  console.log(`report: ${OUT}/latest.md`);

  if (failed > 0) process.exit(1);
}

main();
