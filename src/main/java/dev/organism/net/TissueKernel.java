package dev.organism.net;

import dev.organism.api.OrganismApi;
import dev.organism.cell.CellHost;
import dev.organism.control.ControlPlaneClient;
import dev.organism.evolution.ControlPlaneUplink;
import dev.organism.evolution.EvolutionController;
import dev.organism.evolution.EvolutionEngine;
import dev.organism.evolution.EvolutionJournal;
import dev.organism.evolution.HumanApprovalGate;
import dev.organism.evolution.MutationGuard;
import dev.organism.evolution.ParetoFront;
import dev.organism.genome.Genome;
import dev.organism.immune.ImmuneAgent;
import dev.organism.migration.MigrationManager;
import dev.organism.obs.Json;
import dev.organism.obs.ObservabilityExporter;
import dev.organism.security.AuditLog;
import dev.organism.security.RbacPolicy;
import dev.organism.security.TransportSecurity;
import dev.organism.storage.StorageHost;
import dev.organism.symbiont.SymbiontWrapper;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * TissueKernel v4 = v3 + opt-in продакшн-слой. БЕЗ новых флагов поведение v3 идентично.
 *
 * v4 флаги (все opt-in):
 *   --secure --psk <key>            AES-256-GCM конверт gossip (64-hex или passphrase->PBKDF2)
 *   --audit-path <file>             hash-chain HMAC аудит (+<file>.key если нет --psk, +<file>.head anchor)
 *   --rbac-file <file>              строки <sha256hex(token)>:<ROLE>
 *   --api-port <n>                  HTTP API (Loom): /health /metrics /agents /genome /mutations /decide
 *   --tls-keystore <p12> --tls-pass <pw>
 *   --approval-risk <0..1> --approval-ttl-ms <n>   human-in-the-loop для рискованных мутаций
 *   --immune-enforce                дропать BudRequest на QUARANTINED узлы (детекция всегда on)
 *   --symbiont "<cmd>" --symbiont-port <n> --symbiont-mem <mb>
 *   --symbiont-backoff-ms <n> --symbiont-max-restarts <n>
 *   --snapshot-dir <dir> --snapshot-every <ticks>
 *   --otlp-endpoint <url> --otlp-interval-ms <n>
 *   --control-plane <url> --control-token <t> --control-poll-ms <n> --control-timeout-ms <n>
 *       6.1 uplink: зеркалит risky-мутации в control plane (four-eyes/Raft-аудит), применяет remote-решения
 */
public final class TissueKernel {
    private static final Set<String> BOOL_FLAGS = Set.of("secure", "immune-enforce");

    public static void main(String[] args) throws Exception {
        var a = parse(args);

        Genome genome = null;
        String genomePath = a.getOrDefault("genome", "genome.yaml");
        try {
            genome = Genome.fromFile(Path.of(genomePath));
            System.out.println("[GENOME] Loaded from " + genomePath + " | Objectives: " + genome.objectives.size() + ", Constraints: " + genome.constraints.size());
        } catch (Exception e) {
            System.err.println("[GENOME] Failed to load " + genomePath + ": " + e.getMessage());
            System.err.println("[GENOME] Falling back to default hardcoded parameters.");
        }

        int port = Integer.parseInt(a.getOrDefault("port", "7100"));
        int cellsPort = Integer.parseInt(a.getOrDefault("cells-port", "8100"));
        String advertise = a.getOrDefault("advertise", "127.0.0.1:" + port);
        String bind = a.getOrDefault("bind", "0.0.0.0");
        long periodMs = Long.parseLong(a.getOrDefault("period-ms", "1000"));
        long maxTicks = Long.parseLong(a.getOrDefault("ticks", "0"));
        long seed = Long.parseLong(a.getOrDefault("seed", "42"));
        String zone = a.getOrDefault("zone", "local");
        double targetP99 = genome != null && !genome.objectives.isEmpty() && genome.objectives.get(0) instanceof Genome.LatencyObjective l ? l.targetP99Ms() : Double.parseDouble(a.getOrDefault("target-p99", "120"));
        double usd = genome != null && !genome.objectives.isEmpty() && genome.objectives.stream().anyMatch(o -> o instanceof Genome.CostObjective) ? 0.10 : Double.parseDouble(a.getOrDefault("usd", "0.10"));

        var metrics = new ObservabilityExporter();
        String psk = a.get("psk");
        if (a.containsKey("secure") && (psk == null || psk.isBlank()))
            throw new IllegalArgumentException("--secure requires --psk");
        final TransportSecurity sec = a.containsKey("secure") ? new TransportSecurity(psk) : null;

        var extra = new Extras();
        String auditSecret = null;
        if (a.containsKey("audit-path")) {
            Path ap = Path.of(a.get("audit-path"));
            auditSecret = psk != null ? psk : loadOrCreateKey(ap);
            extra.audit = new AuditLog(ap, auditSecret);
            safeAudit(extra, metrics, "KERNEL_START", "advertise=" + advertise + " secure=" + (sec != null));
        }
        final BiConsumer<String, String> safeAudit = (ev, d) -> safeAudit(extra, metrics, ev, d);
        final RbacPolicy rbac = a.containsKey("rbac-file") ? RbacPolicy.fromFile(Path.of(a.get("rbac-file"))) : null;
        final boolean enforce = a.containsKey("immune-enforce");
        final var immune = new ImmuneAgent(targetP99, 2, 4, 3, 50, ev -> {
            System.out.println("[t" + ev.tick() + "] IMMUNE " + ev.node() + " " + ev.from() + "->" + ev.to()
                    + " p99=" + ev.p99Ms() + " " + ev.reason());
            metrics.inc("organism_immune_events_total", 1, "to", ev.to().name());
            if (ev.to() == ImmuneAgent.Health.QUARANTINED) metrics.inc("organism_immune_quarantine_total", 1);
            safeAudit(extra, metrics, "IMMUNE", "node=" + ev.node() + " " + ev.from() + "->" + ev.to()
                    + " p99=" + ev.p99Ms() + " reason=" + ev.reason());
        });

        var sock = new DatagramSocket(new InetSocketAddress(bind, port));
        byte[] secret = "organism-genome-secret-v1".getBytes(StandardCharsets.UTF_8);
        var codec = new WireCodec(secret);
        var journal = Files.newBufferedWriter(Path.of("kernel-journal-" + port + ".jsonl"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        var hostHolder = new Object() { CellHost h; StorageHost s; };

        Membership.Transport tx = (to, msg) -> {
            try {
                if (enforce && msg instanceof Wire.BudRequest && immune.isQuarantined(to)) {
                    metrics.inc("organism_bud_blocked_total", 1);
                    safeAudit.accept("BUD_BLOCKED", "to=" + to + " quarantined");
                    return;
                }
                byte[] b = codec.wrap(msg);
                if (sec != null) b = sec.wrap(b);
                int c = to.lastIndexOf(':');
                var addr = new InetSocketAddress(to.substring(0, c), Integer.parseInt(to.substring(c + 1)));
                sock.send(new DatagramPacket(b, b.length, addr));
            } catch (IOException e) { /* UDP: потеря допустима протоколом */ }
        };
        Membership.Listener listener = new Membership.Listener() {
            @Override public void onChange(long tick, String node, Wire.Status from, Wire.Status to, int inc) {
                String line = "{\"t\":" + tick + ",\"node\":\"" + node + "\",\"from\":\"" + from
                        + "\",\"to\":\"" + to + "\",\"inc\":" + inc + "}";
                System.out.println("[t" + tick + "] " + node + " " + from + " -> " + to + " (inc " + inc + ")");
                try { journal.write(line); journal.newLine(); journal.flush(); }
                catch (IOException e) { throw new UncheckedIOException(e); }
                metrics.inc("organism_membership_changes_total", 1, "to", String.valueOf(to));
                safeAudit.accept("MEMBERSHIP", "node=" + node + " " + from + "->" + to + " inc=" + inc);
                if (to == Wire.Status.DEAD) immune.forget(tick, node);
            }
            @Override public void onBudReq(long tick, String from, double load) {
                if (hostHolder.h != null && hostHolder.h.freeMemMb() > 300) {
                    System.out.println("[t" + tick + "] BUD_REMOTE_REQ from=" + from + " load=" + load);
                }
            }
        };

        var membership = new Membership(advertise, seed, tx, listener);
        for (var s : a.getOrDefault("seeds", "").split(",")) if (!s.isBlank()) membership.addSeed(s.trim());

        HumanApprovalGate gate = null;
        EvolutionEngine engine = null;
        ParetoFront pareto = null;
        if (a.containsKey("approval-risk")) {
            if (genome == null) {
                System.err.println("[EVO] --approval-risk requires genome; skipping evolution layer");
            } else {
                gate = new HumanApprovalGate(Long.parseLong(a.getOrDefault("approval-ttl-ms", "300000")));
                var controller = new EvolutionController(gate, safeAudit, metrics, Double.parseDouble(a.get("approval-risk")));
                extra.evoJournal = new EvolutionJournal(Path.of("evolution-" + port + ".jsonl"));
                engine = new EvolutionEngine(genome, membership, extra.evoJournal, new MutationGuard(), seed, controller);
                var base = new TreeMap<String, Double>();
                base.put("poolSize", 10.0); base.put("queueLimit", 500.0); base.put("cacheMb", 128.0); base.put("timeoutMs", 500.0);
                engine.registerAgent(advertise, base);
                var shadow = new TreeMap<>(base);
                shadow.put("timeoutMs", 600.0);
                engine.registerAgent(advertise + "#shadow", shadow);
                controller.bind(engine);
                pareto = ParetoFront.fromGenome(genome);
                System.out.println("[EVO] human-in-the-loop enabled: riskThreshold=" + a.get("approval-risk"));
            }
        }

        ControlPlaneUplink uplink = null;
        long controlPollEvery = 0;
        if (a.containsKey("control-plane")) {
            if (gate == null) {
                System.err.println("[UPLINK] --control-plane requires --approval-risk with genome; skipping uplink");
            } else {
                var cpClient = new ControlPlaneClient(a.get("control-plane"), a.get("control-token"),
                        Integer.parseInt(a.getOrDefault("control-timeout-ms", "3000")));
                uplink = new ControlPlaneUplink(cpClient, gate, safeAudit, metrics);
                extra.uplink = uplink;
                long pollMs = Long.parseLong(a.getOrDefault("control-poll-ms", "2000"));
                controlPollEvery = Math.max(1, pollMs / Math.max(1, periodMs));
                safeAudit.accept("CONTROL_UPLINK_ENABLED", "url=" + a.get("control-plane"));
                System.out.println("[UPLINK] control-plane=" + a.get("control-plane") + " pollEvery=" + controlPollEvery + " ticks");
            }
        }

        MigrationManager mm = null;
        long snapEvery = 0;
        if (a.containsKey("snapshot-dir")) {
            String snapSecret = psk != null ? psk : auditSecret;
            if (snapSecret == null) {
                snapSecret = "organism-snapshot-dev-v1";
                System.err.println("[SNAPSHOT] WARN: dev secret; set --psk (or --audit-path) for production");
            }
            mm = new MigrationManager(Path.of(a.get("snapshot-dir")), snapSecret);
            snapEvery = Long.parseLong(a.getOrDefault("snapshot-every", "10"));
        }

        hostHolder.s = new StorageHost(membership, req -> {
            try {
                byte[] b = codec.wrap(req);
                if (sec != null) b = sec.wrap(b);
                for (var e : membership.view().entrySet()) {
                    if (!e.getKey().equals(advertise) && e.getValue() == Wire.Status.ALIVE) {
                        int c = e.getKey().lastIndexOf(':');
                        var addr = new InetSocketAddress(e.getKey().substring(0, c), Integer.parseInt(e.getKey().substring(c + 1)));
                        sock.send(new DatagramPacket(b, b.length, addr));
                    }
                }
            } catch (IOException e) { /* UDP: потеря допустима */ }
        }, Path.of("data", "wal"));

        hostHolder.h = new CellHost(membership, cellsPort, seed, targetP99,
                (t, msg) -> {
                    try {
                        journal.write("{\"t\":" + t + ",\"ev\":\"" + msg.replace("\"", "\\\"") + "\"}\n");
                        journal.flush();
                    } catch (IOException e) { throw new UncheckedIOException(e); }
                    System.out.println("[t" + t + "] " + msg);
                });

        Thread.ofVirtual().name("wire-rx").start(() -> {
            byte[] buf = new byte[4096];
            var pkt = new DatagramPacket(buf, buf.length);
            while (!sock.isClosed()) {
                try {
                    sock.receive(pkt);
                    byte[] payload = buf;
                    int len = pkt.getLength();
                    if (sec != null) {
                        byte[] dec = sec.unwrap(buf, len);
                        if (dec == null) { metrics.inc("organism_gossip_rejected_total", 1); continue; }
                        payload = dec;
                        len = dec.length;
                    }
                    var msg = codec.unwrap(payload, len);
                    if (msg instanceof Wire.ReplicateRequest rr) {
                        hostHolder.s.onReplicateRequest(rr);
                    } else {
                        membership.onMessage(msg);
                    }
                } catch (IOException e) { break; }
                catch (RuntimeException e) { /* битая датаграмма: дроп */ }
            }
        });

        if (a.containsKey("symbiont")) {
            int sp = Integer.parseInt(a.getOrDefault("symbiont-port", "0"));
            int smem = Integer.parseInt(a.getOrDefault("symbiont-mem", "0"));
            long sback = Long.parseLong(a.getOrDefault("symbiont-backoff-ms", "500"));
            int smax = Integer.parseInt(a.getOrDefault("symbiont-max-restarts", "5"));
            extra.symbiont = new SymbiontWrapper(a.get("symbiont"), sp, smem,
                    Path.of("symbiont-" + port + ".log"), sback, smax, (ev, d) -> {
                safeAudit.accept(ev, d);
                metrics.inc("organism_symbiont_events_total", 1, "event", ev);
                System.out.println("[SYMBIONT] " + ev + " " + d);
            });
            try { extra.symbiont.start(); }
            catch (IllegalStateException e) { System.err.println("[SYMBIONT] refused: " + e.getMessage()); }
        }

        if (a.containsKey("api-port")) {
            if (rbac == null) System.err.println("[API] WARN: no --rbac-file; protected endpoints fail-closed 403");
            final EvolutionEngine fe = engine;
            final HumanApprovalGate fg = gate;
            final Genome fgenome = genome;
            Supplier<String> agentsSup = () -> agentsJson(fe, hostHolder.h, immune);
            Supplier<String> genomeSup = () -> genomeJson(fgenome);
            Runnable manualTick = fe == null ? null : fe::tick;
            final String fAuditSecret = auditSecret;
            final Path fAuditPath = a.containsKey("audit-path") ? Path.of(a.get("audit-path")) : null;
            Supplier<String> clusterSup = () -> clusterJson(membership, advertise, immune);
            Supplier<String> auditSup = () -> auditJson(fAuditPath, fAuditSecret);
            extra.api = new OrganismApi(Integer.parseInt(a.get("api-port")), rbac, metrics,
                    agentsSup, genomeSup, fg, manualTick, a.get("tls-keystore"), a.get("tls-pass"),
                    clusterSup, auditSup, true);
            System.out.println("[API] listening :" + extra.api.port() + (a.containsKey("tls-keystore") ? " TLS" : ""));
        }

        if (a.containsKey("otlp-endpoint")) {
            extra.otlp = metrics.startOtlpPush(a.get("otlp-endpoint"),
                    Long.parseLong(a.getOrDefault("otlp-interval-ms", "15000")), "organism-" + advertise);
        }

        final MigrationManager fmm = mm;
        final long fSnapEvery = snapEvery;
        final EvolutionEngine fengine = engine;
        final HumanApprovalGate fgate = gate;
        final ParetoFront fpareto = pareto;
        final ControlPlaneUplink fuplink = uplink;
        final long fControlEvery = controlPollEvery;
        var closed = new AtomicBoolean();
        Runnable shutdown = () -> {
            if (!closed.compareAndSet(false, true)) return;
            sock.close(); hostHolder.h.close(); hostHolder.s.close();
            try { journal.close(); } catch (IOException ignored) { }
            if (extra.api != null) extra.api.close();
            if (extra.symbiont != null) extra.symbiont.close();
            if (extra.uplink != null) extra.uplink.close();
            if (extra.otlp != null) { try { extra.otlp.close(); } catch (Exception ignored) { } }
            if (extra.evoJournal != null) extra.evoJournal.close();
            if (extra.audit != null && !extra.auditDead) {
                try { extra.audit.log("KERNEL_STOP", "advertise=" + advertise); extra.audit.close(); }
                catch (RuntimeException ignored) { }
            }
        };
        Runtime.getRuntime().addShutdownHook(new Thread(shutdown));

        System.out.println("tissue-kernel " + advertise + " cells-port=" + cellsPort + " zone=" + zone + " period=" + periodMs + "ms");
        var rt = Runtime.getRuntime();
        long tick = 0;
        while (maxTicks == 0 || tick < maxTicks) {
            tick++;
            int memFree = (int) ((rt.maxMemory() - rt.totalMemory() + rt.freeMemory()) >> 20);
            int diskFree = (int) (new File(".").getUsableSpace() >> 20);
            membership.setFacts(new Wire.NodeFacts(advertise, zone, membership.incarnation(), tick,
                    rt.availableProcessors(), memFree, diskFree, usd, hostHolder.h.cellCount(), hostHolder.h.myP99()));
            hostHolder.h.tick(tick);
            hostHolder.s.tick(tick);
            membership.tick();

            immune.observe(tick, advertise, hostHolder.h.myP99());
            for (var e : membership.view().entrySet()) {
                if (e.getValue() == Wire.Status.ALIVE && !e.getKey().equals(advertise)) {
                    var f = membership.facts(e.getKey());
                    if (f != null && f.p99Ms() > 0) immune.observe(tick, e.getKey(), f.p99Ms());
                }
            }
            immune.sweep(tick);
            metrics.gauge("organism_tick", tick);
            metrics.gauge("organism_cells", hostHolder.h.cellCount());
            metrics.gauge("organism_p99_ms", hostHolder.h.myP99(), "node", advertise);
            metrics.gauge("organism_mem_free_mb", memFree);
            metrics.gauge("organism_view_size", membership.view().size());

            if (fengine != null) {
                double fit = fpareto.scalarize(Map.of("p99", Math.max(hostHolder.h.myP99(), 1e-9), "usd", usd));
                fengine.updateFitness(advertise, fit);
                fengine.updateFitness(advertise + "#shadow", fit * 0.9);
                fengine.tick();
                int expired = fgate.sweep();
                if (expired > 0) {
                    metrics.inc("organism_mutations_expired_total", expired);
                    safeAudit.accept("MUTATION_EXPIRED", "count=" + expired);
                }
                metrics.gauge("organism_mutations_pending", fgate.pendingCount());
            }

            if (fuplink != null && tick % fControlEvery == 0) fuplink.poll();

            if (fmm != null && fSnapEvery > 0 && tick % fSnapEvery == 0) {
                try {
                    if (fengine != null) {
                        for (var ag : fengine.snapshot().values())
                            fmm.snapshot(ag.id(), paramsJson(ag.params()).getBytes(StandardCharsets.UTF_8));
                    } else {
                        fmm.snapshot(advertise, ("{\"p99\":" + Json.num(hostHolder.h.myP99())
                                + ",\"cells\":" + hostHolder.h.cellCount() + ",\"tick\":" + tick + "}")
                                .getBytes(StandardCharsets.UTF_8));
                    }
                    metrics.inc("organism_snapshots_total", 1);
                } catch (IOException e) {
                    metrics.inc("organism_snapshot_failures_total", 1);
                    System.err.println("[SNAPSHOT] failed: " + e.getMessage());
                }
            }

            if (tick % 5 == 0) System.out.println("[t" + tick + "] view=" + membership.view() + " cells=" + hostHolder.h.cellCount());
            Thread.sleep(periodMs);
        }
        shutdown.run();
    }

    private static final class Extras {
        OrganismApi api; SymbiontWrapper symbiont; AutoCloseable otlp;
        AuditLog audit; EvolutionJournal evoJournal; boolean auditDead; ControlPlaneUplink uplink;
    }

    private static void safeAudit(Extras x, ObservabilityExporter metrics, String ev, String d) {
        if (x.audit == null || x.auditDead) return;
        try { x.audit.log(ev, d); }
        catch (RuntimeException e) {
            x.auditDead = true;
            metrics.inc("organism_audit_failures_total", 1);
            System.err.println("[AUDIT] FAILED, disabling audit: " + e.getMessage());
        }
    }

    private static String loadOrCreateKey(Path auditFile) throws IOException {
        if (auditFile.getParent() != null) Files.createDirectories(auditFile.getParent());
        Path kp = auditFile.resolveSibling(auditFile.getFileName() + ".key");
        if (Files.exists(kp)) return Files.readString(kp).trim();
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        String hex = java.util.HexFormat.of().formatHex(k);
        try {
            Files.createFile(kp, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                           java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException e) { Files.createFile(kp); }
        Files.writeString(kp, hex);
        System.out.println("[AUDIT] generated secret key file: " + kp + " (keep it to verify the chain)");
        return hex;
    }

    private static String clusterJson(Membership membership, String self, ImmuneAgent immune) {
        var sb = new StringBuilder("{\"self\":").append(Json.str(self)).append(",\"nodes\":[");
        boolean first = true;
        for (var e : membership.view().entrySet()) {
            if (!first) sb.append(',');
            first = false;
            String node = e.getKey();
            var f = membership.facts(node);
            sb.append("{\"node\":").append(Json.str(node))
              .append(",\"status\":").append(Json.str(e.getValue().name()))
              .append(",\"self\":").append(node.equals(self))
              .append(",\"health\":").append(Json.str(immune.health(node).name()));
            if (f != null) sb.append(",\"zone\":").append(Json.str(f.zone()))
              .append(",\"p99\":").append(Json.num(f.p99Ms()))
              .append(",\"cells\":").append(f.cells())
              .append(",\"memFree\":").append(f.memFreeMb())
              .append(",\"inc\":").append(f.incarnation());
            sb.append('}');
        }
        return sb.append("]}").toString();
    }

    private static String auditJson(Path file, String secret) {
        if (file == null || secret == null || !Files.exists(file)) return "{\"verified\":false,\"events\":[]}";
        try {
            boolean verified = AuditLog.verify(file, secret);
            var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            var sb = new StringBuilder("{\"verified\":").append(verified).append(",\"events\":[");
            int from = Math.max(0, lines.size() - 40);
            boolean first = true;
            for (int i = from; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.isEmpty()) continue;
                int seqEnd = l.indexOf(',');
                String seq = l.substring(7, seqEnd);
                int evS = l.indexOf("\"event\":\"") + 9;
                int evE = l.indexOf("\",\"detail\"");
                String event = evS >= 9 && evE > evS ? l.substring(evS, evE) : "";
                int dS = l.indexOf("\"detail\":\"") + 10;
                int dE = l.lastIndexOf("\",\"prev\":\"");
                String detail = dS >= 10 && dE > dS ? l.substring(dS, dE) : "";
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"seq\":").append(seq).append(",\"event\":").append(Json.str(event))
                  .append(",\"detail\":").append(Json.str(detail)).append('}');
            }
            return sb.append("]}").toString();
        } catch (Exception e) {
            return "{\"verified\":false,\"events\":[],\"error\":\"read failed\"}";
        }
    }

    private static String paramsJson(Map<String, Double> p) {
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var e : new TreeMap<>(p).entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(Json.str(e.getKey())).append(':').append(Json.num(e.getValue()));
        }
        return sb.append('}').toString();
    }

    private static String agentsJson(EvolutionEngine engine, CellHost h, ImmuneAgent immune) {
        var sb = new StringBuilder("{");
        sb.append("\"cells\":").append(h.cellCount())
          .append(",\"p99\":").append(Json.num(h.myP99()))
          .append(",\"immune\":{");
        boolean first = true;
        for (var e : immune.snapshot().entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(Json.str(e.getKey())).append(":").append(Json.str(e.getValue().name()));
        }
        sb.append("},\"agents\":[");
        if (engine != null) {
            boolean f2 = true;
            for (var ag : new TreeMap<>(engine.snapshot()).values()) {
                if (!f2) sb.append(',');
                f2 = false;
                sb.append("{\"id\":").append(Json.str(ag.id()))
                  .append(",\"fitness\":").append(Json.num(ag.fitness()))
                  .append(",\"lastMutation\":").append(ag.lastMutation())
                  .append(",\"canary\":").append(ag.canary())
                  .append(",\"params\":").append(paramsJson(ag.params())).append('}');
            }
        }
        return sb.append("]}").toString();
    }

    private static String genomeJson(Genome g) {
        if (g == null) return "{}";
        var obj = new StringJoiner(",");
        for (var o : g.objectives) {
            switch (o) {
                case Genome.LatencyObjective l -> obj.add("{\"type\":\"latency\",\"targetP99Ms\":" + Json.num(l.targetP99Ms()) + "}");
                case Genome.CostObjective c -> obj.add("{\"type\":\"cost\",\"maxUsdPerMonth\":" + Json.num(c.maxUsdPerMonth()) + "}");
                case Genome.ThroughputObjective t -> obj.add("{\"type\":\"throughput\",\"targetRps\":" + Json.num(t.targetRps()) + "}");
                default -> { }
            }
        }
        var con = new StringJoiner(",");
        for (var c : g.constraints) {
            switch (c) {
                case Genome.RegionConstraint r -> con.add("{\"type\":\"region\",\"allowedRegion\":" + Json.str(r.allowedRegion()) + "}");
                case Genome.ResourceConstraint r -> con.add("{\"type\":\"resource\",\"maxRamMb\":" + r.maxRamMb() + "}");
                case Genome.ForbiddenApisConstraint f -> con.add("{\"type\":\"forbidden_apis\",\"apis\":" + Json.str(f.forbiddenApis()) + "}");
                default -> { }
            }
        }
        var cap = new StringJoiner(",");
        for (var c : g.capabilities) {
            switch (c) {
                case Genome.ProcessingCapability p -> cap.add("{\"type\":\"processing\",\"minInstances\":" + p.minInstances() + ",\"maxInstances\":" + p.maxInstances() + "}");
                case Genome.ImmuneCapability i -> cap.add("{\"type\":\"immune\",\"enabled\":" + i.enabled() + "}");
                case Genome.StorageCapability st -> cap.add("{\"type\":\"storage\",\"shards\":" + st.shards() + ",\"replication\":" + st.replication() + "}");
                case Genome.BalancingCapability ba -> cap.add("{\"type\":\"balancing\",\"algorithm\":" + Json.str(ba.algorithm()) + "}");
                default -> { }
            }
        }
        var p = g.mutationPolicy;
        return "{\"objectives\":[" + obj + "],\"constraints\":[" + con + "],\"capabilities\":[" + cap + "],"
                + "\"mutationPolicy\":{\"poolSizeStep\":" + Json.num(p.poolSizeStep())
                + ",\"poolSizeMin\":" + Json.num(p.poolSizeMin()) + ",\"poolSizeMax\":" + Json.num(p.poolSizeMax())
                + ",\"queueLimitStep\":" + Json.num(p.queueLimitStep()) + ",\"queueLimitMin\":" + Json.num(p.queueLimitMin())
                + ",\"queueLimitMax\":" + Json.num(p.queueLimitMax()) + ",\"cacheMbStep\":" + Json.num(p.cacheMbStep())
                + ",\"cacheMbMin\":" + Json.num(p.cacheMbMin()) + ",\"cacheMbMax\":" + Json.num(p.cacheMbMax())
                + ",\"timeoutMsStep\":" + Json.num(p.timeoutMsStep()) + ",\"timeoutMsMin\":" + Json.num(p.timeoutMsMin())
                + ",\"timeoutMsMax\":" + Json.num(p.timeoutMsMax()) + "}}";
    }

    private static Map<String, String> parse(String[] args) {
        var m = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String k = args[i].substring(2);
            if (BOOL_FLAGS.contains(k)) m.put(k, "true");
            else if (i + 1 < args.length) m.put(k, args[++i]);
        }
        return m;
    }
}
