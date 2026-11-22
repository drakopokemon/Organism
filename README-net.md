# ORGANISM M1 — реальная сеть (tissue-kernel)

SWIM-lite membership поверх UDP. Симуляция (dev.organism.*) не тронута.

## Быстрый старт (JDK 21+)

    javac -encoding UTF-8 -d out @sources.txt   (Windows, см. run.bat)
    javac -d out $(find src/main/java -name '*.java')   (Linux/macOS)
    java -cp out dev.organism.net.NetSelfTest

3 терминала — живой кластер:

    java -cp out dev.organism.net.TissueKernel --port 7101 --seeds 127.0.0.1:7102,127.0.0.1:7103
    java -cp out dev.organism.net.TissueKernel --port 7102 --seeds 127.0.0.1:7101
    java -cp out dev.organism.net.TissueKernel --port 7103 --seeds 127.0.0.1:7101

Ctrl+C на любом -> остальные печатают SUSPECT -> DEAD за ~4-6 с.
Перезапуск -> incarnation-refute -> все снова ALIVE. Журнал: kernel-journal-PORT.jsonl.

## Честные границы M1
- Таймаут пробы = 1 период (1 с). Суб-периодные таймауты — M2.
- Полный Sync каждый период: до ~100 узлов. Piggyback-дельты — M2.
- Wire без аутентификации: только доверенная сеть/VPN. Секрет/mTLS — M2.
- Клеток (Processing/Storage) в кернеле ещё нет — M2/M3. Сейчас растёт нервная ткань.
