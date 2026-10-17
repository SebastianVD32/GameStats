# GameStats v1.0.0 — entrega para el profesor

Necesita Docker con Compose v2 (compatible con `up --wait`), contenedores Linux e internet. Imágenes `linux/amd64`: en ARM se necesita la emulación que ofrece Docker Desktop. No necesita descargar el repositorio ni instalar Go, Scala, Java o Python. Las imágenes deben haberse publicado como públicas en GHCR antes de distribuir este ZIP. Este paquete no contiene el código fuente.

Extraiga el ZIP en una carpeta y abra una terminal allí:

```console
docker compose up -d --wait
docker compose run --rm demo
```

La demo lleva sus datos dentro de la imagen. Envía eventos a Go y consulta Scala. Compara doce partidas y una carrera adicional con resultados esperados; verifica presión de tiempo, rachas, remontada, cadenas de fantasmas, casos inválidos, reenvíos y acumulados. Al terminar debe mostrar `DEMOSTRACION APROBADA` y devolver código 0. Los rechazos 400/409/422/502 comprobados por la demo son casos esperados, no fallos de la entrega.

Resultados de ejemplo: ajedrez gana Alice con blancas, 5 movimientos y 1 captura; baloncesto `demo-basket-2` termina 8–0 con racha de ocho puntos; carreras `demo-race-3` termina Erin → Alice → Dan tras aplicar la sanción; Pac-Man suma 1460, 60 y 3050 puntos. Alice tiene además una segunda partida igual: acumula 2920 puntos, mejor 1460. La carrera `demo-race-comeback` detecta una remontada.

## Consultas manuales

Abra estas direcciones en un navegador (devuelven JSON):

- Go disponible: http://localhost:8080/healthz
- Eventos agrupados: http://localhost:8080/api/v1/matches/demo-chess-1
- Scala disponible: http://localhost:8081/healthz
- Resultados: http://localhost:8081/api/v1/matches/demo-chess-1
- Otros ejemplos: `/api/v1/matches/demo-basket-2`, `/api/v1/matches/demo-race-3`, `/api/v1/matches/demo-pacman-3`, `/api/v1/matches/demo-race-comeback` en el puerto 8081.
- Acumulados Pac-Man: http://localhost:8081/api/v1/pacman/players/demo-pac-alice
- Victorias por color: http://localhost:8081/api/v1/chess/colors

Si los puertos están ocupados, defina `GAMESTATS_GO_PORT` y `GAMESTATS_SCALA_PORT` en un archivo `.env` en esta carpeta, por ejemplo 18080 y 18081, y cambie los puertos de estas direcciones. No son credenciales. La comunicación interna no cambia.

## Repetir y detener

Puede repetir `docker compose run --rm demo`: usa identificadores estables para sus partidas y comprueba los duplicados; los casos negativos tienen identificadores distintos en cada ejecución. No duplica resultados ni puntos. Reiniciar Go borra solo su estado temporal; Scala conserva resultados en el volumen de esta entrega.

```console
docker compose stop
docker compose up -d --wait
docker compose run --rm demo
```

Para quitar contenedores y red conservando resultados: `docker compose down`. Para empezar desde cero y eliminar **solo los datos de este proyecto de entrega**, ejecute desde esta carpeta:

```console
docker compose --profile demo down --volumes
```

No use comandos globales de limpieza. El proyecto se llama `gamestats-entrega-v1`; mantenga ese nombre al repetir. Para otra instalación independiente, añada el mismo `-p otro-nombre` a todos los comandos y use puertos diferentes.

Si falla la disponibilidad, ejecute `docker compose ps` y `docker compose logs --tail 100`. Un fallo al descargar indica imágenes aún no publicadas, privadas, falta de conexión o una versión inexistente. La entrega usa únicamente `image:` y nunca compila al arrancar.
