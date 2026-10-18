# GameStats

GameStats procesa eventos de ajedrez, baloncesto, carreras y Pac-Man. Go recibe, valida y agrupa eventos; Scala calcula estadísticas mediante funciones puras y persiste resultados consultables por HTTP. Docker Compose conecta ambos módulos.

## Entrega sin código fuente

La distribución versionada se prepara en `dist/gamestats-v1.1.0.zip`, con un Compose que usa únicamente imágenes y un README en español. El profesor solo necesitará Docker con Compose e internet. Las imágenes de entrega deben publicarse como públicas y pasar la descarga anónima antes de entregar el ZIP; el candidato local no acredita esa publicación.

Consulta [la preparación, pruebas y publicación autorizada](Docs/distribucion.md). Los archivos de desarrollo y sus comandos siguientes se mantienen separados de la entrega.

## Verificación completa

Con Docker Desktop en modo Linux, desde la raíz, ejecutar en Windows PowerShell:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Verify-Integration.ps1
```

El script prueba Go con detector de carreras, construye ambos contenedores (incluidas las pruebas Scala), asigna puertos libres y verifica los cuatro juegos mediante Go → HTTP → Scala. Usa proyecto, red, imágenes y volúmenes exclusivos; al finalizar elimina únicamente esos recursos. Conserva logs y resumen en `scala-analytics/output/gamestats-verify-*/`. No requiere Go, Java ni Scala instalados en Windows. Primera ejecución: descarga imágenes y dependencias.

El [informe de integración](Docs/verificacion-integracion.md) detalla resultados, contratos, límites y pendientes. El workflow ejecuta también esta verificación en Linux; su ejecución remota debe revisarse en GitHub Actions después de publicar los cambios.

La [documentación de CI/CD](Docs/ci-cd.md) explica los jobs, el resumen automático de cada ejecución, los artefactos y el flujo de integración a `main`.

## Módulo disponible

- [Servicio de ingestión Go](go-ingestion/README.md): API REST, carga desde archivos, validación, deduplicación, estado por partida y entrega al analizador.
- [Análisis Scala y Docker](scala-analytics/README.md): cuatro juegos, pruebas e integración por HTTP o archivo sin instalaciones locales de Java o Scala.
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

Las partidas finalizadas se escriben de forma predeterminada en `data/ready-matches.jsonl`. Al definir `ANALYZER_URL`, se envían por HTTP al servicio Scala.
