# PSwitch

An LSPosed / Vector module that changes which logged-in Google account Google Play uses
for **in-app purchases and billing**, not only the account shown in the Play Store UI.

The companion app lists the Google accounts available to Play Store and lets you pick
one with a tap. No root is needed: the selected account is stored in the app's own
preferences and shared with the hook through a small exported `ContentProvider`; a
broadcast applies changes instantly to a running Play Store process.

## Details

- Application ID: `io.github.ash_iir.pswitch`
- Scope: `com.android.vending` (Google Play Store)
- Xposed API: 102 (modern libxposed API, no legacy `de.robv` bridge)
- Android: 8.0 (API 26) or newer

## Install

1. Install the APK from this repository's Releases.
2. Enable **PSwitch** in LSPosed / Vector and limit its scope to **Google Play Store**.
3. Force-stop Google Play Store once.
4. Open PSwitch and tap the account you want for purchases. Confirm in the purchase
   sheet. Switching later needs no restart.
5. Use **Reset** to return to the Play Store default account.

## Privacy

The account list and the chosen account stay on the device. Nothing is uploaded and the
module requests no network permission.

## Build

`build.sh` uses `aapt2`, `javac`, D8 and `jarsigner` directly (no Gradle). It downloads
the compile-only `libxposed:api:102.0.0` classes automatically:

```sh
bash build.sh
```

The signed APK is written to `PSwitch.apk`. You still need `aapt2`, an Android `android.jar`,
D8/R8 and `zipalign` available in your `PATH`; adjust the tool paths in `build.sh` for your
environment.

## Disclaimer

Unofficial module, not affiliated with Google. Use only on devices and accounts you own.
