package dev.egmp.aaev;

import java.util.ArrayList;
import java.util.List;

/**
 * Host test for RelayService.answer: what the relay service asks HMG-API for,
 * and what it tells GPM, for each request (hmgapi-mod/test/run.sh). The fake
 * answers like HMG-API 1.0.0's Commands (hmgapi.vehicle.Commands): route values
 * and "route" (the holder only), a mod's own automatic stop doesn't block its
 * next start, a warm pack is refused, and an automatic start the car didn't
 * take within the activation time blocks automatic starts until a manual one.
 */
public final class RelayTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    static final class FakeHmgApi implements RelayService.Commands {
        final List<String> calls = new ArrayList<String>();
        String holder;   // who conditions (null: nobody)
        boolean auto, manualStop, timedOut, warmPack;

        @Override
        public String command(String name, String... a) throws Exception {
            calls.add(name + " " + String.join(" ", a));
            if (!"conditioning".equals(name)) throw new Exception("not declared in mod.json (vehicle commands): " + name);
            if ("status".equals(a[0])) {
                return holder == null ? "ok " + (timedOut ? "timed_out" : "off") : "ok active (" + holder + ")";
            }
            if ("stop".equals(a[0])) {
                if (holder == null) return "ok not conditioning";
                if (!(auto && "egmp-aa-ev".equals(holder))) manualStop = true;
                holder = null;
                return "ok conditioning stopped";
            }
            if ("route".equals(a[0])) {
                if (!"egmp-aa-ev".equals(holder)) {
                    throw new Exception("refused: only the mod conditioning changes its route (" + (holder == null ? "not conditioning" : holder) + ")");
                }
                return "ok conditioning route " + a[1] + " km " + a[2] + " min";
            }
            boolean isAuto = "auto-start".equals(a[0]);
            if (!isAuto && !"start".equals(a[0])) throw new Exception("refused: conditioning start|auto-start|stop|route|status");
            if (isAuto && manualStop) throw new Exception("refused: stopped by hand; automatic starts wait for a manual start");
            if (isAuto && timedOut) throw new Exception("refused: the car didn't start conditioning last time; automatic starts wait for a manual start");
            if (holder != null) return "ok already conditioning (" + holder + ")";
            if (warmPack) throw new Exception("refused: the pack is warm (24 °C / 75 °F; this car usually won't heat at 21 °C / 70 °F or warmer); add \"warm\" to send it anyway");
            holder = "egmp-aa-ev";
            auto = isAuto;
            return "ok conditioning started";
        }

        /** HMG-API's own tick: the indicator never came on in time */
        void timeOut() {
            holder = null;
            timedOut = true;
        }
    }

    public static void main(String[] args) throws Exception {
        FakeHmgApi api = new FakeHmgApi();
        RelayService.Owned own = new RelayService.Owned(null);
        RelayService.Owned own2 = new RelayService.Owned(null);
        check("a stop before any start isn't sent", RelayService.answer(api, own, "stop").equals("ok not ours to stop") && api.calls.isEmpty());
        check("ping", RelayService.answer(api, own, "ping").equals("ok") && api.calls.isEmpty());

        String r = RelayService.answer(api, own, "start 30 20");
        check("start -> auto-start with the route values: " + api.calls, api.calls.get(0).equals("conditioning auto-start 30 20"));
        check("start answered ok", r.equals("ok conditioning started"));

        r = RelayService.answer(api, own, "start 30 20");
        check("a second start, ours already: ok", r.startsWith("ok already conditioning (egmp-aa-ev)"));

        api.calls.clear();
        r = RelayService.answer(api, own, "route 25 17");
        check("route -> conditioning route: " + api.calls, api.calls.get(0).equals("conditioning route 25 17"));
        check("route answered ok: " + r, r.equals("ok conditioning route 25 km 17 min"));

        r = RelayService.answer(api, own, "stop");
        check("stop", r.equals("ok conditioning stopped"));

        r = RelayService.answer(api, own, "start 30 20");
        check("after our own stop the next automatic start goes (1.0.0): " + r, r.equals("ok conditioning started"));

        api.timeOut();
        r = RelayService.answer(api, own, "route 22 15");
        check("HMG-API stopped it (the car didn't take it in time): the route update is refused: " + r, r.startsWith("err refused: only the mod conditioning"));
        check("... and it isn't ours any more", !own.owned);
        api.calls.clear();
        check("... so Precond's cancel isn't sent", RelayService.answer(api, own, "stop").equals("ok not ours to stop") && api.calls.isEmpty());
        r = RelayService.answer(api, own, "start 30 20");
        check("after a timeout automatic starts wait for a manual one: " + r, r.startsWith("err refused: the car didn't start"));
        check("... and it isn't marked ours", !own.owned);

        FakeHmgApi warm = new FakeHmgApi();
        warm.warmPack = true;
        RelayService.Owned own4 = new RelayService.Owned(null);
        r = RelayService.answer(warm, own4, "start 30 20");
        check("a warm pack: refused, err to Precond: " + r, r.startsWith("err refused: the pack is warm") && !own4.owned);

        FakeHmgApi other = new FakeHmgApi();
        other.holder = "launchpad";
        r = RelayService.answer(other, own2, "start 30 20");
        check("someone else's conditioning is never ours: " + r, r.startsWith("err already conditioning (launchpad)"));
        other.calls.clear();
        r = RelayService.answer(other, own2, "stop");
        check("... so Precond's cancel after that refusal doesn't stop theirs: " + r, r.equals("ok not ours to stop") && other.calls.isEmpty() && "launchpad".equals(other.holder));

        // someone took over (the owner stopped ours and started their own)
        RelayService.Owned own3 = new RelayService.Owned(null);
        own3.set(true);
        RelayService.Commands patched = new RelayService.Commands() {
            public String command(String name, String... a) throws Exception {
                if ("route".equals(a[0])) throw new Exception("refused: only the mod conditioning changes its route (launchpad)");
                throw new Exception("unexpected " + a[0]);
            }
        };
        r = RelayService.answer(patched, own3, "route 20 12");
        check("a route refusal naming another holder: err, and not ours any more: " + r, r.startsWith("err refused: only") && !own3.owned);
        check("... so the cancel that follows isn't sent", RelayService.answer(patched, own3, "stop").equals("ok not ours to stop"));

        // A persisted journal can outlive HMG-API's holder. No route update is
        // required between takeover/timeout and recovery's cancellation.
        RelayService.Owned stale = new RelayService.Owned(null);
        stale.set(true);
        other.calls.clear();
        r = RelayService.answer(other, stale, "stop");
        check("stale ownership cannot stop a replacement holder: " + r,
                r.equals("ok not ours to stop") && "launchpad".equals(other.holder)
                && other.calls.equals(java.util.Arrays.asList("conditioning status")) && !stale.owned);
        java.io.File journal = java.io.File.createTempFile("egmp-relay-owned-", ".test");
        try {
            RelayService.Owned recovered = new RelayService.Owned(journal);
            check("relay restart restores the ownership journal", recovered.owned);
            other.calls.clear();
            r = RelayService.answer(other, recovered, "stop");
            check("restart journal is checked against the current holder",
                    r.equals("ok not ours to stop") && !recovered.owned && !journal.exists()
                    && other.calls.equals(java.util.Arrays.asList("conditioning status"))
                    && "launchpad".equals(other.holder));
        } finally { journal.delete(); }
        stale.set(true);
        api.calls.clear();
        r = RelayService.answer(api, stale, "stop");
        check("core timeout is detected before cancellation: " + r,
                r.equals("ok not ours to stop")
                && api.calls.equals(java.util.Arrays.asList("conditioning status")) && !stale.owned);

        final List<String> statusCalls = new ArrayList<String>();
        RelayService.Commands unavailable = new RelayService.Commands() {
            public String command(String name, String... a) throws Exception {
                statusCalls.add(a[0]);
                throw new Exception("status unavailable");
            }
        };
        stale.set(true);
        r = RelayService.answer(unavailable, stale, "stop");
        check("unreadable status sends no stop and keeps journal for retry",
                r.startsWith("err") && stale.owned && statusCalls.equals(java.util.Arrays.asList("status")));
        RelayService.Commands unknownStatus = new RelayService.Commands() {
            public String command(String name, String... a) throws Exception {
                statusCalls.add(a[0]);
                return "ok future-state (egmp-aa-ev)";
            }
        };
        statusCalls.clear();
        r = RelayService.answer(unknownStatus, stale, "stop");
        check("unknown status sends no stop and keeps journal for retry",
                r.startsWith("err") && stale.owned && statusCalls.equals(java.util.Arrays.asList("status")));
        check("owned waiting and requested states can be cancelled",
                RelayService.holdsRequest("ok waiting (egmp-aa-ev)")
                && RelayService.holdsRequest("ok requested (egmp-aa-ev)"));
        check("ending states without a holder aren't ours",
                !RelayService.holdsRequest("ok ended") && !RelayService.holdsRequest("ok stopping"));

        check("km out of range", RelayService.answer(api, own, "start 2047 20").startsWith("err"));
        check("minutes out of range", RelayService.answer(api, own, "route 10 1023").startsWith("err"));
        check("not a number", RelayService.answer(api, own, "start ten 20").startsWith("err"));
        check("unknown request", RelayService.answer(api, own, "charge_door open").startsWith("err unknown"));
        check("empty request", RelayService.answer(api, own, "").startsWith("err"));

        System.out.println("RelayTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
