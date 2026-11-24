package io.github.ash_iir.pswitch;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * PSwitch entry for the modern libxposed API 102.
 *
 * Forces the Google account used by Google Play Store (Finsky) for billing /
 * in-app purchases to the one chosen in the companion app.
 *
 * PATCHED (shape-based resolution):
 * - Class names of Finsky internals churn between releases; "poc" survived but
 *   its method names did not (NoSuchMethodException: poc#a on 54.3.08).
 * - Methods are now located by signature shape first, exact name second.
 * - Every hook installs independently; one failure no longer kills the rest.
 * - On any ambiguity the full method/field table of the class is dumped to the
 *   log so the exact names can be pinned without guessing.
 */
public class XposedInit extends XposedModule {

    private static final String TAG = "PSwitch";

    static final String VENDING = "com.android.vending";
    static final String POC = "poc";
    static final String WFM = "wfm";
    static final String TCT = "tct";
    static final String TBJ = "tbj";
    static final String SZF = "szf";
    static final String SKY = "sky";
    static final String SXK = "sxk";
    static final String BILLING_EXTRA = "BillingAccountModel.billingAccountName";
    static final String TARGET_FILE =
            "/data/data/" + VENDING + "/files/pswitch_target.txt";
    static final Uri PROVIDER_URI =
            Uri.parse("content://io.github.ash_iir.pswitch.provider/target");
    static final String ACTION_SET = "io.github.ash_iir.pswitch.SET_ACCOUNT";
    static final String ACTION_REQUEST = "io.github.ash_iir.pswitch.REQUEST_ACCOUNTS";
    static final String ACTION_ACCOUNTS = "io.github.ash_iir.pswitch.ACCOUNTS";
    static final String APP_PACKAGE = "io.github.ash_iir.pswitch";

    private static XposedInit self;

    private static Context appContext;
    private static ClassLoader packageLoader;
    private static Object pocInstance;
    private static boolean accountsSent;
    private static String overrideTarget;
    private static boolean hasOverride;
    private static String cachedProvider;
    private static long cachedVersion = -1;
    private static boolean providerCached;
    private static boolean hooked;
    private static Class<?> szfClass;

    private static final Set<String> triedLoaders = new HashSet<>();
    private static final Set<String> loggedLookups = new HashSet<>();

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        self = this;
        logI("API " + getApiVersion() + " loaded on "
                + getFrameworkName() + " in " + param.getProcessName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!VENDING.equals(param.getPackageName()))
            return;

        packageLoader = param.getClassLoader();
        logI("onPackageReady " + param.getPackageName()
                + " loader=" + packageLoader);

        hookApplication();
        hookClassLoader();
        tryInstall(packageLoader, "packageReady");
        tryInstall(Thread.currentThread().getContextClassLoader(), "thread");
    }

    /** Application.onCreate gives us a real Context for the provider + broadcasts. */
    private void hookApplication() {
        try {
            final Method onCreate = Application.class.getDeclaredMethod("onCreate");
            hookMethod(onCreate).setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            Object app = chain.getThisObject();
                            if (app instanceof Context) {
                                appContext = (Context) app;
                                registerReceivers(appContext);
                                tryInstall(appContext.getClassLoader(), "application");
                                tryInstall(packageLoader, "packageReady");
                            }
                            return result;
                        }
                    });
            logI("hooked Application.onCreate");
        } catch (Throwable t) {
            logE("Application hook failed: " + t);
        }
    }

    /** Last resort: catch the exact moment Finsky resolves its obfuscated "poc". */
    private void hookClassLoader() {
        try {
            hookMethod(ClassLoader.class.getDeclaredMethod(
                    "loadClass", String.class, boolean.class))
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            try {
                                if (POC.equals(chain.getArg(0)) && result instanceof Class)
                                    installHooks((Class<?>) result, "loadClass");
                            } catch (Throwable ignored) {
                            }
                            return result;
                        }
                    });
        } catch (Throwable t) {
            logD("loadClass(boolean) hook failed: " + t);
        }

        try {
            hookMethod(ClassLoader.class.getDeclaredMethod("loadClass", String.class))
                    .intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            try {
                                if (POC.equals(chain.getArg(0)) && result instanceof Class)
                                    installHooks((Class<?>) result, "loadClass1");
                            } catch (Throwable ignored) {
                            }
                            return result;
                        }
                    });
        } catch (Throwable t) {
            logD("loadClass(String) hook failed: " + t);
        }
    }

    private static synchronized void tryInstall(ClassLoader cl, String source) {
        if (hooked || cl == null)
            return;
        String key = source + ":" + cl.hashCode();
        if (!triedLoaders.add(key))
            return;
        try {
            Class<?> poc = Class.forName(POC, false, cl);
            logI("found " + POC + " via " + source);
            installHooks(poc, source);
        } catch (Throwable t) {
            logD(POC + " not in " + source + ": " + t);
        }
    }

    private static synchronized void installHooks(final Class<?> poc, String source) {
        if (hooked)
            return;
        boolean any = false;

        // ---- poc: exact legacy names first, shape-based fallback second ----
        Method acctFromName = null;
        Method acctNoArg = null;
        Method nameNoArg = null;
        try {
            acctFromName = findMethod(poc, "a", String.class);
            logI("poc#a(String) exact hit");
        } catch (Throwable t) {
            logW("poc#a(String) gone: " + t);
        }
        try {
            acctNoArg = findMethod(poc, "b");
            logI("poc#b() exact hit");
        } catch (Throwable t) {
            logW("poc#b() gone: " + t);
        }
        try {
            nameNoArg = findMethod(poc, "f");
            logI("poc#f() exact hit");
        } catch (Throwable t) {
            logW("poc#f() gone: " + t);
        }

        Method[] shape = resolvePocByShape(poc);
        if (acctFromName == null) acctFromName = shape[0];
        if (acctNoArg == null) acctNoArg = shape[1];
        if (nameNoArg == null) nameNoArg = shape[2];

        if (acctFromName != null) {
            final Method m = acctFromName;
            try {
                // Main IAP source. Replacing the result avoids calling the original.
                hookMethod(m).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        if (pocInstance == null)
                            pocInstance = chain.getThisObject();
                        if (!accountsSent && appContext != null) {
                            accountsSent = true;
                            sendAccounts(appContext);
                        }
                        String target = readTarget();
                        if (target == null)
                            return chain.proceed();
                        Object requested = chain.getArg(0);
                        logOnce("lookup:" + requested,
                                "poc.a(\"" + requested + "\") -> " + target);
                        return targetAccount(chain.getThisObject(), target);
                    }
                });
                logI("hooked " + poc.getName() + "#" + m.getName() + "(String)");
                any = true;
            } catch (Throwable t) {
                logE("poc.a hook failed: " + t);
            }
        } else {
            logE("poc: no (String)->Account method resolvable, dump follows");
        }

        if (acctNoArg != null) {
            final Method m = acctNoArg;
            try {
                hookMethod(m).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        if (pocInstance == null)
                            pocInstance = chain.getThisObject();
                        String target = readTarget();
                        if (target == null)
                            return chain.proceed();
                        return targetAccount(chain.getThisObject(), target);
                    }
                });
                logI("hooked " + poc.getName() + "#" + m.getName() + "():Account");
                any = true;
            } catch (Throwable t) {
                logE("poc.b hook failed: " + t);
            }
        }

        if (nameNoArg != null) {
            final Method m = nameNoArg;
            try {
                hookMethod(m).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        String target = readTarget();
                        if (target == null)
                            return chain.proceed();
                        return target;
                    }
                });
                logI("hooked " + poc.getName() + "#" + m.getName() + "():String");
                any = true;
            } catch (Throwable t) {
                logE("poc.f hook failed: " + t);
            }
        }

        // ---- checkout account in szf.c (best effort, independent) ----
        try {
            szfClass = Class.forName(SZF, false, poc.getClassLoader());
            final Constructor<?> ctor =
                    szfClass.getDeclaredConstructor(Context.class, Bundle.class);
            hookMethod(ctor).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    String target = readTarget();
                    if (target != null)
                        setField(chain.getThisObject(), "c", target);
                    return result;
                }
            });
            logI("hooked szf constructor");
            any = true;
        } catch (Throwable t) {
            logW("szf hook failed: " + t);
        }

        // ---- force the resolved account object itself (wfm.b) ----
        try {
            final Class<?> wfm = Class.forName(WFM, false, poc.getClassLoader());
            final Class<?> tbj = Class.forName(TBJ, false, poc.getClassLoader());
            final Class<?> tct = Class.forName(TCT, false, poc.getClassLoader());
            Class<?> optionalCls;
            try {
                optionalCls = Class.forName("j$.util.Optional", false, poc.getClassLoader());
            } catch (Throwable e) {
                optionalCls = java.util.Optional.class;
            }
            final Method wfmB = findMethod(wfm, "b", tbj, optionalCls,
                    Context.class, boolean.class);
            hookMethod(wfmB).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    String target = readTarget();
                    if (target == null || result == null || !tct.isInstance(result))
                        return result;
                    Object current = getField(result, "a");
                    if (current instanceof Account
                            && target.equals(((Account) current).name))
                        return result;
                    setField(result, "a", targetAccount(chain.getThisObject(), target));
                    logOnce("wfm.b", "wfm.b forced tct.a -> " + target);
                    return result;
                }
            });
            logI("hooked wfm.b");
            any = true;
        } catch (Throwable t) {
            logW("wfm.b hook failed: " + t);
        }

        // ---- sky.accept forces szf.c on updates ----
        if (szfClass != null) {
            try {
                final Class<?> sky = Class.forName(SKY, false, poc.getClassLoader());
                final Method accept = findMethod(sky, "accept", Object.class);
                hookMethod(accept).intercept(new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        String target = readTarget();
                        if (target == null || chain.getThisObject() == null)
                            return result;
                        Object owner = getField(chain.getThisObject(), "a");
                        if (owner == null || !szfClass.isInstance(owner))
                            return result;
                        Object current = getField(owner, "c");
                        if (!target.equals(current)) {
                            setField(owner, "c", target);
                            logOnce("sky", "sky forced szf.c -> " + target);
                        }
                        return result;
                    }
                });
                logI("hooked sky.accept");
                any = true;
            } catch (Throwable t) {
                logW("sky hook failed: " + t);
            }
        }

        // ---- sxk.onSaveInstanceState keeps the BillingAccountModel extra ----
        try {
            final Class<?> sxk = Class.forName(SXK, false, poc.getClassLoader());
            final Method save = findMethod(sxk, "onSaveInstanceState", Bundle.class);
            hookMethod(save).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    String target = readTarget();
                    if (target == null || chain.getArg(0) == null)
                        return result;
                    if (chain.getArg(0) instanceof Bundle)
                        ((Bundle) chain.getArg(0)).putString(BILLING_EXTRA, target);
                    return result;
                }
            });
            logI("hooked sxk.onSaveInstanceState");
            any = true;
        } catch (Throwable t) {
            logW("sxk hook failed: " + t);
        }

        if (any) {
            hooked = true;
            logI("Finsky account hooks installed via " + source);
        } else {
            logE("installHooks: nothing resolved via " + source + ", full dump follows");
        }
        dumpClass(poc, POC);
    }

    /**
     * Locate the poc methods by shape instead of name:
     *   [0] resolver: instance (String) -> Account   (legacy a)
     *   [1] current:  instance () -> Account          (legacy b)
     *   [2] default:  instance () -> String           (legacy f)
     * A slot stays null when zero or several candidates match (ambiguous shape
     * is never hooked blindly — check the dump instead).
     */
    private static Method[] resolvePocByShape(Class<?> poc) {
        List<Method> byName = new ArrayList<>();
        List<Method> curAcct = new ArrayList<>();
        List<Method> curName = new ArrayList<>();
        for (Method m : poc.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.isBridge() || m.isSynthetic())
                continue;
            Class<?> ret = m.getReturnType();
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length == 1 && ps[0] == String.class
                    && Account.class.isAssignableFrom(ret))
                byName.add(m);
            else if (ps.length == 0 && Account.class.isAssignableFrom(ret))
                curAcct.add(m);
            else if (ps.length == 0 && ret == String.class)
                curName.add(m);
        }
        logShapeCandidates("name->Account", byName);
        logShapeCandidates("()->Account", curAcct);
        logShapeCandidates("()->String", curName);
        return new Method[]{
                byName.size() == 1 ? byName.get(0) : null,
                curAcct.size() == 1 ? curAcct.get(0) : null,
                curName.size() == 1 ? curName.get(0) : null
        };
    }

    private static void logShapeCandidates(String what, List<Method> ms) {
        StringBuilder sb = new StringBuilder("shape [" + what + "] "
                + ms.size() + " candidate(s):");
        for (Method m : ms) {
            sb.append(' ').append(m.getName()).append('(');
            Class<?>[] ps = m.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(ps[i].getName());
            }
            sb.append(")->").append(m.getReturnType().getName());
        }
        logI(sb.toString());
    }

    /** Full table of declared methods + fields, so the exact names are known. */
    private static void dumpClass(Class<?> c, String label) {
        StringBuilder sb = new StringBuilder("dump " + label + " " + c.getName() + ":");
        for (Method m : c.getDeclaredMethods()) {
            sb.append("\n  ").append(m.toString());
        }
        for (Field f : c.getDeclaredFields()) {
            sb.append("\n  field ").append(f.getType().getName())
                    .append(' ').append(f.getName());
        }
        logI(sb.toString());
    }

    private static void registerReceivers(Context ctx) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    String action = intent.getAction();
                    if (ACTION_SET.equals(action)) {
                        overrideTarget = intent.getStringExtra("email");
                        hasOverride = true;
                        cachedProvider = overrideTarget;
                        providerCached = true;
                        logI("broadcast target -> " + overrideTarget);
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
                m.invoke(ctx, receiver, filter, 2); // RECEIVER_EXPORTED
            } catch (Throwable old) {
                ctx.registerReceiver(receiver, filter);
            }
            logI("SET_ACCOUNT + REQUEST_ACCOUNTS receivers registered");
        } catch (Throwable t) {
            logE("receiver failed: " + t);
        }
    }

    /** Play Store can see every Google account; send the list to the app. */
    private static void sendAccounts(Context ctx) {
        List<String> names = new ArrayList<>();
        Object list = callMethod(pocInstance, "i");
        if (list instanceof List) {
            for (Object o : (List<?>) list) {
                if (o instanceof Account) {
                    Account a = (Account) o;
                    if ("com.google".equals(a.type) && !names.contains(a.name))
                        names.add(a.name);
                }
            }
        }
        if (names.isEmpty()) {
            try {
                for (Account a : AccountManager.get(ctx).getAccountsByType("com.google"))
                    if (!names.contains(a.name))
                        names.add(a.name);
            } catch (Throwable ignored) {
            }
        }
        Intent out = new Intent(ACTION_ACCOUNTS);
        out.setPackage(APP_PACKAGE);
        out.putExtra("accounts", names.toArray(new String[names.size()]));
        String current = readTarget();
        if (current != null)
            out.putExtra("current", current);
        ctx.sendBroadcast(out);
        logI("sent " + names.size() + " accounts to app");
    }

    /** Target chosen in the app (broadcast/provider), falling back to root file. */
    static String readTarget() {
        if (hasOverride)
            return overrideTarget;
        String fromProvider = readProvider();
        if (providerCached)
            return fromProvider;
        return readFile();
    }

    private static String readProvider() {
        if (appContext == null)
            return null;
        try {
            android.database.Cursor c = appContext.getContentResolver()
                    .query(PROVIDER_URI, null, null, null, null);
            if (c == null)
                return null;
            String email = null;
            long version = 0;
            try {
                if (c.moveToFirst()) {
                    int i = c.getColumnIndex("email");
                    if (i >= 0 && !c.isNull(i))
                        email = c.getString(i);
                    int v = c.getColumnIndex("version");
                    if (v >= 0)
                        version = c.getLong(v);
                }
            } finally {
                c.close();
            }
            if (providerCached && version == cachedVersion)
                return cachedProvider;
            cachedVersion = version;
            cachedProvider = email;
            providerCached = true;
            return email;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readFile() {
        try {
            File f = new File(TARGET_FILE);
            if (!f.exists() || !f.isFile())
                return null;
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(buf);
            in.close();
            if (n <= 0)
                return null;
            String s = new String(buf, 0, n, "UTF-8").trim();
            return s.indexOf('@') > 0 ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static Account targetAccount(Object poc, String target) {
        Object list = callMethod(poc != null ? poc : pocInstance, "i");
        if (list instanceof List) {
            for (Object o : (List<?>) list) {
                if (o instanceof Account && target.equals(((Account) o).name))
                    return (Account) o;
            }
        }
        return new Account(target, "com.google");
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

    private static XposedInterface.HookBuilder hookMethod(
            java.lang.reflect.Executable executable) {
        return self.hook(executable);
    }

    private static void logOnce(String tag, String msg) {
        if (loggedLookups.add(tag))
            logI(msg);
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params)
            throws NoSuchMethodException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(cls.getName() + "#" + name);
    }

    private static Object callMethod(Object obj, String name) {
        if (obj == null)
            return null;
        try {
            return findMethod(obj.getClass(), name).invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object getField(Object obj, String name) throws Exception {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setField(Object obj, String name, Object value) throws Exception {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(obj, value);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }
}
