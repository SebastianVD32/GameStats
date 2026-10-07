# syntax=docker/dockerfile:1

ARG GO_VERSION=1.24
FROM golang:${GO_VERSION}-alpine AS build

WORKDIR /src
COPY go.mod ./
RUN go mod download

COPY cmd ./cmd
COPY internal ./internal

RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/gamestats ./cmd/gamestats \
    && mkdir -p /out/data

FROM scratch

COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build --chown=65532:65532 /out/gamestats /app/gamestats
COPY --from=build --chown=65532:65532 /out/data /data

USER 65532:65532
EXPOSE 8080

ENV HTTP_ADDR=:8080 \
    OUTPUT_PATH=/data/ready-matches.jsonl

ENTRYPOINT ["/app/gamestats"]
