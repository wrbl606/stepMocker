# Step Mocker

A Kotlin / Jetpack Compose Android app for generating realistic-looking test steps in Health Connect. Application ID: `xyz.wrbl.stepMocker`.

## Why it exists

Real step activity arrives in small increments—such as 10 steps over a minute—from phones, watches and fitness bands with different manufacturers, models and recording methods. A day's activity can span thousands of records. Testing with one large daily total misses pagination bugs, incomplete reads and aggregation issues.

Step Mocker scatters an exact step total across random intervals and simulated device metadata, reproducing that fragmented data without walking thousands of steps yourself.

## Use

1. Grant Health Connect step read/write access on a supported Android 9+ device.
2. Select a time range (defaults to today at local midnight through now), review the chart, and tap **Add steps** to preview and confirm the addition.
3. Expand **Step settings** to change the total steps, number of entries, or contributing devices (1–5). Defaults are **8,000 steps**, **1,001 entries**, and **5 devices**. The collapsed card summarizes these settings. Leave the entry count blank for automatic sizing. When there are fewer entries than selected devices, the summary and generation use the smaller count.
4. Inspect the automatic hourly/2-hour/6-hour/12-hour chart and expandable record timeline. Select this app's records to delete them with confirmation.

Try **8,000 steps in 1,001 portions over 24 hours** to exercise pagination. Supports 1–10,000 portions and 1–1,000,000 steps; each portion needs at least one step and enough time to fit the cadence limit. Periods must be 1 minute–90 days and end in the past. Records within a generation do not overlap; separate generations may overlap.

## Pagination

**1,000 is the default read page size, not a total-record limit.** The Android platform API allows page sizes up to **5,000**. This app requests 1,000 records per page, so **1,001+ readable records** exercise multiple pages. Follow `pageToken` until null or empty. See the [reading guide](https://developer.android.com/health-and-fitness/health-connect/read-data) and [platform page-size reference](https://developer.android.com/reference/android/health/connect/ReadRecordsRequestUsingFilters.Builder#setPageSize(int)).

Portions count individual records, not API calls. Writes/deletes use chunks of 500 records. Multi-chunk operations can partially succeed; review the timeline before repeating a failed write.

All simulated devices are attributed to Step Mocker. Chart totals use Health Connect aggregation and may reflect deduplication/source priorities rather than the raw inserted total. Other apps' records are read-only here. Data stays on-device; other apps with Health Connect access can read generated records.

## Build and test

Use Android Studio with SDK 36 and JDK 17+, or set `JAVA_HOME` / `ANDROID_HOME` and run:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Tests cover exact totals and portion counts, time bounds, cadence, metadata diversity and validation. Live Health Connect writes and multi-page reads require a supported device/emulator for verification.

## Release builds

This workspace's local release key is `.signing/step-mocker-release.jks`, with alias `step-mocker`. Its generated password is stored in `signing.properties`; both files are Git-ignored and restricted to the local user. Back up both securely to retain the ability to sign future updates. They are not included when cloning the project.

Release builds disable debugging and use R8 code optimization and resource shrinking:

```sh
./gradlew assembleRelease bundleRelease testReleaseUnitTest lintRelease
```

Without signing credentials, the APK is `app/build/outputs/apk/release/app-release-unsigned.apk` and cannot be installed until signed. Release builds never fall back to the debug signing key.

To enable signing, use an existing release keystore or create one outside the repository (keytool prompts for passwords):

```sh
keytool -genkeypair -v -keystore /absolute/path/to/step-mocker-release.jks -storetype JKS -alias step-mocker -keyalg RSA -keysize 3072 -validity 10000
```

Copy `signing.properties.example` to `signing.properties` and fill in the four values. The actual properties file and keystores are ignored by Git. Keep a secure backup of the keystore and passwords; future updates need the same signing identity. In CI, supply `STEPMOCKER_STORE_FILE`, `STEPMOCKER_STORE_PASSWORD`, `STEPMOCKER_KEY_ALIAS`, and `STEPMOCKER_KEY_PASSWORD` from your secret store instead. Environment values take precedence. Partial signing configuration fails with an actionable error.

With signing configured, the same command produces:

- Signed APK: `app/build/outputs/apk/release/app-release.apk`
- Signed Play bundle: `app/build/outputs/bundle/release/app-release.aab`
- R8 mapping: `app/build/outputs/mapping/release/mapping.txt` (archive with each release for crash deobfuscation)

Override versions for subsequent releases, increasing the code each time:

```sh
./gradlew assembleRelease bundleRelease -PappVersionCode=2 -PappVersionName=1.1
```

A release-signed APK cannot replace an existing debug-signed install with the same application ID. Use a separate test device/profile, or remove the debug install first if its local state can be discarded. Smoke-test permissions, generation, multi-page timeline reads and deletion with the optimized APK before distribution.
