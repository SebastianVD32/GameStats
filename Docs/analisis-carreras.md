# Análisis funcional de carreras

Se reutilizan `MatchBatch`, `Event`, `JsonCodec`, la CLI y el contenedor existentes.
`RacingAnalysis.analyze` es una función pura con estructuras inmutables y devuelve
`Either[String, RacingResult]`. No se modifican Go ni el contrato de eventos.

## Cálculos y salida

`drivers` conserva el orden de participantes e incluye:

- `bestLapTimeMs`, `averageLapTimeMs`, `totalTimeMs` y `completedLaps`.
- `pitStops` y `stops`: cada parada con `stopId`, eventos de entrada/salida,
  vueltas de entrada/salida y duración por timestamps. El tiempo de boxes ya
  pertenece al tiempo de vuelta y nunca se suma al total.
- `timePenaltyMs` y `positionPenalty`: sanciones acumuladas por separado.
  Los totales usan `BigInt` y se codifican como números JSON para evitar desbordamientos.
- `adjustedTimeMs`: tiempo de vueltas más sanciones TIME.
- `positionWithoutPenalties`, `finalPosition` y `positionChange`:
  posición original menos definitiva; positivo significa avance.
- `comeback`: gana finalmente y terminó alguna vuelta en la última posición.

`classificationWithoutPenalties` ordena los tiempos de vueltas sin sanciones.
`classification` primero ordena por tiempo ajustado, desempatando por posición
de la última vuelta. Después aplica cada sanción POSITION en orden de `sequence`
sobre la clasificación actual. El piloto baja hasta el último puesto como máximo;
los pilotos desplazados avanzan. La suma de sanciones de posición puede diferir
del cambio real de puesto. Las sanciones no alteran la mejor vuelta.

`fastestLaps` identifica la vuelta más rápida global y conserva todas las vueltas
empatadas en ese tiempo, en orden de eventos.

Se validan sobres, identidad, secuencia, UTC y cronología, participantes, vueltas
continuas completas, posiciones únicas por vuelta, referencias de paradas,
salida única del mismo piloto, vueltas válidas y penalizaciones positivas.
No se agregan restricciones sobre paradas simultáneas que Go no impone.
La clasificación final se compara por piloto y posición; el orden del arreglo
JSON de `MATCH_FINISHED` no importa. Ante inconsistencia, se devuelve un error con
ambas clasificaciones; la CLI termina con código 1 y no escribe un resultado válido.

## Datos originales y resultados esperados

Los fixtures `testdata/race-{1,2,3}.json` se extraen sin cambios del archivo Go.
Los JSON esperados se calculan independientemente mediante valores fijos en
`scripts/prepare_racing_expected.py`; Python no es dependencia de ejecución.

| Partida | Resultado |
|---|---|
| race-1 | Alice 90000 ms, Bob 91000 ms; gana Alice, mejor vuelta 90000 ms, sin remontada |
| race-2 | Dan 93000 ms, Carol 95000 ms; gana Dan; Carol tiene una parada de 1000 ms, total 95000 ms |
| race-3 | Orden calculado Erin → Alice → Dan; TIME de Dan 5000 ms, ajustado 93000 ms; vuelta más rápida Dan 88000 ms |

**El `MATCH_FINISHED` original de race-3 es inconsistente:** declara Erin → Dan →
Alice. El analizador lo rechaza. No se altera el archivo ni la lógica Go.
`race-3.expected.json` documenta las estadísticas calculadas para el orden correcto;
las pruebas comparan ese JSON usando una copia en memoria con el final corregido.
Otra prueba confirma el rechazo de los eventos originales.

Las tres partidas originales no tienen remontada. Los casos sintéticos cubren
ganadores que estuvieron últimos, perdedores que estuvieron últimos, ganadores
que nunca estuvieron últimos, múltiples vueltas y sanciones que cambian al ganador.

## PowerShell

Con Docker Desktop funcionando con contenedores Linux, ejecutar desde la raíz:

```powershell
Set-Location 'Y:\proyecto-lenguajes\GameStats'
docker compose -f scala-analytics/compose.yaml run --rm scala-dev sbt test
foreach ($n in 1..2) {
    docker compose -f scala-analytics/compose.yaml run --rm scala-dev sbt "run testdata/race-$n.json output/race-$n.result.json"
}
Get-Content scala-analytics/output/race-2.result.json
docker compose -f scala-analytics/compose.yaml run --rm scala-dev sbt "run testdata/race-3.json"
```

La última ejecución debe terminar con código 1 y mostrar `Classification
inconsistency` con las posiciones calculadas y registradas. Es un rechazo esperado.

Para verificar la imagen JVM y el intercambio real de las nueve partidas:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scala-analytics/scripts/Test-GoFileIntegration.ps1
```

Salida final esperada:

```text
Integration OK: Go produced 9 batches; Scala analyzed 8 matches and rejected inconsistent race-3.
```

La carpeta `scala-analytics/output/integration-<id>/` contiene ocho resultados,
los nueve lotes individuales y `race-3.error.txt`. El script comprueba seis JSON
completos esperados y el diagnóstico de la partida inconsistente.

El servicio conserva su nombre histórico y ejecuta los tres juegos:

```powershell
docker compose -f scala-analytics/compose.yaml build chess-cli
docker compose -f scala-analytics/compose.yaml run --rm chess-cli /data/race-1.json
docker compose -f scala-analytics/compose.yaml run --rm chess-cli /data/race-3.json
$LASTEXITCODE # 1: clasificación inconsistente
```

## Validación realizada

El 10 de octubre de 2026 se ejecutaron las 35 pruebas Scala (10 ajedrez,
11 baloncesto y 14 carreras) sin fallos, la CLI de race-1 y race-2 con escritura
de resultados, la construcción de la imagen JVM y el script de intercambio
real Go → archivo → Scala. También se verificó que los tres fixtures conservan
los eventos originales y que Go no tiene cambios. No se ejecutó la suite Go.
