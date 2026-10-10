# Verificación analítica de ajedrez y baloncesto

## Ajedrez antes de avanzar

Se contrastaron el DOCX de definición de eventos, el contrato Go, el código Scala,
las pruebas y el reporte XML de la ejecución previa (10 pruebas, cero fallos,
9 de octubre de 2026). La suite se volvió a ejecutar junto con baloncesto.

| Requisito de ajedrez | Implementación y evidencia |
|---|---|
| Movimientos y capturas por jugador, movimientos totales | `ChessAnalysis.analyze`; comparación completa de `chess-1.expected.json` |
| Promedio de decisión y duración | Campos `decisionTimeMs` y marcas de inicio/final; prueba con tiempos variados |
| Promedio en movimientos con captura | Relación por `moveEventId`; prueba de captura referida a un movimiento anterior |
| Porcentaje de victorias por color, incluyendo tablas | `winsByColor`; prueba con las tres partidas, replay, conflicto y entrada vacía |
| Tres jugadas propias consecutivas con menos de 60000 ms | `pressureWindows`; prueba del límite exacto, interrupción y ventanas superpuestas |
| Ausencia de observaciones | Promedios y porcentajes `null`; presión `false` |
| Partidas de ambos colores y empate | `chess-1`, `chess-2`, `chess-3` |

Todos los requisitos analíticos de ajedrez están completos. No hizo falta cambiar
su cálculo. La agregación por color existe como función pura; la CLI sigue
procesando una partida por ejecución. HTTP, persistencia y consultas de historial
son trabajo posterior documentado y no forman parte de esta implementación.

## Baloncesto

`BasketballAnalysis` recibe el mismo `MatchBatch` y devuelve `Either[String,
BasketballResult]`. No lee archivos, no accede a redes ni modifica estado global.
`Main` conserva el transporte por archivo y selecciona el cálculo por `gameId`.
Los modelos son inmutables; las secuencias se procesan con `foldLeft`, `map`,
`filter` y mapas inmutables. La lógica Go no se modifica.

- `players`: todos los participantes, incluso sin acciones; puntos, asistencias,
  rebotes ofensivos/defensivos, pérdidas, faltas y efectividad.
- `teams`: marcador calculado, puntos asistidos y efectividad conjunta.
- `fieldGoals`: tiros de 2 y 3; `threePointers`: tiros de 3;
  `freeThrows`: tiros de 1. Cada categoría incluye `made`, `attempted` y
  `percentage` entre 0 y 100, o `null` si no existen intentos.
- `assistedBasketPoints`: puntos del anotador en canastas con asistencia;
  en equipos, suma de esas canastas una sola vez.
  `pointsCreatedByAssists`: puntos creados por las asistencias del pasador.
  Estas métricas no se añaden de nuevo al marcador.
- `fifthFoul`: evento, secuencia, timestamp y período de la quinta falta;
  `excluded` indica exclusión. Se rechaza cualquier acción posterior del infractor.
- `scoringRuns`: una entrada por racha máxima de al menos ocho puntos;
  contiene total, tiros, inicio, último tiro anotado y tiro que alcanzó el umbral.
  Los tiros libres cuentan. Fallos, pérdidas, faltas y cambios de período no la
  cortan; un enceste rival la corta. El final de partida cierra la última racha.

Se comprueban sobre, secuencia, identidad, UTC, cronología, participantes,
referencias previas, equipo/período, asistencia única a canasta de campo de un
compañero, rebote único a fallo y marcador final. No se impone una progresión de
períodos/reloj adicional a la del contrato Go. Se normaliza `reboundType` como Go.

Los fixtures `basket-{1,2,3}.json` conservan los eventos originales del archivo
intercalado Go. Sus archivos `.expected.json` son resultados completos calculados
independientemente. `scripts/prepare_expected.py` documenta esos cálculos fijos;
no es dependencia de ejecución ni se invoca durante las pruebas Scala.

| Partida | Resultado esperado |
|---|---|
| basket-1 | 2–0; h1 anota 2 puntos asistidos y recoge un rebote defensivo; h2 da una asistencia; a1 falla un triple |
| basket-2 | 8–0; h1 anota 5, h2 anota 3; una racha de 8 alcanza el umbral en b2-e4 |
| basket-3 | 0–0; h1 acumula 5 faltas, excluido en b3-e6, secuencia 6, período 4 |

## Comandos PowerShell

Con Docker Desktop funcionando en modo Linux, desde la raíz:

```powershell
Set-Location 'Y:\proyecto-lenguajes\GameStats'
docker compose -f scala-analytics/compose.yaml run --rm scala-dev sbt test
foreach ($n in 1..3) {
    docker compose -f scala-analytics/compose.yaml run --rm scala-dev sbt "run testdata/basket-$n.json output/basket-$n.result.json"
}
Get-Content scala-analytics/output/basket-1.result.json
powershell -NoProfile -ExecutionPolicy Bypass -File scala-analytics/scripts/Test-GoFileIntegration.ps1
```

Las pruebas deben mostrar `Passed: Total 21, Failed 0, Errors 0, Passed 21`.
La CLI guarda los resultados en `scala-analytics/output/`. El script construye
la imagen JVM, ejecuta Go sin cambios sobre 46 eventos y comprueba las tres
partidas de cada juego. Debe finalizar con:

```text
Integration OK: Go produced 9 batches; Scala analyzed 3 chess and 3 basketball matches.
```

El script imprime la carpeta nueva de los seis resultados. El nombre histórico
del servicio `chess-cli` se conserva por compatibilidad; ejecuta ambos juegos:

```powershell
docker compose -f scala-analytics/compose.yaml build chess-cli
docker compose -f scala-analytics/compose.yaml run --rm chess-cli /data/basket-2.json
```

El último comando imprime únicamente el JSON del análisis en stdout y termina.
