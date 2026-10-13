# Scala Pac-Man, servicio HTTP y Docker

## Cambios

PacmanAnalytics anade analisis puro, modelos y codecs estrictos, puntuaciones sin overflow (BigInt), cadenas por efecto y acumulados por jugador. Analysis centraliza la seleccion de los cuatro analizadores. Main conserva CLI y ejecuta HTTP sin argumentos (o con `serve`). ResultStore persiste lotes y resultados atomicamente, valida registros al iniciar, serializa peticiones concurrentes y evita duplicados/conflictos.

La imagen multietapa ejecuta pruebas y stageRuntime, incorpora JAR y dependencias, y arranca Java directamente. Usa usuario 65532, escucha 0.0.0.0:8081, volumen scala-results y healthcheck HTTP real. Compose conserva gamestats-data y conecta Go con scala-analysis despues de estar saludable. El entorno scala-dev y sus caches continuan disponibles.

Archivos modificados/anadidos:

- `compose.yaml`, `README.md`.
- `scala-analytics/Dockerfile`, `.dockerignore`, `README.md`.
- `scala-analytics/src/main/scala/gamestats/{Model,JsonCodec,Main,PacmanAnalytics,AnalysisServer}.scala`.
- `scala-analytics/src/test/scala/gamestats/PacmanAnalyticsSuite.scala`.
- `scala-analytics/testdata/pacman-{1,2,3}.json` y sus `.expected.json`.
- `scala-analytics/scripts/Test-HttpIntegration.ps1` y ajuste de `Test-GoFileIntegration.ps1` para seguir usando la salida por archivo con ANALYZER_URL vacio.
- `Docs/contrato-pacman.md` y este documento.

No se modificaron logica Go ni GitHub Actions. No hubo push, merge ni despliegue.

## PowerShell: pruebas, empaquetamiento y contenedores

Desde `Y:\proyecto-lenguajes\GameStats`, con Docker Desktop ejecutondose y contenedores Linux habilitados:

```powershell
Set-Location 'Y:\proyecto-lenguajes\GameStats'
docker compose -f .\scala-analytics\compose.yaml run --rm scala-dev sbt test stageRuntime
docker compose build
docker compose up -d --wait
docker compose ps
curl.exe --fail-with-body http://localhost:8081/healthz
```

El build Scala tambion ejecuta las pruebas; no requiere Java, Scala ni sbt en Windows. El modulo de desarrollo conserva sus volumenes de cache.

## Pac-Man con curl.exe o Postman

Desde la raiz del repositorio:

```powershell
curl.exe --fail-with-body -H "Content-Type: application/json" --data-binary "@scala-analytics/testdata/pacman-1.json" http://localhost:8081/api/v1/matches
curl.exe --fail-with-body -H "Content-Type: application/json" --data-binary "@scala-analytics/testdata/pacman-2.json" http://localhost:8081/api/v1/matches
curl.exe --fail-with-body -H "Content-Type: application/json" --data-binary "@scala-analytics/testdata/pacman-3.json" http://localhost:8081/api/v1/matches
curl.exe --fail-with-body http://localhost:8081/api/v1/matches/pacman-3
curl.exe --fail-with-body http://localhost:8081/api/v1/pacman/players/p1
# Reenviar pacman-2 no incrementa completedMatches ni totalScore.
curl.exe --fail-with-body -H "Content-Type: application/json" --data-binary "@scala-analytics/testdata/pacman-2.json" http://localhost:8081/api/v1/matches
docker compose restart scala-analysis
docker compose up -d --wait
curl.exe --fail-with-body http://localhost:8081/api/v1/matches/pacman-3
```

En Postman: POST `http://localhost:8081/api/v1/matches`, Body -> raw -> JSON, pegar el archivo completo; Content-Type application/json. Consultar con GET las mismas URL anteriores.

| Partida | Jugador | Normales | Poder | Fantasmas | Puntos | Vidas perdidas/restantes | Niveles | Duracion ms | Cadenas |
|---|---|---:|---:|---:|---:|---|---:|---:|---:|
| pacman-1 | p1 | 2 | 1 | 0 | 70 | 0 / 3 | 0 | 4000 | 0 |
| pacman-2 | p1 | 0 | 1 | 3 | 1450 | 0 / 3 | 0 | 5000 | 1 |
| pacman-3 | p2 | 1 | 2 | 4 | 3110 | 2 / 1 | 2 | 12000 | 1 |

Celculos independientes: 2*10+50=70; 50+200+400+800=1450; 2*50+10+200+400+800+1600=3110. p1 acumula 1520, mejor 1450, dos partidas; p2 acumula 3110, mejor 3110, una partida. pacman-1 y el segundo efecto de pacman-3 no tienen capturas. Los JSON expected incluyen cada efecto y evento de deteccion.

## Verificacion automatizada HTTP

Desde la raiz, en un entorno sin resultados previos que entren en conflicto con estos matchId:

```powershell
.\scala-analytics\scripts\Test-HttpIntegration.ps1
```

Compara los resultados Pac-Man completos, reenvios, acumulados y persistencia tras reiniciar el contenedor. Envia eventos a Go y verifica ocho resultados almacenados en Scala: tres ajedrez, tres baloncesto y dos carreras. El fixture Go race-3 tiene clasificacion final inconsistente: el script comprueba 422 al enviarlo directamente a Scala. Se conserva esa limitacion documentada; no se cambia Go ni se anuncia integracion Pac-Man a traves de Go.

Para conservar la verificacion anterior por archivo:

```powershell
.\scala-analytics\scripts\Test-GoFileIntegration.ps1
```

## Pruebas ejecutadas el 13 de octubre de 2026

- `go test ./...`, desde go-ingestion, con GOCACHE apuntando a `.cache/go-build`: todos los paquetes pasaron.
- sbt 1.11.1, Scala 3.3.6 y JDK 24 existente: `test stageRuntime`; 45 pruebas pasaron (10 ajedrez, 11 baloncesto, 14 carreras, 10 Pac-Man). Compilacion y empaquetamiento de aplicacion y dependencias correctos.
- Servicio JVM compilado en localhost:8081: healthz 200; POST de las tres partidas y reenvios comparados con expected; acumulados correctos.
- Go sin modificar: `go run ./cmd/gamestats -input ../scala-analytics/output/http-valid-events.jsonl -serve=false -analyzer-url http://localhost:8081/api/v1/matches` desde go-ingestion. El archivo temporal contiene los 40 eventos existentes excluyendo race-3: ocho entregas HTTP correctas.
- Reinicio real del proceso JVM con el mismo directorio: once resultados persistidos recuperados, comparados con todos los expected disponibles; p1 sigue con dos partidas. race-3 rechazado con 422.
- Prueba de almacenamiento con 20 reenvios concurrentes, conflicto de matchId y reapertura: paso.
- `docker compose config --quiet` y `git diff --check`: pasaron.

Limitaciones reales: el cliente Docker existe pero no hay conexion al motor (`docker_engine` ausente). No se construyo ni ejecuto la imagen, ni se comprobo persistencia tras reiniciar un contenedor. La ejecucion local uso JDK 24; la imagen usa Java 21. Los comandos anteriores y Test-HttpIntegration.ps1 cubren la verificacion pendiente de Docker. El servicio es una opcion sencilla para un directorio y una instancia; no ofrece autenticacion ni coordinacion de meltiples replicas. Go todavia no soporta PACMAN; sus tareas exactas estan en [el contrato](contrato-pacman.md#pendientes-concretos-de-go).
