#!/usr/bin/env bash
# ORGANISM one-command launch: компиляция + генерация секретов + secured cluster + GUI.
# ./organism.sh [nodes]   (по умолчанию 3). Останов: Ctrl+C.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -d out ] || [ -n "$(find src/main/java -name '*.java' -newer out 2>/dev/null | head -1)" ]; then
  echo "[organism] compiling (JDK only)..."
  mkdir -p out
  javac -d out $(find src/main/java -name '*.java')
  cp -r src/main/resources/* out/
fi

WORK="${ORGANISM_HOME:-.organism}"
mkdir -p "$WORK"
PSK_FILE="$WORK/psk";        [ -f "$PSK_FILE" ]   || head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$PSK_FILE"
TOKEN_FILE="$WORK/admin.token"; [ -f "$TOKEN_FILE" ] || head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$TOKEN_FILE"
PSK=$(cat "$PSK_FILE"); TOKEN=$(cat "$TOKEN_FILE")
RBAC="$WORK/rbac.txt"; printf '%s:ADMIN\n' "$(printf '%s' "$TOKEN" | sha256sum | cut -d' ' -f1)" > "$RBAC"
KS="$WORK/tls.p12"
[ -f "$KS" ] || keytool -genkeypair -alias organism -keyalg RSA -keysize 2048 -storetype PKCS12 \
  -keystore "$KS" -storepass organism -dname "CN=localhost" -ext SAN=dns:localhost,ip:127.0.0.1 -validity 3650 >/dev/null 2>&1

NODES="${1:-3}"
BP=7100; BC=8100; BA=9100
PIDS=()
cleanup(){ for p in "${PIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT INT TERM

for i in $(seq 1 "$NODES"); do
  port=$((BP+i)); cells=$((BC+i)); api=$((BA+i))
  seeds=""; [ "$i" -gt 1 ] && seeds="--seeds 127.0.0.1:$((BP+1))"
  java -cp out dev.organism.net.TissueKernel --port "$port" --cells-port "$cells" --seed "$i" $seeds \
    --secure --psk "$PSK" --audit-path "$WORK/audit-$port.jsonl" --rbac-file "$RBAC" \
    --api-port "$api" --tls-keystore "$KS" --tls-pass organism \
    --approval-risk 0.10 --snapshot-dir "$WORK/spores-$port" --snapshot-every 10 \
    > "$WORK/node-$port.log" 2>&1 &
  PIDS+=($!)
  sleep 1
done

echo "========================================================"
echo "  ORGANISM cluster: $NODES nodes"
echo "  secure gossip (AES-GCM) + TLS API + hash-chain audit"
echo "  GUI:    https://127.0.0.1:$((BA+1))/"
echo "  TOKEN:  $TOKEN"
echo "  logs:   $WORK/node-*.log   home: $WORK"
echo "  Ctrl+C to stop"
echo "========================================================"
wait
