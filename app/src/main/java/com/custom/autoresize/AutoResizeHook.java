package com.custom.autoresize;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class AutoResizeHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
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
            } catch (Throwable t) { XposedBridge.log(t); }
            return;
        }

        // Smart orientation: only unlock when the window is actually wider than tall
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

        // Auto-scale DPI + sync dp dimensions cleanly on first frame
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
                            config.densityDpi = targetDpi;
                            metrics.densityDpi = targetDpi;
                            metrics.density = targetDpi / 160f;
                            metrics.scaledDensity = metrics.density * config.fontScale;
                        }
                    }
                }
            );
        } catch (Throwable t) { XposedBridge.log(t); }
    }
}
