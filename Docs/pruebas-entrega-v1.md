# Comprobación y uso de la entrega v1.0.0

## Estado comprobado

Se consultó GitHub, se descargaron sus referencias y se ejecutó `git pull --ff-only origin main`. No había cambios de código pendientes: `main`, `origin/main` y `v1.0.0` apuntan a `2cd3aaefbd4135f78d3c869e36316b3898e76aab`. No se hizo merge ni se crearon ramas.

- [Release publicada, no borrador](https://github.com/SebastianVD32/GameStats/releases/tag/v1.0.0).
- [Workflow de verificación y Release aprobado](https://github.com/SebastianVD32/GameStats/actions/runs/39087874451): pruebas Go, construcción, integración, paquete y descarga anónima.
- Evidencia de integración remota: 11 grupos aprobados, 24 resultados persistidos.
- Se descargaron los tres assets reales: ZIP, checksum y manifiesto de imágenes. El ZIP contiene únicamente `README.md` y `compose.yaml`.
- SHA256 del ZIP publicado: `a60a4985dab73f5f192342765e390b36652bc85e012fe21d1bd57c0101b46dd5`.
- Se ejecutó localmente el ZIP publicado en modo Registry: descarga obligatoria y anónima de las tres imágenes, revisión y digests iguales al manifiesto de la Release, arranque saludable, dos demostraciones y otra después de reiniciar ambos servicios; consulta HTTP desde el host aprobada. Cada demo comprobó 14 resultados, cuatro juegos, patrones, acumulados, reenvíos y casos inválidos.
- Resultado local: `failed: false`, `registry_download_verified: true`. Evidencia local en `.cache/distribution/gamestats-package-e364c76c62d3/`, excluida de Git. Sus contenedores, red y volumen temporales se eliminaron al finalizar.

Docker Desktop estaba detenido en el primer intento local; se inició y la ejecución completa posterior pasó. La evidencia del primer intento fallido se conserva por separado.

La distribución Docker está comprobada. No se verificó un servicio alojado en internet: los puertos de la entrega se publican solo en localhost. Tampoco existe en el ZIP un instalador, un ejecutable Windows o una interfaz gráfica; sus consultas devuelven JSON.

El repositorio sigue siendo privado. Sus imágenes pueden descargarse anónimamente, pero para obtener el asset de su Release se necesita acceso al repositorio. El equipo puede entregar directamente el ZIP al profesor sin incluir código ni credenciales.

## Pruebas de aceptación desde otra PC

1. Instalar y abrir Docker Desktop con contenedores Linux y Compose v2. Tener conexión a internet. Las imágenes son `linux/amd64`; ARM requiere emulación y no fue validado en hardware ARM. No se necesitan Go, Scala, Java, Python ni Git.
2. Descargar de la Release **`gamestats-v1.0.0.zip`**, junto con su checksum si se quiere verificar la descarga. No elegir los archivos automáticos "Source code". Si el profesor no tiene acceso al repositorio, entregarle directamente el ZIP y el checksum.
3. En PowerShell, comprobar el archivo antes de extraerlo:

   ```powershell
   (Get-FileHash .\gamestats-v1.0.0.zip -Algorithm SHA256).Hash.ToLower()
   ```

   Debe coincidir con el SHA256 indicado arriba y en el asset `.sha256`.

4. Extraerlo en una carpeta vacía fuera del repositorio. Abrir una terminal en esa carpeta y ejecutar cada comando, comprobando su resultado antes de continuar:

   ```powershell
   docker version
   docker compose version
   docker compose --profile demo pull
   docker compose up -d --wait
   docker compose ps
   docker compose run --rm demo
   $LASTEXITCODE
   ```

   `docker version` debe mostrar cliente y servidor. Los dos servicios deben figurar saludables. La demo debe terminar en `DEMOSTRACION APROBADA`, con 14 resultados y código `0`. Si falla, conservar `docker compose logs --tail 100` y detener la secuencia para revisar el error.

5. Consultar en el navegador:

   - Salud Go: <http://localhost:8080/healthz>.
   - Salud Scala: <http://localhost:8081/healthz>.
   - Ajedrez: <http://localhost:8081/api/v1/matches/demo-chess-1>.
   - Baloncesto: <http://localhost:8081/api/v1/matches/demo-basket-2>.
   - Carreras: <http://localhost:8081/api/v1/matches/demo-race-3>.
   - Remontada: <http://localhost:8081/api/v1/matches/demo-race-comeback>.
   - Pac-Man: <http://localhost:8081/api/v1/matches/demo-pacman-3>.
   - Acumulados: <http://localhost:8081/api/v1/pacman/players/demo-pac-alice>.

   Comprobar Alice ganadora con blancas, 5 movimientos y 1 captura; baloncesto 8–0 y racha de ocho puntos; carrera Erin → Alice → Dan; Pac-Man de Alice con dos partidas, 2920 puntos y mejor marca 1460. La demo compara automáticamente los resultados completos y todos los patrones; estos ejemplos facilitan la revisión manual.

6. Verificar repetición y persistencia. Ejecutar y comprobar código `0` en cada demo:

   ```powershell
   docker compose run --rm demo
   docker compose restart go-ingestion scala-analysis
   docker compose up -d --wait
   docker compose run --rm demo
   ```

   Los acumulados deben conservarse, sin duplicados. Los errores HTTP provocados deliberadamente por la demo son casos negativos esperados si la comprobación termina aprobada.

7. Detener conservando los resultados:

   ```powershell
   docker compose down
   ```

   Para comprobar una instalación limpia, borrar exclusivamente los datos de esta entrega desde la misma carpeta y repetir el paso 4:

   ```powershell
   docker compose --profile demo down --volumes
   ```

Conservar captura de `docker compose ps`, salida de la demo, código de salida y consultas manuales como evidencia de la prueba en la segunda PC. Esa prueba física no está sustituida por la verificación local o de GitHub Actions.

Si los puertos están ocupados, usar el `.env` descrito en el README del paquete. Mantener el mismo nombre de proyecto al repetir. No ejecutar comandos globales de limpieza Docker.

## Verificación adicional del mantenedor

Desde el repositorio y con Docker disponible, volver a comprobar el ZIP publicado descargado:

```powershell
./scripts/Verify-Distribution.ps1 -Mode Registry -Archive .cache/release-audit/v1.0.0/gamestats-v1.0.0.zip -ExpectedRevision 2cd3aaefbd4135f78d3c869e36316b3898e76aab
```

El archivo bajo `.cache` es local: en otra máquina descargar primero el asset y ajustar `-Archive` a su ubicación. Para volver a ejecutar todas las pruebas de desarrollo, incluidas Go, Scala e integración HTTP, usar `./scripts/Verify-Integration.ps1` desde el repositorio o revisar/repetir CI. El profesor solo necesita la demo del paquete.

## Siguiente paso para ejecutar con doble clic

La entrega actual ya evita copiar el repositorio: solo se distribuye el ZIP. Para Windows, el siguiente cambio práctico es preparar **otra versión** del paquete con lanzadores `Iniciar.cmd`, `Demostrar.cmd` y `Detener.cmd` junto a Compose. No están implementados ni publicados en v1.0.0.

El lanzador de inicio debe localizar su propia carpeta, comprobar Docker/Compose y el motor, ejecutar `docker compose up -d --wait`, detectar errores y abrir una dirección de resultados solo cuando esté disponible. El de demostración debe mostrar y conservar el resultado y el código de salida; detener debe conservar los datos por defecto. Conviene añadir una página local legible si se quiere una experiencia de aplicación en lugar de JSON. Validar rutas con espacios, Docker detenido o ausente, puertos ocupados, falta de internet y repeticiones en una segunda PC Windows.

Después, actualizar el empaquetador y sus validaciones para incluir esos archivos, probar el nuevo ZIP y publicar una nueva Release mediante CI/CD sin sobrescribir v1.0.0. Un `.cmd` ofrece doble clic manteniendo Docker como requisito; no equivale a un binario autónomo.

Para acceso mediante un enlace desde cualquier PC **sin instalar Docker**, hace falta alojar el sistema en un servidor y ofrecer una interfaz web con HTTPS y almacenamiento persistente. Es un trabajo adicional y requiere decidir alojamiento y acceso antes de desplegar. No se ha realizado ese despliegue. Un `.exe` autónomo también requiere otro empaquetado, incluyendo el runtime Java y la coordinación de ambos procesos; renombrar el ZIP o el script no lo proporciona.

## Límites de la validación del proyecto completo

La entrega aprobada no demuestra por sí sola cumplimiento de toda la rúbrica académica. `Docs/verificacion-integracion.md` documenta pendientes que deben contrastarse con el enunciado: contar sanciones de carreras por tipo, además de sus valores acumulados, y revisar el DOCX/PDF para reflejar los cuatro juegos. No se modificaron las analíticas ni esos documentos en esta revisión. Las menciones anteriores a publicación y CI pendientes describen una etapa histórica; la Release y las verificaciones actuales están enlazadas arriba.

Go conserva estado temporal de ingestión; Scala persiste resultados. No se certifican recuperación ante cortes eléctricos ni varias réplicas Scala escribiendo en el mismo volumen.
