FROM sbtscala/scala-sbt:amazoncorretto-al2023-21.0.7_1.11.1_3.3.6 AS build
WORKDIR /app
COPY build.sbt ./
COPY project/build.properties project/build.properties
RUN sbt update
COPY src ./src
COPY testdata ./testdata
RUN sbt test stageRuntime

FROM amazoncorretto:21-alpine
RUN apk add --no-cache curl && mkdir -p /data/results && chown -R 65532:65532 /data
WORKDIR /app
COPY --from=build /app/target/runtime /app/lib
ENV RESULTS_DIR=/data/results PORT=8081
EXPOSE 8081
USER 65532:65532
HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=5 CMD curl --fail --silent http://127.0.0.1:8081/healthz || exit 1
ENTRYPOINT ["java", "-cp", "/app/lib/*", "gamestats.Main"]
