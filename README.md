# X Attestation Fix

An LSPosed module that rescues the X (formerly Twitter) Android app's hardware
key attestation on devices where the TEE's Remote Key Provisioning path
(CSR v2 / RKP) is broken.

## The problem

To make signed API requests (login, posting, and so on) X generates an Android
Key Attestation key through `KeyPairGenerator` with the `AndroidKeyStore`
provider, gets a certificate chain, and exchanges it for a
`HardwareAttestationToken`. On some devices the TEE cannot generate an
attestation key at all. `generateKeyPair()` fails:

```
E/keystore2: security_level.rs: Error::Km(SECURE_HW_COMMUNICATION_FAILED) (internal Keystore code: -49)
java.security.ProviderException: Failed to generate key pair
```

(On older builds the same breakage surfaced as CSR v2 error `-18`.) With no
attestation key, X never obtains a token, its signing gate rejects every signed
request, and the user is told:

> Please use official X apps to proceed or try again later.

## How the fix works

The module does **not** hook any of X's own classes. Those are obfuscated and
renamed on every release (the attestation keygen moved from class `y1` to `t1`
in a single month), so pinning their names guarantees the module breaks on the
next update. Instead it hooks the stable Android framework API and uses a
**TEE first, StrongBox only on failure** strategy:

1. X's own key generation runs first, completely untouched.
2. When a `KeyGenParameterSpec.Builder` produces an attestation key (detected via
   the public `getAttestationChallenge()` on the built spec), the module
   pre-computes a StrongBox twin of that exact spec from X's own `Builder`,
   inheriting X's algorithm, purpose, digests, key size, curve, and alias.
3. Only if `generateKeyPair()` actually throws does the module re-initialize the
   same generator with the StrongBox twin and retry, under the identical keystore
   alias. StrongBox has its own pre-provisioned keys and does not depend on the
   broken TEE RKP path.

Because the StrongBox branch runs only after a real TEE failure, a device whose
TEE path already works is never touched: behaviour is identical to not having
the module, so it cannot regress a working device. It is gated on
`FEATURE_STRONGBOX_KEYSTORE` and fails closed if StrongBox is absent.

## When this helps, and when it does not

This module fixes exactly one thing: an attestation key that **fails to
generate** because the TEE path is broken, on a device that **has a working
StrongBox** to fall back to. It produces a genuine hardware attestation and does
**not** forge anything.

It cannot help if:

- **Your device has no StrongBox.** Check with `adb shell pm list features | grep
  strongbox`. If that prints nothing (true for many phones, including most
  OnePlus / Oppo models before the OnePlus 13), there is nothing to fall back to.
- **X rejects your device for its boot state or integrity**, not for a keygen
  crash. This module does not spoof `deviceLocked` or `verifiedBootState`; the
  chain it produces reflects the real state of the device. On the reference
  OnePlus 13 the genuine StrongBox attestation reports `deviceLocked = false` and
  `verifiedBootState = orange`, and X accepts it, so a genuine chain is enough
  there. On a device where X refuses an unlocked bootloader, a genuine chain will
  not satisfy it.

For those cases you need a **keybox-based** solution that mints a fully valid,
forged attestation in software (no StrongBox required, and it can present a
locked / verified chain):

- [TEESimulator](https://github.com/JingMatrix/TEESimulator): set Operation mode
  to *generate* and add `com.twitter.android` to its target apps.
- tricky_store: add `com.twitter.android` to `target.txt` (needs a valid keybox).

Do not run this module and a keybox solution against X at the same time; pick
one. If you use a keybox tool for X, disable this module.

## Requirements

- Rooted device (Magisk or KernelSU) with Zygisk / ReZygisk and LSPosed
- A StrongBox-capable device. Check with:
  ```kotlin
  context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
  ```
  If the device has no StrongBox, this fallback cannot help; the TEE path needs a
  firmware or OS fix instead.
- The X (Twitter) app, package `com.twitter.android`

## Install

1. Download the APK from the releases page, or build it from source (below).
2. In LSPosed Manager, enable the module. Its scope defaults to
   `com.twitter.android` (shown as "Recommended").
3. Force-stop X and relaunch it.
4. Verify the module loaded:
   ```
   adb logcat -s XAttestFix
   ```
   On launch you should see:
   ```
   I/XAttestFix: loaded for com.twitter.android; StrongBox TEE-failover armed
   ```
   The next time X generates an attestation key on a broken-TEE device you will
   see:
   ```
   I/XAttestFix: StrongBox available = true
   W/XAttestFix: TEE attestation keygen failed; StrongBox fallback succeeded
   ```
   If the TEE path already works, the module stays silent after the load line,
   which is expected.

## Build from source

Requirements: JDK 17+, Android SDK with `platforms/android-35` and
`build-tools/35.0.0`, and Python 3.

```bash
./build.sh
```

The script performs a full from-scratch build (no prebuilt base APK):
`aapt2` compiles the manifest and resources, `javac` compiles the hook against
the Android SDK and the compile-only Xposed stubs, `d8` dexes only the module
classes (never the stubs, which would shadow LSPosed's runtime API), and the dex
is spliced into the APK, zipaligned, and signed with a local debug key. Output:
`build/xbypass-fix.apk`. Installing over a copy signed with a different key
requires an uninstall first.

## What the module touches

| Class | Method | Hook | Purpose |
|-------|--------|------|---------|
| `android.security.keystore.KeyGenParameterSpec$Builder` | `build()` | after | Detect attestation keys, pre-compute the StrongBox twin |
| `java.security.KeyPairGenerator$Delegate` | `initialize(...)` | after | Associate a generator with its StrongBox twin |
| `java.security.KeyPairGenerator$Delegate` | `generateKeyPair()` | after | Retry on StrongBox only if the TEE attempt threw |

It runs only inside `com.twitter.android`. It does not modify network requests
or responses, inject tokens, bypass certificate pinning, alter Play Integrity
results, or touch signing keys. It never deletes a keystore entry; the retry
regenerates under the same alias, which AndroidKeyStore overwrites in place. The
`app_attestation` (Play Integrity) flow is a separate path and is not touched.

## Verified

Reproduced and confirmed on a OnePlus 13 (CPH2653), Android 16, security patch
2026-07-01, X v12.17.0-release.0 (312170000):

- A fresh TEE attestation key generation fails with
  `SECURE_HW_COMMUNICATION_FAILED`.
- With the module active, the same request is transparently regenerated on
  StrongBox: the resulting key reports `securityLevel = STRONGBOX` with a
  5-certificate chain.
- The StrongBox attestation's RootOfTrust reports `deviceLocked = false` and
  `verifiedBootState = orange` (the real state; this device's green/locked
  `getprop` values come from a separate Play Integrity spoof and are not what the
  attestation contains). X accepts this genuine chain on this device.

## Limitations

- The fallback can only rescue a device that actually has StrongBox. Without it,
  the module fails closed and does nothing. See "When this helps" above.
- It never forges `deviceLocked` or `verifiedBootState`. If X rejects your
  device for its boot state or a Play Integrity verdict rather than a keygen
  crash, use a keybox solution (TEESimulator / tricky_store) instead.
- Server-side rejection of a genuine chain is the one failure a client-side
  keygen fallback cannot observe (it produces no local exception). Because the
  module is TEE first, it only acts after the TEE keygen has already failed, so
  it never makes a working device worse.

## License

MIT
