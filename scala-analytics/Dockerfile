FROM sbtscala/scala-sbt:amazoncorretto-al2023-21.0.7_1.11.1_3.3.6@sha256:c176f1975dd73677e3023f132c8b12ef2dc0a57ec47f6112bdd92a8d27f10307 AS build
WORKDIR /app
COPY build.sbt ./
COPY project/build.properties project/build.properties
RUN sbt update
COPY src ./src
COPY testdata ./testdata
RUN sbt test stageRuntime

FROM amazoncorretto:21-alpine@sha256:b7451ecea0f2a02b9597493bb57c4014f43c41db4b470a8aee7228d7a0279025
RUN apk add --no-cache curl && mkdir -p /data/results && chown -R 65532:65532 /data
WORKDIR /app
COPY --from=build /app/target/runtime /app/lib
ENV RESULTS_DIR=/data/results PORT=8081
EXPOSE 8081
USER 65532:65532
HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=5 CMD curl --fail --silent http://127.0.0.1:8081/healthz || exit 1
ENTRYPOINT ["java", "-cp", "/app/lib/*", "gamestats.Main"]
