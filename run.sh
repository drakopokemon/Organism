#!/usr/bin/env bash
# Сборка без Maven (только JDK 21+) и запуск. Все аргументы передаются в приложение:
#   ./run.sh                 живая система + дашборд (откроется браузер)
#   ./run.sh --selftest      проверка инвариантов
#   ./run.sh --ab            A/B: статика vs ORGANISM
#   ./run.sh --symbiont      демо симбиоза с внешними процессами
#   ./run.sh --help
set -euo pipefail
cd "$(dirname "$0")"

command -v java >/dev/null || { echo "✘ Не найден java. Установите JDK 21+ (лучше 25): https://adoptium.net"; exit 1; }
V=$(java -version 2>&1 | grep -m1 ' version ' | sed -E 's/.*version "([0-9]+).*/\1/')
[ "${V:-0}" -ge 21 ] || { echo "✘ Нужна Java 21+, найдена: ${V:-?}"; exit 1; }
FLAGS=""; [ "$V" -lt 25 ] && FLAGS="--enable-preview"   # ScopedValue: preview до Java 25

if command -v javac >/dev/null; then JAVAC=(javac)
elif java --list-modules 2>/dev/null | grep -q '^jdk.compiler'; then JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main)
else echo "✘ Нет компилятора (нужен JDK, а не JRE)"; exit 1; fi

rm -rf out && mkdir out
"${JAVAC[@]}" --source "$V" $FLAGS -Xlint:-preview -Xlint:-options -nowarn -d out $(find src/main/java -name '*.java') 2> >(grep -v -e '^Note:' -e 'Picked up' >&2)
cp -r src/main/resources/* out/
exec java -Dstdout.encoding=UTF-8 $FLAGS -cp out dev.organism.Main "$@"
