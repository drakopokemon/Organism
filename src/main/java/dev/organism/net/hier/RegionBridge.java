package dev.organism.net.hier;

import dev.organism.net.Membership;
import dev.organism.net.Wire;

import java.util.ArrayList;
import java.util.List;

/**
 * Межрегиональный мост поверх ЗАМОРОЖЕННОГО Membership API: периодические батчевые Wire.Sync-сводки.
 * WAN-трафик ограничен: (ticks/period) сводок на мост, а не per-node chatter.
 * Churn внутри региона не течёт в WAN: мост несёт агрегированное состояние. pull=false — без ping-pong шторма.
 */
public final class RegionBridge {
    public interface WanLink { void send(String remoteRegion, Wire.Sync summary); }

    private final Membership local;
    private final String region;
    private final long periodTicks;
    private final WanLink wan;

    public RegionBridge(Membership local, String region, long periodTicks, WanLink wan) {
        this.local = local;
        this.region = region;
        this.periodTicks = Math.max(1, periodTicks);
        this.wan = wan;
    }

    public String region() { return region; }

    public void tick() {
        if (local.tickCount() % periodTicks != 0) return;
        var view = local.view();
        List<Wire.Member> members = new ArrayList<>();
        List<Wire.NodeFacts> facts = new ArrayList<>();
        for (var e : view.entrySet()) {
            Wire.NodeFacts f = local.facts(e.getKey());
            members.add(new Wire.Member(e.getKey(), f == null ? 0 : f.incarnation(), e.getValue()));
            if (f != null) facts.add(f);
        }
        for (int i = 0; i < members.size(); i += 1000) {
            var chunk = List.copyOf(members.subList(i, Math.min(i + 1000, members.size())));
            wan.send(region, new Wire.Sync(local.self, false, chunk, i == 0 ? facts : List.of()));
        }
        if (members.isEmpty()) wan.send(region, new Wire.Sync(local.self, false, List.of(), List.of()));
    }

    public void onRemote(Wire.Sync s) { local.onMessage(s); }
}
