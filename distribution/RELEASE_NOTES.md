# GameStats v1.0.0

Descargar **gamestats-v1.0.0.zip**, extraerlo y ejecutar:

```console
docker compose up -d --wait
docker compose run --rm demo
```

Requisitos: Docker con Compose v2, contenedores Linux con soporte linux/amd64 e internet. No requiere el repositorio ni instalaciones de Go, Scala, Java o Python. El ZIP solo contiene Compose y README en español; no seleccionar los archivos automáticos “Source code” de GitHub para esta modalidad.

Las imágenes GHCR se verifican mediante descarga anónima antes de crear esta Release. La demo comprueba los cuatro juegos, resultados completos, patrones positivos/negativos, casos inválidos y repetición sin duplicar acumulados. `images-v1.0.0.json` identifica los digests y revisiones verificados; el archivo `.sha256` permite verificar el ZIP.

El repositorio mantiene su visibilidad original. Si es privado, el profesor puede recibir el ZIP directamente del equipo; las imágenes públicas se descargan sin credenciales. No se realiza un despliegue externo.
