FROM python:3.13-slim@sha256:70729b46c69b4f1e97c4822c1af3df53a1476cf5ddc6c087c0c10bc3a5678c2f
WORKDIR /app
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
COPY distribution/demo/demo.py ./demo.py
COPY distribution/demo/fixtures ./fixtures/extra
COPY go-ingestion/testdata/valid/interleaved-events.jsonl ./fixtures/events.jsonl
COPY go-ingestion/testdata/invalid ./fixtures/invalid
COPY scala-analytics/testdata/*.expected.json ./fixtures/expected/
COPY panel/server.py ./server.py
COPY panel/static ./static
RUN mkdir -p /data/panel && chown 65532:65532 /data/panel
USER 65532:65532
EXPOSE 8090
HEALTHCHECK --interval=5s --timeout=3s --retries=12 CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/healthz', timeout=2).read()"]
ENTRYPOINT ["python", "/app/server.py"]
