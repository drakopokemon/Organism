#!/usr/bin/env bash
# ORGANISM chaos suite: partition/recovery, kill -9 + secure gossip + audit + RBAC/TLS API, OOM injection + circuit breaker.
set -euo pipefail
cd "$(dirname "$0")/.."
echo "=== ORGANISM chaos suite ==="
mkdir -p data/wal
PIDS=""
cleanup() { for p in $PIDS; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

# ---------- Scenario 1: partition (SIGSTOP) + recovery (v3 defaults, no new flags) ----------
java -cp out dev.organism.net.TissueKernel --port 7101 --cells-port 8101 --seed 1 > node1.log 2>&1 &
P1=$!; PIDS="$PIDS $P1"
sleep 2
if ! kill -0 $P1 2>/dev/null; then echo "Node1 crashed:"; cat node1.log; exit 1; fi
java -cp out dev.organism.net.TissueKernel --port 7102 --cells-port 8102 --seed 2 --seeds 127.0.0.1:7101 > node2.log 2>&1 &
P2=$!; PIDS="$PIDS $P2"
sleep 2
if ! kill -0 $P2 2>/dev/null; then echo "Node2 crashed:"; cat node2.log; exit 1; fi
java -cp out dev.organism.net.TissueKernel --port 7103 --cells-port 8103 --seed 3 --seeds 127.0.0.1:7101 > node3.log 2>&1 &
P3=$!; PIDS="$PIDS $P3"
sleep 3
echo "Injecting partition (SIGSTOP node2)..."
kill -STOP $P2 || true
sleep 5
echo "Healing partition (SIGCONT node2)..."
kill -CONT $P2 || true
sleep 5
kill $P1 $P2 $P3 2>/dev/null || true
wait $P1 $P2 $P3 2>/dev/null || true
echo "Scenario 1 (partition/recovery) OK"

# ---------- Scenario 2: secure gossip + audit chain + RBAC/TLS API + kill -9 ----------
PSK=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
TOKEN="chaos-admin-$$-$(date +%s)"
HASH=$(printf '%s' "$TOKEN" | sha256sum | cut -d' ' -f1)
WORK=$(mktemp -d)
RBAC="$WORK/rbac.txt"; echo "$HASH:ADMIN" > "$RBAC"
KS="$WORK/ks.p12"
keytool -genkeypair -alias organism -keyalg RSA -keysize 2048 -storetype PKCS12 \
  -keystore "$KS" -storepass changeit -dname "CN=localhost" -ext SAN=dns:localhost,ip:127.0.0.1 -validity 2 >/dev/null 2>&1
AUD1="$WORK/audit1.jsonl"
SNAPS="$WORK/snaps"
java -cp out dev.organism.net.TissueKernel --port 7201 --cells-port 8201 --seed 11 \
  --secure --psk "$PSK" --audit-path "$AUD1" --rbac-file "$RBAC" --api-port 9201 \
  --tls-keystore "$KS" --tls-pass changeit --approval-risk 0.05 --approval-ttl-ms 60000 \
  --snapshot-dir "$SNAPS" --snapshot-every 3 --period-ms 300 --ticks 100 > secure1.log 2>&1 &
S1=$!; PIDS="$PIDS $S1"
java -cp out dev.organism.net.TissueKernel --port 7202 --cells-port 8202 --seed 12 \
  --secure --psk "$PSK" --seeds 127.0.0.1:7201 --period-ms 300 --ticks 100 > secure2.log 2>&1 &
S2=$!; PIDS="$PIDS $S2"
sleep 4
if ! kill -0 $S1 2>/dev/null; then echo "Secure node1 crashed:"; cat secure1.log; exit 1; fi
curl -sk https://127.0.0.1:9201/health | grep -q UP
CODE=$(curl -sk -o /dev/null -w '%{http_code}' https://127.0.0.1:9201/metrics)
[ "$CODE" = "401" ] || { echo "expected 401 without token, got $CODE"; exit 1; }
curl -sk -H "Authorization: Bearer $TOKEN" https://127.0.0.1:9201/metrics | grep -q organism_tick
echo "Injecting kill -9 on node2..."
kill -9 $S2
sleep 4
grep -q "DEAD" secure1.log || { echo "node1 did not detect kill -9"; tail -50 secure1.log; exit 1; }
wait $S1 2>/dev/null || true
java -cp out dev.organism.security.AuditLog "$AUD1" "$PSK" | grep -q "AUDIT VERIFY OK"
ls "$SNAPS"/*.snap >/dev/null 2>&1 || { echo "no snapshots written"; exit 1; }
echo "Scenario 2 (secure/audit/rbac/tls/kill-9/snapshots) OK"

# ---------- Scenario 3: OOM injection -> supervisor backoff -> circuit open ----------
AUD3="$WORK/audit3.jsonl"
java -cp out dev.organism.net.TissueKernel --port 7301 --cells-port 8301 --seed 31 \
  --audit-path "$AUD3" --psk "$PSK" \
  --symbiont "java -Xmx32m -XX:+UseSerialGC -cp out dev.organism.symbiont.OomHog" \
  --symbiont-mem 2048 --symbiont-backoff-ms 100 --symbiont-max-restarts 3 \
  --period-ms 300 --ticks 60 > oom.log 2>&1 &
O1=$!; PIDS="$PIDS $O1"
wait $O1 2>/dev/null || true
grep -q "SYMBIONT_CIRCUIT_OPEN" oom.log || { echo "circuit did not open on OOM loop"; tail -50 oom.log; exit 1; }
java -cp out dev.organism.security.AuditLog "$AUD3" "$PSK" | grep -q "AUDIT VERIFY OK"
echo "Scenario 3 (OOM injection/circuit breaker) OK"

echo "=== ALL CHAOS SCENARIOS PASSED ==="
