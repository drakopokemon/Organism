# ORGANISM: GraalVM Native Image -> distroless. Только JDK-код, ноль внешних зависимостей.
# CI (javac + selftests) проверяет JVM-режим; native-сборка — отдельный артефакт для edge.
# Ядро без рефлексии (sealed Wire, ручной JSON) — reflect-config не требуется.
# TLS включён (--enable-https) для HttpsServer + PKCS12.
FROM ghcr.io/graalvm/native-image-community:21 AS build
WORKDIR /src
COPY src/main/java src/main/java
COPY genome.yaml genome.yaml
RUN mkdir -p out && javac -d out $(find src/main/java -name '*.java') \
 && native-image -cp out -o organism dev.organism.net.TissueKernel \
      --no-fallback --enable-https -march=compatibility -H:+ReportExceptionStackTraces -H:IncludeResources=organism-ui.html

FROM gcr.io/distroless/base-debian12
WORKDIR /
COPY --from=build /src/organism /organism
COPY --from=build /src/genome.yaml /genome.yaml
EXPOSE 7100 8100 9100
ENTRYPOINT ["/organism"]
