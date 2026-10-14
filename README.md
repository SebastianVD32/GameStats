# GameStats

GameStats procesa eventos de ajedrez, baloncesto, carreras y Pac-Man. Este repositorio contiene actualmente el módulo de ingestión y control de flujo desarrollado en Go; el análisis estadístico funcional se integrará como un servicio Scala independiente.

## Módulo disponible

- [Servicio de ingestión Go](go-ingestion/README.md): API REST, carga desde archivos, validación, deduplicación, estado por partida y entrega al analizador.
- [Contrato de eventos](Docs/contrato-eventos-go.md): estructura JSON y reglas implementadas para cada juego.
- [Flujo de Git y commits](Docs/flujo-git.md): rama recomendada, commits parciales, publicación, merge y limpieza.

## Inicio rápido

Se necesita Go 1.24 o una versión posterior.

```bash
cd go-ingestion
go test ./...
go run ./cmd/gamestats
```

La API queda disponible en `http://localhost:8080`. Para comprobar de una vez las doce partidas de demostración:

```powershell
go run ./cmd/gamestats -input ./testdata/valid/interleaved-events.jsonl -serve=false
```

Con Docker:

```bash
docker compose up --build
```

Las partidas finalizadas se escriben de forma predeterminada en `data/ready-matches.jsonl`. Al definir `ANALYZER_URL`, se envían por HTTP al futuro módulo Scala.
