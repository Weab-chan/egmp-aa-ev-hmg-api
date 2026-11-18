package com.google.android.projection.protocol;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real worker threads and fake car/native outputs: no head-unit or vehicle writes. */
public final class SessionWorkerTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else { failed++; System.out.println("FAIL: " + name); }
    }

    static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    static Thread worker(Class<?> type) throws Exception {
        return (Thread) field(type, "sThread").get(null);
    }

    static void await(CountDownLatch latch, String name) throws Exception {
        check(name, latch.await(2, TimeUnit.SECONDS));
    }

    static void sensorFixture() throws Exception {
        // Stand in for a fresh cached car reading, avoiding any binder access.
        int[] values = (int[]) field(EvCar.class, "sHevVal").get(null);
        long[] times = (long[]) field(EvCar.class, "sHevAt").get(null);
        values[0x19] = 150;
        values[0x1f] = 120000;
        times[0x19] = times[0x1f] = EvCar.now();
    }

    static final class ModelSink implements EvModelSender.Sink {
        final CountDownLatch sent = new CountDownLatch(1);
        final boolean stopSelf;
        long stopMs;
        ModelSink(boolean stopSelf) { this.stopSelf = stopSelf; }
        public int send(byte[] bytes) {
            if (stopSelf) {
                long start = System.nanoTime();
                EvModelSender.stop();
                stopMs = (System.nanoTime() - start) / 1000000L;
            }
            sent.countDown();
            return 0;
        }
    }

    static void modelSessions() throws Exception {
        // Android Base64 is a throwing host stub; decode the real template on
        // the desktop and prevent the unrelated always-on logger from starting.
        field(EvModelSender.class, "sTemplate").set(null,
                java.util.Base64.getDecoder().decode(EvModelTemplate.TEMPLATE_B64));
        field(EvDriveLog.class, "sThread").set(null, Thread.currentThread());
        ModelSink first = new ModelSink(false);
        EvModelSender.start(first);
        Thread old = worker(EvModelSender.class);
        await(first.sent, "first model session sends");
        EvModelSender.stop();
        check("stop wakes the five-second model sleeper", !old.isAlive());
        ModelSink second = new ModelSink(false);
        EvModelSender.start(second);
        Thread current = worker(EvModelSender.class);
        await(second.sent, "reconnected model session sends");
        check("reconnect has one model worker", current != old && current.isAlive() && !old.isAlive());
        EvModelSender.stop();
        ModelSink self = new ModelSink(true);
        EvModelSender.start(self);
        await(self.sent, "model worker can stop itself");
        check("model stop does not join its own thread", self.stopMs < 1000);
        check("self-stopped model is inactive", !EvModelSender.aaActive());
    }

    static class DataOut implements LiveData.Out {
        final CountDownLatch sent = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        public void fuel(int percent, int range, boolean low) throws Exception {
            calls.incrementAndGet();
            sent.countDown();
        }
        public void odometer(double km) {}
    }

    static void dataSessions() throws Exception {
        sensorFixture();
        DataOut first = new DataOut();
        LiveData.start(first);
        Thread old = worker(LiveData.class);
        await(first.sent, "first live-data session sends");
        LiveData.stop();
        check("live-data worker exits on stop", !old.isAlive());
        sensorFixture();
        DataOut second = new DataOut();
        LiveData.start(second);
        Thread current = worker(LiveData.class);
        await(second.sent, "reconnected live-data session sends");
        check("reconnect has one live-data worker", current != old && current.isAlive() && !old.isAlive());
        LiveData.stop();

        // Simulate an in-flight native call that ignores interruption and
        // outlives the bounded stop wait. Once it returns, its worker must exit
        // even though a new AA session is already running.
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        DataOut blocked = new DataOut() {
            public void fuel(int percent, int range, boolean low) {
                entered.countDown();
                boolean done = false;
                while (!done) {
                    try { release.await(); done = true; }
                    catch (InterruptedException ignored) { /* native call still busy */ }
                }
            }
        };
        try {
            sensorFixture();
            LiveData.start(blocked);
            old = worker(LiveData.class);
            await(entered, "old native output is in flight");
            LiveData.stop();
            check("bounded stop returns while fake native output is blocked", old.isAlive());
            sensorFixture();
            DataOut next = new DataOut();
            LiveData.start(next);
            current = worker(LiveData.class);
            await(next.sent, "new session starts while old output unwinds");
            release.countDown();
            old.join(1000);
            check("stale live-data worker exits instead of joining the new session", !old.isAlive());
            check("stale completion leaves current worker running", current.isAlive() && next.calls.get() == 1);
        } finally {
            release.countDown();
            LiveData.stop();
        }

        sensorFixture();
        final CountDownLatch stopped = new CountDownLatch(1);
        final long[] stopMs = { Long.MAX_VALUE };
        LiveData.start(new DataOut() {
            public void fuel(int percent, int range, boolean low) {
                long start = System.nanoTime();
                LiveData.stop();
                stopMs[0] = (System.nanoTime() - start) / 1000000L;
                stopped.countDown();
            }
        });
        await(stopped, "live-data worker can stop itself");
        check("live-data stop does not join its own thread", stopMs[0] < 1000);
    }

    public static void main(String[] args) throws Exception {
        try {
            check("preconditioning is off without explicit settings", !HmgApiEntry.precondOn());
            modelSessions();
            dataSessions();
        } finally {
            EvModelSender.stop();
            LiveData.stop();
        }
        System.out.println("SessionWorkerTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }
}
