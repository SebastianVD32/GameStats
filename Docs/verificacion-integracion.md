# Verificación de integración de GameStats

**Estado general: PARCIAL. Integración local: APROBADA.** Fecha: 16 de octubre de 2026, Costa Rica.

## Punto de partida

Commit `f8c533190997dbb7c0f3e64eadda9bc4f0e388e3`, rama `feat/scala-docker-chess`. Existían un README modificado, el script HTTP sin seguimiento y `go-ingestion/gamestats.exe`. Se conservó el contenido útil del README y el binario (ahora ignorado). El script anterior se respaldó en `scala-analytics/output/http-script-backup-f49b8681b7d34ed3ac112197be4526f9.ps1`; su entrada habitual ahora invoca la verificación aislada. No se cambiaron ramas ni se hicieron commits, merge, push o despliegues.

Se revisaron el DOCX `Definición de los eventos`, contratos, README, Dockerfiles, Compose, código y workflow. No se encontró `AGENTS.md` en el repositorio ni en sus padres examinados, ni un enunciado general independiente con rúbrica de entrega/deployment. El PDF del documento no se extrajo independientemente; no se certifica su concordancia editorial.

## Comando único

Con Docker Desktop en modo Linux, desde la raíz:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Verify-Integration.ps1
```

No necesita Go/Java/Scala locales. El script deriva la configuración del Compose raíz; usa proyecto, red, imágenes y volúmenes propios y puertos dinámicos en localhost. Redescubre los puertos tras reiniciar. Limita las esperas y solicitudes, devuelve 1 ante fallos y elimina solo sus recursos en `finally`; conserva imágenes base y caches. PowerShell 7 puede ejecutarlo con `./scripts/Verify-Integration.ps1`.

## Resultados observados

Ejecución completa `gamestats-verify-ef0eab218599`: **11 grupos aprobados, `failed: false`, código 0**. Evidencia: `scala-analytics/output/gamestats-verify-ef0eab218599/` (`summary.json`, `execution.log`, `services.log`, `actual-results.json` y Compose efectivo).

| Comprobación | Resultado |
|---|---|
| Go en Linux: formato, vet, pruebas con `-race -coverprofile`, compilación | Aprobado; siete paquetes examinados, sin fallos |
| Ingestión por archivo Go | 67 eventos, 12 lotes; diez fixtures inválidos rechazados |
| Scala: `sbt test stageRuntime` dentro del Dockerfile | 47 pruebas aprobadas; JAR y dependencias empaquetados. Ejecución observada en la preparación; construcciones posteriores reutilizaron ese cache con las mismas fuentes |
| Compose y construcción de ambas imágenes | Aprobado; servicios listos y comunicación interna Go → Scala |
| API Go con eventos intercalados de los cuatro juegos | Doce resultados completos iguales a expectativas fijas independientes |
| Errores HTTP | Duplicados, campos faltantes, referencias inválidas y diez fixtures negativos rechazados; sin resultados parciales |
| Doce partidas adicionales concurrentes | Orden conservado dentro de cada partida; resultados completos y acumulados correctos |
| Reenvíos por Go tras reiniciar su proceso | Sin duplicar resultados ni acumulados Scala |
| Reenvíos Scala concurrentes y conflicto de contenido | Reintentos idénticos aceptados; conflicto 409 |
| Reinicio Scala | Los 24 resultados completos y acumulados permanecen correctos |
| Logs y limpieza | Sin panics, excepciones ni fallos de almacenamiento inesperados; recursos de prueba eliminados |

Go recibe eventos en `/api/v1/events` (también ofrece `/api/v1/events/batch`) y entrega lotes completos a Scala en `/api/v1/matches`. Ambos ofrecen `/healthz`. En ejecución habitual usan puertos 8080/8081; el verificador imprime sus puertos libres. Go permite consultar sus eventos agrupados; Scala permite consultar resultados por partida, acumulados Pac-Man por jugador y victorias de ajedrez por color.

Los patrones positivos y negativos (presión, rachas, remontadas y cadenas), relaciones y límites se prueban en las suites Scala. La carga HTTP comprueba las expectativas completas disponibles, sin afirmar que el fixture base contiene todos los patrones positivos. Los acumulados se verifican entre partidas con los mismos jugadores, antes/después de reenvíos y reinicios.

`race-3` original tiene clasificación inconsistente. Se conserva como dato fuente y se verifica 502 en Go y ausencia de resultado Scala. Solo en memoria se corrige el evento final a Erin → Alice → Dan y se reintenta por Go, comparando el resultado completo. No se presenta el original como partida analíticamente válida.

## Correcciones y archivos

- `scripts/Verify-Integration.ps1` y entrada compatible `scala-analytics/scripts/Test-HttpIntegration.ps1`: aislamiento, expectativas completas, concurrencia, reenvíos, persistencia, logs y limpieza. El anterior mezclaba fixtures Scala/Go con iguales identificadores y contenidos distintos y usaba recursos habituales. Se corrigieron también escape de variables Compose, colisión con `$Matches`, enumeración JSON de Windows PowerShell y puertos reasignados tras reinicios; se repitieron las pruebas.
- `PacmanAnalytics.scala` y `PacmanAnalyticsSuite.scala`: el cierre Go acepta cualquier motivo no vacío, conforme al contrato; valida puntos y deriva vidas/niveles. Conserva el formato Scala anterior y sus validaciones. La prueba HTTP incluye `USER_STOPPED`.
- `AnalysisServer.scala` y `ChessAnalysisSuite.scala`: consulta persistente `GET /api/v1/chess/colors`, con regresión de reenvíos y reinicio.
- `testdata/chess-{2,3}.expected.json`: expectativas independientes que faltaban.
- `.github/workflows/ci-cd.yml`: nuevo job integral para Go, Scala, ambas imágenes y HTTP, con evidencia como artefacto; no publica imágenes ni despliega.
- `.gitignore`, ambos README, `Docs/contrato-pacman.md`, documentos históricos de verificación y este informe: binario local excluido, comando reproducible y contratos actualizados.

## Pendientes que impiden declarar terminado todo el proyecto

1. **CI remoto:** este informe registra la auditoría local previa a la publicación; en esa etapa no se ejecutó ni observó GitHub Actions. Los resultados posteriores deben consultarse por SHA en Actions; la [documentación de CI/CD](ci-cd.md) explica el resumen y los artefactos del workflow.
2. **Deployment:** no hay despliegue externo verificado ni publicación de imágenes. La ejecución local no equivale a deployment. Su obligatoriedad debe contrastarse con la rúbrica general ausente.
3. **Estadística de carreras:** el DOCX pide contar sanciones además de acumular sus valores. Actualmente se exponen milisegundos y posiciones acumulados, pero no el número de sanciones por tipo. No bloquea la integración; queda pendiente como requisito analítico y debe añadirse contemplando los registros persistidos existentes.
4. **Documentación de entrega:** corregir menciones residuales a tres juegos en el DOCX y revisar su PDF; contrastar el proyecto con el enunciado general. Los documentos históricos conservan resultados de sus etapas.

La persistencia comprobada corresponde a Scala y a reinicios normales. Go conserva deliberadamente estado temporal de ingestión/deduplicación: reiniciarlo elimina partidas en curso. No se certifican recuperación ante cortes eléctricos, múltiples réplicas Scala compartiendo un volumen ni requisitos de un enunciado no disponible.
