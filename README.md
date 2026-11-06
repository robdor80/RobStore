# RobStore Android

Catálogo Android personal para distribuir aplicaciones propias y públicas en
GitHub. No utiliza Firebase ni un backend propio.

**Estado: RS-04 — Descarga e instalación segura de APK (PR Draft para auditoría).**
Home presenta una malla adaptable con iconos protagonistas y descubre aplicaciones Android públicas de `robdor80` con topic `robstore-app`,
manifiesto `robstore.json` v1 y una Release estable con el APK exacto declarado.
Detalle muestra metadatos de esa etiqueta publicada. El catálogo se conserva sin
conexión; un catálogo vacío real es válido. La descarga de APK requiere una acción
explícita, revalida el origen, tamaño, SHA-256 cuando existe y paquete Android.
La instalación usa el instalador oficial y permiso por origen. Sin SHA oficial
exige confirmación adicional; abrir el instalador no acredita instalación correcta.
Consulta [RS-04](docs/RS-04.md) para límites y [cómo descargar RobStore DEV desde Actions](docs/RS-04.md#14-descargar-robstore-dev-desde-actions-en-un-móvil).

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
app/src/main/       Manifest, MainActivity, catalog/, download/, install/, ui/ y ui/robstyle/
app/src/debug/      Nombre DEV y recursos del icono DEV
app/src/test/       Catálogo, HTTP simulado, caché, iconos, identidad y Compose
gradle/            Catálogo de versiones y Wrapper
.github/workflows/  Validación Android
docs/              Contrato RS-02, RS-03, descarga/instalación RS-04 y evidencias anteriores
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
./gradlew test --no-daemon
./gradlew lint --no-daemon
./gradlew assembleDebug --no-daemon
./gradlew assembleRelease --no-daemon
git diff --check
```

`test` ejecuta ambas variantes: interfaz en Robolectric API 28 y parser/URI en API 26 y 33.
Comprueban descubrimiento, manifiestos, Releases/APK, HTTP y cuota, caché/offline,
refresco, iconos, identidad, launcher, Activity, navegación y estados visuales.
Los tests usan transporte simulado y no consultan APIs reales. No requieren
emulador y no sustituyen una prueba visual en dispositivo. La primera ejecución necesita acceso a Google Maven, Maven Central y
las distribuciones Gradle.

Los APK se generan en `app/build/outputs/apk/`; informes de tests en
`app/build/reports/tests/` y de lint en `app/build/reports/`.
La CI ejecuta tests Debug/Release, lint, ambas compilaciones y verificación de
identidad, firma Debug, alineación y SHA-256 de los APK reales. Publica evidencias
y, solo en PR internas correctas, un artefacto instalable `robstore-dev-<SHA>`
durante 30 días. No publica Releases ni claves privadas. La firma Debug puede
cambiar entre runners; una DEV anterior puede requerir desinstalarse para instalar
la nueva, con pérdida de sus datos locales. [Resultados RS-04](docs/evidencias/rs04/resultados.md).
Se conservan las 198 ejecuciones RS-03, sin omitir pruebas.

## RobStyle y alcance

`ui/robstyle` aplica una paleta azul profundo/cian, tipografía, formas y componentes
reutilizables sobre Material 3. El tema es oscuro por defecto. Previews incluyen
pantallas compactas, grandes, horizontales, texto ampliado y estados reales.
RS-03 incorpora fichas de producto, Ajustes agrupados y fechas localizadas.
La malla aumenta la anchura mínima con la escala tipográfica y se limita a
1000 dp en pantallas amplias. No hay fixtures accesibles desde producción.
Consulta [docs/RS-03.md](docs/RS-03.md) para decisiones, previews y comprobaciones
manuales reproducibles en Android real. Consulta [docs/RS-01.md](docs/RS-01.md)
para navegación, estados, validaciones y generación reproducible del icono oficial.

Para incorporar otra app: crea un repositorio público de `robdor80`, añade el topic
`robstore-app`, versiona `robstore.json` e icono y publica una Release estable con
el APK declarado. El manifiesto se lee desde la etiqueta de la Release. Cada
actualización usa una nueva etiqueta/Release; no requiere recompilar RobStore.

RS-04 añade descarga streaming en almacenamiento privado, cancelación real,
comprobación de archivo/paquete y apertura interactiva del instalador. No consulta
apps instaladas ni compara sus versiones. Parsear el APK no verifica su firma;
la decisión final de firma/compatibilidad corresponde a Android. Los metadatos de
GitHub no garantizan por sí solos autenticidad del editor. Auditoría y pruebas
físicas pendientes, sin fusión ni publicación de Releases.

Consulta [docs/RS-00.md](docs/RS-00.md) para las decisiones, el entorno Cloud y las
validaciones de esta entrega.


## Publicación desde RobPublisher

[RS-05 automático](docs/RS-05-AUTOMATICO.md): una orden compila, firma con las
identidades existentes y publica la Release. Incluye la migración única de los
entornos actuales y el contrato mínimo del botón de RobPublisher.
