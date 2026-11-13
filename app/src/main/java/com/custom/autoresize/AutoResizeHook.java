package com.custom.autoresize;

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

        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                lpparam.classLoader,
                "setRequestedOrientation",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.args[0] = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
                    }
                }
            );
        } catch (Throwable t) { XposedBridge.log(t); }

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
