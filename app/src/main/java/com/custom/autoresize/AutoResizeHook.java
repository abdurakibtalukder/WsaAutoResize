package com.custom.autoresize;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class AutoResizeHook implements IXposedHookLoadPackage {

    private static final String PREFS_NAME = "wsa_autoresize_prefs";
    private static final String KEY_NATIVE_PORTRAIT = "native_portrait";
    private static final String KEY_LAST_W = "last_portrait_w";
    private static final String KEY_LAST_H = "last_portrait_h";

    // System-side memory across app restarts within the WSA session
    private static final ConcurrentHashMap<String, Boolean> sPortraitPackages = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, int[]> sLastPortraitBounds = new ConcurrentHashMap<>();

    private static boolean isPortraitOrientation(int orientation) {
        return orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                || orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                || orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                || orientation == ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
    }

    private static boolean isSystemPackage(String pkg) {
        return pkg == null
                || "android".equals(pkg)
                || pkg.startsWith("com.android.systemui")
                || pkg.startsWith("com.microsoft.windows");
    }

    private static boolean isNativePortraitApp(Activity activity) {
        if (activity == null) return false;
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_NATIVE_PORTRAIT, false)) {
                return true;
            }
            ActivityInfo ai = (ActivityInfo) XposedHelpers.getObjectField(activity, "mActivityInfo");
            if (ai == null && activity.getPackageManager() != null && activity.getComponentName() != null) {
                ai = activity.getPackageManager().getActivityInfo(activity.getComponentName(), 0);
            }
            if (ai != null && isPortraitOrientation(ai.screenOrientation)) {
                prefs.edit().putBoolean(KEY_NATIVE_PORTRAIT, true).apply();
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void enforceAppPortraitIfNeeded(Activity activity) {
        if (activity == null || activity.getResources() == null) return;
        if (!isNativePortraitApp(activity)) return;

        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        if (dm == null || dm.widthPixels <= 0 || dm.heightPixels <= 0) return;

        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        if (dm.heightPixels >= dm.widthPixels) {
            // Valid portrait size: remember it
            int savedW = prefs.getInt(KEY_LAST_W, 0);
            int savedH = prefs.getInt(KEY_LAST_H, 0);
            if (savedW != dm.widthPixels || savedH != dm.heightPixels) {
                prefs.edit()
                        .putBoolean(KEY_NATIVE_PORTRAIT, true)
                        .putInt(KEY_LAST_W, dm.widthPixels)
                        .putInt(KEY_LAST_H, dm.heightPixels)
                        .apply();
            }
        } else {
            // Shrunk short or launched in landscape: do NOT save; force portrait back
            int curReq = activity.getRequestedOrientation();
            if (!isPortraitOrientation(curReq)) {
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
        }
    }

    private static boolean isRecordPortrait(Object activityRecord) {
        if (activityRecord == null) return false;
        try {
            String pkg = (String) XposedHelpers.getObjectField(activityRecord, "packageName");
            if (isSystemPackage(pkg)) return false;

            if (Boolean.TRUE.equals(sPortraitPackages.get(pkg))) {
                return true;
            }
            ActivityInfo info = (ActivityInfo) XposedHelpers.getObjectField(activityRecord, "info");
            if (info != null && isPortraitOrientation(info.screenOrientation)) {
                sPortraitPackages.put(pkg, Boolean.TRUE);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {

        // 1. SYSTEM FRAMEWORK: Suppress Size Compat Mode, allow resize, and guard portrait Tasks
        if ("android".equals(lpparam.packageName)) {
            try {
                XposedHelpers.findAndHookMethod(
                    "android.content.pm.ActivityInfo",
                    lpparam.classLoader,
                    "isResizeableMode",
                    int.class,
                    XC_MethodReplacement.returnConstant(true)
                );
            } catch (Throwable t) { XposedBridge.log(t); }

            try {
                Class<?> arClass = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityRecord", lpparam.classLoader
                );
                XposedBridge.hookAllMethods(arClass, "inSizeCompatMode", XC_MethodReplacement.returnConstant(false));
                XposedBridge.hookAllMethods(arClass, "shouldCreateCompatDisplayInsets", XC_MethodReplacement.returnConstant(false));
                XposedBridge.hookAllMethods(arClass, "isResizeable", XC_MethodReplacement.returnConstant(true));

                // Track and preserve portrait orientation at the ActivityRecord level
                XposedBridge.hookAllMethods(arClass, "setRequestedOrientation", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && param.args[0] instanceof Integer) {
                            int req = (Integer) param.args[0];
                            try {
                                String pkg = (String) XposedHelpers.getObjectField(param.thisObject, "packageName");
                                if (isSystemPackage(pkg)) return;

                                if (isPortraitOrientation(req)) {
                                    sPortraitPackages.put(pkg, Boolean.TRUE);
                                } else if (isRecordPortrait(param.thisObject)) {
                                    param.args[0] = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                });
            } catch (Throwable t) { XposedBridge.log(t); }

            // Guard Task.setBounds(Rect) so portrait apps never shrink short into landscape
            try {
                final Class<?> taskClass = XposedHelpers.findClass(
                    "com.android.server.wm.Task", lpparam.classLoader
                );
                Class<?> targetClass = taskClass;
                Method setBoundsMethod = null;
                while (targetClass != null && targetClass != Object.class) {
                    try {
                        setBoundsMethod = targetClass.getDeclaredMethod("setBounds", Rect.class);
                        break;
                    } catch (NoSuchMethodException e) {
                        targetClass = targetClass.getSuperclass();
                    }
                }

                if (setBoundsMethod != null) {
                    XposedBridge.hookMethod(setBoundsMethod, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!taskClass.isInstance(param.thisObject)) return;
                            if (param.args.length == 0 || !(param.args[0] instanceof Rect)) return;

                            Rect bounds = (Rect) param.args[0];
                            if (bounds == null || bounds.isEmpty()) return;

                            try {
                                Object top = null;
                                try {
                                    top = XposedHelpers.callMethod(param.thisObject, "getTopMostActivity");
                                } catch (Throwable ignored) {}
                                if (top == null) {
                                    try {
                                        top = XposedHelpers.callMethod(param.thisObject, "getRootActivity");
                                    } catch (Throwable ignored) {}
                                }
                                if (top == null || !isRecordPortrait(top)) return;

                                String pkg = (String) XposedHelpers.getObjectField(top, "packageName");
                                int w = bounds.width();
                                int h = bounds.height();

                                if (h >= w) {
                                    // Valid portrait resize: remember this size for future reopens
                                    if (w >= 200 && h >= 300) {
                                        sLastPortraitBounds.put(pkg, new int[]{w, h});
                                    }
                                } else {
                                    // Width > Height (shrunk short or launched wide): snap back to portrait
                                    int maxDisplayW = 1920;
                                    int maxDisplayH = 1080;
                                    try {
                                        Object dc = XposedHelpers.callMethod(param.thisObject, "getDisplayContent");
                                        if (dc != null) {
                                            Rect db = (Rect) XposedHelpers.callMethod(dc, "getBounds");
                                            if (db != null && db.width() > 0 && db.height() > 0) {
                                                maxDisplayW = db.width();
                                                maxDisplayH = db.height();
                                            }
                                        }
                                    } catch (Throwable ignored) {}

                                    int targetW;
                                    int targetH;
                                    int[] saved = sLastPortraitBounds.get(pkg);
                                    if (saved != null && saved[1] > saved[0]) {
                                        targetW = saved[0];
                                        targetH = saved[1];
                                    } else if (h >= 360 && w <= maxDisplayH - 40) {
                                        // Swap width & height into portrait if it fits vertically
                                        targetW = h;
                                        targetH = w;
                                    } else {
                                        // Compute clean portrait aspect clamped to display height
                                        targetH = Math.min(maxDisplayH - 60, Math.max(h, 760));
                                        targetW = Math.max(360, Math.round(targetH * 0.54f));
                                    }

                                    targetH = Math.min(targetH, maxDisplayH);
                                    targetW = Math.min(targetW, Math.min(maxDisplayW, targetH - 1));

                                    Rect fixed = new Rect(bounds);
                                    fixed.right = fixed.left + targetW;
                                    fixed.bottom = fixed.top + targetH;
                                    param.args[0] = fixed;
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                }
            } catch (Throwable t) { XposedBridge.log(t); }

            return;
        }

        // 2. SMART ORIENTATION (App Process): Preserve portrait apps; only unlock non-portrait apps
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                lpparam.classLoader,
                "setRequestedOrientation",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Activity activity = (Activity) param.thisObject;
                            int req = (Integer) param.args[0];

                            if (isPortraitOrientation(req)) {
                                // Record that this app natively expects portrait & keep it portrait
                                if (activity != null) {
                                    activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                            .edit()
                                            .putBoolean(KEY_NATIVE_PORTRAIT, true)
                                            .apply();
                                }
                                return;
                            }

                            if (isNativePortraitApp(activity)) {
                                // Never let a natively-portrait app switch to unspecified/landscape
                                param.args[0] = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                                return;
                            }

                            if (activity != null && activity.getResources() != null) {
                                DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                                if (dm != null && dm.widthPixels > dm.heightPixels) {
                                    param.args[0] = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            );
        } catch (Throwable t) { XposedBridge.log(t); }

        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                lpparam.classLoader,
                "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        enforceAppPortraitIfNeeded((Activity) param.thisObject);
                    }
                }
            );

            XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                lpparam.classLoader,
                "onConfigurationChanged",
                Configuration.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        enforceAppPortraitIfNeeded((Activity) param.thisObject);
                    }
                }
            );
        } catch (Throwable t) { XposedBridge.log(t); }

        // 3. RESPONSIVE DPI & DP RE-CALCULATION
        try {
            XposedHelpers.findAndHookMethod(
                "android.content.res.ResourcesImpl",
                lpparam.classLoader,
                "updateConfiguration",
                Configuration.class,
                DisplayMetrics.class,
                "android.content.res.CompatibilityInfo",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Configuration config = (Configuration) param.args[0];
                        DisplayMetrics metrics = (DisplayMetrics) param.args[1];
                        if (config != null && metrics != null && metrics.widthPixels > 0) {
                            int targetDpi = (metrics.widthPixels >= 1000) ? 150 : 180;
                            if (config.densityDpi > 0 && config.densityDpi != targetDpi) {
                                float ratio = (float) config.densityDpi / targetDpi;
                                if (config.screenWidthDp > 0) {
                                    config.screenWidthDp = Math.round(config.screenWidthDp * ratio);
                                }
                                if (config.screenHeightDp > 0) {
                                    config.screenHeightDp = Math.round(config.screenHeightDp * ratio);
                                }
                                if (config.smallestScreenWidthDp > 0) {
                                    config.smallestScreenWidthDp = Math.round(config.smallestScreenWidthDp * ratio);
                                }
                            }
                            float fontScale = (config.fontScale > 0) ? config.fontScale : 1.0f;
                            config.densityDpi = targetDpi;
                            metrics.densityDpi = targetDpi;
                            metrics.density = targetDpi / 160f;
                            metrics.scaledDensity = metrics.density * fontScale;
                        }
                    }
                }
            );
        } catch (Throwable t) { XposedBridge.log(t); }
    }
}
