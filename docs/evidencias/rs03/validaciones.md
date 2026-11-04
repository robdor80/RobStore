# Validaciones locales — 2026-10-31 UTC

Fuentes: `bd327626dbb1c6d266981113bbff349810f7d187`, rama
`codex/rs-03-ux-visual-robstyle`. Ejecutadas individualmente sobre las mismas
fuentes finales, sin filtros ni pruebas deshabilitadas:

| Comando | Salida final | Código de salida | Duración Gradle |
| --- | --- | ---: | --- |
| `./gradlew test --no-daemon` | BUILD SUCCESSFUL; 198/198 según XML | 0 | 1 min 9 s |
| `./gradlew lint --no-daemon` | BUILD SUCCESSFUL; 0 errores, 7 avisos según XML | 0 | 1 min 4 s |
| `./gradlew assembleDebug --no-daemon` | BUILD SUCCESSFUL | 0 | 1 min 32 s |
| `./gradlew assembleRelease --no-daemon` | BUILD SUCCESSFUL | 0 | 1 min 35 s |
| `git diff --check` | Sin salida; sin errores de whitespace | 0 | — |

Entorno: Linux, Temurin JDK 17.0.21, Gradle Wrapper 8.13, AGP 8.13.2, Kotlin
2.2.21, Android SDK 36 y Build Tools 35.0.0. No se cambian dependencias ni
configuración del proyecto para validar. El primer intento con proxy incompleto
se registra en [pruebas.md](pruebas.md); el resultado de esta tabla es la
repetición completa posterior a corregir el entorno.

## Lint y ausencia de errores nuevos

El comando `lint` genera `lint-results-debug.xml`. **Cero errores y siete avisos**,
todos `NewerVersionAvailable`, en el catálogo de versiones intacto:
`libs.versions.toml`, líneas 3 (tres avisos), 7, 10, 11 y 14.
Coinciden con los siete avisos declarados en el HEAD auditado; no se han añadido
supresiones ni baseline. La compilación Release ejecuta además
`lintVitalAnalyzeRelease`, `lintVitalReportRelease` y `lintVitalRelease`, correctos.
Los resultados actuales de cada caso y suite, sin fallos/errores/omisiones,
están en [resultados.md](resultados.md).

Persisten el aviso de empaquetado sin stripping de `libandroidx.graphics.path.so`
y el mensaje de decoder `unimplemented` al probar una imagen deliberadamente
inválida. El fallback y sus dimensiones se verifican sin ocultar ese escenario.

## APK e identidades comprobadas

Los dos APK se generan localmente, no se versionan ni se adjuntan como evidencia.
`aapt dump badging` confirma:

| Variante | applicationId | Etiqueta | versionCode | versionName |
| --- | --- | --- | ---: | --- |
| Debug | `es.robertodorado.robstore.debug` | RobStore DEV | 1 | 0.1.0-DEV |
| Release | `es.robertodorado.robstore` | RobStore | 1 | 0.1.0 |

- `app-debug.apk`: SHA-256 `4e551b29406cecc7f910c26b6f51359f2dd11d8d9a978e94b24b81c56b1086cd`.
- `app-release-unsigned.apk`: SHA-256 `dbc48d5c81e109599ea684d816030d479e06fdc080bbf8a48336cf93cdb19625`.

Son hashes de esta ejecución local, no una promesa de reproducibilidad binaria
entre runners. Release continúa sin firma privada configurada. Los tests de
identidad y launcher pasan en ambas variantes.

## Arquitectura y alcance comprobados

`git diff 1fa7d7d -- app/src/main/java/es/robertodorado/robstore/catalog/`
no muestra cambios. Tampoco cambian manifiesto, Gradle/dependencias, iconos ni
identidades. Las seis suites de RS-02 conservan 51 casos por variante, todos
superados. La navegación por `repositoryId`, malla adaptable, fuentes ampliadas,
estados offline/carga/error/vacío, iconos y callbacks conservan sus regresiones.

`adb devices -l` devuelve una lista vacía. Los renders y tests JVM no se presentan
como validación física en Realme GT Neo 2 o Galaxy Tab S9+; esa comprobación
vertical/horizontal y TalkBack queda pendiente.

## Verificación independiente en GitHub Actions

El commit de implementación pasa las mismas validaciones en:

- [Workflow de PR](https://github.com/robdor80/RobStore/actions/runs/41166530676).
- [Workflow de push](https://github.com/robdor80/RobStore/actions/runs/41166527212).
- [Artefacto de PR con XML/HTML, resúmenes y renders](https://github.com/robdor80/RobStore/actions/runs/41166530676/artifacts/13029467971).
- [Checks del HEAD más reciente](https://github.com/robdor80/RobStore/pull/4/checks).

Los pasos de compilación, resumen y subida de artefactos terminaron en success.
Se verificó el resumen del job: **198/198 y cero errores lint**, con **15 avisos
lint en CI**, frente a siete locales. Los ocho adicionales afectan solo a
configuración intacta de SDK/Gradle/dependencias. [Extracto y ubicaciones](ci-implementacion.md).
Los commits de evidencias/documentación se revalidan al publicarse. El estado
terminal del HEAD final se consulta en los checks y en el cuerpo actualizado de
la PR, sin confundirlo con el éxito del commit de implementación.
