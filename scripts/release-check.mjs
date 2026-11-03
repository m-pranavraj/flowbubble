/*
 * Release-channel check. The benchmark proves readiness by broadcasting to the debug transcript
 * receiver, and a release build deliberately has no such receiver, so the shipped APK is verified
 * the way a user's device would see it: bind accessibility, turn the bubble on through the app's own
 * Setup screen, and require the bubble window to be present over another app.
 */
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const ADB = 'C:/Users/Pranav/AppData/Local/Android/Sdk/platform-tools/adb.exe';
const DEVICE = process.env.DEVICE || 'emulator-5554';
const PKG = 'com.flowbubble.app';
const SDK = 'C:/Users/Pranav/AppData/Local/Android/Sdk';

const gradleFile = fileURLToPath(new URL('../app/build.gradle.kts', import.meta.url));
const manifestFile = fileURLToPath(new URL('../app/src/main/AndroidManifest.xml', import.meta.url));

/*
 * The version under test comes from the build file, not from a literal in this script. A pinned
 * version made the first check fail on every release after the one it was written for, which is the
 * same mistake as hard-coding a passing score: the gate reports on the world instead of testing it.
 */
const gradle = readFileSync(gradleFile, 'utf8');
const expectedVersion = gradle.match(/versionName\s*=\s*"([^"]+)"/)?.[1] ?? '';
const expectedCode = Number(gradle.match(/versionCode\s*=\s*(\d+)/)?.[1] ?? 0);

const run = (command, args, { allowFail = false } = {}) => {
  const out = spawnSync(command, args, { encoding: 'utf8', timeout: 120000, maxBuffer: 32 * 1024 * 1024 });
  if (out.error) {
    if (allowFail) return '';
    throw out.error;
  }
  return `${out.stdout ?? ''}${out.stderr ?? ''}`;
};

/* apksigner and friends are .bat files, which CreateProcess cannot start directly. */
const bat = (path, args, opts) => run('cmd.exe', ['/d', '/s', '/c', path, ...args], opts);

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

/*
 * A failed dump leaves whatever the previous dump wrote at that path, so reading the file alone can
 * return a screen that is no longer on the device. Deleting first makes a failed dump a missing file
 * — which the retry treats as one — rather than a stale tree that looks fresh.
 */
const dumpUi = (attempts = 3) => {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    shell('rm -f /sdcard/rel-ui.xml', { allowFail: true, timeout: 15000 });
    shell('uiautomator dump /sdcard/rel-ui.xml', { allowFail: true, timeout: 20000 });
    const xml = shell('cat /sdcard/rel-ui.xml', { allowFail: true, timeout: 20000 });
    if (xml.includes('<node') && xml.length > 800) return xml;
    sleep(700);
  }
  return '';
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
  return { bound: out.includes('Your Bubble voice access'), crashed: crashed.includes(PKG) };
}

function turnBubbleOn() {
  if (overlayRunning()) return true;
  const { w, h } = size();
  shell(`monkey -p ${PKG} -c android.intent.category.LAUNCHER 1`, { allowFail: true });
  sleep(2500);
  /*
   * A cold start is still drawing when the launch command returns, so one read can describe the
   * screen before it. The harness taps by node, and a node from a screen that is gone sends a tap to
   * coordinates that now belong to something else — which reads as a broken app, not a race.
   */
  let setup = null;
  for (let attempt = 0; attempt < 6 && !setup; attempt += 1) {
    setup = centre(dumpUi(), 'text', 'Setup');
    if (!setup) {
      shell(`monkey -p ${PKG} -c android.intent.category.LAUNCHER 1`, { allowFail: true });
      sleep(1500);
    }
  }
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

/*
 * A release build has no debug receiver, so this script cannot probe the accessibility connection the
 * way the benchmark does. What it can do is the same thing a user does from the Setup screen, and then
 * read Android's own answer: dumpsys accessibility lists the service under Bound services, and parks a
 * dead one under Crashed services. Deleting the setting first matters — writing back the same component
 * string changes nothing, and Android will not rebind a service whose process was reinstall-killed.
 */
function grantPrerequisites() {
  for (const permission of ['RECORD_AUDIO', 'POST_NOTIFICATIONS']) {
    shell(`pm grant ${PKG} android.permission.${permission}`, { allowFail: true });
  }
  shell(`appops set ${PKG} SYSTEM_ALERT_WINDOW allow`, { allowFail: true });
}

function bindAccessibility(attempts = 3) {
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    shell('settings delete secure --user 0 enabled_accessibility_services', { allowFail: true });
    sleep(3000);
    shell(
      `settings put secure --user 0 enabled_accessibility_services ${PKG}/${PKG}.access.FlowAccessibilityService`,
      { allowFail: true },
    );
    shell('settings put secure --user 0 accessibility_enabled 1', { allowFail: true });
    sleep(6000);
    const state = accessibilityState();
    if (state.bound && !state.crashed) return state;
  }
  return accessibilityState();
}

const checks = [];
const record = (name, ok, detail = '') => {
  checks.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  ${detail}` : ''}`);
};

/*
 * The artifact gate runs before the device does, because none of these faults show up as a slow test:
 * a phone simply says "App not installed" and the user has no way to learn why. An unsigned build, a
 * V2-only signature that re-packaging installers reject, a build with no arm64 code, a download big
 * enough to break a phone browser, a versionCode that did not move — all of them are invisible to
 * every check below this one, and all of them are measured here from the file that actually ships.
 */
const buildTools = (() => {
  const roots = readdirSync(`${SDK}/build-tools`);
  return `${SDK}/build-tools/${roots.sort().pop()}`;
})();

const releaseDir = fileURLToPath(new URL('../app/build/outputs/apk/release/', import.meta.url));
const signedApk = `${releaseDir}YourBubble-${expectedVersion}-release.apk`;
const unsignedApk = `${releaseDir}YourBubble-${expectedVersion}-release-unsigned.apk`;
const apkPath = existsSync(signedApk) ? signedApk : existsSync(unsignedApk) ? unsignedApk : '';
const apkBytes = apkPath ? statSync(apkPath).size : 0;

const manifest = readFileSync(manifestFile, 'utf8');
/*
 * apksigner only judges the schemes that apply to the SDK range it is given, and by default it takes
 * that range from the APK's own minSdkVersion. On a minSdk 26 build it answers "v1: false" for an APK
 * whose JAR signature is present and intact, because no platform it supports is obliged to look at it.
 * Asking twice — once at a range where a phone falls back to v1, once at the build's own range for v2 —
 * measures the signatures instead of the defaults.
 */
const v1Report =
  apkPath === signedApk
    ? bat(`${buildTools}/apksigner.bat`, ['verify', '--verbose', '--min-sdk-version', '21', '--max-sdk-version', '23', apkPath])
    : '';
const v2Report = apkPath === signedApk ? bat(`${buildTools}/apksigner.bat`, ['verify', '--verbose', apkPath]) : '';
const signReport = `${v1Report}\n${v2Report}`;
const badging = apkPath ? run(`${buildTools}/aapt2.exe`, ['dump', 'badging', apkPath]) : '';
const abis = badging.match(/native-code:\s*([^\r\n]*)/)?.[1]?.replace(/'/g, '').trim().split(/\s+/) ?? [];

record(
  'release artifact exists and is signed',
  apkPath === signedApk,
  apkPath ? (apkPath === unsignedApk ? 'the release build is UNSIGNED — no phone will install it' : '') : `no APK under ${releaseDir}`,
);
record(
  'signed with both the v1 and v2 schemes',
  /Verified using v1 scheme \(JAR signing\): true/.test(signReport) &&
    /Verified using v2 scheme \(APK Signature Scheme v2\): true/.test(signReport),
  `v1=${/v1 scheme \(JAR signing\): true/.test(signReport)} v2=${/v2 scheme \(APK Signature Scheme v2\): true/.test(signReport)} — v2-only APKs die when an installer re-packs the file`,
);
record('carries arm64 code for a real phone', abis.includes('arm64-v8a'), `native-code: ${abis.join(', ') || 'none'}`);
record('carries no dead 32-bit x86 code', apkPath !== '' && !abis.includes('x86'), `native-code: ${abis.join(', ') || 'none'}`);
record(
  'small enough for a phone to fetch and unpack',
  apkBytes > 0 && apkBytes < 100_000_000,
  `${(apkBytes / 1e6).toFixed(1)} MB`,
);
record(
  'a phone can see the speech recognizer',
  manifest.includes('android.speech.RecognitionService'),
  'without a <queries> entry, package visibility hides the recognizer service on targetSdk 30+ and isRecognitionAvailable() returns false',
);
record(
  'declares the permissions its own commands need',
  manifest.includes('android.permission.CAMERA') && manifest.includes('android.permission.ACCESS_NOTIFICATION_POLICY'),
  'setTorchMode needs CAMERA; without ACCESS_NOTIFICATION_POLICY the app is not listed under Do Not Disturb access',
);

/*
 * An install is only an upgrade if the number went up. Android refuses a same-version replace when the
 * signing certificate differs, and a lower versionCode is a downgrade it refuses outright — either way
 * the phone reports "App not installed" with no hint that the releases page is holding the newer build.
 */
const publishedTag = run('gh.exe', ['api', 'repos/m-pranavraj/flowbubble/releases/latest', '--jq', '.tag_name']).trim();
const publishedGradle = publishedTag
  ? Buffer.from(
      run('gh.exe', [
        'api',
        `repos/m-pranavraj/flowbubble/contents/app/build.gradle.kts?ref=${publishedTag}`,
        '--jq',
        '.content',
      ]).trim(),
      'base64',
    ).toString('utf8')
  : '';
const publishedCode = Number(publishedGradle.match(/versionCode\s*=\s*(\d+)/)?.[1] ?? 0);
record(
  `versionCode upgrades over ${publishedTag || 'the published release'}`,
  publishedCode > 0 && expectedCode > publishedCode,
  publishedCode
    ? `the release on GitHub is ${publishedCode}, this build is ${expectedCode}`
    : 'could not read the published release, so an upgrade install is unproven',
);

const blocked = checks.filter((c) => !c.ok);
if (blocked.length) {
  console.log(`\n${blocked.length}/${checks.length} artifact checks failed — the device stage is skipped, because a phone would refuse this build before it ever runs.`);
  process.exit(1);
}
console.log(`\n${checks.length}/${checks.length} artifact checks passed\n`);

const installed = shell('dumpsys package com.flowbubble.app', { allowFail: true });
const installedVersion = installed.match(/versionName=(\S+)/)?.[1] ?? '';
record(
  'release build installed',
  installedVersion === expectedVersion && !/DEBUGGABLE/.test(installed),
  `versionName=${installedVersion}, expected ${expectedVersion}, debuggable=${/DEBUGGABLE/.test(installed)}`,
);

const noDebugReceiver = !sh(['shell', 'dumpsys', 'package', PKG], { allowFail: true }).includes('DebugSimulateReceiver');
record('no debug transcript receiver in the release manifest', noDebugReceiver);

grantPrerequisites();
const a11y = bindAccessibility();
record('accessibility service bound', a11y.bound && !a11y.crashed, a11y.crashed ? 'android reports it crashed' : a11y.bound ? '' : 'android never listed the service as bound');

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
const block = windows.match(/Window\{\S+ u0 Your Bubble\}:[\s\S]*?(?=\n\s*Window #|\n\s*$)/)?.[0] ?? '';

record('bubble window is hosted by the release build', /type=2038/.test(block), /type=\d+/.exec(block)?.[0] ?? 'no overlay window');
record('bubble is drawn above another app', /isVisible=true/.test(block) && /shown=true/.test(block) && !focus.includes(PKG), `focus=${focus || 'unknown'}`);

const passed = checks.filter((c) => c.ok).length;
console.log(`\n${passed}/${checks.length} release-channel checks passed`);
process.exit(passed === checks.length ? 0 : 1);
