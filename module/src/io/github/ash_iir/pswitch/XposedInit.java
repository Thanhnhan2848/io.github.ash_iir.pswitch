package io.github.ash_iir.pswitch;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public class XposedInit extends XposedModule {

    private static final String TAG = "PSwitch";
    private static final String VENDING = "com.android.vending";
    private static final String GOOGLE_ONE = "com.google.android.apps.subscriptions.red";
    private static final String MAIHOKU = "io.github.xiaotong6666.maihoku";
    private static final String PAID_TASKS = "com.google.android.apps.paidtasks";
    private static final String APP_PACKAGE = "io.github.ash_iir.pswitch";
    private static final String RESET_BTN_TAG = "pswitch_quick_reset_btn";
    private static final String TARGET_FILENAME = "pswitch_target.txt";

    private static final Uri PROVIDER_URI =
            Uri.parse("content://io.github.ash_iir.pswitch.provider/target");

    private static final String ACTION_SET = "io.github.ash_iir.pswitch.SET_ACCOUNT";
    private static final String ACTION_REQUEST = "io.github.ash_iir.pswitch.REQUEST_ACCOUNTS";
    private static final String ACTION_ACCOUNTS = "io.github.ash_iir.pswitch.ACCOUNTS";

    private static final String[] BILLING_KEYS = new String[]{
            "BillingAccountModel.billingAccountName",
            "authAccount",
            "accountName",
            "account_name",
            "selected_account",
            "buyer_account"
    };

    private static XposedInit self;
    private static Context appContext;
    private static ClassLoader packageLoader;
    private static String currentPackage;
    private static boolean isVendingProcess;
    private static boolean isPaidTasksProcess;

    private static volatile String overrideTarget;
    private static volatile boolean hasOverride;
    private static volatile String cachedProvider;
    private static volatile long cachedVersion = -1;
    private static volatile boolean providerCached;

    private static final ThreadLocal<Boolean> bypassAccountFilter = new ThreadLocal<>();

    private static final Set<String> knownGoogleEmails =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> inspectedClasses =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> hookedMethods =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> loggedOnce =
            Collections.synchronizedSet(new HashSet<String>());

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        self = this;
        logI("Loaded API " + getApiVersion() + " in " + param.getProcessName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (APP_PACKAGE.equals(pkg)) {
            return;
        }
        currentPackage = pkg;
        isVendingProcess = VENDING.equals(pkg);
        isPaidTasksProcess = PAID_TASKS.equals(pkg);
        packageLoader = param.getClassLoader();
        logI("onPackageReady: " + pkg);

        hookApplication();
        hookAccountManager();
        hookActivityLaunch();
        if (isPaidTasksProcess) {
            hookPaidTasksQuickReset();
        }
    }

    private void hookApplication() {
        try {
            Method onCreate = Application.class.getDeclaredMethod("onCreate");
            hookMethod(onCreate).setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            Object res = chain.proceed();
                            Object app = chain.getThisObject();
                            if (app instanceof Context) {
                                appContext = (Context) app;
                                registerReceivers(appContext);
                                // Đồng bộ ngay với provider nếu đang sống, ngược lại giữ file đã lưu
                                readProvider();
                            }
                            return res;
                        }
                    });
            logI("Hooked Application.onCreate in " + currentPackage);
        } catch (Throwable t) {
            logE("hookApplication failed: " + t);
        }
    }

    private void hookAccountManager() {
        try {
            Method getByType = AccountManager.class.getDeclaredMethod(
                    "getAccountsByType", String.class);
            hookMethod(getByType).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Object res = chain.proceed();
                    if (!(res instanceof Account[])) {
                        return res;
                    }
                    Object arg0 = chain.getArg(0);
                    if (arg0 != null && !"com.google".equals(arg0.toString())) {
                        return res;
                    }
                    Account[] raw = (Account[]) res;
                    recordKnownEmails(raw);
                    if (Boolean.TRUE.equals(bypassAccountFilter.get())) {
                        return raw;
                    }
                    if (isVendingProcess) {
                        discoverFromStackTrace(new Throwable().getStackTrace());
                    }
                    String target = readTarget();
                    if (target == null) {
                        return raw;
                    }
                    return isolateTargetAccount(raw, target, true, "getAccountsByType");
                }
            });
            logI("Hooked AccountManager.getAccountsByType");
        } catch (Throwable t) {
            logE("hook getAccountsByType failed: " + t);
        }

        try {
            Method getAll = AccountManager.class.getDeclaredMethod("getAccounts");
            hookMethod(getAll).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Object res = chain.proceed();
                    if (!(res instanceof Account[])) {
                        return res;
                    }
                    Account[] raw = (Account[]) res;
                    recordKnownEmails(raw);
                    if (Boolean.TRUE.equals(bypassAccountFilter.get())) {
                        return raw;
                    }
                    if (isVendingProcess) {
                        discoverFromStackTrace(new Throwable().getStackTrace());
                    }
                    String target = readTarget();
                    if (target == null) {
                        return raw;
                    }
                    return isolateTargetAccount(raw, target, false, "getAccounts");
                }
            });
            logI("Hooked AccountManager.getAccounts");
        } catch (Throwable t) {
            logE("hook getAccounts failed: " + t);
        }

        try {
            Method getByPkg = AccountManager.class.getDeclaredMethod(
                    "getAccountsByTypeForPackage", String.class, String.class);
            hookMethod(getByPkg).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Object res = chain.proceed();
                    if (!(res instanceof Account[])) {
                        return res;
                    }
                    Object arg0 = chain.getArg(0);
                    if (arg0 != null && !"com.google".equals(arg0.toString())) {
                        return res;
                    }
                    Account[] raw = (Account[]) res;
                    recordKnownEmails(raw);
                    if (Boolean.TRUE.equals(bypassAccountFilter.get())) {
                        return raw;
                    }
                    if (isVendingProcess) {
                        discoverFromStackTrace(new Throwable().getStackTrace());
                    }
                    String target = readTarget();
                    if (target == null) {
                        return raw;
                    }
                    return isolateTargetAccount(raw, target, true, "getAccountsByTypeForPackage");
                }
            });
            logI("Hooked AccountManager.getAccountsByTypeForPackage");
        } catch (Throwable ignored) {
        }
    }

    private static void recordKnownEmails(Account[] accounts) {
        if (accounts == null) {
            return;
        }
        for (Account a : accounts) {
            if (a != null && "com.google".equals(a.type) && a.name != null) {
                knownGoogleEmails.add(a.name.toLowerCase());
            }
        }
    }

    private static Account[] isolateTargetAccount(
            Account[] original, String target, boolean googleOnly, String src) {
        if (original == null || original.length == 0) {
            return original;
        }
        Account matched = null;
        List<Account> nonGoogle = new ArrayList<>();
        for (Account a : original) {
            if (a == null) {
                continue;
            }
            if ("com.google".equals(a.type)) {
                if (target.equalsIgnoreCase(a.name)) {
                    matched = a;
                }
            } else if (!googleOnly) {
                nonGoogle.add(a);
            }
        }
        if (matched == null) {
            return original;
        }
        List<Account> out = new ArrayList<>(1 + nonGoogle.size());
        out.add(matched);
        out.addAll(nonGoogle);
        logOnce("isolate:" + currentPackage + ":" + src + ":" + target,
                "[" + currentPackage + "] Isolated Google account to [" + target + "] via "
                        + src + " (was " + original.length + " accounts)");
        return out.toArray(new Account[out.size()]);
    }

    private void hookActivityLaunch() {
        try {
            Method callOnCreate = Instrumentation.class.getDeclaredMethod(
                    "callActivityOnCreate", Activity.class, Bundle.class);
            hookMethod(callOnCreate).setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            Object arg0 = chain.getArg(0);
                            Object arg1 = chain.getArg(1);
                            if (arg0 instanceof Activity) {
                                handleActivityBeforeCreate((Activity) arg0,
                                        arg1 instanceof Bundle ? (Bundle) arg1 : null);
                            }
                            return chain.proceed();
                        }
                    });
            logI("Hooked Instrumentation.callActivityOnCreate(Activity,Bundle)");
        } catch (Throwable t) {
            logE("hook callActivityOnCreate failed: " + t);
        }

        try {
            Class<?> pbCls = Class.forName("android.os.PersistableBundle");
            Method callOnCreate3 = Instrumentation.class.getDeclaredMethod(
                    "callActivityOnCreate", Activity.class, Bundle.class, pbCls);
            hookMethod(callOnCreate3).setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            Object arg0 = chain.getArg(0);
                            Object arg1 = chain.getArg(1);
                            if (arg0 instanceof Activity) {
                                handleActivityBeforeCreate((Activity) arg0,
                                        arg1 instanceof Bundle ? (Bundle) arg1 : null);
                            }
                            return chain.proceed();
                        }
                    });
        } catch (Throwable ignored) {
        }

        try {
            Method onNewIntent = Instrumentation.class.getDeclaredMethod(
                    "callActivityOnNewIntent", Activity.class, Intent.class);
            hookMethod(onNewIntent).setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            Object arg0 = chain.getArg(0);
                            Object arg1 = chain.getArg(1);
                            String target = readTarget();
                            if (target != null && arg1 instanceof Intent) {
                                String where = (arg0 != null ? arg0.getClass().getName() : "Act")
                                        + "#newIntent";
                                patchIntent((Intent) arg1, target, where);
                            }
                            return chain.proceed();
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    /**
     * Chèn nút nổi "Reset Acc" trong Google Phần thưởng cho ý kiến.
     */
    private void hookPaidTasksQuickReset() {
        try {
            Method onResume = Instrumentation.class.getDeclaredMethod(
                    "callActivityOnResume", Activity.class);
            hookMethod(onResume).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Object res = chain.proceed();
                    Object arg0 = chain.getArg(0);
                    if (arg0 instanceof Activity) {
                        injectResetButton((Activity) arg0);
                    }
                    return res;
                }
            });
            logI("Hooked Instrumentation.callActivityOnResume for PaidTasks quick reset");
        } catch (Throwable t) {
            logE("hookPaidTasksQuickReset failed: " + t);
        }
    }

    private static void injectResetButton(final Activity act) {
        try {
            if (act == null || act.isFinishing() || act.getWindow() == null) {
                return;
            }
            View decor = act.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) {
                return;
            }
            ViewGroup root = (ViewGroup) decor;
            if (root.findViewWithTag(RESET_BTN_TAG) != null) {
                return;
            }

            TextView btn = new TextView(act);
            btn.setTag(RESET_BTN_TAG);
            btn.setText("Reset Acc");
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            int padH = dp(act, 14);
            int padV = dp(act, 8);
            btn.setPadding(padH, padV, padH, padV);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xD9C62828);
            bg.setCornerRadius(dp(act, 20));
            btn.setBackgroundDrawable(bg);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            lp.setMargins(0, 0, dp(act, 16), dp(act, 88));

            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showResetConfirmDialog(act);
                }
            });

            root.addView(btn, lp);
        } catch (Throwable t) {
            logW("injectResetButton failed: " + t);
        }
    }

    private static void showResetConfirmDialog(final Activity act) {
        try {
            final Dialog dialog = new Dialog(act);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            LinearLayout card = new LinearLayout(act);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(act, 22), dp(act, 20), dp(act, 22), dp(act, 18));

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(0xFF1F2024);
            cardBg.setCornerRadius(dp(act, 22));
            cardBg.setStroke(dp(act, 1), 0xFF33353B);
            card.setBackgroundDrawable(cardBg);

            TextView title = new TextView(act);
            title.setText("Đăng xuất & Xóa dữ liệu");
            title.setTextColor(0xFFF1F3F4);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            title.setTypeface(null, Typeface.BOLD);
            card.addView(title);

            TextView msg = new TextView(act);
            msg.setText("Xóa toàn bộ dữ liệu Google Phần thưởng cho ý kiến và thoát app?");
            msg.setTextColor(0xFFBDC1C6);
            msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            msg.setLineSpacing(dp(act, 3), 1.0f);
            LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            msgLp.setMargins(0, dp(act, 10), 0, dp(act, 22));
            card.addView(msg, msgLp);

            LinearLayout btnRow = new LinearLayout(act);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);

            TextView cancelBtn = new TextView(act);
            cancelBtn.setText("Hủy");
            cancelBtn.setTextColor(0xFFDADCE0);
            cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            cancelBtn.setPadding(dp(act, 18), dp(act, 9), dp(act, 18), dp(act, 9));
            GradientDrawable cancelBg = new GradientDrawable();
            cancelBg.setColor(0xFF2E3036);
            cancelBg.setCornerRadius(dp(act, 16));
            cancelBtn.setBackgroundDrawable(cancelBg);
            LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cancelLp.setMargins(0, 0, dp(act, 10), 0);
            cancelBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
            btnRow.addView(cancelBtn, cancelLp);

            TextView confirmBtn = new TextView(act);
            confirmBtn.setText("Xóa ngay");
            confirmBtn.setTextColor(Color.WHITE);
            confirmBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            confirmBtn.setTypeface(null, Typeface.BOLD);
            confirmBtn.setPadding(dp(act, 18), dp(act, 9), dp(act, 18), dp(act, 9));
            GradientDrawable confirmBg = new GradientDrawable();
            confirmBg.setColor(0xFFC62828);
            confirmBg.setCornerRadius(dp(act, 16));
            confirmBtn.setBackgroundDrawable(confirmBg);
            confirmBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    clearOwnAppData(act);
                }
            });
            btnRow.addView(confirmBtn);

            card.addView(btnRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            dialog.setContentView(card);
            dialog.setCanceledOnTouchOutside(true);

            Window win = dialog.getWindow();
            if (win != null) {
                win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                int width = Math.round(act.getResources().getDisplayMetrics().widthPixels * 0.84f);
                win.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }

            dialog.show();
        } catch (Throwable t) {
            logW("showResetConfirmDialog failed: " + t);
            clearOwnAppData(act);
        }
    }

    private static void clearOwnAppData(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                Method clearMethod = ActivityManager.class.getMethod("clearApplicationUserData");
                Object ok = clearMethod.invoke(am);
                logI("clearApplicationUserData() -> " + ok);
                return;
            }
        } catch (Throwable t) {
            logW("clearApplicationUserData reflection failed: " + t);
        }

        try {
            File dataDir = ctx.getFilesDir().getParentFile();
            if (dataDir != null && dataDir.exists()) {
                File[] children = dataDir.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (!"lib".equals(child.getName())) {
                            deleteRecursive(child);
                        }
                    }
                }
            }
            Toast.makeText(ctx, "Đã xóa dữ liệu, hãy mở lại app", Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        f.delete();
    }

    private static int dp(Context ctx, int value) {
        float density = ctx.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private static void handleActivityBeforeCreate(Activity act, Bundle savedState) {
        String target = readTarget();
        if (target == null || act == null) {
            return;
        }
        String clsName = act.getClass().getName();
        Intent intent = act.getIntent();
        if (intent != null) {
            if (isVendingProcess && (clsName.contains("billing")
                    || clsName.contains("acquire")
                    || clsName.contains("purchase"))) {
                dumpIntentExtrasOnce(clsName, intent);
            }
            patchIntent(intent, target, clsName + "#intent");
        }
        if (savedState != null) {
            patchBundle(savedState, target, clsName + "#savedState");
        }
    }

    private static void dumpIntentExtrasOnce(String clsName, Intent intent) {
        if (!loggedOnce.add("dumpIntent:" + clsName)) {
            return;
        }
        try {
            Bundle extras = intent.getExtras();
            if (extras == null) {
                return;
            }
            StringBuilder sb = new StringBuilder("Intent extras for " + clsName + ":");
            for (String k : extras.keySet()) {
                Object v = extras.get(k);
                String t = v == null ? "null" : v.getClass().getSimpleName();
                sb.append("\n  ").append(k).append(" (").append(t).append(")");
                if (v instanceof String || v instanceof Account) {
                    sb.append(" = ").append(v);
                }
            }
            logI(sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private static void patchIntent(Intent intent, String target, String where) {
        try {
            Bundle extras = intent.getExtras();
            if (extras == null) {
                return;
            }
            if (patchBundle(extras, target, where)) {
                intent.replaceExtras(extras);
            }
        } catch (Throwable t) {
            logW("patchIntent failed for " + where + ": " + t);
        }
    }

    private static boolean patchBundle(Bundle b, String target, String where) {
        if (b == null) {
            return false;
        }
        boolean modified = false;
        Set<String> keys;
        try {
            keys = new HashSet<>(b.keySet());
        } catch (Throwable t) {
            return false;
        }
        for (String key : keys) {
            Object val;
            try {
                val = b.get(key);
            } catch (Throwable ignored) {
                continue;
            }
            if (val instanceof String) {
                String s = (String) val;
                if (!target.equalsIgnoreCase(s)
                        && (isBillingKey(key) || isOtherKnownGoogleEmail(s, target))) {
                    b.putString(key, target);
                    modified = true;
                    logI("Patched " + where + " [" + key + "]: " + s + " -> " + target);
                }
            } else if (val instanceof Account) {
                Account a = (Account) val;
                if ("com.google".equals(a.type) && !target.equalsIgnoreCase(a.name)) {
                    b.putParcelable(key, resolveTargetAccount(target));
                    modified = true;
                    logI("Patched Account " + where + " [" + key + "]: " + a.name + " -> " + target);
                }
            } else if (val instanceof Bundle) {
                if (patchBundle((Bundle) val, target, where + "." + key)) {
                    modified = true;
                }
            }
        }
        return modified;
    }

    private static boolean isBillingKey(String key) {
        if (key == null) {
            return false;
        }
        for (String k : BILLING_KEYS) {
            if (k.equals(key)) {
                return true;
            }
        }
        String low = key.toLowerCase();
        return low.contains("accountname") || low.contains("account_name")
                || low.contains("authaccount");
    }

    private static boolean isOtherKnownGoogleEmail(String val, String target) {
        if (val == null || val.indexOf('@') <= 0) {
            return false;
        }
        if (target.equalsIgnoreCase(val)) {
            return false;
        }
        return knownGoogleEmails.contains(val.toLowerCase());
    }

    private static void discoverFromStackTrace(StackTraceElement[] frames) {
        ClassLoader cl = appContext != null ? appContext.getClassLoader() : packageLoader;
        if (cl == null || frames == null) {
            return;
        }
        StringBuilder chainLog = new StringBuilder();
        for (StackTraceElement f : frames) {
            String cn = f.getClassName();
            if (cn == null
                    || cn.startsWith("java.")
                    || cn.startsWith("javax.")
                    || cn.startsWith("android.")
                    || cn.startsWith("dalvik.")
                    || cn.startsWith("com.android.internal.")
                    || cn.startsWith("io.github.")
                    || cn.startsWith("LSPHooker")) {
                continue;
            }
            if (chainLog.length() > 0) {
                chainLog.append(" <- ");
            }
            chainLog.append(cn).append('#').append(f.getMethodName());
            try {
                Class<?> cls = Class.forName(cn, false, cl);
                inspectHierarchy(cls, 1);
            } catch (Throwable ignored) {
            }
        }
        if (chainLog.length() > 0) {
            logOnce("am_stack:" + chainLog, "AccountManager stack: " + chainLog);
        }
    }

    private static void inspectHierarchy(Class<?> cls, int depth) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            inspectCandidateClass(c);
            if (depth > 0) {
                try {
                    for (Field f : c.getDeclaredFields()) {
                        Class<?> ft = f.getType();
                        String fn = ft.getName();
                        if (!ft.isPrimitive() && !ft.isArray() && !ft.isInterface()
                                && (fn.indexOf('.') < 0 || fn.startsWith("com.google.android.finsky"))) {
                            inspectCandidateClass(ft);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void inspectCandidateClass(Class<?> cls) {
        if (cls == null || cls.isInterface() || cls.isPrimitive() || cls.isArray()) {
            return;
        }
        String name = cls.getName();
        if (name.startsWith("java.") || name.startsWith("android.")
                || name.startsWith("dalvik.") || name.startsWith("io.github.")) {
            return;
        }
        if (!inspectedClasses.add(name)) {
            return;
        }

        Method[] methods;
        try {
            methods = cls.getDeclaredMethods();
        } catch (Throwable t) {
            return;
        }

        List<Method> toAccount = new ArrayList<>();
        List<Method> toAccountArray = new ArrayList<>();
        List<Method> noArgToList = new ArrayList<>();
        List<Method> noArgToString = new ArrayList<>();

        for (Method m : methods) {
            int mod = m.getModifiers();
            if (Modifier.isStatic(mod) || Modifier.isAbstract(mod) || Modifier.isNative(mod)
                    || m.isBridge() || m.isSynthetic()) {
                continue;
            }
            Class<?> ret = m.getReturnType();
            Class<?>[] ps = m.getParameterTypes();
            if (Account.class.isAssignableFrom(ret)) {
                toAccount.add(m);
            } else if (ret == Account[].class && ps.length == 0) {
                toAccountArray.add(m);
            } else if (ps.length == 0 && List.class.isAssignableFrom(ret)) {
                noArgToList.add(m);
            } else if (ps.length == 0 && ret == String.class && !"toString".equals(m.getName())) {
                noArgToString.add(m);
            }
        }

        boolean looksLikeRepo = !toAccount.isEmpty() || !toAccountArray.isEmpty();
        if (!looksLikeRepo) {
            return;
        }

        logI("Discovered concrete Account class: " + name
                + " (->Acct=" + toAccount.size()
                + ", ()->Acct[]=" + toAccountArray.size()
                + ", ()->List=" + noArgToList.size()
                + ", ()->Str=" + noArgToString.size() + ")");
        dumpClass(cls, name);

        for (Method m : toAccount) {
            hookAccountReturnMethod(cls, m);
        }
        for (Method m : toAccountArray) {
            hookAccountArrayReturnMethod(cls, m);
        }
        for (Method m : noArgToList) {
            hookAccountListReturnMethod(cls, m);
        }
        for (Method m : noArgToString) {
            hookEmailStringReturnMethod(cls, m);
        }
    }

    private static void hookAccountReturnMethod(final Class<?> cls, final Method m) {
        final String sig = cls.getName() + "#" + m.getName() + "(" + m.getParameterTypes().length + "):Account";
        if (!hookedMethods.add(sig)) {
            return;
        }
        try {
            m.setAccessible(true);
            hookMethod(m).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    String target = readTarget();
                    if (target == null) {
                        return chain.proceed();
                    }
                    Object orig = null;
                    try {
                        orig = chain.proceed();
                    } catch (Throwable ignored) {
                    }
                    if (orig instanceof Account && !"com.google".equals(((Account) orig).type)) {
                        return orig;
                    }
                    logOnce("hook_acct:" + sig + ":" + target,
                            "Redirected " + sig + " -> " + target);
                    return resolveTargetAccount(target);
                }
            });
            logI("Hooked " + sig);
        } catch (Throwable t) {
            logW("Failed hooking " + sig + ": " + t);
        }
    }

    private static void hookAccountArrayReturnMethod(final Class<?> cls, final Method m) {
        final String sig = cls.getName() + "#" + m.getName() + "():Account[]";
        if (!hookedMethods.add(sig)) {
            return;
        }
        try {
            m.setAccessible(true);
            hookMethod(m).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Object res = chain.proceed();
                    if (!(res instanceof Account[])) {
                        return res;
                    }
                    String target = readTarget();
                    if (target == null) {
                        return res;
                    }
                    return isolateTargetAccount((Account[]) res, target, false, sig);
                }
            });
            logI("Hooked " + sig);
        } catch (Throwable t) {
            logW("Failed hooking " + sig + ": " + t);
        }
    }

    private static void hookAccountListReturnMethod(final Class<?> cls, final Method m) {
        final String sig = cls.getName() + "#" + m.getName() + "():List";
        if (!hookedMethods.add(sig)) {
            return;
        }
        try {
            m.setAccessible(true);
            hookMethod(m).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Object res = chain.proceed();
                    if (!(res instanceof List)) {
                        return res;
                    }
                    String target = readTarget();
                    if (target == null) {
                        return res;
                    }
                    List<?> raw = (List<?>) res;
                    Account matched = null;
                    boolean hasGoogle = false;
                    for (Object o : raw) {
                        if (o instanceof Account && "com.google".equals(((Account) o).type)) {
                            hasGoogle = true;
                            if (target.equalsIgnoreCase(((Account) o).name)) {
                                matched = (Account) o;
                            }
                        }
                    }
                    if (!hasGoogle || matched == null) {
                        return res;
                    }
                    List<Object> out = new ArrayList<>();
                    out.add(matched);
                    for (Object o : raw) {
                        if (!(o instanceof Account) || !"com.google".equals(((Account) o).type)) {
                            out.add(o);
                        }
                    }
                    logOnce("hook_list:" + sig + ":" + target,
                            "Isolated list " + sig + " -> [" + target + "]");
                    return out;
                }
            });
            logI("Hooked " + sig);
        } catch (Throwable t) {
            logW("Failed hooking " + sig + ": " + t);
        }
    }

    private static void hookEmailStringReturnMethod(final Class<?> cls, final Method m) {
        final String sig = cls.getName() + "#" + m.getName() + "():String";
        if (!hookedMethods.add(sig)) {
            return;
        }
        try {
            m.setAccessible(true);
            hookMethod(m).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Object orig = chain.proceed();
                    String target = readTarget();
                    if (target == null || !(orig instanceof String)) {
                        return orig;
                    }
                    String s = (String) orig;
                    if (isOtherKnownGoogleEmail(s, target)) {
                        logOnce("hook_str:" + sig + ":" + target,
                                "Redirected " + sig + ": " + s + " -> " + target);
                        return target;
                    }
                    return orig;
                }
            });
            logI("Hooked " + sig);
        } catch (Throwable t) {
            logW("Failed hooking " + sig + ": " + t);
        }
    }

    private static void registerReceivers(Context ctx) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (intent == null) {
                        return;
                    }
                    String action = intent.getAction();
                    if (ACTION_SET.equals(action)) {
                        String email = intent.getStringExtra("email");
                        if (email != null && email.trim().isEmpty()) {
                            email = null;
                        }
                        overrideTarget = email;
                        hasOverride = true;
                        cachedProvider = email;
                        providerCached = true;
                        writeTargetFile(email);
                        logI("[" + currentPackage + "] Broadcast target -> " + overrideTarget);
                    } else if (ACTION_REQUEST.equals(action) && isVendingProcess) {
                        sendAccounts(c);
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_SET);
            if (isVendingProcess) {
                filter.addAction(ACTION_REQUEST);
            }
            try {
                Method m = Context.class.getMethod("registerReceiver",
                        BroadcastReceiver.class, IntentFilter.class, int.class);
                m.invoke(ctx, receiver, filter, 2);
            } catch (Throwable old) {
                ctx.registerReceiver(receiver, filter);
            }
            logI("Receivers registered in " + currentPackage);
        } catch (Throwable t) {
            logE("registerReceivers failed: " + t);
        }
    }

    private static void sendAccounts(Context ctx) {
        List<String> names = new ArrayList<>();
        if (ctx != null) {
            bypassAccountFilter.set(Boolean.TRUE);
            try {
                Account[] accts = AccountManager.get(ctx).getAccountsByType("com.google");
                recordKnownEmails(accts);
                for (Account a : accts) {
                    if (!names.contains(a.name)) {
                        names.add(a.name);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                bypassAccountFilter.set(Boolean.FALSE);
            }
            Intent out = new Intent(ACTION_ACCOUNTS);
            out.setPackage(APP_PACKAGE);
            out.putExtra("accounts", names.toArray(new String[names.size()]));
            String current = readTarget();
            if (current != null) {
                out.putExtra("current", current);
            }
            ctx.sendBroadcast(out);
            logI("Sent " + names.size() + " accounts to PSwitch app");
        }
    }

    private static Account resolveTargetAccount(String target) {
        if (appContext != null) {
            bypassAccountFilter.set(Boolean.TRUE);
            try {
                Account[] accts = AccountManager.get(appContext).getAccountsByType("com.google");
                for (Account a : accts) {
                    if (target.equalsIgnoreCase(a.name)) {
                        return a;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                bypassAccountFilter.set(Boolean.FALSE);
            }
        }
        return new Account(target, "com.google");
    }

    static String readTarget() {
        if (hasOverride) {
            return overrideTarget;
        }
        String fromProvider = readProvider();
        if (providerCached) {
            return fromProvider;
        }
        return readFile();
    }

    private static String readProvider() {
        if (appContext == null) {
            return null;
        }
        Cursor c = null;
        try {
            c = appContext.getContentResolver().query(PROVIDER_URI, null, null, null, null);
            if (c == null) {
                return null;
            }
            if (!c.moveToFirst()) {
                return null;
            }
            String email = null;
            long version = 0;
            int i = c.getColumnIndex("email");
            if (i >= 0 && !c.isNull(i)) {
                email = c.getString(i);
            }
            int v = c.getColumnIndex("version");
            if (v >= 0) {
                version = c.getLong(v);
            }
            if (email != null && email.trim().isEmpty()) {
                email = null;
            }
            if (!providerCached || version != cachedVersion) {
                cachedVersion = version;
                cachedProvider = email;
                providerCached = true;
                writeTargetFile(email);
            }
            return cachedProvider;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static File getTargetFile() {
        if (appContext != null) {
            try {
                File dir = appContext.getFilesDir();
                if (dir != null) {
                    return new File(dir, TARGET_FILENAME);
                }
            } catch (Throwable ignored) {
            }
        }
        if (currentPackage != null && !currentPackage.isEmpty()) {
            return new File("/data/data/" + currentPackage + "/files/" + TARGET_FILENAME);
        }
        return new File("/data/data/" + VENDING + "/files/" + TARGET_FILENAME);
    }

    private static void writeTargetFile(String email) {
        FileOutputStream out = null;
        try {
            File f = getTargetFile();
            if (email == null || email.indexOf('@') <= 0) {
                if (f.exists()) {
                    f.delete();
                }
                return;
            }
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            out = new FileOutputStream(f, false);
            out.write(email.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable t) {
            logW("writeTargetFile failed: " + t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String readFile() {
        FileInputStream in = null;
        try {
            File f = getTargetFile();
            if (!f.exists() || !f.isFile()) {
                return null;
            }
            byte[] buf = new byte[(int) f.length()];
            in = new FileInputStream(f);
            int n = in.read(buf);
            if (n <= 0) {
                return null;
            }
            String s = new String(buf, 0, n, "UTF-8").trim();
            return s.indexOf('@') > 0 ? s : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void dumpClass(Class<?> c, String label) {
        StringBuilder sb = new StringBuilder("dump " + label + ":");
        for (Method m : c.getDeclaredMethods()) {
            sb.append("\n  ").append(m.toString());
        }
        for (Field f : c.getDeclaredFields()) {
            sb.append("\n  field ").append(f.getType().getName()).append(' ').append(f.getName());
        }
        logI(sb.toString());
    }

    private static XposedInterface.HookBuilder hookMethod(
            java.lang.reflect.Executable executable) {
        return self.hook(executable);
    }

    private static void logOnce(String tag, String msg) {
        if (loggedOnce.add(tag)) {
            logI(msg);
        }
    }

    private static void logI(String msg) {
        if (self != null) self.log(Log.INFO, TAG, msg); else Log.i(TAG, msg);
    }

    private static void logW(String msg) {
        if (self != null) self.log(Log.WARN, TAG, msg); else Log.w(TAG, msg);
    }

    private static void logE(String msg) {
        if (self != null) self.log(Log.ERROR, TAG, msg); else Log.e(TAG, msg);
    }
}
