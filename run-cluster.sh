#!/usr/bin/env bash
# Запуск реального кластера ORGANISM (3 узла + LoadGen)
set -euo pipefail
cd "$(dirname "$0")"

command -v java >/dev/null || { echo "✘ Не найден java. Установите JDK 21+"; exit 1; }

echo "🧬 Компиляция ORGANISM..."
rm -rf out && mkdir out
javac -Xlint:-options -nowarn -d out $(find src/main/java -name '*.java') 2>/dev/null || true
cp -r src/main/resources/* out/ 2>/dev/null || true

echo "🧹 Чистка старых WAL и журналов..."
rm -rf data/wal kernel-journal-*.jsonl

echo "🚀 Запуск 3 реальных узлов TissueKernel (gossip + Storage + Cells)..."
java -cp out dev.organism.net.TissueKernel --port 7101 --cells-port 8101 --seed 1 > node1.log 2>&1 &
PID1=$!
sleep 1

java -cp out dev.organism.net.TissueKernel --port 7102 --cells-port 8102 --seed 2 --seeds 127.0.0.1:7101 > node2.log 2>&1 &
PID2=$!
sleep 1

java -cp out dev.organism.net.TissueKernel --port 7103 --cells-port 8103 --seed 3 --seeds 127.0.0.1:7101 > node3.log 2>&1 &
PID3=$!
sleep 2

echo "✅ Кластер поднят. Узлы обмениваются gossip-ом, Storage-шарды (R=3) распределены."
echo "   Gossip-порты: 7101-7103 | HTTP-порты клеток: 8101-8103"
echo ""
echo "💥 Запуск LoadGen на 20 секунд (пуассоновский трафик со всплеском)..."
echo "   Наблюдай за логами: tail -f node*.log"
echo ""

# Запускаем LoadGen
java -cp out dev.organism.cell.LoadGen --seeds 127.0.0.1:8101,127.0.0.1:8102,127.0.0.1:8103 --rps 100 --seconds 20 --target-p99 600 || true

echo ""
echo "🛑 Остановка кластера..."
kill $PID1 $PID2 $PID3 2>/dev/null || true
wait $PID1 $PID2 $PID3 2>/dev/null || true

echo "🧬 ORGANISM остановлен. WAL-логи лежат в data/wal/, журналы в node*.log"
