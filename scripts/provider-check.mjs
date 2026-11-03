/*
 * Provider compatibility check.
 *
 * Your Bubble now talks to any OpenAI-compatible endpoint, so the release has to be proven against a
 * server that is not OpenAI: no key, plain http, a path the client assembles itself, and a response
 * the app has to parse. This script starts such a server on the workstation, configures profiles
 * through the app's own Settings screen, and reads back what the server actually received.
 *
 * Run against a debug build (the transcript receiver is debug-only):
 *   node scripts/provider-check.mjs
 * Environment: DEVICE (default emulator-5554), PORT (default 8971).
 */
import { execFileSync, spawn } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';

const ADB = 'C:/Users/Pranav/AppData/Local/Android/Sdk/platform-tools/adb.exe';
const DEVICE = process.env.DEVICE || 'emulator-5554';
const PKG = 'com.flowbubble.app';
const PORT = Number(process.env.PORT || 8971);
const ENDPOINT = `http://10.0.2.2:${PORT}/v1`;
const LABEL = 'Your Bubble voice access';

/* The version is declared once, in the Gradle file, so the User-Agent assertion and the install hint
 * below cannot go stale on the next release the way a pinned "1.0.2" did. */
const VERSION = readFileSync('app/build.gradle.kts', 'utf8').match(/versionName = "([^"]+)"/)?.[1] ?? 'unknown';

const sleepBuffer = new Int32Array(new SharedArrayBuffer(4));
const sleep = (ms) => Atomics.wait(sleepBuffer, 0, 0, ms);

function sh(argv, { allowFail = false, timeout = 40000 } = {}) {
  try {
    return execFileSync(ADB, ['-s', DEVICE, ...argv], {
      encoding: 'utf8',
      timeout,
      maxBuffer: 32 * 1024 * 1024
    }).trim();
  } catch {
    if (allowFail) return '';
    throw new Error(`adb ${argv.join(' ')} failed`);
  }
}
const shell = (command, opts) => sh(['shell', command], opts);
const deviceQuote = (text) => `'${text.replace(/'/g, `'\\''`)}'`;
const screen = (name) =>
  shell(`am start -n ${PKG}/.MainActivity --es ${PKG}.extra.SCREEN ${name}`, { allowFail: true, timeout: 20000 });

/*
 * A cold start is still drawing when the intent lands, so the first dump after `screen` can return
 * the previous screen. Every tap the harness then makes is aimed at a node that is not on the device,
 * and the run reports a broken UI when what happened is a race. The Settings column is re-asked for
 * and polled until its own card is actually on it, and the tree that proved it gets handed back.
 */
function showSettings(attempts = 10) {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    const xml = dumpUi();
    if (first(xml, 'AI MODELS')) return xml;
    screen('settings');
    sleep(1200);
  }
  return '';
}

/*
 * uiautomator intermittently answers "ERROR: null root node returned by UiTestAutomationBridge"
 * during a window transition, and "ERROR: could not get idle state" while the companion overlay is
 * redrawing. In both cases the file it was writing keeps whatever the previous dump put there, so a
 * retry that only reads the file can hand back a different screen and the harness then taps nodes that
 * are not on it. Deleting the file first turns a failed dump into a missing file — which the length
 * check below already treats as a retry — instead of a stale tree that looks fresh.
 */
function dumpUi(attempts = 5) {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    shell('rm -f /sdcard/provider-ui.xml', { allowFail: true, timeout: 15000 });
    shell('uiautomator dump /sdcard/provider-ui.xml', { allowFail: true, timeout: 25000 });
    const xml = shell('cat /sdcard/provider-ui.xml', { allowFail: true, timeout: 25000 });
    if (xml.includes('<node') && xml.length > 800) return xml;
    sleep(900);
  }
  return '';
}

function decode(value) {
  return value
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#39;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&');
}

function screenSize() {
  const m = shell('wm size', { allowFail: true }).match(/(\d+)x(\d+)/);
  return { w: m ? +m[1] : 1080, h: m ? +m[2] : 2400 };
}

/*
 * A card the companion shows is its own window, and `uiautomator dump` only walks the active window,
 * so the harness cannot see one — but it can see its touchable rectangle in the window list, and that
 * rectangle is what ate every gesture in the lower third of the screen while a run was stuck. The
 * panel window is the app's overlay with no title: the activity is named `…/MainActivity` and the
 * bubble is named `Your Bubble`, so a bare `com.flowbubble.app,` in the visible list is the card.
 */
function panelBand() {
  const text = shell('dumpsys window windows', { allowFail: true, timeout: 25000 });
  const line = text.split('\n').find((entry) => entry.includes('visible windows:')) ?? '';
  const m = line.match(/[0-9a-f]+ com\.flowbubble\.app, frame=\[Rect\((\d+),(\d+) - (\d+),(\d+)\)\]/);
  if (!m) return null;
  return { top: +m[2], bottom: +m[4], height: +m[4] - +m[2] };
}

/** Wait for a card that is on screen to close itself, because nothing below it is touchable until it does. */
function awaitPanelClear(timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const band = panelBand();
    if (!band) return true;
    sleep(1500);
  }
  const stuck = panelBand();
  console.log(`   [panel] a card is still covering y=${stuck?.top}–${stuck?.bottom} after ${timeoutMs}ms`);
  return false;
}

/** Every node whose text contains the needle, top to bottom. */
function matches(xml, needle) {
  const found = [];
  for (const node of xml.split('<node')) {
    const text = node.match(/text="([^"]*)"/)?.[1];
    if (text === undefined || !decode(text).includes(needle)) continue;
    const bounds = node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    if (!bounds) continue;
    found.push({
      text: decode(text),
      x: Math.round((+bounds[1] + +bounds[3]) / 2),
      y: Math.round((+bounds[2] + +bounds[4]) / 2)
    });
  }
  return found.sort((a, b) => a.y - b.y);
}

const first = (xml, needle) => matches(xml, needle)[0] ?? null;

/** The first node containing needle that sits below another node, so identical rows can be told apart. */
function below(xml, anchor, needle) {
  const anchorNode = first(xml, anchor);
  if (!anchorNode) return null;
  return matches(xml, needle).find((node) => node.y > anchorNode.y) ?? null;
}

/*
 * A check that cannot find a node says nothing about what was on the screen, so a wrong needle, a
 * stale tree and a control the app genuinely does not show all print the same line. Whatever is
 * readable gets printed when a finder gives up and when a check fails — and the whole tree, because
 * a short list cuts off exactly the control that is missing.
 */
const screenTexts = (limit = 60) =>
  matches(dumpUi(), '')
    .map((node) => node.text.replace(/\s+/g, ' ').trim().slice(0, 40))
    .filter((text) => text.length > 0)
    .slice(0, limit)
    .join(' | ') || 'nothing readable';

const gaveUp = (what) => console.log(`   [harness] no ${what} — on screen: ${screenTexts()}`);

function tap(node, waitMs = 900) {
  if (!node) return false;
  shell(`input tap ${node.x} ${node.y}`, { allowFail: true });
  sleep(waitMs);
  return true;
}

/*
 * Typing raises the soft keyboard, which covers the bottom half of the screen — and the scroll
 * gesture below starts inside that region, so the swipe lands on the keyboard and scrolls nothing.
 * The fields under it are then not composed at all, and a control the app really has, "Save model",
 * is missing from the tree. A back press with the keyboard up closes only the keyboard, so every
 * typed value ends by putting the column back; it is guarded on the keyboard actually being shown,
 * because with nothing up the same key would leave the Settings screen.
 */
function hideKeyboard() {
  const shown = shell('dumpsys input_method', { allowFail: true, timeout: 20000 });
  if (!shown.includes('mInputShown=true')) return false;
  shell('input keyevent KEYCODE_BACK', { allowFail: true });
  sleep(900);
  return true;
}

/** Types with the device's own key injection so the app's field really receives the text. */
function type(value) {
  shell(`input text ${deviceQuote(value.replace(/ /g, '%s'))}`, { allowFail: true, timeout: 20000 });
  sleep(800);
  hideKeyboard();
}

/*
 * A gesture the screen does not answer is not a scroll. One run reported five missing controls in a
 * row while every dump came back byte-identical: the column had stopped moving, but nothing in the
 * script could tell a stale picture from a screen that genuinely has no such button, so it walked the
 * same view eight times per finder and the log read like a broken UI. Each scroll now compares what is
 * on the device before and after, dismisses a keyboard that is eating the gesture, wakes the screen,
 * and says out loud when the device will not move at all.
 */
const screenSignature = () => {
  const xml = dumpUi();
  if (xml.length <= 800) return '';
  /*
   * Both coordinates belong in the signature. A column that scrolled is exactly a screen whose nodes
   * keep their text and their x and change their y, so a signature without y calls a working scroll
   * frozen — and then this function's own recovery presses BACK, which is the opposite of helpful.
   */
  return matches(xml, '').map((node) => `${node.text}${node.x}${node.y}`).join('|');
};

function swipe(fromFraction, toFraction) {
  const { w, h } = screenSize();
  shell(
    `input swipe ${Math.round(w / 2)} ${Math.round(h * fromFraction)} ${Math.round(w / 2)} ${Math.round(h * toFraction)} 800`,
    { allowFail: true }
  );
  sleep(1100);
}

/*
 * A gesture that lands on another window never reaches the column under it, and only the card window
 * has its own reader. So when a scroll dies this asks the window manager which rectangles actually
 * cover the swipe path and prints them — a stuck keyboard, a companion card and a popup menu all look
 * identical to a script that can only read the active window's tree.
 */
function windowsOver(a, b) {
  const top = Math.min(a, b);
  const bottom = Math.max(a, b);
  const line = (shell('dumpsys window windows', { allowFail: true, timeout: 25000 })
    .split('\n')
    .find((entry) => entry.includes('visible windows:')) ?? '');
  const found = [];
  for (const m of line.matchAll(/([0-9a-f]+ [^,]+), frame=\[Rect\((\d+),(\d+) - (\d+),(\d+)\)\]/g)) {
    const [, name, , y1, , y2] = m;
    if (Number(y2) <= top || Number(y1) >= bottom) continue;
    found.push(`${name.trim().replace(/^com\.flowbubble\.app\//, '')} ${y1}–${y2}`);
  }
  return found.join(' | ') || 'nothing above the app window';
}

function scroll(fromFraction, toFraction, label) {
  const before = screenSignature();
  for (let attempt = 0; attempt < 3; attempt += 1) {
    swipe(fromFraction, toFraction);
    if (screenSignature() !== before) return true;
    if (hideKeyboard()) continue;
    shell('svc power stayon usb', { allowFail: true });
    shell('input keyevent KEYCODE_WAKEUP', { allowFail: true });
    sleep(1000);
  }
  const { h } = screenSize();
  console.log(
    `   [scroll] the ${label} gesture moved nothing across three tries — the screen is frozen where it was`
  );
  console.log(`   [scroll] windows over y=${Math.round(h * fromFraction)}–${Math.round(h * toFraction)}: ${windowsOver(h * fromFraction, h * toFraction)}`);
  return false;
}

const scrollDown = () => scroll(0.78, 0.22, 'down');
const scrollUp = () => scroll(0.22, 0.78, 'up');

/** Scrolls the settings column until a node appears, then returns it. */
function reveal(needle, { attempts = 8 } = {}) {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    const node = first(dumpUi(), needle);
    if (node) return node;
    scrollDown();
  }
  gaveUp(`"${needle}"`);
  return null;
}

const overlayRunning = () =>
  shell(`dumpsys activity services ${PKG}`, { allowFail: true, timeout: 20000 })
    .includes(`${PKG}/.service.OverlayService`);

function accessibilityBound() {
  const out = shell('dumpsys accessibility', { allowFail: true, timeout: 20000 });
  if (!out.includes(LABEL)) return false;
  const crashed = out.split('\n').find((line) => line.trim().startsWith('Crashed services:')) || '';
  return !crashed.includes(PKG);
}

/*
 * Reading a field and inserting text both go through the accessibility connection, so this harness
 * uses the same live probe the benchmark does: ask the running service which app is foreground now.
 */
function accessibilityLive(timeoutMs = 20000) {
  const probe = () => {
    shell('logcat -c', { allowFail: true });
    shell(`am broadcast -p ${PKG} -a ${PKG}.DEBUG_SIMULATE --es probe windows`, { allowFail: true, timeout: 15000 });
    sleep(1800);
    const log = sh(['shell', 'logcat -d -s FlowBubbleDebug'], { allowFail: true, timeout: 20000 });
    const m = log.match(/probe service=true active=(\S+) foreground=(\S+)/);
    return Boolean(m) && m[2] !== 'null';
  };
  if (!accessibilityBound()) return false;
  probe();
  shell('input keyevent KEYCODE_HOME', { allowFail: true });
  sleep(1000);
  shell('monkey -p com.google.android.deskclock 1', { allowFail: true });
  sleep(1500);
  const deadline = Date.now() + timeoutMs;
  let live = probe();
  while (!live && Date.now() < deadline) {
    shell('input keyevent KEYCODE_HOME', { allowFail: true });
    sleep(900);
    live = probe();
  }
  shell('input keyevent KEYCODE_HOME', { allowFail: true });
  return live;
}

function rebindAccessibility() {
  shell('settings delete secure --user 0 enabled_accessibility_services', { allowFail: true });
  sleep(3000);
  shell(`settings put secure --user 0 enabled_accessibility_services ${PKG}/${PKG}.access.FlowAccessibilityService`, { allowFail: true });
  shell('settings put secure --user 0 accessibility_enabled 1', { allowFail: true });
  sleep(6000);
}

function awaitAccessibilityEvents(attempts = 3) {
  shell(`monkey -p ${PKG} -c android.intent.category.LAUNCHER 1`, { allowFail: true });
  sleep(2000);
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    rebindAccessibility();
    if (accessibilityLive()) return true;
  }
  throw new Error(`device is not ready: ${LABEL} never delivered events after ${attempts} rebinds`);
}

function enableBubble() {
  if (overlayRunning()) return true;
  const { w, h } = screenSize();
  screen('home');
  sleep(2200);
  let setup = null;
  for (let attempt = 0; attempt < 6 && !setup; attempt += 1) {
    setup = first(dumpUi(), 'Setup');
    if (!setup) {
      screen('home');
      sleep(1500);
    }
  }
  tap(setup, 1300);
  for (let cycle = 0; cycle < 3; cycle += 1) {
    // Android can drop a settings-written accessibility binding while the app is running, and the
    // Setup screen then refuses the bubble. Re-assert the binding on every pass instead of scanning
    // a screen that cannot offer the switch.
    if (cycle > 0) rebindAccessibility();
    let target = null;
    for (let scan = 0; scan < 12 && !target; scan += 1) {
      const xml = dumpUi();
      target = first(xml, 'Turn on bubble');
      if (target) break;
      if (first(xml, 'Turn off bubble') || overlayRunning()) return true;
      if (!first(xml, 'Finish the required steps')) {
        shell(`input swipe ${Math.round(w / 2)} ${Math.round(h * 0.78)} ${Math.round(w / 2)} ${Math.round(h * 0.22)} 800`, { allowFail: true });
      }
      sleep(1500);
    }
    if (!target) continue;
    tap(target, 800);
    const deadline = Date.now() + 15000;
    while (Date.now() < deadline) {
      if (overlayRunning()) return true;
      sleep(700);
    }
  }
  return overlayRunning();
}

/* ---- the stand-in provider ---- */

/*
 * The endpoint runs as a second process for one reason: `listen()` is itself an event-loop job, and
 * this harness lives entirely inside blocking adb calls, so with the server in the same process the
 * socket was never bound — not once, in any run. Every "no request arrived" below was the harness
 * driving the app to knock on a port nothing was listening on, and reporting that as a product
 * result. Requests land in a JSON-lines file the blocking parent can read with readFileSync.
 */
const REQUESTS = 'verify/provider/requests.jsonl';
const seen = [];

/** Pulls whatever the endpoint has recorded so far into `seen`; returns how many there are. */
function syncSeen() {
  let text = '';
  try {
    text = readFileSync(REQUESTS, 'utf8');
  } catch {
    text = '';
  }
  const rows = text
    .split('\n')
    .filter(Boolean)
    .map((line) => {
      try {
        return JSON.parse(line);
      } catch {
        return null;
      }
    })
    .filter(Boolean);
  if (rows.length !== seen.length) {
    seen.length = 0;
    seen.push(...rows);
  }
  return seen.length;
}

const since = (index) => (syncSeen(), seen.slice(index));
const lastRequest = (index) => (syncSeen(), seen.slice(index).at(-1) ?? null);

function startMockProvider() {
  mkdirSync('verify/provider', { recursive: true });
  writeFileSync(REQUESTS, '');
  return spawn(process.execPath, ['scripts/mock-openai.mjs', String(PORT), REQUESTS], {
    stdio: ['ignore', 'inherit', 'inherit']
  });
}

/* ---- driving the app ---- */

const checks = [];
const record = (name, ok, detail = '') => {
  checks.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  ${detail}` : ''}`);
  /*
   * A failure with no detail is the worst kind of line in this report: it cannot be told apart from
   * a check that failed because the app is wrong, one that failed because a needle is wrong, and one
   * that failed because the dump was stale. The screen gets printed on the way out so the next read
   * of the log answers that without another run.
   */
  if (!ok) console.log(`   [screen] ${screenTexts()}`);
};

/*
 * The card's add button reads "Add a model" while no profile exists and "Add another model" once one
 * does, and node matching is substring based, so neither phrase finds the other. Both are tried, and
 * from the card header downward, because the card sits mid-column and the last scroll of the previous
 * check leaves it above or below the viewport at random.
 *
 * The editor also replaces the button in place, so an editor left open by an earlier check hides the
 * button entirely and every later step then taps nothing. It is dismissed before opening a new one.
 */
const ADD_MODEL_LABELS = ['Add a model', 'Add another model'];

const addModelNode = (xml) =>
  ADD_MODEL_LABELS.map((label) => below(xml, 'AI MODELS', label) ?? first(xml, label)).find(Boolean) ?? null;

function climbToCard() {
  for (let climb = 0; climb < 8 && !first(dumpUi(), 'AI MODELS'); climb += 1) scrollUp();
}

/*
 * The editor occupies the place the add button has, so one step that leaves it open makes every later
 * search for that button scroll to the bottom of Settings and tap nothing — which is how a single
 * missed role tap became five failures in a row. The scan therefore reads its own state as it goes:
 * the add button means closed, a Cancel means open, and Cancel is tapped and the card re-climbed.
 * Detection has to be by scrolling because both the button and the editor's own controls sit at the
 * very bottom edge, where the tree reports a clipped node or none at all.
 */
function openModelEditor() {
  for (let round = 0; round < 3; round += 1) {
    climbToCard();
    for (let scan = 0; scan < 8; scan += 1) {
      const xml = dumpUi();
      const node = addModelNode(xml);
      if (node) return node;
      const cancel = first(xml, 'Cancel');
      if (cancel) {
        tap(cancel, 1500);
        break;
      }
      scrollDown();
    }
  }
  gaveUp('add-model button');
  return null;
}

/*
 * The name alone cannot prove a save, because the field still holds it when validation refuses one.
 * A Test button under that name only exists on a row the app actually stored.
 */
function storedRow(name) {
  climbToCard();
  let node = null;
  for (let scan = 0; scan < 8 && !node; scan += 1) {
    const xml = dumpUi();
    if (first(xml, name)) node = below(xml, name, 'Test');
    if (!node) scrollDown();
  }
  if (!node) gaveUp(`stored row "${name}" with a Test button`);
  return node;
}

/** Adds a profile through the Settings UI; true only if the row appeared and nothing was sent. */
function addProfile({ name, baseUrl, model, apiKey }) {
  const before = syncSeen();
  if (!tap(openModelEditor(), 1300)) return false;
  if (!tap(reveal('Name'), 600)) return false;
  type(name);
  if (!tap(reveal('Base URL'), 600)) return false;
  type(baseUrl);
  if (!tap(reveal('Model'), 600)) return false;
  type(model);
  if (apiKey) {
    if (!tap(reveal('API key'), 600)) return false;
    type(apiKey);
  }
  /* Tapping a Save that was never found is the one way this function can report a clean run of a
   * profile it did not store, so the missing button is a reason to stop rather than to carry on. */
  const save = reveal('Save model');
  if (!save) return false;
  tap(save, 1800);
  return Boolean(storedRow(name)) && syncSeen() === before;
}

/*
 * What the app answers inside a profile row after Test is pressed. Matching is on lowercased node
 * text because the app writes its own success line ("Worked in 412ms: …"), its own status line
 * ("Custom endpoint returned HTTP 401: …") and OkHttp's failure verbatim ("failed to connect to
 * /10.0.2.2 (port 8971)", "unable to resolve host") — three different capitals in one column.
 */
const OUTCOME_MARKS = ['worked in', 'returned http', 'failed to connect', 'unable to resolve', 'timed out'];

/**
 * The top of the row that was pressed. The name line is what a row owns — "Asking Default mock…" and
 * "Worked in 45ms: OK" share the slot under it, so anchoring on the name keeps the transient status
 * line from being mistaken for the row's own top.
 */
function rowTop(xml, name, row) {
  const above = matches(xml, name).filter((node) => node.y <= row.y && node.text.startsWith(name));
  return above.length ? above[above.length - 1].y : -1;
}

/*
 * Each row keeps its own outcome, written between the profile's name and its buttons, so "the first
 * outcome on the screen" names whichever row sits highest — which is how a working 401 read as the
 * previous profile's success. An outcome only counts as this row's if it falls between the row's name
 * and the button that was pressed.
 */
const outcomeOn = (xml, afterY = -1, beforeY = Number.MAX_SAFE_INTEGER) =>
  matches(xml, '')
    .filter((node) => node.y > afterY && node.y < beforeY)
    .map((node) => node.text.replace(/\s+/g, ' ').trim())
    .find((text) => OUTCOME_MARKS.some((mark) => text.toLowerCase().includes(mark))) ?? null;

function pressTest(profileName) {
  /*
   * The row has to be read as a whole, not just spotted. A profile name that sits at the very bottom
   * of the column is on screen while its Test button is not, and tapping the name is a press that
   * nothing receives — the check then blames the app for an outcome it was never asked to write.
   */
  revealRow(profileName);
  const row = storedRow(profileName);
  if (!tap(row, 500)) return null;
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    /*
     * "no outcome shown" is a different claim from "the app said it could not reach the endpoint",
     * and only the second one points at the real cause. The failure text is collected as an outcome
     * too, so a check that failed because the device has no route says so instead of implying the
     * Test button never fired.
     */
    const xml = dumpUi();
    const outcome = outcomeOn(xml, rowTop(xml, profileName, row), row.y);
    if (outcome) return outcome;
    sleep(1200);
  }
  /*
   * A mark list cannot cover every sentence the app answers with, and an unlisted one would read as
   * "Test never fired". Whatever the screen actually says gets printed, so a missing mark is visible
   * in the log instead of looking like a product failure.
   */
  console.log(`   [test] no outcome matched for "${profileName}" — on screen: ${screenTexts(20)}`);
  return null;
}

const fieldContents = (needle) => first(dumpUi(), needle)?.text ?? '';

/*
 * A dump reads only the active window, so the field the app resolved and the field the dump can see
 * are not the same thing: a pill or a card over the editor hides exactly the text this check needs.
 * The app is asked instead — its debug probe prints the focused node it found, text included — and
 * what it answers is the same string the insert would have written.
 */
function probeFieldText() {
  shell('logcat -c', { allowFail: true });
  shell(`am broadcast -p ${PKG} -a ${PKG}.DEBUG_SIMULATE --es probe windows`, {
    allowFail: true,
    timeout: 20000
  });
  sleep(2200);
  const log = shell('logcat -d -s FlowBubbleDebug', { allowFail: true, timeout: 25000 });
  for (const line of log.split('\n')) {
    const resolved = line.match(/probe focusedNode=.*?text: (.*?); error: /);
    if (resolved) return resolved[1];
    const scanned = line.match(/probe scan root=\S+ visited=\d+ focused=\[[^\]]*?text=(.*?) editable=/);
    if (scanned) return scanned[1];
  }
  return '';
}

/*
 * A dump that prints `focused="true"` on a node says nothing about whether the app's own
 * `findFocus()` can reach it — the dump reads a per-node flag, the app asks the window. So the app is
 * asked directly: its debug receiver prints every application window, what findFocus returns for each
 * and which nodes actually carry the focused flag. Without this line "nowhere to type" cannot be told
 * apart from "a field was focused and the app was blind to it".
 */
function logFocusProbe() {
  shell('logcat -c', { allowFail: true });
  shell(`am broadcast -p ${PKG} -a ${PKG}.DEBUG_SIMULATE --es probe windows`, {
    allowFail: true,
    timeout: 20000
  });
  sleep(2200);
  const log = shell('logcat -d -s FlowBubbleDebug', { allowFail: true, timeout: 25000 });
  for (const line of log.split('\n')) {
    if (!line.includes('probe ')) continue;
    console.log(`   [focus] ${line.replace(/^.*FlowBubbleDebug:\s*/, '').trim()}`);
  }
}

/*
 * The per-role menu is a popup: it can open above its own row when there is no room below, and it
 * needs a frame to draw. So where it sits is not matchable, but what it says is — the app composes an
 * option as "label · model", while a stored profile row shows the label on one line and the model on
 * the next. Both halves inside one node is a menu option and nothing else on this screen; matching the
 * label alone taps the profile row above the card, which is how a working picker was reported broken.
 */
const menuOption = (xml, name, model) =>
  matches(xml, name).find((node) => node.text.includes(model)) ?? null;

/** Scrolls until a node reads exactly this, so card copy that names a role cannot stand in for the row. */
/*
 * The answer a role row gives is the line *under* its label, so a row parked at the bottom edge shows
 * its name and hides what it answers with — which reads as "the assignment did not survive a restart"
 * when the stored prefs say otherwise. A row only counts as revealed once there is room for its second
 * line above the navigation bar.
 */
function revealRow(label, attempts = 8) {
  const { h } = screenSize();
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    const node = matches(dumpUi(), '').find((n) => n.text === label && n.y < h - 240);
    if (node) return node;
    scrollDown();
  }
  gaveUp(`"${label}" as its own line with room under it`);
  return null;
}

/** The line a role row writes for itself, which is the only proof an assignment landed on that row. */
function roleAssignment(rowLabel, name, model) {
  const xml = dumpUi();
  const row = matches(xml, '').find((n) => n.text === rowLabel);
  if (!row) return null;
  return matches(xml, name).find((n) => n.text.includes(model) && n.y > row.y && n.y - row.y < 170) ?? null;
}

const roleLine = (rowLabel) => {
  const xml = dumpUi();
  const row = matches(xml, '').find((n) => n.text === rowLabel);
  if (!row) return 'the role row is not on screen';
  return matches(xml, '').find((n) => n.y > row.y && n.y - row.y < 170)?.text ?? 'nothing under it';
};

function assignRole(rowLabel, name, model) {
  const row = revealRow(rowLabel);
  if (!row) return { ok: false, detail: 'the role row never scrolled into view' };
  const change = matches(dumpUi(), 'Change').find((n) => n.y > row.y && n.y - row.y < 120) ?? null;
  if (!change) return { ok: false, detail: `no Change button on the "${rowLabel}" row` };
  tap(change, 1400);
  let option = null;
  for (let attempt = 0; attempt < 6 && !option; attempt += 1) {
    option = menuOption(dumpUi(), name, model);
    if (!option) sleep(700);
  }
  if (!option) return { ok: false, detail: `the menu never offered "${name}"` };
  tap(option, 1500);
  const written = roleAssignment(rowLabel, name, model);
  return { ok: Boolean(written), detail: written?.text ?? `${rowLabel} still reads "${roleLine(rowLabel)}"` };
}

/* ---- run ---- */

const mock = startMockProvider();
console.log(`mock provider listening on ${PORT}; the device will call ${ENDPOINT}`);

try {
  const pkgInfo = shell(`dumpsys package ${PKG}`, { allowFail: true });
  record(
    'debug build with the transcript receiver is installed',
    pkgInfo.includes('DebugSimulateReceiver'),
    pkgInfo.match(/versionName=\S+/)?.[0] ?? 'not installed'
  );
  if (!pkgInfo.includes('DebugSimulateReceiver')) {
    throw new Error(`install the debug APK first: adb install -r -t app/build/outputs/apk/debug/YourBubble-${VERSION}-debug.apk`);
  }

  shell(`am force-stop ${PKG}`, { allowFail: true });
  /*
   * Every profile this run adds is asserted by name, so a row left behind by the last run would make
   * the first match the wrong one. Clearing app data is what makes the run repeatable; the grants and
   * the accessibility binding below are re-asserted either way, so nothing else has to be re-done.
   */
  shell(`pm clear ${PKG}`, { allowFail: true });
  sleep(2000);
  for (const permission of ['RECORD_AUDIO', 'POST_NOTIFICATIONS']) {
    shell(`pm grant ${PKG} android.permission.${permission}`, { allowFail: true });
  }
  shell(`appops set ${PKG} SYSTEM_ALERT_WINDOW allow`, { allowFail: true });
  shell(`cmd notification allow_listener ${PKG}/${PKG}.notifications.FlowNotificationService`, { allowFail: true });
  /*
   * Every gesture below is worthless with the display asleep, and this run is long enough for the
   * default timeout to fall in the middle of it. Keeping the screen lit on USB is the cheapest way to
   * make "the screen did not move" mean something other than "the device went to sleep".
   */
  shell('svc power stayon usb', { allowFail: true });
  shell('input keyevent KEYCODE_WAKEUP', { allowFail: true });
  /*
   * Every network-backed check below can fail for a reason that is not the app: the workstation's
   * endpoint not being reachable from the app's own uid. Asserting that first, before the UI is
   * driven, is what stops "no request arrived" from being read as a product failure — the ambiguity
   * that let a socket nothing had ever bound survive in this script unnoticed. It retries because
   * the endpoint is a second process and its `listen()` may not have completed yet; that is a
   * readiness wait, not a product assertion.
   */
  let reachable = '';
  for (let attempt = 0; attempt < 6; attempt += 1) {
    reachable = shell(`run-as ${PKG} nc -z -w 3 10.0.2.2 ${PORT}; echo rc=$?`, { allowFail: true });
    if (reachable.includes('rc=0')) break;
    sleep(1500);
  }
  record(
    'the mock endpoint is reachable from the app itself',
    reachable.includes('rc=0'),
    reachable.replace(/\s+/g, ' ').slice(0, 60)
  );
  if (!reachable.includes('rc=0')) {
    console.log(
      `   [route] ${shell('ip route get 10.0.2.2', { allowFail: true })} | ` +
        `${shell('dumpsys connectivity | grep "Active default network"', { allowFail: true })}`
    );
  }
  awaitAccessibilityEvents();
  record(
    'the bubble turns on from the app Setup screen',
    enableBubble(),
    overlayRunning() ? 'OverlayService running' : 'it never started'
  );

  const settingsXml = showSettings();
  const card = first(settingsXml, 'AI MODELS');
  record('settings shows one AI models card', Boolean(card), card?.text ?? 'missing');

  record(
    'a hosted endpoint over plain http without a key is refused in the UI',
    (() => {
      const before = syncSeen();
      tap(openModelEditor(), 1300);
      tap(reveal('Name'), 500); type('Bad profile');
      tap(reveal('Base URL'), 500); type('http://api.groq.com/openai/v1');
      tap(reveal('Model'), 500); type('llama-3.3-70b-versatile');
      tap(reveal('Save model'), 1500);
      const xml = dumpUi();
      /*
       * Both messages are the app's own refusal, and "Save model" is still on screen because the
       * editor never closed — which is the only way to tell a refused save from one that stored the
       * profile, since the name field holds "Bad profile" either way.
       */
      const refused = Boolean(first(xml, 'Unencrypted http')) &&
        Boolean(first(xml, 'needs an API key')) &&
        Boolean(first(xml, 'Save model'));
      tap(reveal('Cancel'), 900);
      return refused && syncSeen() === before && !storedRow('Bad profile');
    })()
  );

  record('a keyless local endpoint saves as a profile', addProfile({
    name: 'Default mock', baseUrl: ENDPOINT, model: 'mock-default', apiKey: ''
  }), ENDPOINT);

  const beforeDefaultTest = syncSeen();
  const defaultOutcome = pressTest('Default mock');
  const defaultRequest = lastRequest(beforeDefaultTest);
  record(
    'test reaches the mock over plain http with no Authorization header',
    defaultRequest?.path === '/v1/chat/completions' && defaultRequest.auth === '',
    defaultRequest ? `${defaultRequest.method} ${defaultRequest.path} auth="${defaultRequest.auth}"` : 'no request arrived'
  );
  record(
    'the client identifies itself with the running version',
    defaultRequest?.agent === `YourBubble/${VERSION}`,
    defaultRequest?.agent ?? ''
  );
  record(
    'the request carries the profile model and both messages',
    defaultRequest?.model === 'mock-default' && defaultRequest?.messages === 2,
    JSON.stringify({ model: defaultRequest?.model, messages: defaultRequest?.messages })
  );
  record(
    'the app reports the round trip in its own UI',
    String(defaultOutcome).startsWith('Worked in'),
    defaultOutcome ?? 'no outcome shown'
  );

  record('a second profile can be added for one role', addProfile({
    name: 'Dictation mock', baseUrl: ENDPOINT, model: 'mock-dictation', apiKey: 'sk-device-proof-9137'
  }));

  const beforeKeyTest = syncSeen();
  pressTest('Dictation mock');
  const keyRequest = lastRequest(beforeKeyTest);
  record(
    'a stored key is sent as a bearer token',
    keyRequest?.auth === 'Bearer sk-device-proof-9137',
    keyRequest ? `auth="${keyRequest.auth}"` : 'no request arrived'
  );

  const grepHits = shell(
    `run-as ${PKG} sh -c 'grep -rl sk-device-proof-9137 . 2>/dev/null'`,
    { allowFail: true, timeout: 30000 }
  );
  record(
    'the stored key is nowhere in app storage as plain text',
    grepHits.replace(/\s+/g, ' ').trim() === '',
    grepHits.replace(/\s+/g, ' ').trim() || 'no file matches the key'
  );

  const role = assignRole('Dictation cleanup', 'Dictation mock', 'mock-dictation');
  record('a role can be assigned to one profile from the UI', role.ok, role.detail);

  /*
   * Dictation is the one AI call the app makes by itself, so it is the path that has to prove role
   * routing for real. The editor's own Base URL field is focused as the insertion target, and the
   * text that lands in it is the model's answer, read back from the accessibility tree. With no field
   * to write into there is nothing to route, so that is said instead of blaming the app for a
   * broadcast nobody received.
   */
  awaitPanelClear();
  const dictationField = tap(openModelEditor(), 1300) ? tap(reveal('Base URL'), 900) : null;
  if (dictationField) logFocusProbe();
  const beforeDictation = syncSeen();
  if (dictationField) {
    shell(`am broadcast -p ${PKG} -a ${PKG}.DEBUG_SIMULATE --es text ${deviceQuote('um so basically meet me at eight')}`, {
      allowFail: true,
      timeout: 15000
    });
  }
  let dictationRequest = null;
  let inserted = '';
  const dictationDeadline = Date.now() + 45000;
  while (dictationField && Date.now() < dictationDeadline) {
    dictationRequest = since(beforeDictation).find((entry) => entry.model === 'mock-dictation') ?? null;
    inserted = fieldContents('[mock-dictation]') || probeFieldText();
    if (dictationRequest && inserted.startsWith('[mock-dictation]')) break;
    sleep(1500);
  }
  const noField = 'the harness never got a focused field to write into';
  record(
    'dictation cleanup goes to the profile assigned to that role',
    dictationRequest?.model === 'mock-dictation',
    dictationRequest ? `model=${dictationRequest.model}` : (dictationField ? 'the dictation model was never asked' : noField)
  );
  record(
    'only the spoken words leave the device and the answer comes back whole',
    inserted.startsWith('[mock-dictation]') && (dictationRequest?.userCharacters ?? 999) < 90,
    `${inserted.slice(0, 64) || (dictationField ? 'the field still holds the dictation text' : noField)} (${dictationRequest?.userCharacters ?? 0} chars sent)`
  );
  awaitPanelClear();
  tap(reveal('Cancel'), 900);

  record('a profile that will fail can still be added', addProfile({
    name: 'Reject mock', baseUrl: ENDPOINT, model: 'reject-me', apiKey: 'sk-leak-check-4471'
  }));
  const beforeReject = syncSeen();
  const rejectOutcome = pressTest('Reject mock');
  const rejectRequest = lastRequest(beforeReject);
  record(
    'a refused request names the status and hides the key',
    String(rejectOutcome).includes('HTTP 401') && !String(rejectOutcome).includes('sk-leak-check-4471'),
    String(
      rejectOutcome ??
        (rejectRequest ? 'the request went out but the row wrote no outcome' : 'the Test press never reached the server')
    ).slice(0, 96)
  );

  shell(`am force-stop ${PKG}`, { allowFail: true });
  sleep(1500);
  const persisted = showSettings();
  /*
   * This check is named for the role assignment, so it reads the role row rather than only the list
   * of profiles: a stored profile whose job went back to the default model on restart is exactly the
   * failure this line exists to catch, and the profile list alone cannot see it.
   */
  revealRow('Dictation cleanup');
  const stillAssigned = roleAssignment('Dictation cleanup', 'Dictation mock', 'mock-dictation');
  record(
    'profiles and the role assignment survive a restart',
    Boolean(first(persisted, 'Default mock')) &&
      Boolean(first(persisted, 'Dictation mock')) &&
      Boolean(first(persisted, '••••9137')) &&
      Boolean(stillAssigned),
    `${matches(persisted, '••••').map((node) => node.text).join(' | ')}; Dictation cleanup answers with ${stillAssigned?.text ?? roleLine('Dictation cleanup')}`
  );
  record(
    'the settings UI never prints a full key',
    !persisted.includes('sk-device-proof-9137') && !persisted.includes('sk-leak-check-4471')
  );
} finally {
  mock.kill();
  mkdirSync('verify/provider', { recursive: true });
  writeFileSync(
    'verify/provider/latest.json',
    JSON.stringify({ ranAt: new Date().toISOString(), endpoint: ENDPOINT, requests: seen, checks }, null, 2)
  );
}

const passed = checks.filter((check) => check.ok).length;
console.log(`\n${passed}/${checks.length} provider checks passed  (${syncSeen()} requests seen)`);
process.exit(passed === checks.length ? 0 : 1);
