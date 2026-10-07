# Contrato de eventos implementado en Go

Este documento concreta las partes que los PDF dejan a decisión del equipo, sobre todo los datos de `MATCH_STARTED` y `MATCH_FINISHED`. Todos los objetos rechazan propiedades desconocidas para detectar errores de escritura temprano.

## Sobre común

```json
{
  "eventId": "identificador-global-unico",
  "timestamp": "2026-09-01T12:00:00Z",
  "gameId": "CHESS",
  "gameVersion": "1.0",
  "matchId": "partida-1",
  "sequence": 1,
  "playerId": null,
  "type": "MATCH_STARTED",
  "data": {}
}
```

Todos los campos son obligatorios. `playerId` también debe estar presente, aunque su valor sea `null`. `timestamp` usa RFC 3339/ISO 8601 con desplazamiento UTC, `sequence` empieza en 1 y debe avanzar sin saltos dentro de cada partida.

Reglas comunes:

- `eventId` es único para todo el servicio.
- `gameId` solo admite `CHESS`, `BASKETBALL` o `RACING`.
- `gameId`, `gameVersion` y `matchId` permanecen constantes durante la partida.
- `MATCH_STARTED` tiene `sequence: 1` y `playerId: null`.
- Los eventos de acción identifican un participante de la partida.
- `MATCH_FINISHED` tiene `playerId: null` y no se aceptan eventos posteriores.
- Las marcas de tiempo no pueden retroceder dentro de una partida.

## Ajedrez

### Inicio

```json
{
  "participants": [
    {"playerId": "p1", "color": "WHITE"},
    {"playerId": "p2", "color": "BLACK"}
  ],
  "initialClockMs": 300000,
  "incrementMs": 2000
}
```

Debe haber exactamente dos jugadores distintos y un jugador por color.

### Acciones

| `type` | Campos de `data` | Validaciones principales |
|---|---|---|
| `MOVE_PLAYED` | `moveNumber`, `piece`, `from`, `to`, `decisionTimeMs`, `clockBeforeMs`, `clockAfterMs` | Alternancia WHITE/BLACK, numeración continua, casillas `a1`–`h8` y consistencia del reloj con el incremento. |
| `PIECE_CAPTURED` | `moveEventId`, `capturedPiece`, `capturedPlayerId` | El movimiento existe, pertenece al capturador, solo recibe una captura y el capturado es el rival. |

Piezas: `KING`, `QUEEN`, `ROOK`, `BISHOP`, `KNIGHT`, `PAWN`. El rey no puede aparecer como pieza capturada.

### Final

```json
{"winnerPlayerId": "p1", "reason": "CHECKMATE"}
```

`reason` admite `CHECKMATE`, `RESIGNATION`, `DRAW` o `TIMEOUT`. Un empate exige `winnerPlayerId: null`; los demás motivos exigen un participante ganador.

## Baloncesto

### Inicio

```json
{
  "teams": [
    {"teamId": "home", "playerIds": ["h1", "h2"]},
    {"teamId": "away", "playerIds": ["a1", "a2"]}
  ],
  "periods": 4
}
```

Debe haber exactamente dos equipos. Cada jugador aparece una sola vez y no se implementan sustituciones.

### Acciones

| `type` | Campos de `data` | Validaciones principales |
|---|---|---|
| `SHOT_ATTEMPTED` | `teamId`, `period`, `periodTimeRemainingMs`, `shotValue`, `made` | `shotValue` es 1, 2 o 3; equipo y período válidos. |
| `ASSIST_RECORDED` | `teamId`, `period`, `scorerId`, `shotEventId` | Referencia una canasta de 2 o 3 puntos de un compañero en el mismo período. |
| `REBOUND_COLLECTED` | `teamId`, `period`, `reboundType`, `shotEventId` | Referencia un fallo; `OFFENSIVE` conserva el equipo y `DEFENSIVE` pertenece al rival. |
| `TURNOVER_COMMITTED` | `teamId`, `period`, `reason` | Equipo del jugador, período y motivo válidos. |
| `FOUL_COMMITTED` | `teamId`, `period`, `affectedPlayerId`, `reason` | El afectado es rival. La quinta falta se acepta y bloquea acciones posteriores del infractor. |

Un tiro solo puede tener una asistencia y un rebote asociados.

### Final

```json
{
  "scores": [
    {"teamId": "home", "points": 82},
    {"teamId": "away", "points": 79}
  ]
}
```

Se exige un marcador por equipo y cada valor debe coincidir con los tiros encestados aceptados por Go.

## Carreras

### Inicio

```json
{
  "driverIds": ["d1", "d2", "d3"],
  "plannedLaps": 10
}
```

Se requieren al menos dos pilotos distintos y una cantidad positiva de vueltas.

### Acciones

| `type` | Campos de `data` | Validaciones principales |
|---|---|---|
| `LAP_COMPLETED` | `lapNumber`, `lapTimeMs`, `position` | Vueltas continuas por piloto, tiempo positivo y posiciones no repetidas por vuelta. |
| `PIT_ENTERED` | `stopId`, `lapNumber`, `reason` | `stopId` único y vuelta válida. |
| `PIT_EXITED` | `stopId`, `lapNumber`, `tireCompound` | Entrada previa del mismo piloto, orden temporal correcto y una sola salida. |
| `PENALTY_APPLIED` | `penaltyType`, `value`, `reason`, `lapNumber` | Tipo `TIME` o `POSITION` y valor positivo. |

### Final

```json
{
  "classification": [
    {"playerId": "d1", "position": 1},
    {"playerId": "d2", "position": 2}
  ]
}
```

Todos los pilotos deben completar `plannedLaps`, no puede quedar una parada abierta y la clasificación contiene cada piloto y posición exactamente una vez. Scala será responsable de verificar analíticamente el orden después de aplicar tiempos y penalizaciones.

## Lote entregado a Scala

Al terminar una partida, Go envía o escribe:

```json
{
  "gameId": "CHESS",
  "gameVersion": "1.0",
  "matchId": "partida-1",
  "events": []
}
```

Si se usa HTTP, este objeto es el cuerpo de un `POST` a la URL completa configurada en `ANALYZER_URL`. Cualquier respuesta `2xx` confirma la entrega. Sin esa variable, cada objeto se escribe como una línea JSON independiente en `OUTPUT_PATH`.
