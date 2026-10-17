# CI/CD de GameStats

El workflow [GameStats CI/CD](../.github/workflows/ci-cd.yml) se ejecuta con cada push a `main` y `feat/**`, con pull requests dirigidos a `main` y manualmente mediante `workflow_dispatch`. Los commits y nombres de las nuevas etapas se escriben en inglés.

## Comprobaciones obligatorias del workflow

| Job | Qué comprueba | Evidencia |
|---|---|---|
| `go-quality` | Formato, `go vet`, pruebas con detector de carreras y cobertura, compilación, doce lotes y rechazo de todos los fixtures inválidos | Logs y artefacto `go-ingestion-<sha>` con binario Linux y `coverage.out` |
| `go-container` | Construcción de la imagen Go después de aprobar `go-quality` | Logs de Docker Buildx; imagen sin publicar |
| `integrated-verification` | Go, pruebas y empaquetamiento Scala, ambas imágenes, disponibilidad, integración HTTP de los cuatro juegos, resultados completos, errores, concurrencia, reenvíos, acumulados y persistencia | Resumen en la página de ejecución y artefacto `integrated-verification-<sha>` |

Cada job debe terminar en verde para considerar aprobada una ejecución. Son verificaciones configuradas en el workflow; no se afirma que las reglas de protección de `main` las exijan, porque esa configuración del repositorio no se modificó.

El verificador utiliza proyecto, red, imágenes, puertos y volúmenes exclusivos de cada ejecución. Limpia sus recursos al terminar. El fixture original `race-3` se verifica como rechazo esperado; su evento final se corrige únicamente en memoria para comprobar también la entrega exitosa por Go. Los resultados esperados provienen de archivos fijos independientes.

## Revisar una ejecución en GitHub

1. Abrir **Actions → GameStats CI/CD** y seleccionar la ejecución del commit y rama que se desea integrar.
2. Revisar los tres jobs y el resumen de integración. Un fallo en cualquier job impide considerar aprobada la ejecución, aunque otros jobs pasen.
3. Descargar los artefactos: `summary.json` informa los grupos aprobados y el estado; `actual-results.json` contiene los resultados completos recuperados tras reiniciar Scala; `services.log` y `execution.log` permiten revisar disponibilidad, rechazos y limpieza. Si falla una comparación se añade `mismatch.json`; ante fallo de verificación se intenta guardar `failure.log`.

El resumen conserva los grupos completados incluso si falla una etapa posterior. Una aprobación parcial de grupos no equivale a éxito del job. Los artefactos se intentan publicar también cuando falla la integración.

## Repetir localmente

Desde la raíz, en Windows PowerShell con Docker Desktop en contenedores Linux:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Verify-Integration.ps1
```

En PowerShell 7 (usado por el runner Linux): `./scripts/Verify-Integration.ps1`.

## Publicación e integración

Guardar los cambios con mensajes en inglés, subir la rama de trabajo y esperar su CI antes de actualizar `main`. Después de actualizar `main`, revisar también su ejecución específica: una aprobación de otra rama o de otro SHA no sustituye esa comprobación. No usar push forzado para resolver divergencias ni omitir protecciones del repositorio.

El [informe de integración](verificacion-integracion.md) registra las pruebas locales previas, correcciones y pendientes del enunciado. El historial de Actions registra los resultados remotos por SHA; una prueba local no demuestra que una ejecución remota haya pasado.

## Alcance de CD y pendientes

El workflow construye y valida contenedores y conserva artefactos. **No publica imágenes en un registro ni realiza deployment externo.** No se agregaron credenciales, infraestructura ni despliegues ficticios. Si el enunciado general exige deployment, esa etapa permanece pendiente y requiere definir el destino autorizado.

También permanecen los pendientes analíticos y editoriales documentados en el informe local: conteo explícito de sanciones por tipo en carreras y revisión de DOCX/PDF frente a la rúbrica general.
