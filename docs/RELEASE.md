# Your Bubble release flow

How a change reaches an installable APK on the releases page.

## GitHub Flow

1. Branch from `main` (`feat/…`, `fix/…`).
2. Open a pull request. CI runs `:app:assembleDebug` and `:app:testDebugUnitTest`;
   both must be green, and the unit test count must not drop.
3. Merge to `main` after review. Main is always releasable.
4. To ship: bump `versionCode` and `versionName` in `app/build.gradle.kts` in its own
   commit on `main`, then tag that commit and push the tag.

```bash
git checkout -b release/1.0.1
# edit app/build.gradle.kts: versionCode = 2, versionName = "1.0.1"
git commit -am "Release 1.0.1"
gh pr create --fill && gh pr merge --admin --squash   # or a normal reviewed merge
git checkout main && git pull
git tag -a v1.0.1 -m "Your Bubble 1.0.1"
git push origin v1.0.1
```

Pushing a `v*` tag runs `.github/workflows/release.yml`. The workflow refuses to build
when the tag does not match `versionName`, so a mistagged release fails loudly instead of
publishing a mislabeled artifact.

## CI

`.github/workflows/ci.yml` runs on every pull request and on `main`. It installs JDK 21 and
the Android 35 SDK, assembles the debug APK, runs the JVM unit tests, and uploads the APK
plus the HTML test report as run artifacts. No signing material is needed for CI.

## Release artifact names

`app/build.gradle.kts` names outputs after the product and version, not `app-release.apk`:

| Build | Artifact |
| --- | --- |
| debug | `YourBubble-<versionName>-debug.apk` |
| release, signing material present | `YourBubble-<versionName>-release.apk` |
| release, signing material missing | `YourBubble-<versionName>-release-unsigned.apk` |

The unsigned name is deliberate: an unsigned release can never be handed to a user by
accident. The release workflow only publishes when `apksigner` verifies the APK it built.

## Signing

Signing material is never committed. `.gitignore` excludes `keystore/` and
`keystore.properties`; without them the release build still compiles, it just stays unsigned.

Create the key once, in the repository root:

```bash
keytool -genkeypair -v -keystore keystore/flowbubble-release.jks \
  -alias flowbubble -keyalg RSA -keysize 2048 -validity 10000
```

keytool prompts for the store and key passwords; use the same values in
`keystore.properties` below.

Back the keystore up offline. Losing it means users must uninstall and reinstall, because
Android refuses an update signed by a different key.

Then write `keystore.properties`:

```properties
storeFile=../keystore/flowbubble-release.jks
storePassword=…
keyAlias=flowbubble
keyPassword=…
```

Build and verify locally:

```bash
./gradlew :app:assembleRelease
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --print-certs \
  app/build/outputs/apk/release/YourBubble-<versionName>-release.apk
```

The artifact name carries `versionName` from `app/build.gradle.kts`, so the file above is
`YourBubble-1.2.0-release.apk` for this release and whatever the next bump says. The first two
releases on GitHub were published as `FlowBubble-…`; 1.2.0 is the first asset under the new name.

`apksigner` lives in `$ANDROID_HOME/build-tools/<version>/`. A verified certificate means the
APK is installable as an update over a previous install by the same key.

### Letting CI sign

One-time, from a machine that has the keystore:

```bash
base64 -w0 keystore/flowbubble-release.jks | gh secret set FLOWBUBBLE_KEYSTORE_BASE64
gh secret set FLOWBUBBLE_KEYSTORE_PASSWORD
gh secret set FLOWBUBBLE_KEY_ALIAS          # flowbubble
gh secret set FLOWBUBBLE_KEY_PASSWORD
```

Until those secrets exist, tag pushes still build and upload the artifact to the run, but no
GitHub Release is published. Releases can always be published from a workstation instead:

```bash
gh release create v1.2.0 \
  app/build/outputs/apk/release/YourBubble-1.2.0-release.apk \
  docs/assets/yourbubble-icon.png \
  --title "Your Bubble v1.2.0" --notes "…"
```

## Installing a release build

Release and debug builds are signed with different keys, so Android rejects one over the other. This is
measured, not assumed — installing the release over a debug build returns:

```
adb: failed to install …: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package
com.flowbubble.app signatures do not match newer version; ignoring!]
```

and the debug build stays installed and running. Switching channels therefore needs an uninstall first,
which clears the app's granted permissions and its history:

```bash
adb uninstall com.flowbubble.app
adb install -r YourBubble-1.2.0-release.apk
```

Then reopen the Setup screen and re-grant microphone, overlay, accessibility, notification access, and
screen capture. `scripts/release-check.mjs` drives exactly that path — bind accessibility, turn the
bubble on through the app's own Setup screen, and require the overlay window to exist over another app —
because a release build has no debug transcript receiver (`src/debug` only). Nothing in this repo can
speak a command into a release APK: the test image has no recognizer language pack, so what
`release-check.mjs` proves is that the shipped artifact installs, binds and draws. Command execution on
a release build is unverified by design, and only real speech on real hardware can close that gap.

## Icon

`app/src/main/res/drawable-nodpi/ic_launcher_foreground.png` is the rendered companion face, and it
feeds the adaptive icon declared in `mipmap-anydpi-v26/ic_launcher.xml` together with
`drawable/ic_launcher_background.xml` and `drawable/ic_launcher_monochrome.xml`. (The foreground used to
be a vector; the PNG replaced it, and the two layers that are still vectors are the background and the
monochrome.) Artwork stays inside the 66dp safe zone because launchers crop the 108dp canvas to a
circle, a squircle, or a rounded square. The monochrome layer is what themed-icon launchers recolor, and
it is still hand-drawn line art of the same face rather than a monochrome trace of the rendered one —
under a themed icon the app therefore reads as a line drawing.
`docs/assets/yourbubble-icon.png` is the raster brand asset used on the releases page.
