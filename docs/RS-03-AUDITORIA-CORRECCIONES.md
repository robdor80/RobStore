# RS-03 — Auditoría de correcciones

## Ejecución y referencias

- Fecha: **31 de octubre de 2026, UTC**.
- Repositorio: `robdor80/RobStore`.
- Rama exclusiva: `codex/rs-03-ux-visual-robstyle`.
- PR existente: [#4 — Experiencia visual y catálogo RobStyle](https://github.com/robdor80/RobStore/pull/4), **Draft**, sin fusionar.
- HEAD inicial remoto y auditado: `1fa7d7d1435a6053300cbf60bb37562d90e2ae04`.
- Commit con las fuentes corregidas y pruebas: `bd327626dbb1c6d266981113bbff349810f7d187`.

Antes de editar se consultaron la PR y `git ls-remote`/`git fetch`: el remoto
coincidía con el HEAD auditado, sin commits adicionales. Se conservan los dos
commits anteriores de RS-03. No se modifica `main`, no se abre otra PR y no se
activa Ready for Review. Los commits posteriores de esta entrega añaden únicamente
documentación y PNG; el HEAD final y sus ejecuciones CI se identifican en el
[cuerpo de la PR](https://github.com/robdor80/RobStore/pull/4).

## Corrección 1: ausencia de sincronización válida

**Defecto y causa.** La rama final de `RobCatalogGrid`, con `snapshot == null`,
sin carga y sin error, reutilizaba `RobVisualState.Empty`. Confundía ausencia de
instantánea con una sincronización completa de cero apps.

**Solución.** Nuevo estado visual `Pending`, con «Pendiente de primera
sincronización» y explicación explícita de que todavía no hay sincronización
válida. El vacío solo se renderiza dentro de `snapshot != null` y
`snapshot.apps.isEmpty()`. Carga inicial y error sin caché conservan sus ramas,
progreso y reintento; una instantánea válida, incluso vacía, sigue teniendo
prioridad sobre errores/refrescos transitorios. No hay apps de sustitución.

**Prueba.** `noSnapshotIsPendingUntilARealEmptySnapshotArrives` comprueba que no
se muestran vacío ni éxito sin datos. Después obtiene una instantánea completa
mediante el descubrimiento real RS-02, con respuesta GitHub vacía simulada, y
verifica la transición al vacío real. Se mantienen carga, error y vacío con fallo.

## Corrección 2: avisos visibles antes de las apps

**Defecto y causa.** El único elemento `sync` estaba después de todas las apps en
la malla perezosa. Un catálogo grande dejaba los avisos fuera del primer viewport.

**Solución.** Home coloca el indicador antes de los elementos de aplicación,
ocupando el ancho de la malla. Superficie RobStyle discreta, sin cambios de paleta,
iconos, tarjetas ni columnas; padding de 12 dp y tipografía secundaria, sin limitar
líneas que oculten información al ampliar fuente. La semántica agrupa el aviso y
usa `LiveRegionMode.Polite`. Fallo y caché desactualizada se resumen en un único
mensaje. El bloqueo se basa en `nextRefreshAt`, también sin diagnóstico textual.
La última sincronización sigue al pie y los detalles técnicos completos siguen
en Ajustes. El error inicial conserva su tarjeta y no duplica el mensaje de fallo.

**Pruebas.** Dos casos con **30 apps** verifican caché, fallo y bloqueo temporal
sin ejecutar scroll: aviso y app 1 visibles, geometría del aviso antes de app 1,
app 30 todavía virtualizada y semántica accesible. Se repiten con fuente **200 %**.
Un caso adicional comprueba bloqueo futuro sin `message`.

## Corrección 3: hora de refresco vencida

**Defecto y causa.** Se formateaba cualquier `nextRefreshAt` positivo sin
compararlo con la hora actual; un diagnóstico antiguo podía mantener un texto
que anunciaba una comprobación futura ya vencida.

**Solución.** Comparación de epoch con `Clock` inyectable antes de formatear:

| Valor temporal | Presentación |
| --- | --- |
| `nextRefreshAt > ahora` | Comprobación bloqueada y próxima hora local. |
| `0 < nextRefreshAt <= ahora` | «Ya puedes comprobar el catálogo» cuando se presenta el estado temporal; nunca la fecha como futura. |
| `nextRefreshAt <= 0` | Información temporal no disponible; no se inventa una fecha ni se afirma que el refresco esté permitido. |

Se utiliza el `displayDate` existente con zona y locale del dispositivo. No se
interpreta ni modifica el texto del diagnóstico para extraer una fecha.
Se recalcula al reanudar la pantalla y en las recomposiciones. Una espera de UI
**única y cancelable hasta el vencimiento** permite corregir el texto si la
pantalla permanece abierta. No hay bucle de sondeo, temporizador permanente,
WorkManager, tarea periódica ni llamada de red al vencer. El callback real sigue
sometido a las políticas del repositorio RS-02.

**Pruebas.** Relojes deterministas con futuro, igualdad exacta, pasado, cero y
negativos; UTC, Europe/Madrid, America/Los_Angeles y Asia/Tokyo; cambio DST, cambio
de día y locale español/US. Casos UI de vencimiento sin refresco automático,
reanudación y conservación del diagnóstico técnico en Ajustes.

## Archivos modificados

| Grupo | Archivos y alcance |
| --- | --- |
| Producción | `ui/RobStoreApp.kt`: ramas de estado, posición/compactación del aviso, comparación y vencimiento en UI. |
| Producción | `ui/CatalogFormatting.kt`: clasificación temporal mediante epoch y Clock. |
| Producción | `ui/robstyle/Components.kt`: estado Pending; `res/values/strings.xml`: textos de los estados corregidos. |
| Tests | `RobUiTest.kt` y `CatalogFormattingTest.kt`: diez métodos nuevos; expectativas anteriores adaptadas al defecto corregido. |
| Evidencia | `tools/summarize_rs03.py`: extracción de recuentos, resultados individuales y lint desde XML, sin rutas privadas. |
| CI | `.github/workflows/android.yml`: diff-check, resumen de job y artefactos de informes/renders, incluso en fallo. |
| Documentación | `docs/RS-03.md`, este informe y `docs/evidencias/rs03/`. README principal no necesita cambios. |
| Visuales | Dos PNG existentes actualizados y siete nuevos en `docs/assets/rs03/`; manifiesto SHA-256 en evidencias. |

Las rutas abreviadas de producción parten de
`app/src/main/java/es/robertodorado/robstore/`; los tests de
`app/src/test/java/es/robertodorado/robstore/` y los textos de `app/src/main/`.

## Pruebas, regresiones y resultados

Los XML finales arrojan **198 ejecutadas y 198 superadas: 99 Debug + 99 Release;
0 fallos, 0 errores, 0 omitidas**. No se presupuso el resultado anterior de 178:
se ejecutaron ambas variantes. Se conservan todas las definiciones anteriores y
se añaden diez métodos ejecutados en ambas variantes. El desglose y los nombres
son verificables en:

- [Recuentos extraídos de XML y lint](evidencias/rs03/resultados.md).
- [Casos Debug](evidencias/rs03/tests-debug.tsv) y [casos Release](evidencias/rs03/tests-release.tsv).
- [Identificación de los diez métodos nuevos, regresiones e incidencia inicial](evidencias/rs03/pruebas.md).
- [Verificación de recuentos y advertencias en CI](evidencias/rs03/ci-implementacion.md).
- [Comandos, compilaciones e identidades APK](evidencias/rs03/validaciones.md).

Las seis suites de `catalog/` de RS-02 permanecen sin cambios respecto al HEAD
auditado: **51 casos por variante, 102 ejecuciones satisfactorias**. Se mantienen
las pruebas de descubrimiento, caché, iconos, transporte, límites y repositorio.
También pasan Home → AppDetail → Home, retorno por flecha/Atrás, estado de app
retirada, carga, vacío real, error sin caché, caché/offline, fuentes ampliadas,
malla adaptable, iconos y las identidades Debug/Release.

El primer intento local falló al inicializar cuatro clases Robolectric por
`UnknownHostException` al descargar su dependencia Android: 59 entradas Debug,
55 superadas y 4 fallidas; Release aún no ejecutada. Se corrigió el proxy del
proceso de tests fuera del repositorio y se repitió toda la suite con el resultado
final anterior. No se ocultó ni se deshabilitó ninguna prueba.

La [tabla de validación](evidencias/rs03/validaciones.md) contiene los resultados
comprobados de los cinco comandos obligatorios, los avisos y el artefacto local
resultante. No se versionan APK, carpetas build, cachés o logs enormes.

## Evidencia visual y preservación del diseño

Se regeneraron los once renders aprobados: **nueve idénticos byte a byte** al HEAD
auditado. Solo cambian `home-cached-error.png` (aviso antes de las apps) y
`settings.png` (información temporal desconocida explícita). Siete PNG nuevos
muestran los estados corregidos:

| Escenario | PNG |
| --- | --- |
| Pendiente, sin instantánea | [Pendiente](assets/rs03/home-pending-sync.png) |
| 30 apps, caché desactualizada | [Normal](assets/rs03/home-30-stale-1.0.png) · [200 %](assets/rs03/home-30-stale-2.0.png) |
| 30 apps, error/caché | [Normal](assets/rs03/home-30-error-1.0.png) · [200 %](assets/rs03/home-30-error-2.0.png) |
| 30 apps, refresco bloqueado | [Normal](assets/rs03/home-30-limited-1.0.png) · [200 %](assets/rs03/home-30-limited-2.0.png) |

Se inspeccionaron los siete nuevos y los dos actualizados. Los tests comprueban
visibilidad inicial y geometría; el aviso normal de bloqueo ocupa aproximadamente
92 dp de alto a 360 dp. Con fuente 200 % crece para mantener toda la información
legible, permaneciendo visible la primera app en la ventana de test.

**Son renders Robolectric API 28 NATIVE, Debug, no capturas de un Android real**.
Canvas/Skia, fixtures solo de test, locale US, UTC y reloj fijo
`2026-10-30T00:00:00Z` (fecha simulada, distinta de la fecha de ejecución).
[Dimensiones y hashes](evidencias/rs03/visuales.tsv),
[reproducción y artefactos](evidencias/rs03/README.md).

## Arquitectura protegida y limitaciones

Respecto al HEAD auditado **no se modifica ningún archivo de producción de
`catalog/`**, manifiesto Android, dependencia, SDK, permiso, configuración de
build, identidad o icono oficial. Se mantienen descubrimiento GitHub, catálogo
RS-02, `robstore.json`, selección de Releases, APK exacto, caché, carga de iconos,
intervalos/cuotas y políticas de actualización. La navegación sigue usando
`repositoryId`. El único ajuste histórico de RS-03 en CatalogRepository —retirar
el timestamp ISO del mensaje de cooldown— ya estaba en el HEAD auditado y se
conserva sin ampliar su alcance.

No se implementan descargas, instalaciones, actualizaciones automáticas ni
detección de aplicaciones instaladas. El código simulado sigue exclusivo de
tests/previews. No hay cambios estéticos fuera de los estados auditados.

Advertencias pendientes: lint local conserva siete `NewerVersionAvailable`; CI
registra **15 avisos**, incluidos ocho adicionales de SDK/Gradle/dependencias en
archivos de configuración intactos (`OldTargetApi`, `AndroidGradlePluginVersion`
y `GradleDependency`). Cero errores en ambos entornos y ningún aviso sobre las
líneas de UI corregidas. Se documenta la diferencia sin igualar todo el inventario
SDK/metadatos del runner: [extracto CI](evidencias/rs03/ci-implementacion.md).
Persisten además el empaquetado de
`libandroidx.graphics.path.so` sin stripping adicional y mensaje nativo
`Failed to create image decoder ... unimplemented` en la prueba deliberada de
imagen inválida, cuyas aserciones pasan. No se añaden suppressions o baseline lint.
Release sigue sin firma privada configurada.

`adb devices -l` no mostró dispositivos. No se certifican GPU, barras del sistema,
TalkBack o interacción táctil en hardware. Queda pendiente la validación física
vertical/horizontal en **Realme GT Neo 2** (6,62 pulgadas, 1080 × 2400 px) y
**Galaxy Tab S9+** (12,4 pulgadas, 1752 × 2800 px). Esas medidas físicas no se
convierten en columnas ni anchos útiles en dp: se conserva `GridCells.Adaptive`
según el espacio disponible de Compose y la escala de fuente.

## GitHub Actions y segunda auditoría

Workflow: [Android](https://github.com/robdor80/RobStore/actions/workflows/android.yml).

- Implementación corregida `bd327626dbb1c6d266981113bbff349810f7d187`:
  [ejecución de PR](https://github.com/robdor80/RobStore/actions/runs/41166530676) y
  [ejecución push](https://github.com/robdor80/RobStore/actions/runs/41166527212).
- [Checks del HEAD actual de la PR #4](https://github.com/robdor80/RobStore/pull/4/checks):
  referencia actualizable para los commits posteriores de documentación, sin
  depender del estado de un commit anterior.
- [Ejecuciones de esta rama](https://github.com/robdor80/RobStore/actions/workflows/android.yml?query=branch%3Acodex%2Frs-03-ux-visual-robstyle).

Ambas ejecuciones del commit de implementación finalizaron en **success**,
incluidos resumen y subida de artefactos, comprobado el 2026-10-31 a las 04:01 UTC.
[Artefacto de la PR](https://github.com/robdor80/RobStore/actions/runs/41166530676/artifacts/13029467971)
(aprox. 1,6 MB; caduca el 30 de noviembre de 2026).
El push final de documentación vuelve a ejecutar Actions. Su resultado terminal,
el SHA final y sus enlaces exactos se registran en el cuerpo de la PR después de
comprobarlos; los checks actuales enlazados arriba permiten verificar ese HEAD.
El éxito anterior no se extrapola a ejecuciones posteriores todavía en curso.

Cada run comprueba tests, lint, Debug y Release, y publica XML/HTML, PNG de ambas
variantes y resumen en el job/artefacto `rs03-validation-<SHA del checkout>`.
La retención de artefactos es 30 días; estos resúmenes de texto y PNG versionados
permanecen en el repositorio. Los hashes de XML que genera el exportador identifican
los XML de **cada run**, cuyos tiempos de ejecución pueden variar.

Para la segunda auditoría: comprobar el HEAD y Draft en la PR, abrir sus checks
actuales, contrastar los 198 casos con los artefactos, revisar el diff desde
`1fa7d7d` y las evidencias anteriores. La aprobación de fusión queda para ChatGPT
y el propietario. Esta entrega no fusiona ni elimina la rama.
