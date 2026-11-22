#!/usr/bin/env bash
# bench/chaos.sh — интеграционный тест: 3 кернела + LoadGen + kill -9 на середине
set -euo pipefail
cd "$(dirname "$0")/.."

echo "=== ORGANISM chaos test ==="
echo "3 kernels + LoadGen + kill -9 + recovery"
echo ""

# Компиляция
echo "[1/5] Compiling..."
javac -Xlint:-options -d out $(find src/main/java -name '*.java')
cp -r src/main/resources/* out/
echo "OK"
echo ""

# Запуск 3 кернелов
echo "[2/5] Starting 3 tissue-kernels..."
java -cp out dev.organism.net.TissueKernel --port 7101 --cells-port 8101 --seeds 127.0.0.1:7102,127.0.0.1:7103 --ticks 120 > kernel1.log 2>&1 &
PID1=$!
java -cp out dev.organism.net.TissueKernel --port 7102 --cells-port 8102 --seeds 127.0.0.1:7101,127.0.0.1:7103 --ticks 120 > kernel2.log 2>&1 &
PID2=$!
java -cp out dev.organism.net.TissueKernel --port 7103 --cells-port 8103 --seeds 127.0.0.1:7101,127.0.0.1:7102 --ticks 120 > kernel3.log 2>&1 &
PID3=$!
sleep 3
echo "PIDs: $PID1 $PID2 $PID3"
echo ""

# Запуск LoadGen
echo "[3/5] Starting LoadGen (60 sec, 200 RPS, spike at 40%)..."
java -cp out dev.organism.cell.LoadGen --seeds 127.0.0.1:8101,127.0.0.1:8102,127.0.0.1:8103 --rps 200 --seconds 60 --target-p99 600 > loadgen.log 2>&1 &
LGPID=$!
echo "LoadGen PID: $LGPID"
echo ""

# Ждём 20 сек, потом kill -9 одного кернела
echo "[4/5] Running for 20 sec, then kill -9 kernel 2..."
sleep 20
kill -9 $PID2
echo "Kernel 2 (PID $PID2) killed"
echo ""

# Ждём завершения LoadGen
echo "[5/5] Waiting for LoadGen to finish..."
wait $LGPID
LG_EXIT=$?
echo ""

# Остановка оставшихся кернелов
kill $PID1 $PID3 2>/dev/null || true
wait $PID1 $PID3 2>/dev/null || true

# Анализ результатов
echo "=== RESULTS ==="
if grep -q '"errRate"' loadgen.log; then
    echo "LoadGen output:"
    tail -1 loadgen.log
    echo ""
    if [ $LG_EXIT -eq 0 ]; then
        echo "✓ LoadGen passed (errRate < 0.15, p99 < target)"
    else
        echo "✗ LoadGen failed (exit code $LG_EXIT)"
    fi
else
    echo "✗ LoadGen did not produce output"
fi
echo ""

echo "Kernel logs (last 10 lines each):"
for i in 1 2 3; do
    echo "--- kernel$i.log ---"
    tail -10 kernel$i.log
    echo ""
done

# Проверка на IMMUNE/BUD события
echo "=== RECOVERY EVENTS ==="
for i in 1 2 3; do
    echo "kernel$i.log:"
    grep -E "(QUARANTINE|RELEASE|IMMUNE_KILL|IMMUNE_RESPAWN|BUD_LOCAL|BUD_REMOTE|EVOLVE)" kernel$i.log | head -5
done
echo ""

exit $LG_EXIT
