/*
 * Provider compatibility check.
 *
 * FlowBubble now talks to any OpenAI-compatible endpoint, so the release has to be proven against a
 * server that is not OpenAI: no key, plain http, a path the client assembles itself, and a response
 * the app has to parse. This script starts such a server on the workstation, configures profiles
 * through the app's own Settings screen, and reads back what the server actually received.
 *
 * Run against a debug build (the transcript receiver is debug-only):
 *   node scripts/provider-check.mjs
 * Environment: DEVICE (default emulator-5554), PORT (default 8971).
 */
import { execFileSync } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdirSync, writeFileSync } from 'node:fs';

const ADB = 'C:/Users/Pranav/AppData/Local/Android/Sdk/platform-tools/adb.exe';
const DEVICE = process.env.DEVICE || 'emulator-5554';
const PKG = 'com.flowbubble.app';
const PORT = Number(process.env.PORT || 8971);
const ENDPOINT = `http://10.0.2.2:${PORT}/v1`;
const LABEL = 'FlowBubble voice access';

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
 * uiautomator intermittently answers "ERROR: null root node returned by UiTestAutomationBridge"
 * during a window transition, and the file it leaves behind is then either missing or the previous
 * screen. Every read of the UI goes through this retry loop, because a stale empty dump makes the
 * harness tap nodes that are not there and blame the app.
 */
function dumpUi(attempts = 5) {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
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

function tap(node, waitMs = 900) {
  if (!node) return false;
  shell(`input tap ${node.x} ${node.y}`, { allowFail: true });
  sleep(waitMs);
  return true;
}

/** Types with the device's own key injection so the app's field really receives the text. */
function type(value) {
  shell(`input text ${deviceQuote(value.replace(/ /g, '%s'))}`, { allowFail: true, timeout: 20000 });
  sleep(800);
}

function scrollDown() {
  const { w, h } = screenSize();
  shell(`input swipe ${Math.round(w / 2)} ${Math.round(h * 0.78)} ${Math.round(w / 2)} ${Math.round(h * 0.22)} 800`, { allowFail: true });
  sleep(1100);
}

function scrollUp() {
  const { w, h } = screenSize();
  shell(`input swipe ${Math.round(w / 2)} ${Math.round(h * 0.22)} ${Math.round(w / 2)} ${Math.round(h * 0.78)} 800`, { allowFail: true });
  sleep(1000);
}

/** Scrolls back toward the top of the card list, looking for a node on the way. */
function revealUp(needle, attempts = 8) {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    const node = first(dumpUi(), needle);
    if (node) return node;
    scrollUp();
  }
  return null;
}

/** Scrolls the settings column until a node appears, then returns it. */
function reveal(needle, { belowAnchor = null, attempts = 8 } = {}) {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    const xml = dumpUi();
    const node = belowAnchor ? below(xml, belowAnchor, needle) : first(xml, needle);
    if (node) return node;
    scrollDown();
  }
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
  shell('settings delete secure enabled_accessibility_services', { allowFail: true });
  sleep(3000);
  shell(`settings put secure enabled_accessibility_services ${PKG}/${PKG}.access.FlowAccessibilityService`, { allowFail: true });
  shell('settings put secure accessibility_enabled 1', { allowFail: true });
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
  tap(first(dumpUi(), 'Setup'), 1300);
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

const seen = [];

function startMockProvider() {
  const server = createServer((req, res) => {
    let raw = '';
    req.on('data', (chunk) => { raw += chunk; });
    req.on('end', () => {
      let body = null;
      try { body = JSON.parse(raw); } catch { body = null; }
      const system = body?.messages?.[0]?.content ?? '';
      const user = body?.messages?.[1]?.content ?? '';
      seen.push({
        method: req.method,
        path: req.url,
        auth: req.headers.authorization ?? '',
        agent: req.headers['user-agent'] ?? '',
        model: body?.model ?? '',
        temperature: body?.temperature,
        messages: body?.messages?.length ?? 0,
        userCharacters: user.length,
        at: new Date().toISOString()
      });

      if (body?.model === 'reject-me') {
        res.writeHead(401, { 'content-type': 'application/json' });
        res.end(JSON.stringify({
          error: {
            message: `mock refused the key ${String(req.headers.authorization ?? '').replace('Bearer ', '')}`
          }
        }));
        return;
      }
      const reply = system.includes('connectivity check')
        ? 'OK'
        : `[${body?.model ?? 'unknown'}] ${user.slice(0, 32)}`;
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({
        id: 'chatcmpl-mock',
        object: 'chat.completion',
        model: body?.model ?? 'unknown',
        choices: [{ index: 0, message: { role: 'assistant', content: reply }, finish_reason: 'stop' }]
      }));
    });
  });
  server.listen(PORT, '0.0.0.0');
  return server;
}

const since = (index) => seen.slice(index);
const lastRequest = (index) => seen.slice(index).at(-1) ?? null;

/* ---- driving the app ---- */

const checks = [];
const record = (name, ok, detail = '') => {
  checks.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  ${detail}` : ''}`);
};

/** Adds a profile through the Settings UI; true only if the row appeared and nothing was sent. */
function addProfile({ name, baseUrl, model, apiKey }) {
  const before = seen.length;
  if (!tap(reveal('Add your first model') ?? reveal('Add another model'), 1300)) return false;
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
  tap(reveal('Save model'), 1800);
  return Boolean(revealUp(name)) && seen.length === before;
}

function pressTest(profileName) {
  for (let climb = 0; climb < 6 && !first(dumpUi(), 'AI MODELS'); climb += 1) scrollUp();
  let button = null;
  for (let scan = 0; scan < 8 && !button; scan += 1) {
    const xml = dumpUi();
    if (first(xml, profileName)) button = below(xml, profileName, 'Test');
    if (!button) scrollDown();
  }
  if (!tap(button, 500)) return null;
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    const outcome = first(dumpUi(), 'Worked in') ?? first(dumpUi(), 'returned HTTP');
    if (outcome) return outcome.text;
    sleep(1200);
  }
  return null;
}

const fieldContents = (needle) => first(dumpUi(), needle)?.text ?? '';

/* ---- run ---- */

const server = startMockProvider();
console.log(`mock provider listening on ${PORT}; the device will call ${ENDPOINT}`);

try {
  const pkgInfo = shell(`dumpsys package ${PKG}`, { allowFail: true });
  record(
    'debug build with the transcript receiver is installed',
    pkgInfo.includes('DebugSimulateReceiver'),
    pkgInfo.match(/versionName=\S+/)?.[0] ?? 'not installed'
  );
  if (!pkgInfo.includes('DebugSimulateReceiver')) {
    throw new Error('install the debug APK first: adb install -r -t app/build/outputs/apk/debug/FlowBubble-1.0.2-debug.apk');
  }

  shell(`am force-stop ${PKG}`, { allowFail: true });
  for (const permission of ['RECORD_AUDIO', 'POST_NOTIFICATIONS']) {
    shell(`pm grant ${PKG} android.permission.${permission}`, { allowFail: true });
  }
  shell(`appops set ${PKG} SYSTEM_ALERT_WINDOW allow`, { allowFail: true });
  shell(`cmd notification allow_listener ${PKG}/${PKG}.notifications.FlowNotificationService`, { allowFail: true });
  awaitAccessibilityEvents();
  record(
    'the bubble turns on from the app Setup screen',
    enableBubble(),
    overlayRunning() ? 'OverlayService running' : 'it never started'
  );

  screen('settings');
  sleep(1500);
  const card = first(dumpUi(), 'AI MODELS');
  record('settings shows one AI models card', Boolean(card), card?.text ?? 'missing');

  record(
    'a hosted endpoint over plain http without a key is refused in the UI',
    (() => {
      const before = seen.length;
      tap(reveal('Add your first model') ?? reveal('Add another model'), 1300);
      tap(reveal('Name'), 500); type('Bad profile');
      tap(reveal('Base URL'), 500); type('http://api.groq.com/openai/v1');
      tap(reveal('Model'), 500); type('llama-3.3-70b-versatile');
      tap(reveal('Save model'), 1500);
      const xml = dumpUi();
      const cleared = Boolean(first(xml, 'Unencrypted http')) &&
        Boolean(first(xml, 'needs an API key')) &&
        !first(xml, 'Bad profile');
      if (cleared) tap(reveal('Cancel'), 900);
      return cleared && seen.length === before;
    })()
  );

  record('a keyless local endpoint saves as a profile', addProfile({
    name: 'Default mock', baseUrl: ENDPOINT, model: 'mock-default', apiKey: ''
  }), ENDPOINT);

  const beforeDefaultTest = seen.length;
  const defaultOutcome = pressTest('Default mock');
  const defaultRequest = lastRequest(beforeDefaultTest);
  record(
    'test reaches the mock over plain http with no Authorization header',
    defaultRequest?.path === '/v1/chat/completions' && defaultRequest.auth === '',
    defaultRequest ? `${defaultRequest.method} ${defaultRequest.path} auth="${defaultRequest.auth}"` : 'no request arrived'
  );
  record(
    'the client identifies itself with the running version',
    /FlowBubble\/1\.0\.2/.test(defaultRequest?.agent ?? ''),
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

  const beforeKeyTest = seen.length;
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

  const roleRow = reveal('Dictation cleanup', { belowAnchor: 'WHICH MODEL ANSWERS WHAT' });
  const assigned = (() => {
    if (!roleRow) return false;
    if (!tap(below(dumpUi(), 'Dictation cleanup', 'Change'), 900)) return false;
    const option = first(dumpUi(), 'Dictation mock');
    if (!tap(option, 1400)) return false;
    return Boolean(below(dumpUi(), 'Dictation cleanup', 'Dictation mock'));
  })();
  record('a role can be assigned to one profile from the UI', assigned, roleRow?.text ?? 'role row not found');

  /*
   * Dictation is the one AI call the app makes by itself, so it is the path that has to prove role
   * routing for real. The editor's own Base URL field is focused as the insertion target, and the
   * text that lands in it is the model's answer, read back from the accessibility tree.
   */
  tap(reveal('Add another model'), 1300);
  tap(reveal('Base URL'), 900);
  const beforeDictation = seen.length;
  shell(`am broadcast -p ${PKG} -a ${PKG}.DEBUG_SIMULATE --es text ${deviceQuote('um so basically meet me at eight')}`, {
    allowFail: true,
    timeout: 15000
  });
  let dictationRequest = null;
  let inserted = '';
  const dictationDeadline = Date.now() + 45000;
  while (Date.now() < dictationDeadline) {
    dictationRequest = since(beforeDictation).find((entry) => entry.model === 'mock-dictation') ?? null;
    inserted = fieldContents('[mock-dictation]');
    if (dictationRequest && inserted.startsWith('[mock-dictation]')) break;
    sleep(1500);
  }
  record(
    'dictation cleanup goes to the profile assigned to that role',
    dictationRequest?.model === 'mock-dictation',
    dictationRequest ? `model=${dictationRequest.model}` : 'the dictation model was never asked'
  );
  record(
    'only the spoken words leave the device and the answer comes back whole',
    inserted.startsWith('[mock-dictation]') && (dictationRequest?.userCharacters ?? 999) < 90,
    `${inserted.slice(0, 64) || 'the field still holds the dictation text'} (${dictationRequest?.userCharacters ?? 0} chars sent)`
  );
  tap(reveal('Cancel'), 900);

  record('a profile that will fail can still be added', addProfile({
    name: 'Reject mock', baseUrl: ENDPOINT, model: 'reject-me', apiKey: 'sk-leak-check-4471'
  }));
  const rejectOutcome = pressTest('Reject mock');
  record(
    'a refused request names the status and hides the key',
    String(rejectOutcome).includes('HTTP 401') && !String(rejectOutcome).includes('sk-leak-check-4471'),
    String(rejectOutcome ?? 'no outcome shown').slice(0, 96)
  );

  shell(`am force-stop ${PKG}`, { allowFail: true });
  sleep(1500);
  screen('settings');
  sleep(2200);
  const persisted = dumpUi();
  record(
    'profiles and the role assignment survive a restart',
    Boolean(first(persisted, 'Default mock')) &&
      Boolean(first(persisted, 'Dictation mock')) &&
      Boolean(first(persisted, '••••9137')),
    matches(persisted, '••••').map((node) => node.text).join(' | ')
  );
  record(
    'the settings UI never prints a full key',
    !persisted.includes('sk-device-proof-9137') && !persisted.includes('sk-leak-check-4471')
  );
} finally {
  server.close();
  mkdirSync('verify/provider', { recursive: true });
  writeFileSync(
    'verify/provider/latest.json',
    JSON.stringify({ ranAt: new Date().toISOString(), endpoint: ENDPOINT, requests: seen, checks }, null, 2)
  );
}

const passed = checks.filter((check) => check.ok).length;
console.log(`\n${passed}/${checks.length} provider checks passed  (${seen.length} requests seen)`);
process.exit(passed === checks.length ? 0 : 1);
