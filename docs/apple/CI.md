# Apple CI — Mac de compilación para la app iPhone

**Fecha:** 2026-10-06. Objetivo: desarrollar una app nativa para iPhone desde Windows/Linux, con macOS CI como host de compilación y pruebas. Este primer workflow es únicamente diagnóstico; no contiene todavía el proyecto iPhone.

## Repositorio y aislamiento

- Repositorio **público**: `cortig00/OpenJump`.
- Rama de trabajo: `dev/apple`, desde `main` público `5c47e887dc06b294f9b9a1f6458df6b670f854aa`.
- Checkout dedicado; no cambiar el remote de otro checkout ni trasladar historial privado.
- Esta slice agrega solo `.github/workflows/apple-environment.yml` y este documento. No modifica Android, dependencias, releases ni `main`.
- El source de producción `app/src/main` y el version catalog coinciden por hash con el baseline usado para investigar la ruta Apple. Esta distribución pública no incluye el corpus/tests privados: no copiarlos ni atribuirle esa cobertura automáticamente.

## Qué hace el diagnóstico

[Workflow](../../.github/workflows/apple-environment.yml): runner estándar `macos-15` ARM64, timeout5min, Xcode26.0.1 seleccionado explícitamente por `DEVELOPER_DIR`. Falla si no está disponible o no coincide; no usar silenciosamente el Xcode predeterminado de la imagen.

Comprueba herramientas/SDK iPhoneOS y simulator26 e inventaría runtimes iOS26 con dispositivos iPhone/iPad disponibles. Registra SHA, OS/arquitectura, versión de imagen, toolchain y simuladores en logs y Job Summary. La imagen del runner es mutable: cada run vuelve a comprobar la pareja seleccionada.

Sin checkout de código, acciones de terceros, permisos del token (`permissions: {}`), secretos Apple, instalación de herramientas, firma, upload, build/test de app o boot de simuladores. No se vuelcan variables de entorno/credenciales. Un diagnóstico verde no prueba frame-exact, cámara, física, UX ni una app iPhone compilada.

## Activación y coste

El usuario autorizó continuar en el público, con checkout separado y rama Apple. El primer push del workflow activa el diagnóstico:

- `push` solo para `dev/apple` y cambios del propio YAML.
- Sin PR/tags/otras ramas ni trigger por cada modificación de la app.
- Sin `workflow_dispatch`: no modificar `main` para habilitar un botón manual. Después del primer run, se puede reejecutar el run existente desde Actions o `gh run rerun <RUN_ID>`; comprobar repo/ref antes de hacerlo.
- Concurrency cancela un diagnóstico obsoleto de la misma rama.

GitHub documenta los **runners estándar gratuitos para repositorios públicos**, incluidos macOS. Este job no usa larger runners, servicios pagos ni almacenamiento de artifacts. No cambiar la visibilidad, tipo de runner, almacenamiento o configuración de facturación sin revisar coste y obtener autorización.

La gratuidad de CI no elimina los requisitos de Apple: simulator sin firma no necesita programa de pago; distribución TestFlight/App Store y firma para testers son tareas separadas. Actions es efímero y no ofrece Xcode/previews/simulador interactivos en Windows.

## Validación y límites

Antes de publicar: validación YAML/filters/permissions, `bash -n` de tres scripts, AST Python y casos de inventario simulados. Estos últimos solo prueban lógica con mocks, no un Mac real. Inspeccionar diff y publicar exclusivamente los dos archivos autorizados.

Tras publicar: registrar commit y URL/runID, esperar conclusión y leer logs/summary del run concreto. Si falla o queda en cola, no inferir éxito ni elegir otro toolchain por fallback silencioso. No habilitar servicios pagos para evitar una cola o un bloqueo de Actions.

### Evidencia de la primera ejecución

- Commit diagnóstico `69703f46e418a0063af4549cfbc927d1f769510c`; [run37423404794](https://github.com/cortig00/OpenJump/actions/runs/37423404794), **FAILURE** en49s.
- Verificación de herramientas **PASS**: imagen `macos-15-arm64` / `20260907.0337.1`, macOS15.7.9, Xcode26.0.1 build17A400, Swift6.2, SDK iPhoneOS/simulator26.0.
- Inventario de simuladores **NO COMPLETADO**: `xcrun simctl list runtimes --json` agotó30s. No prueba ausencia de simuladores ni host completamente cualificado; no hubo build/test/boot/firma de app.
- Ajuste acotado para segunda comprobación: timeout120s solo para consultas `simctl`, manteniendo30s en las demás, timeout global5min, el mismo toolchain y todas las condiciones de disponibilidad. Sin retry automático, fallback de versión ni omisión de checks. **Resultado de ese ajuste pendiente de ejecución**.

## Rollback

1. Cancelar runs propios activos y, si procede, deshabilitar el workflow registrado.
2. Con autorización, revertir únicamente el commit de esta slice en `dev/apple` y publicar el revert.
3. No hacer reset/clean/stash/force-push, no modificar `main`, no borrar trabajo ajeno ni retargetear el checkout privado.

El diagnóstico no toca teléfono/base de datos. Los logs/ejecuciones ya realizados no se deshacen; las futuras migraciones de datos exigirán su propio plan de backup/rollback.

## Siguiente hito: prototipo iPhone

Cuando se demuestre el entorno, preparar una slice acotada para el primer target SwiftUI y build/test de simulator sin firma. La elección core KMP/Swift y el vídeo PTS/frame-exact requieren sus spikes y revisión; no actualizar Kotlin/Android silenciosamente. No confundir el prototipo ni la regresión matemática con accuracy independiente.

Cámara/FPS, Photos/Files reales, interrupciones y validación UX final necesitan un iPhone designado. El runner público solo usará fixtures propios y con derechos: nunca publicar vídeos privados, datos de atletas, copias de seguridad, claves o binarios firmados incidentalmente.

## Fuentes oficiales

- Runners estándar públicos y arquitecturas: <https://docs.github.com/en/actions/reference/runners/github-hosted-runners>.
- Imagen macos15 ARM64 y Xcode/runtimes: <https://github.com/actions/runner-images/blob/main/images/macos/macos-15-arm64-Readme.md>.
- Eventos/default branch: <https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_dispatch>.

Consultadas2026-10-06; revalidar antes de cambiar runner/toolchain. El reporte de la ejecución, no esta lista, es la evidencia de disponibilidad efectiva.
