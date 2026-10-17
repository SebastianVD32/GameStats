FROM python:3.13-slim@sha256:70729b46c69b4f1e97c4822c1af3df53a1476cf5ddc6c087c0c10bc3a5678c2f
WORKDIR /app
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
COPY distribution/demo/demo.py ./demo.py
COPY distribution/demo/fixtures ./fixtures/extra
COPY go-ingestion/testdata/valid/interleaved-events.jsonl ./fixtures/events.jsonl
COPY go-ingestion/testdata/invalid ./fixtures/invalid
COPY scala-analytics/testdata/*.expected.json ./fixtures/expected/
USER 65532:65532
ENTRYPOINT ["python", "/app/demo.py"]
