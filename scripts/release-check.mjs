/*
 * Release-channel check. The benchmark proves readiness by broadcasting to the debug transcript
 * receiver, and a release build deliberately has no such receiver, so the shipped APK is verified
 * the way a user's device would see it: bind accessibility, turn the bubble on through the app's own
 * Setup screen, and require the bubble window to be present over another app.
 */
import { execFileSync } from 'node:child_process';

const ADB = 'C:/Users/Pranav/AppData/Local/Android/Sdk/platform-tools/adb.exe';
const DEVICE = process.env.DEVICE || 'emulator-5554';
const PKG = 'com.flowbubble.app';

const sleepBuffer = new Int32Array(new SharedArrayBuffer(4));
const sleep = (ms) => Atomics.wait(sleepBuffer, 0, 0, ms);

function sh(argv, { allowFail = false, timeout = 40000 } = {}) {
  try {
    return execFileSync(ADB, ['-s', DEVICE, ...argv], { encoding: 'utf8', timeout, maxBuffer: 32 * 1024 * 1024 }).trim();
  } catch {
    if (allowFail) return '';
    throw new Error(`adb ${argv.join(' ')} failed`);
  }
}
const shell = (command, opts) => sh(['shell', command], opts);

const dumpUi = () => {
  shell('uiautomator dump /sdcard/rel-ui.xml', { allowFail: true, timeout: 20000 });
  return shell('cat /sdcard/rel-ui.xml', { allowFail: true, timeout: 20000 });
};

function centre(xml, attribute, label) {
  for (const node of xml.split('<node')) {
    if (!node.includes(`${attribute}="${label}"`)) continue;
    const m = node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    if (m) return { x: Math.round((+m[1] + +m[3]) / 2), y: Math.round((+m[2] + +m[4]) / 2) };
  }
  return null;
}

const overlayRunning = () =>
  shell(`dumpsys activity services ${PKG}`, { allowFail: true, timeout: 20000 }).includes(`${PKG}/.service.OverlayService`);

const size = () => {
  const m = shell('wm size', { allowFail: true }).match(/(\d+)x(\d+)/);
  return m ? { w: +m[1], h: +m[2] } : { w: 1080, h: 2400 };
};

function accessibilityState() {
  const out = shell('dumpsys accessibility', { allowFail: true, timeout: 20000 });
  const crashed = out.split('\n').find((line) => line.trim().startsWith('Crashed services:')) || '';
  return { bound: out.includes('FlowBubble voice access'), crashed: crashed.includes(PKG) };
}

function turnBubbleOn() {
  if (overlayRunning()) return true;
  const { w, h } = size();
  shell(`monkey -p ${PKG} -c android.intent.category.LAUNCHER 1`, { allowFail: true });
  sleep(2500);
  const setup = centre(dumpUi(), 'text', 'Setup');
  if (!setup) return false;
  shell(`input tap ${setup.x} ${setup.y}`, { allowFail: true });
  sleep(1500);
  for (let cycle = 0; cycle < 3; cycle += 1) {
    let target = null;
    for (let scan = 0; scan < 12 && !target; scan += 1) {
      const xml = dumpUi();
      target = centre(xml, 'text', 'Turn on bubble');
      if (target) break;
      if (centre(xml, 'text', 'Turn off bubble') || overlayRunning()) return true;
      if (!centre(xml, 'text', 'Finish the required steps')) {
        shell(`input swipe ${Math.round(w / 2)} ${Math.round(h * 0.55)} ${Math.round(w / 2)} ${Math.round(h * 0.32)} 600`, { allowFail: true });
      }
      sleep(1500);
    }
    if (!target) continue;
    shell(`input tap ${target.x} ${target.y}`, { allowFail: true });
    const deadline = Date.now() + 15000;
    while (Date.now() < deadline) {
      if (overlayRunning()) return true;
      sleep(700);
    }
  }
  return overlayRunning();
}

const checks = [];
const record = (name, ok, detail = '') => {
  checks.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  ${detail}` : ''}`);
};

record('release build installed', /versionName=1\.0\.1/.test(shell('dumpsys package com.flowbubble.app', { allowFail: true })), shell('dumpsys package com.flowbubble.app', { allowFail: true }).match(/versionName=\S+/)?.[0] ?? '');

const noDebugReceiver = !sh(['shell', 'dumpsys', 'package', PKG], { allowFail: true }).includes('DebugSimulateReceiver');
record('no debug transcript receiver in the release manifest', noDebugReceiver);

const a11y = accessibilityState();
record('accessibility service bound', a11y.bound && !a11y.crashed, a11y.crashed ? 'android reports it crashed' : '');

record('bubble turns on from the app Setup screen', turnBubbleOn(), overlayRunning() ? 'OverlayService running' : 'OverlayService never started');

shell('monkey -p com.google.android.deskclock 1', { allowFail: true });
sleep(2500);
shell('input keyevent KEYCODE_HOME', { allowFail: true });
sleep(1500);

/*
 * The bubble is a plain overlay View, so it is not a node in the accessibility hierarchy and a
 * uiautomator dump cannot see it. What Android does report is the window itself: it exists, it is
 * drawn, and the focus belongs to somebody else — which is exactly the claim being tested.
 */
const windows = shell('dumpsys window windows', { allowFail: true, timeout: 30000 });
const focus = shell('dumpsys window', { allowFail: true, timeout: 30000 }).match(/mCurrentFocus=Window\{\S+ u0 (\S+)\//)?.[1] ?? '';
const block = windows.match(/Window\{\S+ u0 FlowBubble\}:[\s\S]*?(?=\n\s*Window #|\n\s*$)/)?.[0] ?? '';

record('bubble window is hosted by the release build', /type=2038/.test(block), /type=\d+/.exec(block)?.[0] ?? 'no overlay window');
record('bubble is drawn above another app', /isVisible=true/.test(block) && /shown=true/.test(block) && !focus.includes(PKG), `focus=${focus || 'unknown'}`);

const passed = checks.filter((c) => c.ok).length;
console.log(`\n${passed}/${checks.length} release-channel checks passed`);
process.exit(passed === checks.length ? 0 : 1);
