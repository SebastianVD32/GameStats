# Panel web de GameStats — candidato v1.1.0

El panel está implementado para revisión local. No se han publicado imágenes v1.1.0 ni una nueva Release. La Release v1.0.0 se conserva. El ZIP candidato usa referencias GHCR nuevas que deben publicarse antes de entregarlo al profesor; la prueba local usa nombres locales diferentes y no demuestra publicación.

## Abrir la instalación de revisión

Desde PowerShell:

```powershell
Set-Location 'Y:\proyecto-lenguajes\GameStats'
$env:GAMESTATS_GO_PORT = '18080'
$env:GAMESTATS_SCALA_PORT = '18081'
$env:GAMESTATS_PANEL_PORT = '8090'
docker compose -p gamestats-panel-review up -d --build --wait
```

Abrir **http://localhost:8090**. Estos puertos conservan la entrega v1.0.0 que ya ocupa 8080/8081; los proyectos y volúmenes son independientes. Go de revisión: http://localhost:18080/healthz; Scala: http://localhost:18081/healthz. Si se eligen otros puertos, mantenerlos en los siguientes comandos. Los puertos del host no cambian la comunicación entre servicios.

1. Revisar Go y Scala **Disponibles**. Es una comprobación de disponibilidad, no de estadísticas.
2. Pulsar **Ejecutar demostración**. Se muestran eventos aceptados/rechazados, etapas aprobadas y pendientes. El servidor rechaza una segunda ejecución simultánea con 409, incluso desde otra pestaña.
3. Al finalizar **Aprobada**, filtrar por juego y pulsar **Ver resultado**. Abrir los detalles de jugadores, tiros, boxes o efectos de poder para consultar sus tablas. Los nulos son “Sin datos” o “Sin intentos”.
4. Revisar **Patrones y evidencia del backend** y **Verificaciones independientes**. Las tablas incluyen escenario, métrica, esperado, obtenido y aprobado/fallido. El filtro permite ver fallos o rechazos esperados. No se confunde un HTTP exitoso con igualdad estadística.
5. Actualizar el navegador y usar el historial. Repetir genera otros 14 resultados, con IDs y jugadores propios, sin borrar los anteriores. Los acumulados Pac-Man se mantienen independientes por ejecución; las victorias por color son globales de todas las partidas almacenadas.

Para ejecutar la demo automática por terminal desde la misma raíz y con los mismos puertos:

```powershell
docker compose -p gamestats-panel-review build demo
docker compose -p gamestats-panel-review run --rm demo
```

La demo de terminal conserva los IDs `demo-*` y sus reintentos estables. El panel usa `web-<UUID>-*`. Para detener conservando datos: `docker compose -p gamestats-panel-review down`. Solo si se desea borrar los datos de esta instalación de revisión: `docker compose -p gamestats-panel-review --profile demo down --volumes`. No se elimina la entrega v1.0.0.

## Arquitectura y límites

- `panel/server.py`: servidor HTTP Python estándar, sin dependencias externas en runtime. Sirve recursos locales y rutas del mismo origen; el navegador no usa nombres internos Docker.
- `panel/static/{index.html,style.css,app.js}`: HTML, CSS y JavaScript en español, fondo claro, etiquetas, estados de carga/error/vacío y tablas desplegables. No usa CDN.
- `distribution/demo/demo.py`: conserva la demo de terminal y permite prefijo/observador para la web. Los fixtures y archivos `*.expected.json` siguen siendo expectativas fijas independientes de las respuestas. Actualiza IDs y referencias internas, incluidas clasificaciones y eventos relacionados. Emite comparaciones por campo y progreso basado en solicitudes y comprobaciones reales.
- Scala sigue calculando todas las estadísticas; Go sigue validando e ingiriendo. No se agregaron endpoints a Go o Scala ni se reimplementaron sus analizadores.
- Un proceso del panel ejecuta como máximo una demo. No utiliza shell, subprocess, socket Docker, URLs de destino suministradas por el usuario ni eventos personalizados enviados desde la web.
- Historial en `/data/panel`, volumen nombrado `panel-history`, con escrituras atómicas. Un reinicio marca ejecuciones incompletas como interrumpidas, sin presentarlas como aprobadas. Scala conserva sus resultados en su propio volumen.
- El catálogo lista partidas creadas por las ejecuciones del panel, no descubre partidas externas ni migraciones previas. Los resultados seleccionados se vuelven a consultar en Scala; el historial de comprobaciones es una evidencia de la ejecución pasada, no una nueva prueba al recargar.
- Los IDs de eventos de presión, rachas y cadenas proceden del backend. Carreras devuelve el indicador de remontada y clasificación, pero no eventos de evidencia; el panel lo declara. Las sanciones se muestran como tiempo y posiciones acumuladas, sin inventar conteos por tipo.
- El panel está diseñado para uso local. Sus puertos se enlazan a localhost; no incluye autenticación para exposición pública. Las comprobaciones de origen protegen el comando del panel contra solicitudes desde otros sitios.

## Rutas del panel

| Método y ruta | Función |
|---|---|
| `GET /`, `/app.js`, `/style.css` | Interfaz y recursos locales |
| `GET /healthz` | Disponibilidad del panel |
| `GET /api/health` | Disponibilidad separada de Go y Scala |
| `GET /api/runs` | Historial resumido |
| `POST /api/runs` | Inicia escenarios fijos; exige cabecera `X-GameStats: panel`, cuerpo vacío y origen compatible; devuelve 202 o 409 |
| `GET /api/runs/{id}` | Progreso, catálogo, solicitudes y comparaciones de la ejecución |
| `GET /api/results/{id}` | Proxy de resultados Scala `/api/v1/matches/{id}` |
| `GET /api/events/{id}` | Proxy de eventos Go `/api/v1/matches/{id}` |
| `GET /api/players/{id}` | Proxy de acumulados Pac-Man Scala |
| `GET /api/colors` | Proxy de victorias por color Scala |

## Pruebas reproducibles

Desde la raíz del repositorio:

```powershell
python -m unittest discover -s panel -p test_panel.py
python -m unittest discover -s scripts -p test_registry_version.py
python scripts/package_distribution.py
./scripts/Verify-Distribution.ps1 -Mode Local
```

El último comando extrae el ZIP en una carpeta sin fuentes ni montajes del repositorio, construye cuatro imágenes con nombres exclusivos de prueba, arranca los servicios en puertos libres, ejecuta dos demos web, valida 28 resultados distintos y las expectativas, comprueba errores y bloqueo simultáneo, reinicia el panel y revisa historial, ejecuta tres demos de terminal incluyendo reinicios y consulta HTTP desde el host. Limpia solo sus recursos. La construcción Scala puede reutilizar el cache de las pruebas y runtime existentes: no se modificaron sus fuentes.

Prueba de navegador opcional local (solo herramientas del mantenedor; no se incluyen en el paquete ni las imágenes):

```powershell
npm install --prefix .cache/panel-browser --no-audit --no-fund playwright@1.64.1
$env:PLAYWRIGHT_MODULE = Join-Path (Get-Location) '.cache/panel-browser/node_modules/playwright'
$env:PLAYWRIGHT_CHANNEL = 'msedge'
node scripts/test_panel_browser.cjs http://127.0.0.1:8090 .cache/panel-browser-evidence
```

Se comprobó con Edge headless instalado: ejecución, bloqueo del botón, cuatro filtros/juegos, resultados reales, nulos, rechazos, recarga, repetición e historial anterior; sin errores JavaScript ni solicitudes a dominios externos. La prueba automatizada también simula un error HTTP para la interfaz. Además se detuvo Scala en el proyecto de revisión y se comprobó en Edge la indisponibilidad y el error real de consulta, restaurando después el servicio.

Para incluir el navegador en toda la prueba del ZIP, ejecutar `./scripts/Verify-Distribution.ps1 -Mode Local` con las variables anteriores. El CI instala Playwright aislado y Chromium, y ejecuta esa prueba. Conserva logs, JSON de comprobaciones y captura del navegador en `.cache/distribution/gamestats-package-*/`, que se sube como evidencia del candidato.

## Publicación pendiente, únicamente tras autorización

Archivos de distribución: `dist/gamestats-v1.1.0.zip` y su `.sha256`; el ZIP contiene solo `README.md` y `compose.yaml`. Las referencias fijas son:

```text
ghcr.io/sebastianvd32/gamestats-go:v1.1.0
ghcr.io/sebastianvd32/gamestats-scala:v1.1.0
ghcr.io/sebastianvd32/gamestats-demo:v1.1.0
ghcr.io/sebastianvd32/gamestats-panel:v1.1.0
```

El Compose de entrega no contiene `build:` ni rutas del repositorio. Incluye healthchecks, dependencias saludables y volúmenes de resultados/historial. El profesor solo necesitará Docker con Compose e internet; abre http://localhost:8090 después de `docker compose up -d --wait` desde el ZIP extraído. Conserva `docker compose run --rm demo`.

Tras revisar y autorizar la publicación: subir los cambios con commits en inglés, aprobar el CI, ejecutar `publish-images` del workflow de Release para **esta nueva versión**, poner también el paquete nuevo `gamestats-panel` en Public y ejecutar `verify-and-release` en el mismo commit. El workflow comprueba que las etiquetas no existan antes de publicar; no sobrescribe v1.0.0. La prueba Registry exige descarga anónima y la revisión autorizada antes de crear la Release v1.1.0 con ZIP, checksum y `images-v1.1.0.json`.

Pendientes reales: CI remoto de estos cambios, autorización/publicación de las cuatro imágenes v1.1.0, descarga anónima de la nueva versión, nueva Release y prueba física en otra PC. Las pruebas locales no acreditan esos pasos. No se hizo merge ni despliegue en nube.
