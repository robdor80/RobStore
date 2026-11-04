# Resultados RS-04

Validación local real, 2026-11-03 UTC, con JDK 17.0.12, SDK 36 y Build Tools 35.0.0.
Estos resultados corresponden a las fuentes finales incluidas en la entrega D.
Los comandos se repiten tras fijar el commit de entrega; el HEAD definitivo y
los enlaces de CI quedan registrados en la PR Draft y el informe final.

| Comando | Código de salida / resultado |
| --- | --- |
| `./gradlew test --no-daemon` | 0; 158 Debug + 158 Release, 0 fallos/errores/omisiones |
| `./gradlew lint --no-daemon` | 0; 0 errores y 9 avisos en lint Debug |
| `./gradlew assembleDebug --no-daemon` | 0; APK real generado y firmado Debug |
| `./gradlew assembleRelease --no-daemon` | 0; APK real sin firma privada |
| `git diff --check` | 0 |
| `python3 tools/check_rs04_regressions.py` | 0; los 99 casos RS-03 por variante siguen presentes y superados |
| `python3 tools/verify_rs04_apks.py` | 0; identidad, SDK, proveedor/rutas, zipalign, firma Debug y SHA-256 comprobados |

## APK y evidencias

Debug: es.robertodorado.robstore.debug / RobStore DEV / 0.1.0-DEV, SDK 26/36,
icono con D conservado mediante pruebas LauncherIconTest. Firma Debug válida
según apksigner. Release: es.robertodorado.robstore / RobStore / 0.1.0, SDK 26/36,
sin firma de distribución. Ambos pasan zipalign. FileProvider no exportado,
autoridad propia de cada variante y XML limitado al directorio verified comprobados
también sobre los APK empaquetados. Ningún APK se ha versionado o instalado físicamente.

El SHA-256 concreto de cada binario y el certificado **público** Debug se incluyen
en verificacion.md, SHA256SUMS y firma-debug.txt del artefacto. CI genera su propia
firma efímera; no se debe comparar su hash binario con el APK compilado localmente.
El verificador resuelve el recurso apk_paths desde resources.arsc porque Release
optimiza/acorta los nombres internos. No se altera la optimización para verificarlo.

Los nueve avisos son siete NewerVersionAvailable y dos UsableSpace en el punto
donde se consulta espacio real. Se conserva la decisión conservadora de exigir
espacio libre más 8 MiB, sin contar caché potencialmente reclamable ni borrar datos
ajenos. No se suprimen avisos o validaciones para obtener PASS.
Se conserva el aviso de stripping de libandroidx.graphics.path.so ya presente
en RS-03; no impide generar los APK. El decoder del caso de imagen inválida
emite el aviso conocido; sus aserciones pasan.

Quince PNG RS-04 por variante en app/build/rs04-evidence/: trece fases de descarga/
instalación y dos fichas sin SHA con fuente 200 %. Se mantienen los renders RS-03.
Se inspeccionaron muestras de SHA coincidente y fuente ampliada compacta/amplia.
Son Robolectric NATIVE, API 28, fixtures; no pruebas en Realme/Samsung ni TalkBack real.
Las pruebas de parser/proveedor se ejecutan adicionalmente en API 26 y 33.

Reproducción: ejecutar los cinco comandos anteriores y luego
`python3 tools/summarize_rs03.py --output app/build/rs04-summary`,
`python3 tools/check_rs04_regressions.py` y `python3 tools/verify_rs04_apks.py`.
Los TSV y huellas XML locales pequeños están junto a este informe. Los XML/HTML,
PNG y reportes completos se conservan en Actions 30 días, sin SDK/cachés/claves.
Los artefactos definitivos se localizan en el check de la PR para su HEAD exacto.

## Debug

| Suite | Ejecutadas | Superadas | Fallos | Errores | Omitidas |
| --- | ---: | ---: | ---: | ---: | ---: |
| es.robertodorado.robstore.AppIdentityTest | 4 | 4 | 0 | 0 | 0 |
| es.robertodorado.robstore.CatalogFormattingTest | 8 | 8 | 0 | 0 | 0 |
| es.robertodorado.robstore.DownloadUiTest | 6 | 6 | 0 | 0 | 0 |
| es.robertodorado.robstore.LauncherIconTest | 3 | 3 | 0 | 0 | 0 |
| es.robertodorado.robstore.RobUiTest | 33 | 33 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.CatalogCacheTest | 5 | 5 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.CatalogDiscoveryTest | 22 | 22 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.CatalogIconLoaderTest | 4 | 4 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.CatalogRepositoryTest | 10 | 10 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.GitHubClientTest | 8 | 8 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.HttpTransportTest | 3 | 3 | 0 | 0 | 0 |
| es.robertodorado.robstore.download.ApkVerifierTest | 9 | 9 | 0 | 0 | 0 |
| es.robertodorado.robstore.download.DownloadCoordinatorTest | 3 | 3 | 0 | 0 | 0 |
| es.robertodorado.robstore.download.DownloadFailureTest | 6 | 6 | 0 | 0 | 0 |
| es.robertodorado.robstore.download.DownloadMetadataTest | 2 | 2 | 0 | 0 | 0 |
| es.robertodorado.robstore.download.DownloadOriginTest | 17 | 17 | 0 | 0 | 0 |
| es.robertodorado.robstore.download.InstallationFlowTest | 3 | 3 | 0 | 0 | 0 |
| es.robertodorado.robstore.install.AndroidInstallerTest | 12 | 12 | 0 | 0 | 0 |
| **Total** | 158 | 158 | 0 | 0 | 0 |

## Release

| Suite | Ejecutadas | Superadas | Fallos | Errores | Omitidas |
| --- | ---: | ---: | ---: | ---: | ---: |
| es.robertodorado.robstore.AppIdentityTest | 4 | 4 | 0 | 0 | 0 |
| es.robertodorado.robstore.CatalogFormattingTest | 8 | 8 | 0 | 0 | 0 |
| es.robertodorado.robstore.DownloadUiTest | 6 | 6 | 0 | 0 | 0 |
| es.robertodorado.robstore.LauncherIconTest | 3 | 3 | 0 | 0 | 0 |
| es.robertodorado.robstore.RobUiTest | 33 | 33 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.CatalogCacheTest | 5 | 5 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.CatalogDiscoveryTest | 22 | 22 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.CatalogIconLoaderTest | 4 | 4 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.CatalogRepositoryTest | 10 | 10 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.GitHubClientTest | 8 | 8 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.HttpTransportTest | 3 | 3 | 0 | 0 | 0 |
| es.robertodorado.robstore.download.ApkVerifierTest | 9 | 9 | 0 | 0 | 0 |
| es.robertodorado.robstore.download.DownloadCoordinatorTest | 3 | 3 | 0 | 0 | 0 |
| es.robertodorado.robstore.download.DownloadFailureTest | 6 | 6 | 0 | 0 | 0 |
| es.robertodorado.robstore.download.DownloadMetadataTest | 2 | 2 | 0 | 0 | 0 |
| es.robertodorado.robstore.download.DownloadOriginTest | 17 | 17 | 0 | 0 | 0 |
| es.robertodorado.robstore.download.InstallationFlowTest | 3 | 3 | 0 | 0 | 0 |
| es.robertodorado.robstore.install.AndroidInstallerTest | 12 | 12 | 0 | 0 | 0 |
| **Total** | 158 | 158 | 0 | 0 | 0 |

## Total de ambas variantes

Ejecutadas / superadas / fallos / errores / omitidas: **316 / 316 / 0 / 0 / 0**.

## Lint

`lint-results-debug.xml`: 0 errores, 9 avisos.

- Warning: `NewerVersionAvailable` (libs.versions.toml:3)
- Warning: `NewerVersionAvailable` (libs.versions.toml:3)
- Warning: `NewerVersionAvailable` (libs.versions.toml:3)
- Warning: `NewerVersionAvailable` (libs.versions.toml:7)
- Warning: `NewerVersionAvailable` (libs.versions.toml:10)
- Warning: `NewerVersionAvailable` (libs.versions.toml:11)
- Warning: `NewerVersionAvailable` (libs.versions.toml:14)
- Warning: `UsableSpace` (DownloadFiles.kt:12)
- Warning: `UsableSpace` (DownloadFiles.kt:12)

Estado de extracción: completo; tests y lint satisfactorios.

Este resumen no certifica compilaciones: consultar el informe de auditoría y los pasos del workflow.
