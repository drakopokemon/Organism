#!/bin/bash
set -e
echo "Starting ORGANISM chaos test..."
mkdir -p data/wal

# Start 3 nodes
java -cp out dev.organism.net.TissueKernel --port 7101 --cells-port 8101 --seed 1 > node1.log 2>&1 &
PID1=$!
sleep 2
if ! kill -0 $PID1 2>/dev/null; then echo "Node1 crashed:"; cat node1.log; exit 1; fi

java -cp out dev.organism.net.TissueKernel --port 7102 --cells-port 8102 --seed 2 --seeds 127.0.0.1:7101 > node2.log 2>&1 &
PID2=$!
sleep 2
if ! kill -0 $PID2 2>/dev/null; then echo "Node2 crashed:"; cat node2.log; exit 1; fi

java -cp out dev.organism.net.TissueKernel --port 7103 --cells-port 8103 --seed 3 --seeds 127.0.0.1:7101 > node3.log 2>&1 &
PID3=$!
sleep 2
if ! kill -0 $PID3 2>/dev/null; then echo "Node3 crashed:"; cat node3.log; exit 1; fi

sleep 3
echo "Nodes started. Injecting chaos (SIGSTOP on node2 to simulate partition)..."
kill -STOP $PID2 || true
sleep 5
echo "Resuming node2..."
kill -CONT $PID2 || true
sleep 5

kill $PID1 $PID2 $PID3 || true
echo "Chaos test completed successfully."
