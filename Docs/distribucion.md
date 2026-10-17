# Distribución ejecutable de GameStats

## Estado de esta preparación

Versión candidata **v1.0.0**, basada en `7af2b86` con los cambios locales de distribución. Al revisar GitHub no existían tags remotos ni Releases. No se hizo commit, push, merge, publicación en GHCR, creación de Release ni cambio de visibilidad del repositorio durante esta preparación.

**La distribución funciona con imágenes construidas localmente. La descarga pública final todavía NO está verificada.** El intento anónimo real contra GHCR devolvió `denied`; no se interpreta como una publicación exitosa ni permite afirmar si el nombre está ausente o es privado.

## Contenido exacto propuesto para publicación

| Imagen GHCR | Contenido ejecutable | Plataforma |
|---|---|---|
| `ghcr.io/sebastianvd32/gamestats-go:v1.0.0` | Binario Go, sonda HTTP de salud y certificados; sin compilador ni shell | `linux/amd64` |
| `ghcr.io/sebastianvd32/gamestats-scala:v1.0.0` | JVM 21, JAR, dependencias y curl para salud; sin sbt ni compilación al iniciar | `linux/amd64` |
| `ghcr.io/sebastianvd32/gamestats-demo:v1.0.0` | Python incluido, datos, expectativas y demo HTTP automática; sin instalaciones en el host | `linux/amd64` |

Se eligió AMD64 porque la imagen sbt existente solo ofrece esa arquitectura. Docker Desktop en ARM puede usar emulación; no se declara soporte ARM nativo. Las bases de los tres Dockerfiles están fijadas por digest. Las etiquetas de entrega tienen versión exacta, no `latest`; el workflow rechaza reutilizar etiquetas existentes. Para una nueva entrega deben actualizarse conjuntamente `release.json`, Compose, README y notas de Release.

Release propuesta: **GameStats v1.0.0**, tag `v1.0.0`, en `SebastianVD32/GameStats`. Assets:

- `gamestats-v1.0.0.zip`: **solo `compose.yaml` y `README.md`** en español, sin fuentes ni secretos.
- `gamestats-v1.0.0.zip.sha256`: checksum del ZIP.
- `images-v1.0.0.json`: digests y revisiones efectivamente descargados del registro; se generará al completar la prueba pública, no antes.

El candidato local está en `dist/gamestats-v1.0.0.zip`. SHA-256 observado: `73e92d4ca1f4bdf7549c64360365cb0c112cc32d45b8121fe8a5e3638496b1a0`. La reconstrucción produjo exactamente el mismo checksum.

## Pruebas realizadas

- `go test ./...` y `go vet ./...`: aprobados, incluidas las pruebas de la sonda HTTP ante servicio saludable, no saludable y ausente.
- Construcción local de las tres imágenes; arranque Go y Scala con healthchecks reales. Los Dockerfiles Scala conservan `sbt test stageRuntime` durante la construcción; esta preparación reutilizó la capa de pruebas ya aprobada para las mismas fuentes Scala.
- `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Verify-Distribution.ps1 -Mode Local`: aprobado. ZIP extraído en `.cache/distribution/gamestats-package-4627fde9c01c/package`, sin fuentes ni montajes del repositorio durante la ejecución. Se cambiaron únicamente nombres de proyecto/imágenes y puertos para aislar la prueba; no se hizo pasar una etiqueta local por una imagen publicada.
- Demo completa ejecutada dos veces y otra después de reiniciar ambos servicios: 14 resultados completos, cuatro juegos, patrones positivos/negativos, diez fixtures inválidos y clasificación inconsistente, reenvíos concurrentes, acumulados sin duplicados. Consulta HTTP desde el host aprobada. Solo se eliminaron recursos de los proyectos de prueba.
- Evidencia local: `.cache/distribution/gamestats-package-4627fde9c01c/{summary.json,docker.log,services.log}`. Su resumen indica `mode: Local`, `failed: false`, `registry_download_verified: false`.
- Prueba real con cliente Docker sin credenciales y pull obligatorio: `.cache/distribution/gamestats-package-ff2d4d2e3a9b/summary.json`; falló por `denied` de GHCR, sin crear contenedores. Se comprobó que no podía aprobar utilizando imágenes locales.
- `actionlint:1.7.7`, análisis sintáctico de PowerShell/Python, `git diff --check` y validación Compose: aprobados. Los workflows nuevos aún no se ejecutaron en GitHub; no están publicados.
- Tres pruebas de la protección de publicación: rechaza una etiqueta encontrada en páginas posteriores de la API y errores de permisos/red; permite un paquete nuevo o una etiqueta todavía no usada. El preflight utiliza la API de Packages con el token efímero del workflow, no considera un `denied` genérico del registro como prueba de inexistencia.

Durante la preparación se corrigieron el descubrimiento del binario/plugin Docker en Windows, la inclusión del perfil demo al validar Compose y la referencia `affectedPlayerId` al separar los datos de demostración. Se repitieron las comprobaciones afectadas.

## Workflow preparado y autorizaciones

El CI existente agrega `distribution-verification`: construye el ZIP y las tres imágenes locales, ejecuta la demo repetida y conserva el candidato/evidencia como artefacto. No publica en GHCR.

El workflow manual `GameStats versioned distribution` tiene tres operaciones:

1. `prepare` (predeterminada): ejecuta las pruebas reutilizando el workflow CI completo; no publica.
2. `publish-images`: **solo después de autorización del propietario**, vuelve a probar y publica las tres referencias indicadas. Usa exclusivamente `GITHUB_TOKEN` con `contents: read` y `packages: write` en ese job. No crea Release ni cambia visibilidad. Si una etiqueta existe, se detiene para evitar sobrescribirla. Si una publicación se interrumpe tras publicar una parte, no se deben borrar ni sobrescribir paquetes automáticamente: revisar lo publicado y preparar otra versión o completar la recuperación de forma explícita.
3. `verify-and-release`: **solo después de autorización de Release y de hacer públicas las imágenes**, descarga anónimamente y prueba el ZIP; verifica que las tres imágenes correspondan al SHA que ejecuta el workflow, registra digests y crea la Release/asset. Tiene `contents: write` solo en ese job; no inicia sesión en GHCR ni solicita credenciales al profesor. Si falla la descarga o la demo, no crea la Release.

No hay disparadores de publicación por push o tag. Las operaciones de publicación se ejecutan manualmente sobre `main`; se necesita subir primero estos archivos a GitHub, sin hacer merge ni desplegar infraestructura. Ejecutar ambos pasos de publicación sobre la misma revisión; si cambia el código entre ellos, el control de revisión rechaza imágenes antiguas. No se configuraron secretos ni tokens personales en archivos.

## Hacer públicas las imágenes sin hacer público el repositorio

Tras publicar, el propietario con permiso de administración de los paquetes debe abrir su perfil **Packages**, elegir cada `gamestats-go`, `gamestats-scala` y `gamestats-demo`, entrar en **Package settings** y cambiar la visibilidad del **paquete** a **Public**. Revisar, si corresponde, la herencia de permisos y **Manage Actions access** para que este repositorio pueda publicar versiones futuras. No cambiar la visibilidad de `SebastianVD32/GameStats`.

GitHub permite acceso anónimo a imágenes públicas de GHCR y visibilidad independiente del paquete. Referencias oficiales: [permisos y visibilidad](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility), [Container registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).

Comprobación final para el mantenedor, desde la raíz y sin iniciar sesión:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Verify-Distribution.ps1 -Mode Registry
```

Este modo usa un directorio Docker con `auths` vacío, conserva solo la ubicación del plugin Compose y el endpoint local del motor, fuerza la descarga de las tres imágenes y comprueba sus RepoDigests. No cambia las credenciales guardadas del usuario. Requiere el motor local estándar de Docker Desktop o un socket Unix; no certifica contextos Docker remotos con TLS personalizado.

Una Release de un repositorio privado solo es accesible a personas con acceso al repositorio. Por ello, el equipo puede descargar su asset y entregar **directamente el ZIP** al profesor; este podrá ejecutar imágenes públicas sin cuenta ni código fuente. El repositorio conserva su visibilidad original. [Acceso a Releases — GitHub](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases).

## Uso del profesor, después de la publicación y prueba pública

Extraer el ZIP y ejecutar allí:

```console
docker compose up -d --wait
docker compose run --rm demo
```

Resultado final esperado: `DEMOSTRACION APROBADA` y código 0. Consultar `http://localhost:8081/api/v1/matches/demo-chess-1` o `/api/v1/pacman/players/demo-pac-alice`. Repetir el mismo comando demo no duplica resultados. Para detener: `docker compose down`. Para borrar solo los datos de esa entrega: `docker compose --profile demo down --volumes` desde la misma carpeta y proyecto. El README del ZIP incluye las demás consultas y puertos alternativos.

## Archivos de implementación

- `distribution/{release.json,compose.yaml,README.md,RELEASE_NOTES.md}`.
- `distribution/demo/{Dockerfile,demo.py,fixtures/race-comeback.json,fixtures/race-comeback.expected.json}`.
- `scripts/{package_distribution.py,Verify-Distribution.ps1,check_registry_version.py,test_registry_version.py}`.
- `go-ingestion/cmd/healthcheck/{main.go,main_test.go}` y Dockerfiles Go/Scala.
- `.github/workflows/{ci-cd.yml,release.yml}`, `.gitignore`, README y documentación CI/distribución.

Pendientes reales: autorización de publicación, subir los cambios, ejecutar los workflows remotos, publicar y hacer públicas las imágenes, aprobar la verificación anónima final y crear/entregar la Release/ZIP. Esta preparación no es un despliegue en un servicio de nube y no cambia los pendientes analíticos previamente documentados del proyecto.
