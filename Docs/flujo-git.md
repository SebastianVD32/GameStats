# Flujo de Git propuesto para el módulo Go

La rama recomendada es `feat/go-ingestion-service`. Como los archivos del módulo ya están modificados sin confirmar sobre `main`, crear la rama inmediatamente; Git conservará esos cambios:

```powershell
git status
git switch -c feat/go-ingestion-service
```

En un inicio futuro con el árbol de trabajo limpio, sí conviene actualizar `main` antes de crear una rama:

```powershell
git switch main
git pull --ff-only origin main
git switch -c nombre-de-la-rama
```

## Commits sugeridos

Ejecutar cada bloque desde la raíz del repositorio. El primer `push` crea la rama remota; los siguientes actualizan la misma rama.

### 1. Contrato y validación por juego

```powershell
git add go-ingestion/go.mod go-ingestion/internal/domain go-ingestion/internal/matchstate
git commit -m "feat(go): add event contracts and match validation"
git push -u origin feat/go-ingestion-service
```

### 2. Procesamiento concurrente y entrega

```powershell
git add go-ingestion/internal/ingestion go-ingestion/internal/delivery
git commit -m "feat(go): add concurrent ingestion and analyzer delivery"
git push
```

### 3. API REST, archivos y ejecutable

```powershell
git add go-ingestion/internal/httpapi go-ingestion/internal/eventsource go-ingestion/cmd
git commit -m "feat(go): expose REST and file ingestion"
git push
```

### 4. Datos de demostración

```bash
git add go-ingestion/testdata
git commit -m "test(go): add interleaved and invalid event fixtures"
git push
```

### 5. Contenedor y ejecución integrada

```powershell
git add go-ingestion/Dockerfile go-ingestion/.dockerignore compose.yaml .gitignore
git commit -m "build(go): containerize ingestion service"
git push
```

### 6. CI/CD

```bash
git add .github/workflows/ci-cd.yml
git commit -m "ci: validate Go service and container"
git push
```

### 7. Documentación

```bash
git add README.md go-ingestion/README.md Docs/contrato-eventos-go.md Docs/flujo-git.md
git commit -m "docs: document Go module and development workflow"
git push
```

Antes de abrir el pull request:

```bash
cd go-ingestion
gofmt -w .
go vet ./...
go test -race ./...
cd ..
git status
```

Crear en GitHub un pull request de `feat/go-ingestion-service` hacia `main`. Después de aprobarlo y fusionarlo:

```bash
git switch main
git pull --ff-only origin main
git branch -d feat/go-ingestion-service
```

Si GitHub no eliminó la rama remota durante el merge:

```bash
git push origin --delete feat/go-ingestion-service
git fetch --prune
```

No eliminar la rama antes de confirmar que el pull request fue fusionado y que `main` contiene todos los commits.
