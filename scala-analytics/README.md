# Scala y Docker: primera etapa de ajedrez

Esta etapa convierte una partida completa del contrato Go en estadísticas JSON.
Solo necesitas Docker Desktop con contenedores Linux y PowerShell; Java, Scala y
sbt se ejecutan dentro de Docker. La primera ejecución descarga dependencias.

## Ejecutar y probar

Desde `Y:\proyecto-lenguajes\GameStats`:

```powershell
docker compose -f scala-analytics/compose.yaml run --rm scala-dev sbt test
docker compose -f scala-analytics/compose.yaml run --rm scala-dev
Get-Content scala-analytics/output/chess-1.result.json
```

Las pruebas deben terminar con `Passed: Total 10, Failed 0, Errors 0, Passed 10`.
El segundo comando compila y ejecuta el programa sobre `testdata/chess-1.json`,
imprime JSON y lo guarda en `output/chess-1.result.json`. `output` está excluido
de Git. No uses la salida completa de sbt como JSON: contiene mensajes de compilación.

La partida tiene 5 movimientos, 1 captura, 9000 ms de duración y gana
`chess-alice` con blancas por `CHECKMATE`. Alice tiene 3 movimientos, 1 captura,
promedio de decisión de 1000 ms y presión de tiempo. Bob tiene 2 movimientos,
0 capturas, promedio de 1000 ms y no tiene presión. Su promedio de captura es `null`.

Para analizar otras partidas existentes:

```powershell
docker compose -f scala-analytics/compose.yaml run --rm scala-dev sbt "run testdata/chess-2.json output/chess-2.result.json"
docker compose -f scala-analytics/compose.yaml run --rm scala-dev sbt "run testdata/chess-3.json output/chess-3.result.json"
```

`chess-2` termina en tablas; `chess-3` tiene ganador negro por tiempo. Ambas
carecen de movimientos: los promedios son `null` y la presión es `false`.

## Imagen de ejecución

```powershell
docker compose -f scala-analytics/compose.yaml build chess-cli
docker compose -f scala-analytics/compose.yaml run --rm chess-cli
```

El Dockerfile compila en una etapa con sbt y copia el JAR y sus dependencias a
una imagen con Java 21. El proceso final usa un usuario sin privilegios y no
necesita sbt. Imprime únicamente el JSON; el servicio `chess-cli` termina al
completar el análisis. `scala-dev` conserva los volúmenes de caché existentes.

## Integración real Go → archivo → Scala

Desde la raíz:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scala-analytics/scripts/Test-GoFileIntegration.ps1
```

El script construye las dos imágenes locales, ejecuta el Go existente sobre los
46 eventos intercalados y extrae `chess-1` del archivo de partidas COMPLETAS
que produce Go. Scala analiza esa salida real y guarda su resultado en una
carpeta nueva `scala-analytics/output/integration-<id>/`. Debe mostrar
`Integration OK: Go produced 9 batches; Scala analyzed chess-1.` y la ruta del JSON.
Cada ejecución usa una carpeta distinta para no acumular líneas del FileSink.
No inicia servidores persistentes ni cambia el código de Go.

## Diseño y reglas

- `Model.scala`: estructuras inmutables. `Event` y `MatchBatch` conservan los
  nombres JSON del contrato; `data` es un `io.circe.Json` inmutable. Los datos
  específicos de ajedrez se decodifican en tipos propios.
- `JsonCodec.scala`: decodificación estricta de campos y codificación de
  resultados. `playerId` y `winnerPlayerId` deben estar presentes incluso si
  son `null`. No sustituye nombres del contrato por otros.
- `ChessAnalysis.scala`: funciones puras, sin archivos, HTTP ni estado global.
  Usa `map`, `filter`, `groupBy` y `foldLeft`; devuelve `Either` para errores.
  Valida estructura completa, secuencia, identidad, tiempos UTC, participantes,
  alternancia, relojes, referencias de captura y resultado final.
- `Main.scala`: lectura de un objeto MatchBatch JSON, invocación del cálculo,
  impresión y escritura opcional. Argumentos: `<match.json> [result.json]`.
  Código de salida 1 ante datos o archivos inválidos; 2 para argumentos inválidos.
- `ChessAnalysisSuite.scala`: resultados completos y casos límite.

Los archivos `testdata/chess-{1,2,3}.json` se extrajeron, sin cambiar los eventos,
de `go-ingestion/testdata/valid/interleaved-events.jsonl`. `chess-1.expected.json`
contiene el resultado calculado previamente para comparar el JSON completo.

Los promedios sin observaciones son `null`. La duración usa las marcas de inicio
y final; los tiempos de decisión usan `decisionTimeMs`, no la diferencia entre
eventos. Las capturas se relacionan por `moveEventId` y no cuentan como otra jugada.
Presión significa tres jugadas PROPIAS consecutivas con `clockBeforeMs < 60000`;
60000 exactos interrumpe la secuencia y las jugadas del rival no la interrumpen.
`pressureWindows` contiene cada ventana de tres movimientos; cuatro jugadas bajo
el umbral producen dos ventanas superpuestas y un solo booleano `timePressure`.

`winsByColor` calcula victorias blancas y negras entre partidas completadas,
incluidos empates; deduplica resultados idénticos por juego, versión y partida,
y rechaza resultados distintos para la misma identidad. Es una función de
agregación probada, disponible para la siguiente etapa; la CLI actual analiza
una partida y no mantiene un historial acumulado.

## Contrato confirmado y trabajo posterior

Se revisaron el PDF, el DOCX, `Docs/contrato-eventos-go.md`, el módulo Go, sus
pruebas y datos, Dockerfiles y los dos Compose. No se encontraron `AGENTS.md`.
Go sigue entregando `{gameId, gameVersion, matchId, events}`. El Compose raíz
conserva `ANALYZER_URL=http://scala-analysis:8081/api/v1/matches` como comentario;
el HTTPSink confirma cualquier `2xx` y descarta el cuerpo de la respuesta.

Esta etapa funciona por archivo. La API `POST /api/v1/matches`, `GET /healthz`,
persistencia, consultas de resultados e idempotencia de HTTP siguen pendientes.
Por eso el Compose raíz conserva la salida a archivo: activar la URL ahora
rompería la entrega. La siguiente integración deberá añadir `scala-analysis`,
su healthcheck y `depends_on` con `service_healthy` antes de activar esa URL.
La deduplicación de la función de victorias todavía no es una garantía de
idempotencia persistente para un futuro servicio.

Baloncesto y carreras tienen contrato y pueden desarrollarse después. Pac-Man
no aparece en los documentos actuales y Go rechaza `PACMAN`: antes de implementarlo
se necesita la definición actualizada. Con ese documento, el compañero deberá
añadir el GameID, tipos y datos, validaciones de inicio/acciones/final, estado de
partida, referencias, casos válidos/inválidos y pruebas de entrega en Go.

No se modificaron la lógica Go, GitHub Actions ni el Compose raíz. La rama de
trabajo es `feat/scala-docker-chess`; no se hace push, merge ni despliegue.

Dependencias: JSON con [Circe](https://circe.github.io/circe/), pruebas con
[MUnit](https://scalameta.org/munit/docs/getting-started.html).

## Validación realizada

Se ejecutaron en Docker Desktop: compilación Scala, las 10 pruebas (todas pasaron),
CLI con archivo de resultado, construcción del Dockerfile y ejecución de su imagen,
y el script Go → archivo → Scala con los 46 eventos y 9 partidas. El JSON completo
de integración coincidió con `chess-1.expected.json`. La imagen devolvió código 1
ante un JSON que no era MatchBatch. `git diff --check` no encontró errores.
No se probaron endpoints HTTP Scala ni persistencia del servicio: pertenecen a la
siguiente etapa y todavía no existen. No se ejecutó la suite Go ni GitHub Actions;
el módulo Go se compiló y se ejecutó sin modificaciones para verificar el intercambio.
