# Scala: cuatro analizadores, servicio HTTP y Docker

El servicio analiza CHESS, BASKETBALL, RACING y PACMAN. Sin argumentos inicia HTTP en 0.0.0.0:8081; con uno o dos argumentos conserva la CLI. POST /api/v1/matches guarda antes de responder; GET /api/v1/matches/{matchId} consulta resultados y GET /api/v1/pacman/players/{playerId} consulta acumulados. GET /healthz comprueba disponibilidad.

Consulta [contrato y pendientes Go](../Docs/contrato-pacman.md) y [comandos PowerShell, resultados esperados y verificaciones](../Docs/verificacion-pacman-docker.md). Docker Compose en la raiz conecta Go y Scala y persiste resultados en scala-results. La configuracion scala-dev de este modulo conserva el entorno sin instalaciones en Windows.

## Scala y Docker: ajedrez, baloncesto y carreras

El analizador convierte partidas completas de ajedrez, baloncesto y carreras del contrato Go en estadísticas JSON.
Solo necesitas Docker Desktop con contenedores Linux y PowerShell; Java, Scala y
sbt se ejecutan dentro de Docker. La primera ejecución descarga dependencias.

## Ejecutar y probar

Desde `Y:\proyecto-lenguajes\GameStats`:

```powershell
docker compose -f scala-analytics/compose.yaml run --rm scala-dev sbt test
docker compose -f scala-analytics/compose.yaml run --rm scala-dev
Get-Content scala-analytics/output/chess-1.result.json
```

Las pruebas deben terminar con `Passed: Total 35, Failed 0, Errors 0, Passed 35`.
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

El script construye las dos imágenes locales, ejecuta Go sobre los 46 eventos
intercalados y verifica las nueve partidas de los tres juegos que
produce Go. Compara el JSON completo cuando existe un archivo `.expected.json`.
Debe mostrar `Integration OK: Go produced 9 batches; Scala analyzed 8 matches and rejected inconsistent race-3.`
y la carpeta `scala-analytics/output/integration-<id>/` con ocho resultados y el diagnóstico de race-3.
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

## Contrato y servicio actual

El documento actualizado de Pac-Man se concreta en Docs/contrato-pacman.md.
El Compose raiz activa ANALYZER_URL, scala-analysis, healthcheck y dependencia saludable.
El HTTPSink Go descarta el cuerpo de la respuesta; Scala persiste antes de responder.
La CLI sigue disponible para los cuatro juegos. Las verificaciones historicas siguientes
corresponden a etapas anteriores; la verificacion actual esta documentada en
Docs/verificacion-pacman-docker.md.

## Validación histórica de la primera etapa

Se ejecutaron en Docker Desktop: compilación Scala, las 10 pruebas (todas pasaron),
CLI con archivo de resultado, construcción del Dockerfile y ejecución de su imagen,
y el script Go → archivo → Scala con los 46 eventos y 9 partidas. El JSON completo
de integración coincidió con `chess-1.expected.json`. La imagen devolvió código 1
ante un JSON que no era MatchBatch. `git diff --check` no encontró errores.
No se probaron endpoints HTTP Scala ni persistencia del servicio: pertenecen a la
siguiente etapa de esa etapa historica. No se ejecutó la suite Go ni GitHub Actions;
el módulo Go se compiló y se ejecutó sin modificaciones para verificar el intercambio.

## Baloncesto y verificación de su etapa

Consulta [la verificación de requisitos y los comandos completos](../Docs/verificacion-ajedrez-baloncesto.md).
`BasketballAnalysis.scala` calcula puntos, asistencias, rebotes, pérdidas, faltas,
efectividad, puntos asistidos, quinta falta y rachas de al menos ocho puntos.
`Main` selecciona ajedrez o baloncesto a partir de `gameId`, reutilizando el lector
JSON y la escritura existentes. Las funciones de análisis son puras.

Desde la raíz, para guardar los tres resultados:

```powershell
foreach ($n in 1..3) {
    docker compose -f scala-analytics/compose.yaml run --rm scala-dev sbt "run testdata/basket-$n.json output/basket-$n.result.json"
}
Get-Content scala-analytics/output/basket-2.result.json
```

Resultados: `basket-1` termina 2–0 con asistencia y rebote; `basket-2` termina 8–0
con una racha de ocho puntos; `basket-3` termina 0–0 con quinta falta en `b3-e6`.
Los porcentajes sin intentos son `null`. El servicio histórico `chess-cli` también
acepta `/data/basket-1.json`, `/data/basket-2.json` y `/data/basket-3.json`.

Validación del 10 de octubre de 2026: 21 pruebas aprobadas (10 de ajedrez y 11 de
baloncesto), compilación y construcción de la imagen JVM, integración real de las
seis partidas con nueve lotes Go y comparación de los cuatro resultados completos
con oracle disponible. La suite Go no se ejecutó; su código quedó sin cambios.

## Carreras y verificación actual

[Reglas, campos de salida y comandos de carreras](../Docs/analisis-carreras.md).
`RacingAnalysis.scala` calcula vueltas, paradas relacionadas por `stopId`,
penalizaciones separadas, clasificación y remontadas mediante funciones puras.
La CLI selecciona también `RACING` y conserva la lectura/escritura existente.

```powershell
foreach ($n in 1..2) {
    docker compose -f scala-analytics/compose.yaml run --rm scala-dev sbt "run testdata/race-$n.json output/race-$n.result.json"
}
docker compose -f scala-analytics/compose.yaml run --rm scala-dev sbt "run testdata/race-3.json"
```

`race-1` gana Alice y `race-2` gana Dan. **race-3 original tiene una clasificación
inconsistente**: Dan suma 93000 ms tras TIME, detrás de Alice (90000 ms), pero su
final declara a Dan segundo. Se conserva el fixture Go; la ejecución termina
con código 1 y reporta ambas clasificaciones. El orden calculado es Erin, Alice,
Dan. La suite verifica el rechazo y las estadísticas completas con una copia
corregida en memoria. El script de integración espera y verifica ese rechazo.

La suite actual contiene 35 pruebas: 10 ajedrez, 11 baloncesto y 14 carreras,
incluyendo desempates, sanciones por sequence, paradas y remontadas.
