# docker build -t organism . && docker run --rm -p 8080:8080 organism
FROM eclipse-temurin:25-jdk
WORKDIR /app
COPY . .
RUN javac -Xlint:-options -d out $(find src/main/java -name '*.java') && cp -r src/main/resources/* out/
EXPOSE 8080
CMD ["java", "-Dstdout.encoding=UTF-8", "-cp", "out", "dev.organism.Main", "--no-open"]
