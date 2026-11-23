#!/usr/bin/env bash
# ORGANISM one-command launch (Linux/macOS). Auto-bootstrap JDK 21+ при необходимости. ./organism.sh [nodes]
set -euo pipefail
cd "$(dirname "$0")"
command -v javac >/dev/null || { echo "[organism] Нужен JDK для bootstrap: https://adoptium.net"; exit 1; }
mkdir -p out_boot
javac -encoding UTF-8 -nowarn -d out_boot src/main/java/dev/organism/tools/JdkBootstrap.java
JDKHOME=$(java -cp out_boot dev.organism.tools.JdkBootstrap "$PWD/.organism/jdk")
[ -n "$JDKHOME" ] || { echo "[organism] JDK 21+ не получен. Поставь: https://adoptium.net"; exit 1; }
JAVAC="$JDKHOME/bin/javac"; JAVA="$JDKHOME/bin/java"
V=$("$JAVAC" -version 2>&1 | sed -E 's/javac ([0-9]+).*/\1/')
CFLAGS=""; RFLAGS=""
if [ "${V:-0}" -lt 25 ]; then CFLAGS="--enable-preview --release $V"; RFLAGS="--enable-preview"; fi
echo "[organism] compiling (JDK $V из $JDKHOME)..."
rm -rf out && mkdir out
"$JAVAC" $CFLAGS -encoding UTF-8 -nowarn -d out $(find src/main/java -name '*.java')
cp -r src/main/resources/* out/
exec "$JAVA" $RFLAGS -Dstdout.encoding=UTF-8 -cp out dev.organism.Launcher "$@"
