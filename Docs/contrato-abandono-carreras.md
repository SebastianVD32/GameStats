# Abandono de carreras: extensión compatible de RACING 1.0

Una carrera iniciada puede cerrar con `MATCH_FINISHED` (todos completaron las
vueltas, clasificación validada) o `MATCH_ABORTED` (progreso incompleto).
Los eventos y resultados de carreras completadas conservan su formato anterior.
La extensión requiere actualizar juntos Go y Scala; versiones anteriores rechazan
el nuevo evento. No cambia el contrato de otros juegos.

## Evento terminal

Se conserva el sobre habitual con identidad estable, `eventId` único, timestamp
UTC no decreciente, secuencia consecutiva y `playerId: null`:

```json
{
  "eventId": "abort-2",
  "timestamp": "2026-11-19T12:00:20Z",
  "gameId": "RACING",
  "gameVersion": "1.0",
  "matchId": "race-1",
  "sequence": 2,
  "playerId": null,
  "type": "MATCH_ABORTED",
  "data": {"reason": "USER_QUIT"}
}
```

`reason` admite únicamente `USER_QUIT` (retiro), `RESTARTED` (nueva carrera) o
`PAGE_CLOSED` (cierre/recarga). No se permiten campos extra ni clasificación.
Debe existir `MATCH_STARTED`. Se admite cero vueltas y boxes abiertos; al menos
un piloto debe tener menos vueltas que `plannedLaps`. Si todos terminaron,
corresponde `MATCH_FINISHED`. No se aceptan acciones posteriores al cierre.

Go valida el evento, entrega el lote a Scala y solo confirma el cierre si la
entrega fue exitosa, igual que en una finalización normal. El reintento del mismo
evento es idempotente. Después del cierre, la partida sale de `activeMatches`.
`finishedMatches` de Go cuenta ciclos cerrados, incluidos abandonos; no significa
carreras completadas. La distinción analítica corresponde a Scala.

## Resultado Scala

`POST /api/v1/matches` acepta el lote y `GET /api/v1/matches/{matchId}` devuelve:

```json
{
  "gameId": "RACING",
  "gameVersion": "1.0",
  "matchId": "race-1",
  "status": "ABORTED",
  "reason": "USER_QUIT",
  "durationMs": 20000,
  "plannedLaps": 3,
  "driverProgress": [
    {"playerId": "a", "completedLaps": 0, "completedLapTimeMs": 0, "openPitStops": 0},
    {"playerId": "b", "completedLaps": 0, "completedLapTimeMs": 0, "openPitStops": 0}
  ]
}
```

`completedLapTimeMs` suma únicamente vueltas efectivamente recibidas, sin estimar
una vuelta parcial. No hay ganador, clasificación, mejor vuelta oficial ni
remontada. El lote original conserva las acciones, sanciones y paradas recibidas.
El resultado se persiste y se valida de nuevo al arrancar como los anteriores.

## Agregados

- `general.completedMatches` y `matchesByGame`: solo partidas completadas.
- `general.processedMatches`, `processedMatchesByGame`: todos los cierres analizados.
- `general.abortedMatches`: abandonos. Tiempo total, duración promedio general y
  eventos incluyen el tiempo y eventos reales de todos los cierres procesados.
- `games.RACING.completedMatches`: carreras completas; `abortedMatches` y
  `abortsByReason` contabilizan los abandonos por separado.
- Mejor vuelta, duración promedio de carreras, boxes y remontadas se calculan
  únicamente con carreras completas. Los abandonos no mejoran récords.

Go publica el progreso durante la partida. Scala produce estadísticas al recibir
un cierre; no ofrece un análisis parcial continuo de una carrera activa.
El cierre de pestaña depende de que el cliente entregue el evento: un proceso
terminado a la fuerza no garantiza notificación.

## Validación

Go cubre razones, payload estricto, ciclo de vida, boxes abiertos, carrera ya
completa, entrega fallida y reintento. `RacingAbortSuite` verifica progreso,
agregados, aislamiento de récords y compatibilidad de resultados persistidos.
GameConsole ejercita carreras completas y abandonadas mediante Flask → Go → Scala.
