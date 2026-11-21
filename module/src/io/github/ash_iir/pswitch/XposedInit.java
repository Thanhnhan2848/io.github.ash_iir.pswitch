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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * PSwitch entry for the modern libxposed API 102.
 *
 * Forces the Google account used by Google Play Store (Finsky) for billing /
 * in-app purchases to the one chosen in the companion app. No root is needed:
 * the app publishes the choice through an exported ContentProvider, and a
 * broadcast applies changes instantly to a running Play Store process.
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

    private static final Set<String> triedLoaders = new HashSet<String>();
    private static final Set<String> loggedLookups = new HashSet<String>();

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
        try {
            final ClassLoader cl = poc.getClassLoader();

            final Method pocA = findMethod(poc, "a", String.class);
            final Method pocB = findMethod(poc, "b");
            final Method pocF = findMethod(poc, "f");

            // Main IAP source. Replacing the result avoids calling the original at all.
            hookMethod(pocA).intercept(new Hooker() {
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

            hookMethod(pocB).intercept(new Hooker() {
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

            hookMethod(pocF).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    String target = readTarget();
                    if (target == null)
                        return chain.proceed();
                    return target;
                }
            });

            // Belt-and-suspenders: force the resolved account object itself.
            try {
                final Class<?> wfm = Class.forName(WFM, false, cl);
                final Class<?> tbj = Class.forName(TBJ, false, cl);
                final Class<?> tct = Class.forName(TCT, false, cl);
                Class<?> optionalCls;
                try {
                    optionalCls = Class.forName("j$.util.Optional", false, cl);
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
            } catch (Throwable t) {
                logW("wfm.b hook failed: " + t);
            }

            // Checkout account also lives in szf.c and in the purchase Bundle.
            try {
                szfClass = Class.forName(SZF, false, cl);
                final java.lang.reflect.Constructor<?> ctor =
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
            } catch (Throwable t) {
                logW("szf hook failed: " + t);
            }

            final Class<?> szfRef = szfClass;
            if (szfRef != null) {
                try {
                    final Class<?> sky = Class.forName(SKY, false, cl);
                    final Method accept = findMethod(sky, "accept", Object.class);
                    hookMethod(accept).intercept(new Hooker() {
                        @Override
                        public Object intercept(Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            String target = readTarget();
                            if (target == null || chain.getThisObject() == null)
                                return result;
                            Object owner = getField(chain.getThisObject(), "a");
                            if (owner == null || !szfRef.isInstance(owner))
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
                } catch (Throwable t) {
                    logW("sky hook failed: " + t);
                }
            }

            try {
                final Class<?> sxk = Class.forName(SXK, false, cl);
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
            } catch (Throwable t) {
                logW("sxk hook failed: " + t);
            }

            hooked = true;
            logI("Finsky account hooks installed via " + source);
        } catch (Throwable t) {
            logE("installHooks failed via " + source + ": " + t);
        }
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
        List<String> names = new ArrayList<String>();
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

    private static XposedInterface.HookBuilder hookMethod(java.lang.reflect.Executable executable) {
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
