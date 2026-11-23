#!/usr/bin/env bash
# ORGANISM one-command launch (Linux/macOS, JDK 21+): compile + keygen + secured cluster + GUI.
# ./organism.sh [nodes]
set -euo pipefail
cd "$(dirname "$0")"
command -v javac >/dev/null || { echo "[organism] Нужен JDK 21+ (javac в PATH): https://adoptium.net"; exit 1; }
V=$(javac -version 2>&1 | sed -E 's/javac ([0-9]+).*/\1/')
CFLAGS=""; RFLAGS=""
if [ "${V:-0}" -lt 25 ]; then CFLAGS="--enable-preview --release $V"; RFLAGS="--enable-preview"; fi
echo "[organism] compiling (JDK $V)..."
rm -rf out && mkdir out
javac $CFLAGS -encoding UTF-8 -nowarn -d out $(find src/main/java -name '*.java')
cp -r src/main/resources/* out/
exec java $RFLAGS -Dstdout.encoding=UTF-8 -cp out dev.organism.Launcher "$@"
