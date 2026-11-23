# ORGANISM

Инфраструктура, которую не настраивают, а **выращивают**: декларативный геном (цели + ограничения + политики мутаций),
нативные Java-агенты, децентрализованный gossip, непрерывная эволюция конфигурации под нагрузку.
**Ноль внешних зависимостей — только JDK 21+ (CI: Temurin 25, чистый javac).**

Не «ещё один Kubernetes». K8s приводит систему к заданному состоянию; ORGANISM непрерывно ищет лучшее состояние
в пространстве конфигураций поведения. Ниша: edge-сети, гетерогенные промышленные кластеры, компании,
уставшие платить за неиспользуемую эластичность.

## Запуск в одну команду

```bash
java Launch.java       # ЛЮБАЯ ОС (Windows/Linux/macOS), нужен JDK 21+ на PATH
java Launch.java 5     # 5 узлов
# обёртки: organism.bat (Windows cmd)  /  ./organism.sh (Linux/macOS)
```

**Windows (cmd/PowerShell):** `organism.bat` или `java Launch.java`. Файл `.sh` в cmd НЕ запускается — это bash.
**Linux/macOS:** `./organism.sh` или `java Launch.java`. Нужен JDK 21+ (Temurin), `java` на PATH.

Скрипт сам компилирует (JDK), генерит PSK/admin-токен/PKCS12 (один раз, в .organism/),
поднимает кластер с secure gossip + TLS API + hash-chain аудитом + human-in-the-loop и печатает URL и токен.


> **JDK 17?** `organism.bat` / `organism.sh` сами скачают portable Temurin 21 в `.organism/jdk`
> (через `JdkBootstrap`, совместимый с 17) и соберут проект им. Вручную: `winget install EclipseAdoptium.Temurin.21.JDK`.
> Ядро проекта — virtual threads (Java 21+), на 17 оно физически не компилируется; это требование, не баг.

## GUI (живой дашборд)

Открой https://127.0.0.1:9101/ (TLS self-signed — прими сертификат), вставь токен из вывода organism.sh.
Дашборд: topology gossip-mesh с immune-подсветкой, vitals + p99-timeline, очередь одобрения мутаций
(APPROVE/REJECT только ADMIN), immune-панель, эволюция агентов, геном, audit-chain с верификацией, симбионт/экспорт.
Vanilla JS + canvas, без CDN/зависимостей, CSP + security headers, per-IP rate-limit на API.

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
- Genome.fromFile теперь РЕАЛЬНО парсит genome.yaml (GenomeParser, fail-closed); при отсутствии файла — типизированный дефолт. Sealed-контракты сохранены, подтипы добавлены аддитивно.
- Эволюция кернела (EvolutionEngine+Gate) governs policy-параметры узла; клеточная эволюция живёт в cell.Evolver
  (canary/adopt/revert). Оба слоя детерминированы при фиксированном seed.
- Это не weekend project: продакшн-доводка (парсинг генома, реальный routing репликации, mTLS для TCP-плоскостей,
  container-sandbox для недоверенных симбионтов) — roadmap.

---

# ORGANISM 6.0 — шесть столпов (JDK-only)

Всё ниже реализовано на чистом JDK (javac, Temurin 25 в CI). Внешних зависимостей НЕТ.
Замороженные контракты (Genome/EvolutionEngine/Membership/Wire/CellHost/StorageHost) не изменены;
все новые возможности — аддитивные и opt-in: **дефолтный запуск без новых флагов ведёт себя как раньше**.

## Архитектура

```
                 +--------------------------------------------------------+
                 | CONTROL PLANE (3-5 узлов, opt-in ControlPlaneServer)   |
                 |  Raft (порядок) -> AuditLog (hash-chain) -> Merkle->TSA|
                 |  Envelope / SPRT / Four-eyes+WebAuthn / CRL endpoint   |
                 +---------------+------------------------+---------------+
                                 | mTLS-мосты (24h серты) |
        +------------------------+-------+      +---------+----------------------+
        | РЕГИОН A (gossip intra-region) |      | РЕГИОН B (gossip intra-region) |
        |  supernodes -- Wire.Sync ------+------+-- supernodes                   |
        |  +--------------------------+  |      |                                |
        |  | NativeSupervisor (Java)  |  |      |  verify Ed25519/cosign -> exec |
        |  |  epoch=N+1 -> env -> cell|  |      |  crun/nobody opt-in, drain     |
        |  |  FencedStorage(epoch)    |  |      |                                |
        |  +--------------------------+  |      |                                |
        +--------------------------------+      +--------------------------------+
```

## Маппинг spec -> JDK-реализация

| Столп | Спека 6.0 | Реализация (только JDK) |
|---|---|---|
| 1. Genome & Supply Chain | CBOR + Ed25519, Sigstore/cosign | supplychain.Cbor (канонический RFC7049-subset), GenomeArtifact (Ed25519, fail-closed, CLI genkey/sign/verify), BinaryVerifier (ED25519 native; COSIGN — opt-in subprocess, fail-closed если cosign нет) |
| 2. Control Plane | Raft, Envelope, SPRT | raft.RaftNode (+RaftStore file/mem, RaftNet in-mem/TCP, RaftAuditBridge идемпотентный replay), control.Envelope (+EnvelopeAutoApprover через существующий gate), control.Sprt (Wald на bounded log-latency) |
| 3. Execution & Isolation | Go-демон + crun + nobody | exec.NativeSupervisor (Java): verify-gate, epoch env, graceful drain SIGTERM->SIGKILL, backoff; crun/runuser opt-in subprocess, fail-closed если нет |
| 4. Data Plane | Fencing tokens, immutable agents | data.EpochFence + FencedStorage НАД замороженным Wire/StorageHost; handoff под общим локом — split-brain исключён; параметры в env, не в образе |
| 5. Substrate | Иерархический gossip, CA 24h, CRL | net.hier.Hierarchy/RegionBridge/TlsBridge (батчевые Wire.Sync через замороженный Membership API), security.ca.JdkCa (keytool -gencert, 24h, CRL в trust manager -> mTLS) |
| 6. Audit & Trust | FIDO2, four-eyes, Merkle+TSA | security.webauthn.WebAuthn (ES256/EdDSA, COSE, signCount, challenge=SHA256 карточки), control.FourEyesGate (opt-in, 2 разных человека), audit.MerkleTree (RFC6962-style) + audit.Tsa/AuditAnchoring (Ed25519-токены, hourly) |

## Честные обоснования (без маркетинга)

- **SWIM не O(N^2).** SWIM-lite — infection-style, суммарно O(N log N). Иерархия нужна не для асимптотики, а для **WAN**: RTT/loss/цена межрегионального канала и локализация churn-штормов. HierarchySelfTest меряет WAN-пересечения при churn 0.5%/тик: flat ~7/8 трафика crosses WAN, hierarchical — только мосты (кратно меньше), конвергенция сохраняется.
- **SPRT на log-latency.** P99 heavy-tailed: голое среднее врёт. Тест Вальда на log(t/c) с обрезкой инкремента (clip): один выброс не переворачивает решение. maxN -> INCONCLUSIVE -> остаёмся на контроле (fail-closed).
- **Raft != неотказуемость.** Raft даёт единый порядок лога. Неотказуемость — подписи людей (WebAuthn поверх SHA-256 карточки мутации) + Merkle-корень в TSA. Скомпрометированный лидер может писать системные события, но без подписей людей аудитор это видит мгновенно.
- **Fencing НАД Wire.** Wire sealed заморожен — эпохи не в протоколе, а в обёртке storage и env супервизора. Запись со старой эпохой отвергается до попадания в StorageHost.
- **TSA — JDK-эквивалент RFC3161**, не ASN.1/TSP: Ed25519-подписанный CBOR {root,ts,serial}. Для юридической силы в проде подключайте внешний RFC3161 TSA через тот же AnchorStore (аддитивно).
- **CA на keytool**, не BouncyCastle: keytool поставляется с JDK. CRL проверяется и в verify(), и в каждом mTLS-handshake (кастомный X509TrustManager).

## Threat model

| Угроза | Механизм защиты | Проверка в CI |
|---|---|---|
| Подмена генома/бинарника | Ed25519 fail-closed до старта | SupplyChainSelfTest, SupervisorSelfTest |
| Форк аудита / два лидера | Raft majority commit, term/fencing | RaftSelfTest (kill/partition) |
| Split-brain stateful-агентов | EpochFence + FencedStorage под общим локом | DataPlaneSelfTest |
| Подчистка лога админом | hash-chain + .head anchor + Merkle/TSA serial/ts | AuditTrustSelfTest (tamper/truncate) |
| Украденный/клонированный ключ человека | signCount monotonic, challenge=SHA256 карточки, origin/rpId | ControlSelfTest |
| Один человек продавил рискованную мутацию | four-eyes opt-in: 2 разных userHandle | ControlSelfTest, ControlPlaneSelfTest |
| Скомпрометированный узел в gossip | CRL + 24h серты, mTLS needClientAuth | CaSelfTest (revoked handshake rejected) |
| DoS через API | body-limit 413, loopback-only control plane, RBAC opt-in | ControlPlaneSelfTest |
| Утечка параметров в образ | параметры только env/CLI при старте | SupervisorSelfTest (EPOCH env) |

Остаточные риски (честно): loopback control plane без RBAC = доверенный хост; TSA-ключ на том же хосте — для банка выносите TSA в отдельный контур; cosign/crun — subprocess, их наличие проверяется fail-closed.

## Runbook

### Supply chain
```bash
java -cp out dev.organism.supplychain.GenomeArtifact genkey genome-key
java -cp out dev.organism.supplychain.GenomeArtifact sign genome.yaml genome-key.key genome.artifact
java -cp out dev.organism.supplychain.GenomeArtifact verify genome.artifact genome-key.pub
```

### Control plane (opt-in, отдельный процесс)
```bash
java -cp out dev.organism.control.ControlPlaneServer --port 7470 --dir cp-data --four-eyes --envelope envelope.cfg --ca-dir ca-data [--rbac-file rbac.txt]
# 3-узловой кластер: cp1: --id cp1 --peers cp2@host2:7471,cp3@host3:7471  (-Dorganism.raft.port=7471 на каждом)
curl -s localhost:7470/health
curl -s -XPOST localhost:7470/propose -d 'DEPLOY|cell-7 rollout'
curl -s -XPOST localhost:7470/audit/anchor
curl -s localhost:7470/audit/root
```
envelope.cfg:
```
poolSize:8:24:arm:0.5
cacheMb:64:1024:*:0.3
!unsafeMode
```

### CA / отзыв узла
```bash
java -cp out dev.organism.security.ca.JdkCa init ca-data changeit
java -cp out dev.organism.security.ca.JdkCa issue ca-data changeit edge-42 edge-42.local
java -cp out dev.organism.security.ca.JdkCa revoke ca-data changeit <serialHex>
curl -s -XPOST 'localhost:7470/crl/revoke?serial=<serialHex>'
```

### Супервизор клетки (программно)
```java
new NativeSupervisor("cell-7", List.of("/opt/cells/cell"))
    .verify(BinaryVerifier.Mode.ED25519, blob, sig, pub)  // fail-closed
    .env("ORGANISM_PARAMS", "{\"poolSize\":24}")           // параметры НЕ в образе
    .dropNobody(true)                                      // opt-in
    .start();
// handoff: fence.allocate -> новый агент epoch N+1, старый отвергается FencedStorage
```

### Аудит-якоря
AuditAnchoring.startHourly(executor) — раз в час Merkle-корень -> TSA-токен -> anchors.b64.
Проверка: AuditLog.verify(file, secret) + AuditAnchoring.verifyLatest(tsaPub) + AnchorStore.verifyChain(pub).

## CI (23 шага)
Compile -> Launcher -> Bootstrap17 -> Net -> Cell -> Storage -> Organism -> Production -> UI+Genome -> Launcher smoke -> Chaos ->
**SupplyChain -> Raft -> Control(envelope/SPRT/WebAuthn/four-eyes) -> DataPlane(epochs) -> Hierarchy(WAN/churn) -> CA/CRL/mTLS -> AuditTrust(Merkle/TSA) -> Supervisor -> ControlPlane E2E**.
Все новые selftest'ы детерминированные: Raft — in-memory transport с ручными tick; WebAuthn — синтетический EC-ключ; SPRT — seeded Random(2026).
