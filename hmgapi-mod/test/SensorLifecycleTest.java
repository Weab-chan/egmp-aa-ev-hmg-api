package com.google.android.projection.protocol;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** JNI lifetime model with real concurrent threads; no native/car operations. */
public final class SensorLifecycleTest {
    static int passed, failed;
    static void check(String name, boolean ok) {
        if (ok) passed++;
        else { failed++; System.out.println("FAIL: " + name); }
    }

    static boolean denied(SensorLifecycle.Session session) throws Exception {
        try {
            session.call(new SensorLifecycle.Call() { public int run() { return 1; } });
            return false;
        } catch (IllegalStateException expected) { return true; }
    }

    static int send(SensorLifecycle.Session session) throws Exception {
        return session.call(new SensorLifecycle.Call() { public int run() { return 7; } });
    }

    static void fingerprintAndShape() throws Exception {
        java.lang.reflect.Method method = SensorSource.class.getDeclaredMethod("shutdown");
        check("reviewed Ioniq 6 stock method accepted",
                SensorLifecycle.verified("d9720ba9ca280d3c5e3d768c193a2ad4", method));
        check("reviewed EV6 stock method accepted",
                SensorLifecycle.verified("96cd7c27a9db19acb3900f21f57ea9f0", method));
        check("unreadable fingerprint refused", !SensorLifecycle.verified(null, method));
        check("other stock fingerprint refused", !SensorLifecycle.verified("48ee793a", method));
        check("near-match EV6 fingerprint refused",
                !SensorLifecycle.verified("96cd7c27a9db19acb3900f21f57ea9f1", method));
        for (String digest : new String[] { SensorLifecycle.IONIQ6_GPM_MD5, SensorLifecycle.EV6_GPM_MD5 }) {
            check("wrong shutdown signature refused for " + digest, !SensorLifecycle.verified(digest,
                    SensorSource.class.getDeclaredMethod("shutdown", int.class)));
            check("wrong declaring class refused for " + digest, !SensorLifecycle.verified(digest,
                    WrongSource.class.getDeclaredMethod("shutdown")));
        }
    }

    static void lifetimes() throws Exception {
        Object first = new Object();
        SensorLifecycle.Session old = SensorLifecycle.begin(first);
        check("pending init cannot send", denied(old));
        check("successful first init activates", old.activate() && send(old) == 7);
        check("repeated init fails closed for that object", SensorLifecycle.begin(first) == null && denied(old));
        check("closed token cannot be reactivated", !old.activate());

        Object second = new Object();
        SensorLifecycle.Session current = SensorLifecycle.begin(second);
        check("fresh reconnect source activates", current.activate() && send(current) == 7);
        SensorLifecycle.closeSource(first);
        check("old source shutdown does not close a fresh source", send(current) == 7);
        SensorLifecycle.closeSource(second);
        check("shutdown permanently blocks new native calls", denied(current));
        check("shutdown source cannot reopen", SensorLifecycle.begin(second) == null);

        Object early = new Object();
        SensorLifecycle.closeSource(early);
        check("shutdown before init creates a closed tombstone", SensorLifecycle.begin(early) == null);
        Object pending = new Object();
        SensorLifecycle.Session p = SensorLifecycle.begin(pending);
        SensorLifecycle.closeSource(pending);
        check("shutdown racing init prevents activation", !p.activate() && denied(p));
    }

    static void inFlightAndReinit() throws Exception {
        final Object source = new Object();
        final SensorLifecycle.Session session = SensorLifecycle.begin(source);
        session.activate();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final CountDownLatch closeAttempt = new CountDownLatch(1), closed = new CountDownLatch(1);
        final CountDownLatch initAttempt = new CountDownLatch(1), reinitialized = new CountDownLatch(1);
        final AtomicInteger nativeCalls = new AtomicInteger();
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        final AtomicReference<SensorLifecycle.Session> replacement = new AtomicReference<SensorLifecycle.Session>();
        Thread sender = new Thread(new Runnable() {
            public void run() {
                try {
                    session.call(new SensorLifecycle.Call() {
                        public int run() throws Exception {
                            nativeCalls.incrementAndGet();
                            entered.countDown();
                            release.await();
                            return 0;
                        }
                    });
                } catch (Throwable t) { error.set(t); }
            }
        }, "fake-native-send");
        Thread shutdown = new Thread(new Runnable() {
            public void run() {
                closeAttempt.countDown();
                SensorLifecycle.closeSource(source);
                // This is where Bridge may run the original native shutdown.
                closed.countDown();
            }
        }, "fake-native-shutdown");
        Thread reinit = new Thread(new Runnable() {
            public void run() {
                initAttempt.countDown();
                replacement.set(SensorLifecycle.begin(source));
                reinitialized.countDown();
            }
        }, "fake-concurrent-reinit");
        sender.setDaemon(true); shutdown.setDaemon(true); reinit.setDaemon(true);
        try {
            sender.start();
            check("native call entered", entered.await(2, TimeUnit.SECONDS));
            shutdown.start(); reinit.start();
            check("both teardown and reinit attempted", closeAttempt.await(1, TimeUnit.SECONDS)
                    && initAttempt.await(1, TimeUnit.SECONDS));
            check("shutdown cannot free during the in-flight native call", !closed.await(100, TimeUnit.MILLISECONDS));
            check("concurrent reinit cannot publish another sending gate", reinitialized.getCount() == 1);
            release.countDown();
            check("shutdown finishes after call drains", closed.await(2, TimeUnit.SECONDS));
            check("reinit drains then fails closed", reinitialized.await(2, TimeUnit.SECONDS) && replacement.get() == null);
            sender.join(1000);
            check("native call completed once without an error", !sender.isAlive() && nativeCalls.get() == 1 && error.get() == null);
            check("future native calls rejected after free", denied(session));
        } finally {
            release.countDown();
            sender.join(1000); shutdown.join(1000); reinit.join(1000);
        }
    }

    public static void main(String[] args) throws Exception {
        fingerprintAndShape();
        lifetimes();
        inFlightAndReinit();
        System.out.println("SensorLifecycleTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }
}

// Host-only shape stand-ins. Never included in hooks.jar or manufacturer files.
class SensorSource {
    public void shutdown() {}
    public void shutdown(int ignored) {}
}
class WrongSource { public void shutdown() {} }
