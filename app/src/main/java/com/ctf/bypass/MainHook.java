package com.ctf.bypass;

import android.content.SharedPreferences;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "com.sollinplayer.leguan";

    private static final String FAKE_STATUS = "{\"isActivated\":true," +
            "\"activationCategory\":\"sponsor\"," +
            "\"normalizedActivationCategory\":\"sponsor\"}";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;

        XposedBridge.log("[Bypass] SollinPlayer hooked: " + lpparam.packageName);

        hookSharedPreferences();
        hookFlutterSPPlugin();
    }

    // ===== Hook 1: Generic SharedPreferences.getString =====
    private void hookSharedPreferences() {
        // Hook android.app.SharedPreferencesImpl (the real implementation)
        XposedHelpers.findAndHookMethod(
                "android.app.SharedPreferencesImpl",
                null,
                "getString",
                String.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[0];
                        if (key == null) return;

                        XposedBridge.log("[Bypass] SP.getString key=" + key);

                        if (key.contains("flutter.activation") ||
                                key.contains("flutter.sponsor")) {
                            param.setResult(FAKE_STATUS);
                            XposedBridge.log("[Bypass] -> FAKE injected");
                        } else if (key.contains("activation_usage_online_download_count")) {
                            param.setResult("0");
                        } else if (key.contains("activation_usage_online_download_date")) {
                            param.setResult("2025-01-01");
                        } else if (key.contains("remote_config_cache")) {
                            param.setResult("{\"forceDisableSponsorOnlyFeatures\":false," +
                                    "\"builtinApiEnabled\":true}");
                        }
                    }
                }
        );

        // Hook getBoolean for feature flags
        XposedHelpers.findAndHookMethod(
                "android.app.SharedPreferencesImpl",
                null,
                "getBoolean",
                String.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String key = (String) param.args[0];
                        if (key == null) return;

                        if (key.contains("forceDisableSponsorOnlyFeatures")) {
                            param.setResult(false);
                            XposedBridge.log("[Bypass] SP.getBoolean " + key + " -> false");
                        } else if (key.contains("builtinApiEnabled") ||
                                key.contains("Activated") ||
                                key.contains("isSponsor")) {
                            param.setResult(true);
                            XposedBridge.log("[Bypass] SP.getBoolean " + key + " -> true");
                        }
                    }
                }
        );
    }

    // ===== Hook 2: Flutter SharedPreferencesPlugin.onMethodCall =====
    // Flutter 的 shared_preferences 通过 MethodChannel 走这里
    private void hookFlutterSPPlugin() {
        try {
            // Method: public void onMethodCall(MethodCall call, Result result)
            XposedHelpers.findAndHookMethod(
                    "io.flutter.plugins.sharedpreferences.SharedPreferencesPlugin",
                    null,
                    "onMethodCall",
                    "io.flutter.plugin.common.MethodCall",
                    "io.flutter.plugin.common.MethodChannel$Result",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object call = param.args[0];
                                String method = (String) XposedHelpers.callMethod(call, "method");

                                if ("getAll".equals(method)) {
                                    // Return full fake prefs map
                                    XposedBridge.log("[Bypass] FlutterSP.getAll intercepted");
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[Bypass] FlutterSP hook error: " + t.getMessage());
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object call = param.args[0];
                                String method = (String) XposedHelpers.callMethod(call, "method");

                                if (method != null && method.startsWith("get")) {
                                    Object result = param.getResult();
                                    String key = (String) XposedHelpers.callMethod(
                                            XposedHelpers.getObjectField(call, "arguments"), "get", 0);

                                    if (key != null && (key.toString().contains("activation") ||
                                            key.toString().contains("sponsor") ||
                                            key.toString().contains("remote_config"))) {

                                        XposedBridge.log("[Bypass] FlutterSP.get key=" +
                                                key + " orig_result=" + result);

                                        // Override result
                                        Object resultObj = param.args[1];
                                        if (key.toString().contains("activation_status") ||
                                                key.toString().contains("sponsor_status")) {
                                            XposedHelpers.callMethod(resultObj, "success", FAKE_STATUS);
                                            param.setResult(null);
                                        } else if (key.toString().contains("activation_category")) {
                                            XposedHelpers.callMethod(resultObj, "success", "sponsor");
                                            param.setResult(null);
                                        } else if (key.toString().contains("remote_config_cache")) {
                                            XposedHelpers.callMethod(resultObj, "success",
                                                    "{\"forceDisableSponsorOnlyFeatures\":false," +
                                                            "\"builtinApiEnabled\":true}");
                                            param.setResult(null);
                                        } else if (key.toString().contains("forceDisableSponsor")) {
                                            XposedHelpers.callMethod(resultObj, "success", false);
                                            param.setResult(null);
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("[Bypass] FlutterSP afterHook error: " + t.getMessage());
                            }
                        }
                    }
            );
            XposedBridge.log("[Bypass] FlutterSharedPreferencesPlugin hooked");
        } catch (Throwable t) {
            XposedBridge.log("[Bypass] SharedPreferencesPlugin not found: " + t.getMessage());
        }
    }
}
