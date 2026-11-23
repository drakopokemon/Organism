# ORGANISM

Инфраструктура, которую не настраивают, а **выращивают**: декларативный геном (цели + ограничения + политики мутаций),
нативные Java-агенты, децентрализованный gossip, непрерывная эволюция конфигурации под нагрузку.
**Ноль внешних зависимостей — только JDK 21+ (CI: Temurin 25, чистый javac).**

Не «ещё один Kubernetes». K8s приводит систему к заданному состоянию; ORGANISM непрерывно ищет лучшее состояние
в пространстве конфигураций поведения. Ниша: edge-сети, гетерогенные промышленные кластеры, компании,
уставшие платить за неиспользуемую эластичность.

## Архитектура

```
                     +--------------------------------------------+
                     |  GENOME (sealed interfaces, compile-time)  |
                     |  objectives / constraints / capabilities   |
                     |  mutationPolicy (12 double, field access)  |
                     +---------------------+----------------------+
                                           |
   +---------------------------------------+---------------------------------------+
   |                            TissueKernel v4 (node)                             |
   |                                                                               |
   |  +-------------+   +--------------+   +---------------+   +----------------+  |
   |  | CellHost    |   | StorageHost  |   | ImmuneAgent   |   | EvolutionEngine|  |
   |  | Processing  |   | 24 shards R3 |   | peer p99      |   | + Controller   |  |
   |  | Evolver     |   | WAL replay   |   | HEALTHY->DEGR |   | + ApprovalGate |  |
   |  | LocalImmune |   |              |   | ->QUARANTINED |   | + ParetoFront  |  |
   |  +-------------+   +--------------+   +---------------+   +----------------+  |
   |                                                                               |
   |  +--------------------------- SUBSTRATE -----------------------------------+  |
   |  | Membership (SWIM-lite) -- WireCodec(HMAC) -- [TransportSecurity AES-GCM]|  |
   |  | UDP: Ping/Ack/PingReq/Sync/NodeFacts/BudRequest/ReplicateRequest        |  |
   |  +-------------------------------------------------------------------------+  |
   |                                                                               |
   |  [SymbiontWrapper]  [MigrationManager]  [AuditLog]  [ObservabilityExporter]   |
   |   supervisor VT      AES-GCM+HMAC        hash-chain   Prometheus + OTLP-JSON  |
   |   backoff/circuit    atomic spores       HMAC verify                          |
   |                                                                               |
   |  [OrganismApi] Loom HTTP: /health /metrics /agents /genome /mutations /decide |
   |   RBAC (ADMIN/OPERATOR/VIEWER) + optional TLS (PKCS12)                        |
   +-------------------------------------------------------------------------------+
```

Слои: **A — Геном** (`dev.organism.genome`, sealed = compile-time полнота обработки, не защита от хакеров),
**B — Агенты** (`cell`, `storage`, `immune`, `symbiont`; GraalVM native для edge),
**C — Среда** (`net`: SWIM-lite gossip, без центрального планировщика; eventual consistency — осознанный trade-off).

## Быстрый старт

```bash
# Сборка (только JDK)
javac -d out $(find src/main/java -name '*.java') && cp -r src/main/resources/* out/

# Живой дашборд-симулятор
./run.sh            # http://localhost:8080

# Кластер из 3 узлов (v3-поведение, дефолт)
java -cp out dev.organism.net.TissueKernel --port 7101 --cells-port 8101 --seed 1
java -cp out dev.organism.net.TissueKernel --port 7102 --cells-port 8102 --seed 2 --seeds 127.0.0.1:7101
java -cp out dev.organism.net.TissueKernel --port 7103 --cells-port 8103 --seed 3 --seeds 127.0.0.1:7101

# Продакшн-узел v4: secure gossip + аудит + RBAC API + TLS + human-in-the-loop + споры
PSK=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
TOKEN=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')
printf '%s:ADMIN\n' "$(printf '%s' "$TOKEN" | sha256sum | cut -d' ' -f1)" > rbac.txt
keytool -genkeypair -alias organism -keyalg RSA -keysize 2048 -storetype PKCS12 \
  -keystore tls.p12 -storepass changeit -dname CN=localhost
java -cp out dev.organism.net.TissueKernel --port 7101 --cells-port 8101 --seed 1 \
  --secure --psk "$PSK" --audit-path audit.jsonl --rbac-file rbac.txt \
  --api-port 9101 --tls-keystore tls.p12 --tls-pass changeit \
  --approval-risk 0.10 --snapshot-dir spores --snapshot-every 10
curl -sk -H "Authorization: Bearer $TOKEN" https://127.0.0.1:9101/metrics
```

## Флаги TissueKernel

| Флаг | Дефолт | Назначение |
|---|---|---|
| `--port` / `--cells-port` / `--bind` / `--advertise` | 7100 / 8100 / 0.0.0.0 / 127.0.0.1:port | gossip UDP / админ-HTTP клеток |
| `--seeds` / `--seed` / `--zone` / `--period-ms` / `--ticks` | — / 42 / local / 1000 / бесконечно | кластер и детерминизм |
| `--genome` / `--target-p99` / `--usd` | genome.yaml / 120 / 0.10 | цели |
| `--secure --psk <key>` | off | AES-256-GCM конверт gossip: ver/ts/nonce/iv/ct+tag, replay-window ±5s, bounded nonce-cache. 64-hex ключ напрямую, иначе PBKDF2WithHmacSHA256 (210k) |
| `--audit-path <file>` | off | hash-chain HMAC-SHA256 аудит; anchor `<file>.head` ловит truncate хвоста; без `--psk` генерит `<file>.key` (0600) |
| `--rbac-file <file>` | off | `<sha256hex(token)>:<ROLE>`; ADMIN/OPERATOR/VIEWER |
| `--api-port <n>` | off | Loom HTTP API; без RBAC — fail-closed 403 (кроме /health) |
| `--tls-keystore <p12> --tls-pass <pw>` | off | HttpsServer + PKCS12 |
| `--approval-risk <0..1>` | off | мутации с risk>threshold -> PENDING, ждут ADMIN /decide |
| `--approval-ttl-ms <n>` | 300000 | TTL proposal -> EXPIRED |
| `--immune-enforce` | off | дроп BudRequest на QUARANTINED узлы (детекция/аудит всегда on) |
| `--symbiont "<cmd>"` | off | внешний процесс под supervisor VT |
| `--symbiont-port <n>` | 0 | TCP health-probe (0 = только liveness) |
| `--symbiont-mem <mb>` | 0 | MemoryMax (systemd-run/cgroup v2) -> fallback ulimit -v -> иначе SANDBOX_NONE + отказ старта |
| `--symbiont-backoff-ms` / `--symbiont-max-restarts` | 500 / 5 | exponential backoff + jitter -> CIRCUIT_OPEN |
| `--snapshot-dir <dir>` / `--snapshot-every <ticks>` | off / 10 | споры: AES-GCM + HMAC, tmp+ATOMIC_MOVE, sanitize(id)__ts.snap |
| `--otlp-endpoint <url>` / `--otlp-interval-ms` | off / 15000 | OTLP-JSON push (java.net.http, без либ) |

**Все v4-флаги opt-in. Без них поведение v3 идентично — существующие selftest не трогаем.**

## API и RBAC

| Endpoint | Action | Роли |
|---|---|---|
| `GET /health` | open | все |
| `GET /metrics` | VIEW | ADMIN, OPERATOR, VIEWER |
| `GET /agents`, `GET /genome` | VIEW | ADMIN, OPERATOR, VIEWER |
| `GET /mutations` (proposals), `POST /mutations` (manual tick) | MUTATE | ADMIN, OPERATOR |
| `POST /decide?id=&decision=approve/reject` | APPROVE | **только ADMIN** |

Bearer-токен -> SHA-256 -> constant-time compare с хэшами из файла. Нет/невалидный токен -> 401;
валидный без нужной роли -> 403; RBAC не сконфигурирован -> 403 fail-closed. Ответы — байты UTF-8.
`who` в аудите одобрений — `sha256:<первые 12 hex хэша токена>` (сырой токен не логируется).

## Threat model (честно)

- **Gossip**: PSK AES-256-GCM (конфиденциальность + целостность), HMAC-конверт WireCodec поверх.
  mTLS на UDP невозможен — не притворяемся. Replay: ±5s окно + nonce-cache; spoofed nonce не выселяет
  кэш (проверка после аутентификации). Компрометация PSK = компрометация кластера; ротация — рестарт с новым ключом.
- **Аудит**: hash-chain ловит подмену/reorder/вырез из середины; truncate хвоста — только при наличии
  anchor-файла .head (храните anchor отдельно/на другом хосте). Без внешнего якоря хвостовой truncate
  криптографически не отличим от конца лога — это физика, не баг.
- **RBAC**: токены хранятся как SHA-256; утечка файла != утечка токенов (но brute-force слабых токенов возможен —
  генерите >=128 бит энтропии). APPROVE только ADMIN — human-in-the-loop для критичных мутаций.
- **Symbiont**: изоляция честная. systemd-run --scope MemoryMax (Linux+cgroup v2+booted systemd) -> fallback
  ulimit -v (address space, грубо) -> иначе **SANDBOX_NONE в аудит и отказ старта** при mem>0.
  ulimit -v не заменяет container runtime: для недоверенного кода нужен containerd/runc/gVisor.
- **Мутации**: MutationGuard проверяет границы policy ДО применения; risk>threshold -> откат canary + ожидание
  человека. Sealed-иерархия — compile-time полнота, не runtime-защита.
- **API**: TLS опционален; без TLS Bearer-токен летит открытым текстом — включайте TLS на не-loopback.
- **Иммунитет != безопасность**: это устойчивость к деградации, не защита от DDoS/эксплойтов.

## Runbook

```bash
# Проверить аудит-цепочку (секрет = --psk или содержимое <audit>.key)
java -cp out dev.organism.security.AuditLog audit.jsonl "$PSK"   # AUDIT VERIFY OK / FAILED

# Посмотреть ожидающие одобрения мутации и одобрить
curl -sk -H "Authorization: Bearer $TOKEN" https://host:9101/mutations
curl -sk -X POST -H "Authorization: Bearer $TOKEN" "https://host:9101/decide?id=m3&decision=approve"

# Метрики (Prometheus scrape идемпотентен)
curl -sk -H "Authorization: Bearer $TOKEN" https://host:9101/metrics | grep organism_

# Карантин: immune-события в аудите и логе; при --immune-enforce BudRequest на больного узла дропается
grep IMMUNE audit.jsonl

# Восстановление из споры: loadLatest(agentId) в MigrationManager (AES-GCM+HMAC, atomic)
ls spores/*.snap

# OOM симбионта: supervisor сам рестартит с backoff; после maxRestarts — CIRCUIT_OPEN (метрика/аудит),
# дальше — ручной разбор причин, рестарт узла или правка --symbiont-mem
grep SYMBIONT audit.jsonl
```

## Тесты и CI

javac (Temurin 25) -> Net selftest (SWIM-lite) -> Cell selftest (эволюция+иммунитет, детерминизм) ->
Storage selftest (репликация) -> Organism selftest (симуляция) -> **Production selftest**
(TransportSecurity/AuditLog/RBAC/Immune/Gate/Controller+Engine/Pareto/Migration/Obs/API+TLS/Symbiont) ->
**Chaos suite** (deploy/chaos-test.sh): partition SIGSTOP/SIGCONT, kill -9 + secure gossip + audit verify +
RBAC/TLS API проверки, OOM-injection (OomHog) -> backoff -> circuit open.

```bash
./run.sh --selftest        # 13 проверок симулятора
bash deploy/chaos-test.sh  # полный chaos (нужен out/ и JDK)
```

## Docker

```bash
docker build -t organism .            # GraalVM native-image -> distroless (TissueKernel)
docker run --rm -p 7100:7100/udp -p 9100:9100 organism --port 7100 --api-port 9100
docker build -f Dockerfile.jvm -t organism-dashboard .   # JVM-дашборд (Main)
```

Native Image: без динамической загрузки классов, без JVMTI — поэтому агенты сами экспортируют
структурированный журнал эволюции и метрики (EvolutionJournal, ObservabilityExporter).
Ядро без рефлексии -> reflect-config не нужен; TLS включается --enable-https на этапе сборки.

## Честные ограничения

- Eventual consistency gossip: узел может «не видеть» соседа секунды; split-brain возможен при partition
  (SWIM сходится после heal). Stateful-миграция — самое сложное: сейчас споры = снапшоты параметров;
  полная репликация состояния Storage — через WAL/ReplicateRequest (R=3).
- Genome.fromFile пока возвращает типизированный дефолт (парсинг YAML — следующий шаг, контракт sealed сохранён).
- Эволюция кернела (EvolutionEngine+Gate) governs policy-параметры узла; клеточная эволюция живёт в cell.Evolver
  (canary/adopt/revert). Оба слоя детерминированы при фиксированном seed.
- Это не weekend project: продакшн-доводка (парсинг генома, реальный routing репликации, mTLS для TCP-плоскостей,
  container-sandbox для недоверенных симбионтов) — roadmap.
