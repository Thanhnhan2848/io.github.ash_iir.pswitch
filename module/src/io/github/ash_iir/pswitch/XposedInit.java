package io.github.ash_iir.pswitch;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
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
    private static final String APP_PACKAGE = "io.github.ash_iir.pswitch";

    private static final String TARGET_FILE =
            "/data/data/" + VENDING + "/files/pswitch_target.txt";
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
    private static volatile Object repoInstance;
    private static volatile Method repoAccountsListMethod;
    private static volatile boolean accountsSent;

    private static volatile String overrideTarget;
    private static volatile boolean hasOverride;
    private static volatile String cachedProvider;
    private static volatile long cachedVersion = -1;
    private static volatile boolean providerCached;

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
        if (!VENDING.equals(param.getPackageName())) {
            return;
        }
        logI("onPackageReady: " + param.getPackageName());

        hookApplication();
        hookAccountManager();
        hookActivityBillingExtras();
        hookClassLoader();
        hookFinskyClasses(param.getClassLoader());
    }

    /**
     * Layer 1: Application.onCreate -> giữ Context để đọc ContentProvider và nhận Broadcast.
     */
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
                                hookFinskyClasses(appContext.getClassLoader());
                            }
                            return res;
                        }
                    });
            logI("Hooked Application.onCreate");
        } catch (Throwable t) {
            logE("hookApplication failed: " + t);
        }
    }

    /**
     * Layer 2: Đưa tài khoản Google mục tiêu lên vị trí đầu tiên (index 0) trong AccountManager.
     */
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
                    String target = readTarget();
                    if (target == null) {
                        return res;
                    }
                    return promoteTargetAccount((Account[]) res, target, "getAccountsByType");
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
                    String target = readTarget();
                    if (target == null) {
                        return res;
                    }
                    return promoteTargetAccount((Account[]) res, target, "getAccounts");
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
                    String target = readTarget();
                    if (target == null) {
                        return res;
                    }
                    return promoteTargetAccount((Account[]) res, target, "getAccountsByTypeForPackage");
                }
            });
            logI("Hooked AccountManager.getAccountsByTypeForPackage");
        } catch (Throwable ignored) {
        }
    }

    private static Account[] promoteTargetAccount(Account[] original, String target, String src) {
        if (original == null || original.length <= 1) {
            return original;
        }
        int foundIdx = -1;
        for (int i = 0; i < original.length; i++) {
            Account a = original[i];
            if (a != null && "com.google".equals(a.type) && target.equalsIgnoreCase(a.name)) {
                foundIdx = i;
                break;
            }
        }
        if (foundIdx <= 0) {
            return original;
        }
        Account[] reordered = new Account[original.length];
        reordered[0] = original[foundIdx];
        int pos = 1;
        for (int i = 0; i < original.length; i++) {
            if (i != foundIdx) {
                reordered[pos++] = original[i];
            }
        }
        logOnce("promote:" + src + ":" + target,
                "Promoted " + target + " to index 0 via " + src);
        return reordered;
    }

    /**
     * Layer 3: Ghi đè tài khoản thanh toán trong Intent & Bundle khi mở màn hình IAP.
     */
    private void hookActivityBillingExtras() {
        try {
            Method onCreate = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            hookMethod(onCreate).setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            String target = readTarget();
                            if (target != null && chain.getThisObject() instanceof Activity) {
                                Activity act = (Activity) chain.getThisObject();
                                Intent intent = act.getIntent();
                                if (intent != null && intent.getExtras() != null) {
                                    patchBundle(intent.getExtras(), target,
                                            act.getClass().getName() + "#intent");
                                }
                                Object arg0 = chain.getArg(0);
                                if (arg0 instanceof Bundle) {
                                    patchBundle((Bundle) arg0, target,
                                            act.getClass().getName() + "#savedState");
                                }
                            }
                            return chain.proceed();
                        }
                    });
            logI("Hooked Activity.onCreate(Bundle)");
        } catch (Throwable t) {
            logE("hookActivityBillingExtras failed: " + t);
        }

        try {
            Method onNewIntent = Activity.class.getDeclaredMethod("onNewIntent", Intent.class);
            hookMethod(onNewIntent).setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            String target = readTarget();
                            if (target != null && chain.getArg(0) instanceof Intent) {
                                Intent intent = (Intent) chain.getArg(0);
                                if (intent.getExtras() != null) {
                                    patchBundle(intent.getExtras(), target, "onNewIntent");
                                }
                            }
                            return chain.proceed();
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private static void patchBundle(Bundle b, String target, String where) {
        if (b == null) {
            return;
        }
        for (String key : BILLING_KEYS) {
            if (b.containsKey(key)) {
                String oldVal = b.getString(key);
                if (oldVal != null && !target.equalsIgnoreCase(oldVal)) {
                    b.putString(key, target);
                    logOnce("bundle:" + where + ":" + key,
                            "Patched " + where + " [" + key + "]: " + oldVal + " -> " + target);
                }
            }
        }
    }

    /**
     * Layer 4: Tự động quét & hook class quản lý tài khoản của Finsky (poc và các class obfuscated khác)
     * dựa theo chữ ký kiểu dữ liệu, không phụ thuộc tên hàm cố định.
     */
    private void hookClassLoader() {
        try {
            hookMethod(ClassLoader.class.getDeclaredMethod(
                    "loadClass", String.class, boolean.class))
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            Object res = chain.proceed();
                            if (res instanceof Class) {
                                inspectCandidateClass((Class<?>) res);
                            }
                            return res;
                        }
                    });
        } catch (Throwable t) {
            logD("loadClass(String,boolean) hook failed: " + t);
        }

        try {
            hookMethod(ClassLoader.class.getDeclaredMethod("loadClass", String.class))
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            Object res = chain.proceed();
                            if (res instanceof Class) {
                                inspectCandidateClass((Class<?>) res);
                            }
                            return res;
                        }
                    });
        } catch (Throwable t) {
            logD("loadClass(String) hook failed: " + t);
        }
    }

    private void hookFinskyClasses(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        try {
            Class<?> poc = Class.forName("poc", false, cl);
            inspectCandidateClass(poc);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> szf = Class.forName("szf", false, cl);
            Constructor<?> ctor = szf.getDeclaredConstructor(Context.class, Bundle.class);
            hookMethod(ctor).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Object res = chain.proceed();
                    String target = readTarget();
                    if (target != null) {
                        setField(chain.getThisObject(), "c", target);
                    }
                    return res;
                }
            });
            logI("Hooked szf.<init>");
        } catch (Throwable ignored) {
        }
        try {
            Class<?> sxk = Class.forName("sxk", false, cl);
            Method save = sxk.getDeclaredMethod("onSaveInstanceState", Bundle.class);
            hookMethod(save).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Object res = chain.proceed();
                    String target = readTarget();
                    if (target != null && chain.getArg(0) instanceof Bundle) {
                        ((Bundle) chain.getArg(0)).putString(
                                "BillingAccountModel.billingAccountName", target);
                    }
                    return res;
                }
            });
            logI("Hooked sxk.onSaveInstanceState");
        } catch (Throwable ignored) {
        }
    }

    private static void inspectCandidateClass(Class<?> cls) {
        String name = cls.getName();
        if (name.indexOf('.') >= 0 || name.length() > 5) {
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

        List<Method> toAccountMethods = new ArrayList<>();
        List<Method> noArgToStringMethods = new ArrayList<>();
        List<Method> noArgToListMethods = new ArrayList<>();

        for (Method m : methods) {
            if (Modifier.isStatic(m.getModifiers()) || m.isBridge() || m.isSynthetic()) {
                continue;
            }
            Class<?> ret = m.getReturnType();
            Class<?>[] ps = m.getParameterTypes();

            if (Account.class.isAssignableFrom(ret)) {
                toAccountMethods.add(m);
            } else if (ps.length == 0 && ret == String.class) {
                noArgToStringMethods.add(m);
            } else if (ps.length == 0 && List.class.isAssignableFrom(ret)) {
                noArgToListMethods.add(m);
            }
        }

        boolean isTargetClass = "poc".equals(name) || !toAccountMethods.isEmpty();
        if (!isTargetClass) {
            return;
        }

        logI("Inspecting Finsky class: " + name
                + " (->Account=" + toAccountMethods.size()
                + ", ()->Str=" + noArgToStringMethods.size()
                + ", ()->List=" + noArgToListMethods.size() + ")");
        dumpClass(cls, name);

        for (final Method m : noArgToListMethods) {
            hookAccountListGetter(cls, m);
        }
        for (final Method m : toAccountMethods) {
            hookAccountGetter(cls, m);
        }
        for (final Method m : noArgToStringMethods) {
            hookAccountStringGetter(cls, m);
        }
    }

    private static void hookAccountListGetter(final Class<?> cls, final Method m) {
        String sig = cls.getName() + "#" + m.getName() + "():List";
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
                    List<?> raw = (List<?>) res;
                    boolean hasGoogleAccount = false;
                    for (Object o : raw) {
                        if (o instanceof Account && "com.google".equals(((Account) o).type)) {
                            hasGoogleAccount = true;
                            break;
                        }
                    }
                    if (!hasGoogleAccount) {
                        return res;
                    }
                    if (repoInstance == null && chain.getThisObject() != null) {
                        repoInstance = chain.getThisObject();
                    }
                    if (repoAccountsListMethod == null) {
                        repoAccountsListMethod = m;
                    }
                    if (!accountsSent && appContext != null) {
                        accountsSent = true;
                        sendAccounts(appContext);
                    }
                    String target = readTarget();
                    if (target == null || raw.size() <= 1) {
                        return res;
                    }
                    Account matched = null;
                    List<Object> reordered = new ArrayList<>(raw.size());
                    for (Object o : raw) {
                        if (matched == null && o instanceof Account
                                && "com.google".equals(((Account) o).type)
                                && target.equalsIgnoreCase(((Account) o).name)) {
                            matched = (Account) o;
                        } else {
                            reordered.add(o);
                        }
                    }
                    if (matched != null) {
                        reordered.add(0, matched);
                        logOnce("list:" + sig + ":" + target,
                                "Reordered " + sig + " -> " + target);
                        return reordered;
                    }
                    return res;
                }
            });
            logI("Hooked " + sig);
        } catch (Throwable t) {
            logW("Failed hooking " + sig + ": " + t);
        }
    }

    private static void hookAccountGetter(final Class<?> cls, final Method m) {
        String sig = cls.getName() + "#" + m.getName() + "(" + m.getParameterTypes().length + "):Account";
        if (!hookedMethods.add(sig)) {
            return;
        }
        try {
            m.setAccessible(true);
            hookMethod(m).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    if (repoInstance == null && chain.getThisObject() != null) {
                        repoInstance = chain.getThisObject();
                    }
                    if (!accountsSent && appContext != null) {
                        accountsSent = true;
                        sendAccounts(appContext);
                    }
                    String target = readTarget();
                    if (target == null) {
                        return chain.proceed();
                    }
                    Object orig = null;
                    try {
                        orig = chain.proceed();
                    } catch (Throwable ignored) {
                    }
                    if (orig instanceof Account) {
                        Account origAcct = (Account) orig;
                        if (!"com.google".equals(origAcct.type)) {
                            return orig;
                        }
                    }
                    logOnce("acct:" + sig + ":" + target,
                            "Intercepted " + sig + " -> " + target);
                    return resolveTargetAccount(chain.getThisObject(), target);
                }
            });
            logI("Hooked " + sig);
        } catch (Throwable t) {
            logE("Failed hooking " + sig + ": " + t);
        }
    }

    private static void hookAccountStringGetter(final Class<?> cls, final Method m) {
        String sig = cls.getName() + "#" + m.getName() + "():String";
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
                    Object orig = chain.proceed();
                    if (orig instanceof String && ((String) orig).indexOf('@') > 0) {
                        logOnce("str:" + sig + ":" + target,
                                "Intercepted " + sig + ": " + orig + " -> " + target);
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
                        logI("Broadcast target -> " + overrideTarget);
                    } else if (ACTION_REQUEST.equals(action)) {
                        sendAccounts(c);
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_SET);
            filter.addAction(ACTION_REQUEST);
            try {
                Method m = Context.class.getMethod("registerReceiver",
                        BroadcastReceiver.class, IntentFilter.class, int.class);
                m.invoke(ctx, receiver, filter, 2);
            } catch (Throwable old) {
                ctx.registerReceiver(receiver, filter);
            }
            logI("Receivers registered");
        } catch (Throwable t) {
            logE("registerReceivers failed: " + t);
        }
    }

    private static void sendAccounts(Context ctx) {
        List<String> names = new ArrayList<>();
        Object repo = repoInstance;
        if (repo != null && repoAccountsListMethod != null) {
            try {
                Object list = repoAccountsListMethod.invoke(repo);
                if (list instanceof List) {
                    for (Object o : (List<?>) list) {
                        if (o instanceof Account) {
                            Account a = (Account) o;
                            if ("com.google".equals(a.type) && !names.contains(a.name)) {
                                names.add(a.name);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (names.isEmpty() && ctx != null) {
            try {
                Account[] accts = AccountManager.get(ctx).getAccountsByType("com.google");
                for (Account a : accts) {
                    if (!names.contains(a.name)) {
                        names.add(a.name);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (ctx != null) {
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

    private static Account resolveTargetAccount(Object repo, String target) {
        Object inst = repo != null ? repo : repoInstance;
        if (inst != null && repoAccountsListMethod != null) {
            try {
                Object list = repoAccountsListMethod.invoke(inst);
                if (list instanceof List) {
                    for (Object o : (List<?>) list) {
                        if (o instanceof Account && target.equalsIgnoreCase(((Account) o).name)) {
                            return (Account) o;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (appContext != null) {
            try {
                Account[] accts = AccountManager.get(appContext).getAccountsByType("com.google");
                for (Account a : accts) {
                    if (target.equalsIgnoreCase(a.name)) {
                        return a;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return new Account(target, "com.google");
    }

    static String readTarget() {
        if (hasOverride) {
            return overrideTarget;
        }
        String fromProvider = readProvider();
        if (fromProvider != null) {
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
            String email = null;
            long version = 0;
            if (c.moveToFirst()) {
                int i = c.getColumnIndex("email");
                if (i >= 0 && !c.isNull(i)) {
                    email = c.getString(i);
                }
                int v = c.getColumnIndex("version");
                if (v >= 0) {
                    version = c.getLong(v);
                }
            }
            if (email != null && email.trim().isEmpty()) {
                email = null;
            }
            if (providerCached && version == cachedVersion) {
                return cachedProvider;
            }
            cachedVersion = version;
            cachedProvider = email;
            providerCached = true;
            return email;
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

    private static String readFile() {
        FileInputStream in = null;
        try {
            File f = new File(TARGET_FILE);
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

    private static void setField(Object obj, String name, Object value) {
        if (obj == null) {
            return;
        }
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(obj, value);
                return;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored) {
                return;
            }
        }
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

    private static void logD(String msg) {
        if (self != null) self.log(Log.DEBUG, TAG, msg); else Log.d(TAG, msg);
    }

    private static void logW(String msg) {
        if (self != null) self.log(Log.WARN, TAG, msg); else Log.w(TAG, msg);
    }

    private static void logE(String msg) {
        if (self != null) self.log(Log.ERROR, TAG, msg); else Log.e(TAG, msg);
    }
}
