# Entregas RS-04

## Comprobaciones iniciales (2026-11-03 UTC)

GitHub y `git ls-remote --heads origin` confirmaron:

- main: `08a07b96e8fd3eff4a0c3597d914a0735f0604b1`.
- PR #4 abierta, Draft y sin fusionar.
- Rama RS-03: `codex/rs-03-ux-visual-robstyle`.
- HEAD RS-03: `8a0f3046c5695ae9e3e949dd9f782fcc3da835be`.
- No existían ramas ni PR RS-04. Rama nueva creada exactamente desde ese HEAD.

## A — Origen, red y descarga

Validación compartida de metadatos, revalidación remota de selección, cliente binario
streaming, allowlist exacta de CDN, cancelación de Call, temporales privados,
límites de tamaño/espacio y coordinador con exclusión de operaciones simultáneas.

`./gradlew testDebugUnitTest --tests '*download.*' --tests '*catalog.*' --no-daemon`:
PASS: 72 pruebas, 0 fallos/errores/omisiones. Tests locales, sin GitHub real.
`git diff --check`: PASS.

Incidencia inicial: incorporación de fuentes mientras una compilación ya estaba
en curso; tests no encontraron el coordinador en la salida de producción de esa
ejecución. Repetición completa del conjunto anterior correcta, sin omitir pruebas.

Cloud: instalación local ignorada por Git de JDK 17.0.12, SDK 36 y Build Tools
35.0.0; proxy y truststore del entorno únicamente para las herramientas de build.
La aplicación usa TLS normal del sistema, sin CA ni proxies propios.

## B — Integridad, almacenamiento y verificación

`./gradlew testDebugUnitTest --tests '*download.*' --tests '*catalog.*' --no-daemon`:
PASS: 81 pruebas, 0 fallos/errores/omisiones. `git diff --check`: PASS.

Verificación independiente del archivo por streaming; SHA oficial coincidente o
estado explícito sin referencia, tamaño exacto, formato reconocible y paquete.
Capacidad PreparedApk privada, publicación atómica y revalidación previa a instalar.
Regresiones de alteración, digest malformado/ausente/distinto, paquete distinto,
archivo corrupto/incompleto/excesivo, caducidad, rutas y limpieza tras reinicio.

Se corrigió un error de compilación en la actualización del enum de cancelación
antes de repetir el conjunto completo. Ninguna prueba omitida o deshabilitada.

## C — Instalación Android y RobStyle

`./gradlew testDebugUnitTest --no-daemon`: PASS, 149 pruebas, 0 fallos/errores/
omisiones; incluye los 99 casos Debug heredados de RS-03. `git diff --check`: PASS.

FileProvider privado, permiso por origen, comprobación real al volver de Ajustes,
rehash/reparse antes de URI, instalador interactivo y resultado no confirmado.
Pruebas oficiales del parser en API 26/33 con manifiesto sintético y sin firma;
permiso, URI, flags, ausencia de instalador y flujos sin instalación automática.
Compose: progreso accesible, acciones por fase, concurrencia visible, navegación,
ViewModel retenido tras recreación y vistas compacta/amplia con fuente 200 %.

Se conservan todos los métodos RS-03; dos aserciones de su antiguo botón inactivo
y el texto que prohibía descargas se actualizan para comprobar el comportamiento
RS-04 autorizado. No se suprimen casos. Los renders RS-03 se siguen generando.

Incidencias corregidas en pruebas: caché estática de raíces de AndroidX FileProvider
entre sandboxes Robolectric; montaje del proveedor usando ProviderInfo real del
manifiesto. Se corrigieron los errores de compilación de ese montaje. La repetición
completa de 149 casos fue satisfactoria, sin ampliar rutas ni exportar el proveedor.
Los PNG al 200 % se inspeccionaron: RobStyle conservado, acción multilínea y ficha
desplazable. No constituyen pruebas físicas ni validación de TalkBack real.
