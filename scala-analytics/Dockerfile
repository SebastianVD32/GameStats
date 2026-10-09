FROM sbtscala/scala-sbt:amazoncorretto-al2023-21.0.7_1.11.1_3.3.6 AS build
WORKDIR /app
COPY build.sbt ./
COPY project/build.properties project/build.properties
RUN sbt update
COPY src ./src
RUN sbt stageRuntime

FROM amazoncorretto:21-alpine
WORKDIR /app
COPY --from=build /app/target/runtime /app/lib
USER 65532:65532
ENTRYPOINT ["java", "-cp", "/app/lib/*", "gamestats.Main"]
