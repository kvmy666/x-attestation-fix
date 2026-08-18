package io.github.mara.xbypass;

import android.content.Context;
import android.content.pm.PackageManager;
import android.security.keystore.KeyGenParameterSpec;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;

/**
 * Rescues Android Key Attestation in the X (Twitter) app on devices where the
 * TEE's Remote Key Provisioning path (CSR v2 / RKP) is broken.
 *
 * <p>Symptom: {@code KeyPairGenerator.generateKeyPair()} fails for an attestation
 * key with {@code SECURE_HW_COMMUNICATION_FAILED} (historically surfaced as CSR
 * v2 error {@code -18}). X can then never obtain a HardwareAttestationToken, its
 * signing gate rejects every signed request, and login fails with
 * "Please use official X apps to proceed or try again later."
 *
 * <p>Strategy — <b>TEE first, StrongBox only on failure</b>. This hooks the stable
 * framework API ({@link KeyGenParameterSpec.Builder} / {@link KeyPairGenerator}),
 * never any of X's obfuscated classes (which are renamed every release — the
 * attestation keygen moved {@code y1 -> t1} in a single month). X's own TEE key
 * generation runs first, completely untouched. Only if it actually throws do we
 * re-generate the same key on StrongBox and let X continue. On a device whose TEE
 * path already works the StrongBox branch never executes, so behaviour is
 * byte-for-byte identical to not having the module — it cannot regress a working
 * device. On a broken-TEE device with StrongBox, the attestation succeeds.
 *
 * <p>Active only inside {@code com.twitter.android}, only for keys that request
 * attestation, and only when the device actually has StrongBox.
 */
public final class StrongBoxHook implements IXposedHookLoadPackage {

    private static final String TAG = "XAttestFix";
    private static final String TARGET_PACKAGE = "com.twitter.android";

    // Xposed additional-instance-field keys (in-memory tags; never touch the object's real fields).
    private static final String TAG_SB_SPEC = "xbypass_sb_spec";      // on a TEE spec: its StrongBox twin
    private static final String TAG_KPG_SB_SPEC = "xbypass_kpg_sb";   // on a KeyPairGenerator: twin to retry with

    /** Tri-state StrongBox availability cache: null = unknown, otherwise resolved. */
    private volatile Boolean strongBoxAvailable = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;

        try {
            // After each Builder.build(), if the produced spec is an attestation key, pre-compute a
            // StrongBox twin from the SAME Builder and stash it (used only if TEE keygen later fails).
            // Detection uses the public getter on the BUILT spec, so ART inlining of the trivial
            // setAttestationChallenge setter can never hide the attestation request from us.
            XposedHelpers.findAndHookMethod(KeyGenParameterSpec.Builder.class, "build", new BuildHook());

            // KeyPairGenerator.getInstance(alg, "AndroidKeyStore") returns a java.security.KeyPairGenerator$Delegate
            // that OVERRIDES initialize()/generateKeyPair(), so hooking the base class alone never fires. Hook the
            // Delegate (what AndroidKeyStore actually uses) and the base class (other providers/direct subclasses).
            hookKeyPairGenerator(KeyPairGenerator.class);
            final Class<?> delegate = XposedHelpers.findClassIfExists(
                    "java.security.KeyPairGenerator$Delegate", lpparam.classLoader);
            if (delegate != null && delegate != KeyPairGenerator.class) hookKeyPairGenerator(delegate);

            Log.i(TAG, "loaded for " + lpparam.packageName + "; StrongBox TEE-failover armed");
        } catch (Throwable t) {
            Log.e(TAG, "hook setup failed", t);
        }
    }

    /** Hooks initialize()/generateKeyPair() on a KeyPairGenerator class (base or its AndroidKeyStore Delegate). */
    private void hookKeyPairGenerator(Class<?> cls) {
        final XC_MethodHook linkSpec = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                linkStrongBoxSpec(param.thisObject, param.args.length > 0 ? param.args[0] : null);
            }
        };
        try {
            XposedHelpers.findAndHookMethod(cls, "initialize", AlgorithmParameterSpec.class, linkSpec);
        } catch (Throwable ignored) { }
        try {
            XposedHelpers.findAndHookMethod(cls, "initialize",
                    AlgorithmParameterSpec.class, SecureRandom.class, linkSpec);
        } catch (Throwable ignored) { }
        try {
            XposedHelpers.findAndHookMethod(cls, "generateKeyPair", new GenerateKeyPairHook());
        } catch (Throwable ignored) { }
    }

    private final class BuildHook extends XC_MethodHook {
        @Override protected void afterHookedMethod(MethodHookParam param) {
            try {
                final Object teeSpec = param.getResult();
                if (!(teeSpec instanceof KeyGenParameterSpec)) return;
                if (((KeyGenParameterSpec) teeSpec).getAttestationChallenge() == null) return; // not attestation

                final Object builder = param.thisObject;
                final Method setStrongBox = KeyGenParameterSpec.Builder.class
                        .getMethod("setIsStrongBoxBacked", boolean.class);
                final Method build = KeyGenParameterSpec.Builder.class.getMethod("build");

                // Build a StrongBox twin from X's OWN Builder — inheriting X's exact algorithm, purpose,
                // digests, key size, curve and alias — differing only by the StrongBox flag. invokeOriginalMethod
                // bypasses our hooks, so this neither re-enters BuildHook nor double-fires.
                XposedBridge.invokeOriginalMethod(setStrongBox, builder, new Object[]{ Boolean.TRUE });
                final Object strongBoxSpec = XposedBridge.invokeOriginalMethod(build, builder, new Object[0]);
                XposedBridge.invokeOriginalMethod(setStrongBox, builder, new Object[]{ Boolean.FALSE }); // restore

                // Attach the twin to the TEE spec; X keeps using its own untouched TEE spec.
                XposedHelpers.setAdditionalInstanceField(teeSpec, TAG_SB_SPEC, strongBoxSpec);
            } catch (Throwable t) {
                Log.w(TAG, "could not prepare StrongBox fallback spec; TEE path unaffected", t);
            }
        }
    }

    private static void linkStrongBoxSpec(Object keyPairGenerator, Object spec) {
        try {
            if (spec == null) return;
            final Object strongBoxSpec = XposedHelpers.getAdditionalInstanceField(spec, TAG_SB_SPEC);
            if (strongBoxSpec != null) {
                XposedHelpers.setAdditionalInstanceField(keyPairGenerator, TAG_KPG_SB_SPEC, strongBoxSpec);
            }
        } catch (Throwable ignored) {
        }
    }

    private final class GenerateKeyPairHook extends XC_MethodHook {
        @Override protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!param.hasThrowable()) return;                    // TEE succeeded: do nothing (no regression)

                final Object kpg = param.thisObject;
                final Object strongBoxSpec = XposedHelpers.getAdditionalInstanceField(kpg, TAG_KPG_SB_SPEC);
                if (strongBoxSpec == null) return;                    // not an attestation keygen we prepared

                if (!isStrongBoxAvailable()) return;                  // fail closed: keep the original TEE error

                final Throwable teeError = param.getThrowable();
                try {
                    // Re-init the SAME KeyPairGenerator with the StrongBox twin and retry the original method.
                    // NOTE (load-bearing): X's attestation code ignores the returned KeyPair and re-reads
                    // KeyStore.getCertificateChain(alias). Correctness therefore relies on regenerating under
                    // the IDENTICAL keystore alias — which the twin carries — and AndroidKeyStore overwrites the
                    // entry in place, so NO deleteEntry is needed (and none is done).
                    XposedHelpers.callMethod(kpg, "initialize", strongBoxSpec);
                    final Object keyPair = XposedBridge.invokeOriginalMethod(param.method, kpg, new Object[0]);
                    param.setResult(keyPair);                         // clears the throwable so X proceeds
                    Log.w(TAG, "TEE attestation keygen failed; StrongBox fallback succeeded", teeError);
                } catch (Throwable strongBoxError) {
                    Log.w(TAG, "StrongBox fallback also failed; leaving original TEE error in place",
                            strongBoxError);
                    // Do NOT rethrow: X keeps its original TEE throwable, the real root cause.
                }
            } catch (Throwable t) {
                Log.e(TAG, "generateKeyPair post-hook failed", t);
            }
        }
    }

    private boolean isStrongBoxAvailable() {
        final Boolean cached = strongBoxAvailable;
        if (cached != null) return cached;
        try {
            final Class<?> activityThread = Class.forName("android.app.ActivityThread");
            final Object app = XposedHelpers.callStaticMethod(activityThread, "currentApplication");
            if (app instanceof Context) {
                final boolean has = ((Context) app).getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE);
                strongBoxAvailable = has;
                Log.i(TAG, "StrongBox available = " + has);
                return has;
            }
        } catch (Throwable t) {
            Log.w(TAG, "StrongBox feature check failed; treating as unavailable", t);
        }
        // Context not ready: fail closed. This path is only reached after a real TEE failure, so a
        // false negative merely preserves the pre-existing failure — it never regresses a working device.
        return false;
    }
}
