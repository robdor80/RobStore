# Evidencias de las correcciones RS-03

Ejecución local: **2026-10-31 UTC**. Fuentes verificadas: commit
`bd327626dbb1c6d266981113bbff349810f7d187`, sobre el HEAD auditado
`1fa7d7d1435a6053300cbf60bb37562d90e2ae04`.
Los cambios posteriores a ese commit son documentación y PNG de test.

- [Recuentos por suite y lint](resultados.md), extraídos de los XML originales.
- [Cada caso Debug](tests-debug.tsv) y [cada caso Release](tests-release.tsv).
- [Resultados y diferencia de avisos en CI](ci-implementacion.md).
- [Validaciones y compilaciones](validaciones.md).
- [Casos añadidos y regresiones](pruebas.md).
- [Dimensiones, procedencia y SHA-256 de los PNG](visuales.tsv).
- [Informe de auditoría](../../RS-03-AUDITORIA-CORRECCIONES.md).

El indicador «árbol con cambios» del resumen generado corresponde a los PNG y
la documentación en preparación; las fuentes coinciden con el commit indicado.
No se versionan APK, cachés, XML con rutas del runner, carpetas build ni logs completos.
Los informes originales de cada ejecución CI se publican como artefactos del
workflow Android: `rs03-validation-<SHA del checkout>`, con retención de 30 días.
En `pull_request`, el SHA del checkout es el merge de prueba de GitHub; la página
del run identifica el `head_sha` de la rama. En `push` coincide con el HEAD.

## Reproducción

Requiere JDK 17, Android SDK 36 y Build Tools 35.0.0, como el workflow. Con acceso
a los repositorios Maven/Google y a la distribución Android de Robolectric:

```sh
./gradlew test --no-daemon
./gradlew lint --no-daemon
./gradlew assembleDebug --no-daemon
./gradlew assembleRelease --no-daemon
git diff --check
python3 tools/summarize_rs03.py
```

Los XML originales están en `app/build/test-results/testDebugUnitTest/` y
`app/build/test-results/testReleaseUnitTest/`; lint en `app/build/reports/`.
El exportador produce el resumen y TSV en `app/build/rs03-summary/`. No presupone
un número de casos: cuenta los `<testcase>`, fallos, errores y omisiones reales;
termina con error si faltan informes o hay incidencias.

Los renders se generan en `app/build/rs03-evidence/debug/` y `release/`.
Para regenerar solo la galería:

```sh
./gradlew testDebugUnitTest --tests '*RobUiTest.*' --rerun-tasks --no-daemon
```

Ese comando filtrado no sustituye la suite completa para la auditoría. Los PNG
versionados proceden de Debug, Robolectric API 28, `GraphicsMode.NATIVE`, locale
US, zona UTC y reloj fijo `2026-10-30T00:00:00Z`. Las etiquetas de la aplicación
siguen en español. Son renders JVM sobre Canvas/Skia y fixtures de test; **no son
capturas de Android físico ni pruebas de un catálogo de producción**.
