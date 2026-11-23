#!/usr/bin/env bash
# Запуск реального кластера ORGANISM (3 узла + LoadGen). JDK 21+, version-aware preview.
set -euo pipefail
cd "$(dirname "$0")"
command -v javac >/dev/null || { echo "✘ Не найден javac. Установите JDK 21+: https://adoptium.net"; exit 1; }
V=$(javac -version 2>&1 | sed -E 's/javac ([0-9]+).*/\1/')
CFLAGS=""; RFLAGS=""
if [ "${V:-0}" -lt 25 ]; then CFLAGS="--enable-preview --release $V"; RFLAGS="--enable-preview"; fi

echo "🧬 Компиляция ORGANISM (JDK $V)..."
rm -rf out && mkdir out
javac $CFLAGS -Xlint:-options -nowarn -d out $(find src/main/java -name '*.java')
cp -r src/main/resources/* out/

echo "🧹 Чистка старых WAL и журналов..."
rm -rf data/wal kernel-journal-*.jsonl

echo "🚀 Запуск 3 узлов TissueKernel..."
java $RFLAGS -cp out dev.organism.net.TissueKernel --port 7101 --cells-port 8101 --seed 1 > node1.log 2>&1 &
PID1=$!
sleep 1
java $RFLAGS -cp out dev.organism.net.TissueKernel --port 7102 --cells-port 8102 --seed 2 --seeds 127.0.0.1:7101 > node2.log 2>&1 &
PID2=$!
sleep 1
java $RFLAGS -cp out dev.organism.net.TissueKernel --port 7103 --cells-port 8103 --seed 3 --seeds 127.0.0.1:7101 > node3.log 2>&1 &
PID3=$!
sleep 2

echo "✅ Кластер поднят. Gossip 7101-7103 | cells HTTP 8101-8103."
echo "💥 LoadGen на 20 секунд..."
java $RFLAGS -cp out dev.organism.cell.LoadGen --seeds 127.0.0.1:8101,127.0.0.1:8102,127.0.0.1:8103 --rps 100 --seconds 20 --target-p99 600 || true

echo "🛑 Остановка кластера..."
kill $PID1 $PID2 $PID3 2>/dev/null || true
wait $PID1 $PID2 $PID3 2>/dev/null || true
echo "🧬 ORGANISM остановлен."
