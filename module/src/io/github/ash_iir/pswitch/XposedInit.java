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
import java.lang.reflect.Method;
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
    private static String currentProcess;
    private static String currentPackage;
    private static boolean isVendingProcess;
    private static boolean isPaidTasksProcess;

    private static volatile boolean isStoreUiActive;
    private static volatile String currentTarget;
    private static volatile boolean targetLoaded;
    private static volatile long cachedVersion = -1L;
    private static volatile int lastRecordedAccountCount = -1;
    private static volatile Account cachedIsolatedAccount;
    private static volatile Account[] cachedIsolatedSingle;

    private static final ThreadLocal<Boolean> bypassAccountFilter = new ThreadLocal<>();
    private static final Set<String> knownGoogleEmails =
            Collections.synchronizedSet(new HashSet<String>());

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        self = this;
        currentProcess = param.getProcessName();
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (APP_PACKAGE.equals(pkg)) {
            return;
        }
        // Bỏ qua tiến trình :background và :quick_launch của Play Store để tránh lag sync nền
        if (VENDING.equals(pkg) && currentProcess != null && !VENDING.equals(currentProcess)) {
            return;
        }

        currentPackage = pkg;
        isVendingProcess = VENDING.equals(pkg);
        isPaidTasksProcess = PAID_TASKS.equals(pkg);

        hookApplication();
        hookAccountManager();
        hookActivityLifecycle();
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
                                initTargetOnce();
                            }
                            return res;
                        }
                    });
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
                    if (Boolean.TRUE.equals(bypassAccountFilter.get())) {
                        return raw;
                    }
                    recordKnownEmailsFast(raw);
                    if (isVendingProcess && isStoreUiActive) {
                        return raw;
                    }
                    String target = getTargetFast();
                    if (target == null) {
                        return raw;
                    }
                    return isolateGoogleOnly(raw, target);
                }
            });
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
                    if (Boolean.TRUE.equals(bypassAccountFilter.get())) {
                        return raw;
                    }
                    recordKnownEmailsFast(raw);
                    if (isVendingProcess && isStoreUiActive) {
                        return raw;
                    }
                    String target = getTargetFast();
                    if (target == null) {
                        return raw;
                    }
                    return isolateAllAccounts(raw, target);
                }
            });
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
                    if (Boolean.TRUE.equals(bypassAccountFilter.get())) {
                        return raw;
                    }
                    recordKnownEmailsFast(raw);
                    if (isVendingProcess && isStoreUiActive) {
                        return raw;
                    }
                    String target = getTargetFast();
                    if (target == null) {
                        return raw;
                    }
                    return isolateGoogleOnly(raw, target);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void recordKnownEmailsFast(Account[] accounts) {
        if (accounts == null || accounts.length == lastRecordedAccountCount) {
            return;
        }
        lastRecordedAccountCount = accounts.length;
        for (int i = 0; i < accounts.length; i++) {
            Account a = accounts[i];
            if (a != null && "com.google".equals(a.type) && a.name != null) {
                knownGoogleEmails.add(a.name.toLowerCase());
            }
        }
    }

    private static Account[] isolateGoogleOnly(Account[] original, String target) {
        if (original == null || original.length <= 1) {
            return original;
        }
        for (int i = 0; i < original.length; i++) {
            Account a = original[i];
            if (a != null && "com.google".equals(a.type) && target.equalsIgnoreCase(a.name)) {
                if (a == cachedIsolatedAccount && cachedIsolatedSingle != null) {
                    return cachedIsolatedSingle;
                }
                Account[] single = new Account[]{a};
                cachedIsolatedAccount = a;
                cachedIsolatedSingle = single;
                return single;
            }
        }
        return original;
    }

    private static Account[] isolateAllAccounts(Account[] original, String target) {
        if (original == null || original.length <= 1) {
            return original;
        }
        Account matched = null;
        int nonGoogleCount = 0;
        for (int i = 0; i < original.length; i++) {
            Account a = original[i];
            if (a == null) {
                continue;
            }
            if ("com.google".equals(a.type)) {
                if (matched == null && target.equalsIgnoreCase(a.name)) {
                    matched = a;
                }
            } else {
                nonGoogleCount++;
            }
        }
        if (matched == null) {
            return original;
        }
        if (nonGoogleCount == 0) {
            if (matched == cachedIsolatedAccount && cachedIsolatedSingle != null) {
                return cachedIsolatedSingle;
            }
            Account[] single = new Account[]{matched};
            cachedIsolatedAccount = matched;
            cachedIsolatedSingle = single;
            return single;
        }
        Account[] out = new Account[1 + nonGoogleCount];
        out[0] = matched;
        int pos = 1;
        for (int i = 0; i < original.length; i++) {
            Account a = original[i];
            if (a != null && !"com.google".equals(a.type)) {
                out[pos++] = a;
            }
        }
        return out;
    }

    private void hookActivityLifecycle() {
        try {
            Method callOnCreate = Instrumentation.class.getDeclaredMethod(
                    "callActivityOnCreate", Activity.class, Bundle.class);
            hookMethod(callOnCreate).setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            Object arg0 = chain.getArg(0);
                            if (arg0 instanceof Activity) {
                                handleActivityBeforeCreate((Activity) arg0);
                            }
                            return chain.proceed();
                        }
                    });
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
                            if (arg0 instanceof Activity) {
                                handleActivityBeforeCreate((Activity) arg0);
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
                            if (arg0 instanceof Activity && arg1 instanceof Intent) {
                                String clsName = arg0.getClass().getName();
                                if (!isVendingProcess || isBillingActivity(clsName)) {
                                    if (isVendingProcess) {
                                        isStoreUiActive = false;
                                    }
                                    String target = getTargetFast();
                                    if (target != null) {
                                        patchIntent((Intent) arg1, target);
                                    }
                                }
                            }
                            return chain.proceed();
                        }
                    });
        } catch (Throwable ignored) {
        }

        if (isVendingProcess || isPaidTasksProcess) {
            try {
                Method onResume = Instrumentation.class.getDeclaredMethod(
                        "callActivityOnResume", Activity.class);
                hookMethod(onResume).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        Object arg0 = chain.getArg(0);
                        if (isVendingProcess && arg0 instanceof Activity) {
                            isStoreUiActive = !isBillingActivity(arg0.getClass().getName());
                        }
                        Object res = chain.proceed();
                        if (isPaidTasksProcess && arg0 instanceof Activity) {
                            injectResetButton((Activity) arg0);
                        }
                        return res;
                    }
                });
            } catch (Throwable t) {
                logE("hook callActivityOnResume failed: " + t);
            }
        }

        if (isVendingProcess) {
            try {
                Method onPause = Instrumentation.class.getDeclaredMethod(
                        "callActivityOnPause", Activity.class);
                hookMethod(onPause).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        Object arg0 = chain.getArg(0);
                        if (arg0 instanceof Activity && !isBillingActivity(arg0.getClass().getName())) {
                            isStoreUiActive = false;
                        }
                        return chain.proceed();
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isBillingActivity(String clsName) {
        if (clsName == null) {
            return false;
        }
        String low = clsName.toLowerCase();
        return low.contains("billing")
                || low.contains("acquire")
                || low.contains("purchase")
                || low.contains("checkout")
                || low.contains("iab");
    }

    private static void handleActivityBeforeCreate(Activity act) {
        if (act == null) {
            return;
        }
        String clsName = act.getClass().getName();
        if (isVendingProcess) {
            if (!isBillingActivity(clsName)) {
                isStoreUiActive = true;
                return;
            }
            isStoreUiActive = false;
        }
        String target = getTargetFast();
        if (target == null) {
            return;
        }
        Intent intent = act.getIntent();
        if (intent != null) {
            patchIntent(intent, target);
        }
    }

    private static void patchIntent(Intent intent, String target) {
        try {
            Bundle extras = intent.getExtras();
            if (extras == null || extras.isEmpty()) {
                return;
            }
            if (patchBundle(extras, target)) {
                intent.replaceExtras(extras);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean patchBundle(Bundle b, String target) {
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
                }
            } else if (val instanceof Account) {
                Account a = (Account) val;
                if ("com.google".equals(a.type) && !target.equalsIgnoreCase(a.name)) {
                    b.putParcelable(key, resolveTargetAccount(target));
                    modified = true;
                }
            } else if (val instanceof Bundle) {
                if (patchBundle((Bundle) val, target)) {
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
        for (int i = 0; i < BILLING_KEYS.length; i++) {
            if (BILLING_KEYS[i].equals(key)) {
                return true;
            }
        }
        String low = key.toLowerCase();
        return low.contains("accountname") || low.contains("account_name")
                || low.contains("authaccount");
    }

    private static boolean isOtherKnownGoogleEmail(String val, String target) {
        if (val == null || val.indexOf('@') <= 0 || target.equalsIgnoreCase(val)) {
            return false;
        }
        return knownGoogleEmails.contains(val.toLowerCase());
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
        } catch (Throwable ignored) {
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
            clearOwnAppData(act);
        }
    }

    private static void clearOwnAppData(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                Method clearMethod = ActivityManager.class.getMethod("clearApplicationUserData");
                clearMethod.invoke(am);
                return;
            }
        } catch (Throwable ignored) {
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
                        currentTarget = email;
                        targetLoaded = true;
                        cachedIsolatedAccount = null;
                        cachedIsolatedSingle = null;
                        writeTargetFileAsync(email);
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
                recordKnownEmailsFast(accts);
                for (int i = 0; i < accts.length; i++) {
                    String name = accts[i].name;
                    if (!names.contains(name)) {
                        names.add(name);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                bypassAccountFilter.set(Boolean.FALSE);
            }
            Intent out = new Intent(ACTION_ACCOUNTS);
            out.setPackage(APP_PACKAGE);
            out.putExtra("accounts", names.toArray(new String[names.size()]));
            String current = getTargetFast();
            if (current != null) {
                out.putExtra("current", current);
            }
            ctx.sendBroadcast(out);
        }
    }

    private static Account resolveTargetAccount(String target) {
        if (appContext != null) {
            bypassAccountFilter.set(Boolean.TRUE);
            try {
                Account[] accts = AccountManager.get(appContext).getAccountsByType("com.google");
                for (int i = 0; i < accts.length; i++) {
                    if (target.equalsIgnoreCase(accts[i].name)) {
                        return accts[i];
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                bypassAccountFilter.set(Boolean.FALSE);
            }
        }
        return new Account(target, "com.google");
    }

    private static String getTargetFast() {
        if (!targetLoaded) {
            currentTarget = readFile();
            targetLoaded = true;
        }
        return currentTarget;
    }

    private static void initTargetOnce() {
        if (!targetLoaded) {
            currentTarget = readFile();
            targetLoaded = true;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                syncFromProvider();
            }
        }).start();
    }

    private static void syncFromProvider() {
        if (appContext == null) {
            return;
        }
        Cursor c = null;
        try {
            c = appContext.getContentResolver().query(PROVIDER_URI, null, null, null, null);
            if (c == null || !c.moveToFirst()) {
                return;
            }
            String email = null;
            long version = 0L;
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
            if (version != cachedVersion) {
                cachedVersion = version;
                currentTarget = email;
                targetLoaded = true;
                cachedIsolatedAccount = null;
                cachedIsolatedSingle = null;
                writeTargetFile(email);
            }
        } catch (Throwable ignored) {
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

    private static void writeTargetFileAsync(final String email) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                writeTargetFile(email);
            }
        }).start();
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
        } catch (Throwable ignored) {
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

    private static XposedInterface.HookBuilder hookMethod(
            java.lang.reflect.Executable executable) {
        return self.hook(executable);
    }

    private static void logE(String msg) {
        if (self != null) self.log(Log.ERROR, TAG, msg); else Log.e(TAG, msg);
    }
}
