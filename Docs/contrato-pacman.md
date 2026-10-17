# Contrato de eventos Pac-Man

Referencia: `Definición de los eventos.docx` del repositorio. Go y Scala admiten PACMAN de extremo a extremo. El formato de entrada vigente se define en [el contrato Go](contrato-eventos-go.md). La verificación reproducible se describe en [el informe de integración](verificacion-integracion.md).

## Sobre comun

Se conservan `Event` y `MatchBatch`, con todas sus propiedades obligatorias y rechazo de propiedades desconocidas. Ejemplo de lote:

```json
{"gameId":"PACMAN","gameVersion":"1.0","matchId":"pacman-1","events":[]}
```

Cada evento contiene `eventId`, `timestamp` ISO 8601 UTC, `gameId`, `gameVersion`, `matchId`, `sequence`, `playerId`, `type` y `data`. Las identidades coinciden con el lote; los identificadores no estan vacios. `sequence` empieza en 1 y avanza sin saltos, determina el orden de procesamiento; los timestamps no retroceden. No se admiten eventId duplicados dentro de la partida. Go debe conservar su deduplicacion global existente.

## Inicio

`MATCH_STARTED`, primero y con `playerId: null` en el sobre:

```json
{"playerId":"p1","initialLives":3,"initialLevel":1}
```

Un jugador no vacio; initialLives e initialLevel enteros positivos. Todas las acciones llevan ese jugador en el sobre.

## Acciones

| type | Campos exactos de data | Regla |
|---|---|---|
| PELLET_EATEN | level, pelletId | +10 puntos |
| POWER_UP_COLLECTED | level, pelletId, durationMs | +50 puntos; duracion entera positiva |
| GHOST_EATEN | level, ghostId, powerUpEventId | +200, 400, 800, 1600 segun orden dentro del efecto |
| LIFE_LOST | level, cause, livesRemaining | disminuye exactamente una vida |
| LEVEL_COMPLETED | level | completa el nivel actual y avanza exactamente uno |

`level` es el nivel actual. `pelletId` es texto no vacio, unico dentro del nivel, incluso entre pastillas normales y de poder; puede reutilizarse en otro nivel. `cause` es texto no vacio, sin enumeracion adicional. No hay frutas ni vidas adicionales.

`ghostId` admite exactamente BLINKY, PINKY, INKY, CLYDE; cada uno una sola vez por efecto. La referencia powerUpEventId identifica una pastilla de poder anterior por sequence, de la misma partida y jugador. El intervalo valido incluye el timestamp inicial y excluye timestamp + durationMs. Una nueva pastilla reemplaza el efecto anterior y reinicia el orden de puntuacion. LIFE_LOST y LEVEL_COMPLETED cancelan el efecto. Al llegar a cero vidas solo se admite MATCH_FINISHED.

## Final

`MATCH_FINISHED`, último y con `playerId: null` en el sobre. El formato de Go es:

```json
{"score":3050,"reason":"GAME_OVER"}
```

`score` coincide con los puntos calculados. `reason` es texto no vacío: el documento fuente no define un catálogo ni condiciona sus valores al número de vidas. Scala deriva vidas y niveles de las acciones. No se inventan restricciones adicionales para este formato.

Por compatibilidad con los fixtures Scala anteriores, la entrada directa a Scala también admite:

```json
{"finalScore":0,"levelsCompleted":0,"livesRemaining":3,"reason":"SIMULATION_ENDED"}
```

Todos los valores coinciden con los calculados. NO_LIVES exige cero vidas; SIMULATION_ENDED exige al menos una.

## Resultados y almacenamiento

PacmanResult conserva gameId/gameVersion/matchId y expone playerId, normalPellets, powerPellets, ghosts, totalScore, livesLost, livesRemaining, levelsCompleted, durationMs y powerEffects. Cada efecto incluye powerUpEventId, ghostIds, ghostEventIds y chainEventId (null si no existe cadena). La deteccion se registra en el tercer fantasma distinto, una sola vez aunque haya cuatro; se conservan efectos sin capturas.

GET /api/v1/pacman/players/{playerId} calcula completedMatches, totalScore y bestScore sobre las partidas persistidas (bestScore null sin partidas). La funcion pura accumulated calcula lo mismo para un vector de resultados.

POST /api/v1/matches valida y almacena antes del 200; GET /api/v1/matches/{matchId} consulta el resultado. Un reenvio identico devuelve 200 sin acumular de nuevo; contenido distinto con el mismo matchId devuelve 409. matchId es unico para todo el almacenamiento. Errores de analisis: 422, cuerpo mayor de 4 MiB: 413, fallo de almacenamiento: 500; no se guarda un resultado parcial exitoso.

Un archivo JSON por partida contiene lote y resultado, con nombre SHA-256 del matchId. Escritura temporal, fsync y renombrado atomico; monitor para concurrencia y bloqueo exclusivo por directorio. Una instancia por volumen; no se requiere base de datos. Los acumulados se derivan de los registros, evitando dos escrituras inconsistentes. Un registro corrupto impide iniciar el servicio. En Windows no se fuerza el directorio; la imagen Linux si lo hace. El healthcheck comprueba HTTP y disponibilidad del almacenamiento.

## Integración Go implementada

1. Admitir PACMAN en los identificadores de juego sin cambiar el sobre.
2. Modelar los siete payloads exactos indicados arriba y rechazar campos desconocidos.
3. Registrar el jugador y configuracion inicial; validar jugador, niveles, pastillas onicas, vidas, puntuacion, motivos y referencias previas.
4. Mantener un efecto activo con expiracion, fantasmas consumidos y reinicio/cancelacion, respetando sequence y los limites del intervalo.
5. Incorporar tres partidas validas y casos invalidos de expiracion, reemplazo, interrupcion, referencias, duplicados, vidas agotadas y final incorrecto a sus pruebas de dominio/estado/ingestion.
6. Entregar el MatchBatch completo mediante HTTPSink existente al ANALYZER_URL configurado. Conservar deduplicacion y manejo de respuestas no 2xx/reintentos.

Los seis puntos anteriores están implementados en Go. Las acciones se envían a `POST /api/v1/events` y los lotes completos llegan por HTTP a Scala. Los fixtures Go y Scala usan los mismos matchId para contenidos distintos: no deben cargarse juntos en un volumen compartido. La verificación integral utiliza solo los fixtures Go y recursos nuevos por ejecución.
