# RobStore Android

Tienda Android privada para distribuir aplicaciones propias. GitHub será el backend
inicial de distribución; no se utilizarán Firebase ni un backend propio.

**Estado: RS-01 — RobStyle + navegación (preparada para auditoría).** Home, Detalle y Ajustes con Navigation Compose.
No hay catálogo, red, descargas, instalaciones ni actualizaciones.

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
app/src/main/       Manifest, MainActivity, ui/RobStoreApp y ui/robstyle/
app/src/debug/      Nombre DEV y recursos del icono DEV
app/src/test/       Pruebas de identidad, manifiesto y arranque Compose
gradle/            Catálogo de versiones y Wrapper
.github/workflows/  Validación Android
docs/              Decisiones y evidencia de RS-00
```

Un único módulo `app`. La entrada Compose separa Activity, pantalla y tema.
Cuando exista estado de pantalla, se incorporarán ViewModel, StateFlow y recolección
con ciclo de vida; ahora no hay estado mutable que justifique esas piezas.
Las rutas son `home`, `settings` y `app/{appId}`. No hay módulos `data`/`domain` ni servicios anticipados.

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
Comprueban identidad, nombre, sufijo DEV, recursos launcher, Activity, render,
navegación y estados visuales. No requieren emulador y no sustituyen una prueba visual en
dispositivo. La primera ejecución necesita acceso a Google Maven, Maven Central y
las distribuciones Gradle.

Los APK se generan en `app/build/outputs/apk/`; informes de tests en
`app/build/reports/tests/` y de lint en `app/build/reports/`.
La CI ejecuta tests, lint y compilaciones Debug/Release, sin publicación ni firma.

## RobStyle y fases siguientes

`ui/robstyle` aplica una paleta azul profundo/cian, tipografía, formas y componentes
reutilizables sobre Material 3. El tema es oscuro por defecto. Previews incluyen
pantallas compactas, grandes y texto ampliado. Consulta [docs/RS-01.md](docs/RS-01.md)
para navegación, estados, validaciones y generación reproducible del icono oficial.

Fases posteriores incorporarán catálogo e integración GitHub, consulta de apps
instaladas, descargas, instalaciones y actualizaciones. Su orden y alcance se
concretarán en cada fase. RS-00 no implementa estas funcionalidades.

Consulta [docs/RS-00.md](docs/RS-00.md) para las decisiones, el entorno Cloud y las
validaciones de esta entrega.
