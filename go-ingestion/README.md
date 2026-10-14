# Módulo de ingestión Go

Este servicio implementa exclusivamente las responsabilidades que los documentos del proyecto asignan a Go: recibir eventos, validarlos, controlar su flujo, mantener estado temporal, procesar varias partidas concurrentemente y preparar la información para Scala. No calcula estadísticas.

## Arquitectura

```text
Fuente externa
  ├─ API REST
  └─ JSON / JSON Lines
          │
          ▼
Decodificación estricta y validación común
          │
          ▼
Dispatcher por hash(matchId)
  ├─ worker 1 ─ estado de sus partidas
  ├─ worker 2 ─ estado de sus partidas
  └─ worker N ─ estado de sus partidas
          │ MATCH_FINISHED válido
          ▼
Entrega transaccional
  ├─ POST al analizador Scala (ANALYZER_URL)
  └─ JSON Lines (OUTPUT_PATH)
```

Los eventos de una misma partida siempre llegan al mismo worker y se procesan en serie. Partidas diferentes pueden avanzar en paralelo. El identificador `eventId` se deduplica globalmente entre todos los workers.

El evento final se aplica sobre una copia del estado. Si la entrega falla, esa copia no se confirma y `MATCH_FINISHED` puede enviarse nuevamente con el mismo `eventId`.

## Ejecución

```bash
go run ./cmd/gamestats
```

Opciones principales:

```text
-address       dirección del servidor; predeterminado :8080
-workers       cantidad de workers; predeterminado número de CPU
-input         archivo JSON o JSON Lines que se carga al iniciar
-serve         mantiene la API después de cargar el archivo; true por defecto
-analyzer-url  endpoint HTTP completo del módulo Scala
-output        archivo JSON Lines alternativo si no hay endpoint Scala
```

Las mismas opciones operativas pueden definirse con variables de entorno:

| Variable | Valor predeterminado | Función |
|---|---:|---|
| `HTTP_ADDR` | `:8080` | Dirección y puerto HTTP. |
| `WORKER_COUNT` | CPU disponibles | Workers concurrentes. |
| `ANALYZER_URL` | vacío | Endpoint que recibe una partida terminada. |
| `OUTPUT_PATH` | `data/ready-matches.jsonl` | Intercambio por archivo cuando no hay URL. |

`ANALYZER_URL` tiene prioridad sobre `OUTPUT_PATH`.

## API

### `POST /api/v1/events`

Recibe un único evento. Devuelve `201` si se acepta, `409` si `eventId` está repetido, `422` si viola el estado de la partida y `502` si no se pudo entregar una partida finalizada.

```powershell
curl.exe -X POST http://localhost:8080/api/v1/events -H "Content-Type: application/json" --data-binary "@event.json"
```

### `POST /api/v1/events/batch`

Recibe un arreglo de 1 a 1000 eventos. Devuelve `201` si todos se aceptan o `207` con un resultado por posición si hubo aceptaciones y rechazos.

### `GET /api/v1/matches/{matchId}`

Consulta los eventos aceptados de una partida en memoria. Es una vista operativa de ingestión, no una consulta de estadísticas.

### `GET /healthz`

Devuelve `200` mientras el proceso HTTP está activo.

## Archivos de prueba

`testdata/valid/interleaved-events.jsonl` contiene 67 eventos de doce partidas —tres por juego— con marcas de tiempo superpuestas. Incluye presión de tiempo, captura, asistencia, rebote, racha de ocho puntos, quinta falta, parada en boxes, penalización y flujos de Pac-Man con poderes, fantasmas, vidas y niveles.

```powershell
go run ./cmd/gamestats -input ./testdata/valid/interleaved-events.jsonl -serve=false -output ./data/ready-matches.jsonl
```

Los casos inválidos están separados en `testdata/invalid/` para demostrar:

- campo obligatorio ausente;
- `eventId` duplicado;
- referencia a un tiro inexistente;
- acción después de la quinta falta;
- salida de boxes sin entrada;
- referencia de poder inexistente o efecto vencido en Pac-Man;
- `pelletId` repetido, puntuación incoherente y acción después de agotar las vidas.

El cargador se detiene en el primer evento rechazado y devuelve un error distinto de cero, lo cual permite usar esos archivos como pruebas negativas.

## Calidad y construcción

```bash
gofmt -w .
go vet ./...
go test -race -cover ./...
go build -trimpath -o bin/gamestats ./cmd/gamestats
```

La imagen se construye desde la raíz del repositorio con:

```bash
docker compose build go-ingestion
```

El contenedor final no incluye shell, se ejecuta con un usuario sin privilegios y solo contiene el binario, certificados TLS y el directorio de salida.

## Límites deliberados

- El estado de partidas en curso y la deduplicación son temporales; reiniciar el proceso los elimina, tal como permite el alcance inicial.
- El servicio valida relaciones y consistencia de entrada, pero no calcula métricas ni la clasificación final; esas tareas pertenecen a Scala.
- La publicación de imágenes y el deployment quedan sin credenciales en CI. El pipeline sí compila, prueba, conserva artefactos y construye el contenedor de forma reproducible.
