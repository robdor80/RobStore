# RobStore Android

Catálogo Android personal para distribuir aplicaciones propias y públicas en
GitHub. No utiliza Firebase ni un backend propio.

**Estado: RS-02 — Catálogo automático de GitHub (entrega para auditoría).**
Home descubre aplicaciones Android públicas de `robdor80` con topic `robstore-app`,
manifiesto `robstore.json` v1 y una Release estable con el APK exacto declarado.
Detalle muestra metadatos de esa etiqueta publicada. El catálogo se conserva sin
conexión; un catálogo vacío real es válido. No hay descarga ni instalación de APK.

## Stack y requisitos

- JDK 17; Android SDK Platform 36 y Build Tools 35.0.0.
- Kotlin 2.2.21, AGP 8.13.2 y Gradle Wrapper 8.13 con checksum SHA-256.
- Jetpack Compose BOM 2025.12.00, Material 3 y AndroidX Activity Compose 1.12.2.
- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`.
- JUnit 4.13.2 y Robolectric 4.16.1 para pruebas JVM sin emulador.

Android Studio es opcional. Configura `JAVA_HOME` con JDK 17 y `ANDROID_HOME` con el
SDK, o indica `sdk.dir` en un `local.properties` local que Git ignora. Acepta las
licencias del SDK e instala `platforms;android-36` y `build-tools;35.0.0`.

## Estructura

```text
app/src/main/       Manifest, MainActivity, catalog/, ui/ y ui/robstyle/
app/src/debug/      Nombre DEV y recursos del icono DEV
app/src/test/       Catálogo, HTTP simulado, caché, iconos, identidad y Compose
gradle/            Catálogo de versiones y Wrapper
.github/workflows/  Validación Android
docs/              Contrato RS-02, decisiones y evidencia RS-00/RS-01
```

Un único módulo `app`. OkHttp y kotlinx.serialization conectan la API pública sin
credenciales. `CatalogRepository`, caché JSON privada con escritura atómica,
`CatalogViewModel` y `StateFlow` separan IO de Compose. Se conserva Navigation
Compose con `home`, `settings` y `app/{appId}`; las fichas usan el ID de GitHub,
independiente de `packageName`.

La caché vale 2 horas. Al iniciar se muestra primero el catálogo persistido y se
refresca si corresponde. «Refrescar catálogo» comprueba GitHub con un intervalo
mínimo de 5 minutos y respeta los límites primarios/secundarios. Los fallos de red
o respuestas incompletas conservan el catálogo anterior. Sin trabajo periódico
en segundo plano. Iconos PNG/WebP de la etiqueta, carga asíncrona, límites y caché.

Consulta [docs/RS-02.md](docs/RS-02.md) para el contrato completo, endpoints,
seguridad, límites de cuota, pruebas y un ejemplo de publicación reproducible.

## Variantes

| Variante | Application ID | Nombre visible | Versión inicial |
| --- | --- | --- | --- |
| Debug | `es.robertodorado.robstore.debug` | RobStore DEV | `0.1.0-DEV` |
| Release | `es.robertodorado.robstore` | RobStore | `0.1.0` |

Pueden convivir instaladas. Debug utiliza la firma de desarrollo automática del
SDK; no hay firma privada Release configurada ni claves versionadas.
`assembleRelease` produce un APK **sin firmar**, que requiere una firma antes de
instalarlo. Los iconos utilizan el PNG oficial con transparencia; Debug añade
un badge D mediante recursos exclusivos de `src/debug`. Véase la
[comparativa de iconos](docs/assets/RS-01-launcher-preview.png).

## Compilación y pruebas

Desde la raíz del repositorio:

```sh
./gradlew --version
./gradlew clean
./gradlew test
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew lint
```

`test` ejecuta pruebas de ambas variantes mediante Robolectric sobre API 28.
Comprueban descubrimiento, manifiestos, Releases/APK, HTTP y cuota, caché/offline,
refresco, iconos, identidad, launcher, Activity, navegación y estados visuales.
Los tests usan transporte simulado y no consultan APIs reales. No requieren
emulador y no sustituyen una prueba visual en dispositivo. La primera ejecución necesita acceso a Google Maven, Maven Central y
las distribuciones Gradle.

Los APK se generan en `app/build/outputs/apk/`; informes de tests en
`app/build/reports/tests/` y de lint en `app/build/reports/`.
La CI ejecuta tests, lint y compilaciones Debug/Release, sin publicación ni firma.
Validación RS-02: **136 pruebas sin fallos ni omisiones**, `lint`, `assembleDebug`
y `assembleRelease` correctos. Lint conserva siete avisos de versiones más recientes.
Resultados y limitaciones en [docs/RS-02.md](docs/RS-02.md).

## RobStyle y fases siguientes

`ui/robstyle` aplica una paleta azul profundo/cian, tipografía, formas y componentes
reutilizables sobre Material 3. El tema es oscuro por defecto. Previews incluyen
pantallas compactas, grandes y texto ampliado. Consulta [docs/RS-01.md](docs/RS-01.md)
para navegación, estados, validaciones y generación reproducible del icono oficial.

Para incorporar otra app: crea un repositorio público de `robdor80`, añade el topic
`robstore-app`, versiona `robstore.json` e icono y publica una Release estable con
el APK declarado. El manifiesto se lee desde la etiqueta de la Release. Cada
actualización usa una nueva etiqueta/Release; no requiere recompilar RobStore.

Fases posteriores incorporarán verificación real de integridad/firma, consulta de
apps instaladas, descarga, instalación y comparación de versiones. RS-02 no las
implementa ni afirma que los metadatos de GitHub sean verificación criptográfica.

Consulta [docs/RS-00.md](docs/RS-00.md) para las decisiones, el entorno Cloud y las
validaciones de esta entrega.
