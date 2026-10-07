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
- Ajuste acotado: timeout120s solo para consultas `simctl`, manteniendo30s en las demás, timeout global5min, el mismo toolchain y todas las condiciones de disponibilidad. Sin retry automático, fallback de versión ni omisión de checks. Validación local12/12casos con mocks y revisión fresca Luna **OK**.

### Segunda ejecución: entorno confirmado

- Commit `8494d112af87df2c58c5f190353d5783ffa0d89e`; [run37423959805](https://github.com/cortig00/OpenJump/actions/runs/37423959805), **SUCCESS**, job1m55s. Logs y resultado consumidos2026-10-06, no inferidos del YAML.
- Misma imagen/macOS/arquitectura y pareja Xcode/Swift/SDK indicados arriba.
- Runtime disponible **iOS26.0.1** (`com.apple.CoreSimulator.SimRuntime.iOS-26-0`): 10dispositivos iPhone y11iPad. Incluye iPhone16/17/SE3 e iPad/Air/Pro/mini. Solo inventario; ninguno arrancado por este workflow.
- El reporte confirma `appBuilt=false`, `testsRun=false`, `simulatorBooted=false`, `signedOrUploaded=false`. No hay todavía proyecto Apple compilado, cobertura física, firma ni distribución. Tampoco prueba compatibilidad KMP/Xcode ni frame-exact.
- `main` público permaneció en `5c47e887dc06b294f9b9a1f6458df6b670f854aa`; Android/source/dependencias intactos. No se creó PR ni se fusionó.

**Conclusión:** hay host CI macOS para los siguientes spikes de la app iPhone sin depender del Mac antiguo para compilar. P0 completo/G0 y el siguiente cambio de app siguen sujetos a sus gates; disponibilidad de simuladores no equivale a un test de app PASS.

## Rollback

1. Cancelar runs propios activos y, si procede, deshabilitar el workflow registrado.
2. Con autorización, revertir únicamente los commits CI/documentación de esta slice en `dev/apple` (más recientes primero) y publicar el revert. Los commits de implementación son `69703f4` y `8494d11`; incluir también su actualización documental de evidencia, no commits Android ni ajenos.
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

## Actualización 2026-10-07: diagnósticos acotados y simulador efímero propio

- `diagnose_simulator.py` conserva `BUDGET40s`, `COMMAND7s` y `CAPTURE65536` total. Solo el help `simctl help list` captura stdout+stderr por separado y de forma acotada (total compartido); el parser solo emite booleanos `device/runtime_filter_advertised` y contadores/flags de presencia/completitud, nunca texto raw ni errores. JSON stdout sigue separado del stderr descartado. Gramática no soportada, nonzero, incompleto, truncado o IDs ausentes siguen `UNKNOWN`; `launchctl 113` sigue `UNKNOWN`, no veredicto. Sin fallback global de inventario ni `PASS` por mera presencia de proceso. Snapshots numéricos opcionales explícitamente omitidos: no caben con seguridad en el mismo presupuesto sin ampliar caps ni retener inventarios.
- `select_simulator.py` crea UN dispositivo efímero propio `OpenJump-ephemeral-*` con tipo exacto `iPhone-17-Pro` y runtime `iOS-26-0` validados desde metadatos disponibles (no nombres arbitrarios). Un solo `simctl create` (consulta 60s + creación 30s = 90s, con margen dentro del helper 120s/outer 3min); sin reintentos. Solo el UUID creado válido y distinto del template acredita propiedad y fluye a `boot`/`xcodebuild`; sin fallback a UUID de fábrica/`booted`/default. Recibo mínimo solo en `RUNNER_TEMP`, sin credenciales ni stderr raw.
- Workflow `apple-prototype.yml` mantiene caps: job 25min, boot 120s + `bootstatus` 240s (step 7min), compilación 420s/8min, tests 420s/8min, `device needs:prototype`, sin `continue-on-error` en boot/tests. Limpieza acotada (60s, step 2min) tras readiness/tests/evidencia, condicionada a `always() && steps.simulator.outputs.owned == 'true'` y recibo válido; nunca plantillas/compartidos/todos ni UDID arbitraria. Un recibo anterior sin propiedad emitida por este job no autoriza borrar. Si falla `GITHUB_OUTPUT` después de crear, la limpieza se omite y el posible huérfano queda para el descarte de la VM hospedada; `skipped` no es limpieza ni salud `PASS` (no se admite inferir lo mismo en un host persistente). Fallo de limpieza no oculta el fallo previo ni certifica `PASS`. Sin workaround de caché, sin cambios SDK/toolchain/tests nativos (117 intactos).
