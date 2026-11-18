package com.google.android.projection.protocol;

import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.WeakHashMap;

import hmgapi.hook.Bridge;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;

/**
 * Narrow private-build adapter for a missing HMG-API 1.1.0-beta.1 lifecycle point.
 * On CEEV and CVEV CAN 260206, GalReceiver.stop frees SensorSource before destroy
 * runs. The reviewed stock lifecycle classes are identical on both targets.
 * The public low-level hook API closes/drains that source's send gate before
 * shutdown; it never changes stock arguments, results or files. Once HMG-API
 * publishes a mapped sensor-source shutdown hook, this shim can be retired.
 */
final class SensorLifecycle {
    static final String IONIQ6_GPM_MD5 = "d9720ba9ca280d3c5e3d768c193a2ad4";
    static final String EV6_GPM_MD5 = "96cd7c27a9db19acb3900f21f57ea9f0";
    private static final String STOCK_GPM = "/system/app/HKMC_GPM.odex";
    // The exact stock class inherits Object identity. Values must not reference
    // their keys: retired sources can be collected, but a live source can never
    // reopen after shutdown or a repeated init.
    private static final WeakHashMap<Object, Session> SESSIONS = new WeakHashMap<Object, Session>();
    private static volatile String sStockDigest;

    private SensorLifecycle() {}

    /** A native send; the session gate covers the entire invocation. */
    interface Call { int run() throws Exception; }

    /** One source lifetime. A token is never reopened, even for the same source object. */
    static final class Session {
        private boolean active;
        private boolean closed;

        synchronized boolean activate() {
            if (closed) return false;
            active = true;
            return true;
        }

        synchronized int call(Call call) throws Exception {
            if (!active || closed) throw new IllegalStateException("Android Auto sensor session closed");
            return call.run();
        }

        /** Waits for an in-flight JNI call, then permanently rejects future calls. */
        synchronized void close() {
            closed = true;
            active = false;
        }
    }

    static Session begin(Object source) {
        Session previous;
        synchronized (SESSIONS) {
            previous = SESSIONS.get(source);
            if (previous == null) {
                Session next = new Session();
                SESSIONS.put(source, next);
                return next;
            }
        }
        // Both reviewed builds construct a new SensorSource per GalReceiver. Unexpected reuse
        // drains old calls and leaves EV sending off for this object, while the
        // stock init remains untouched. Never replace a gate during teardown.
        previous.close();
        return null;
    }

    static void close(Session session) {
        session.close();
    }

    static void closeSource(Object source) {
        Session session;
        synchronized (SESSIONS) {
            session = SESSIONS.get(source);
            if (session == null) {
                session = new Session();
                SESSIONS.put(source, session);
            }
        }
        session.close();
        // Do not stop/join workers here: a model worker takes its own lock
        // before entering this gate. Closing alone prevents any further JNI
        // calls; the normal disconnect/destroy hooks stop the worker threads.
    }

    /** The stock file is read once as root in the zygote, before GPM starts. */
    static void verifyStock(ModContext mod) {
        try { sStockDigest = md5(STOCK_GPM); }
        catch (Throwable t) {
            sStockDigest = null;
            mod.error("stock GPM fingerprint unavailable; EV sensor hooks stay off", t);
        }
    }

    static boolean install(ModContext mod, LoadedApp app) {
        try {
            Class<?> source = Points.cls(app.classLoader, "gpm.sensor_source");
            Method shutdown = source.getDeclaredMethod("shutdown");
            if (!verified(sStockDigest, shutdown)) {
                throw new IllegalStateException("stock SensorSource shutdown was not verified (GPM MD5 "
                        + sStockDigest + ")");
            }
            Bridge.hookMethod(shutdown, new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param p) {
                    // Deliberately active even when the mod is disabled: an
                    // earlier generation must still close before native free.
                    closeSource(p.thisObject);
                }
            });
            mod.log("exact-stock SensorSource.shutdown lifecycle guard installed (GPM MD5 "
                    + sStockDigest + ")");
            return true;
        } catch (Throwable t) {
            mod.error("EV sensor senders off: shutdown lifecycle guard unavailable", t);
            return false;
        }
    }

    static boolean verified(String digest, Method method) {
        return (IONIQ6_GPM_MD5.equals(digest) || EV6_GPM_MD5.equals(digest)) && shutdownMethod(method);
    }

    static boolean shutdownMethod(Method method) {
        int m = method.getModifiers();
        return "com.google.android.projection.protocol.SensorSource".equals(method.getDeclaringClass().getName())
                && "shutdown".equals(method.getName()) && method.getParameterTypes().length == 0
                && method.getReturnType() == void.class && Modifier.isPublic(m)
                && !Modifier.isStatic(m) && !Modifier.isAbstract(m) && !Modifier.isNative(m);
    }

    private static String md5(String path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        FileInputStream in = new FileInputStream(path);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        } finally { in.close(); }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format("%02x", b & 0xff));
        return hex.toString();
    }
}
