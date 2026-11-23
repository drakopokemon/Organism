package dev.organism.data;

import dev.organism.net.Membership;
import dev.organism.storage.StorageHost;

import java.nio.charset.StandardCharsets;

/** Детерминированный selftest столпа 4: fencing-эпохи над Wire, stateful handoff, legacy StorageHost не тронут. */
public final class DataPlaneSelfTest {
    private static int failed;
    private static void check(String name, boolean ok, String detail) {
        if (!ok) failed++;
        System.out.printf("  %s %-62s %s%n", ok ? "OK " : "FAIL", name, detail);
    }

    public static void main(String[] args) throws Exception {
        Membership m = new Membership("n1", 42, (to, msg) -> { }, (tick, node, from, to, inc) -> { });
        StorageHost host = new StorageHost(m, req -> { });
        EpochFence fence = new EpochFence();
        long e1;
        synchronized (fence.lock("cellA")) { e1 = fence.allocate("cellA"); }
        check("first epoch = 1", e1 == 1, "");
        FencedStorage old = new FencedStorage(host, fence, "cellA", e1);
        check("current epoch writes", old.write(3, "hello".getBytes(StandardCharsets.UTF_8)) == 1, "");
        long e2;
        synchronized (fence.lock("cellA")) { e2 = fence.allocate("cellA"); }
        check("handoff epoch = 2", e2 == 2, "");
        FencedStorage neu = new FencedStorage(host, fence, "cellA", e2);
        boolean stale = false;
        try { old.write(3, "split-brain".getBytes(StandardCharsets.UTF_8)); }
        catch (EpochFence.StaleEpochException e) { stale = true; }
        check("old epoch rejected (split-brain blocked)", stale, "");
        check("rejected write did not bump version", host.version(3) == 1, "");
        check("new epoch writes", neu.write(3, "world".getBytes(StandardCharsets.UTF_8)) == 2, "");
        check("check() reflects current epoch", fence.check("cellA", 2) && !fence.check("cellA", 1), "");
        check("resources independent", fence.allocate("cellB") == 1 && fence.current("cellA") == 2, "");
        check("raw StorageHost legacy path intact", host.write(3, "legacy".getBytes(StandardCharsets.UTF_8)) == 3, "");
        host.close();
        System.out.println(failed == 0 ? "DATAPLANE SELFTEST PASSED" : "DATAPLANE SELFTEST FAILED: " + failed);
        if (failed > 0) System.exit(1);
    }
}
