# Auditoría RS-05: evidencia de compatibilidad de Cuadrante

Se inició contra RobStore b15d775833c4167c45d79b5b6bb0254db6b0caea y Cuadrante
a7e27c1c34c8cd8ee7425ba766f1fa7a5b7066b7. Durante el trabajo hubo fusiones externas:
RobStore #7 -> ce63ca1a323e0021036d1ca099fa04943f67f398, #8 ->
50a7ec0f2f0720040467d1187fb07b67ff8b27c0; Cuadrante #2 ->
f1d918f66940d2699c227f9e67afc4eb130e9c75. Esta tarea no las ejecutó y se adaptó a esos
main, conservando baseline, infraestructura y parser existentes. La primera rama local
se sustituyó por una PR complementaria sobre el estado actual, sin sobrescribir ramas remotas.

| Tests locales | PASS | FAIL | SKIP |
| --- | ---: | ---: | ---: |
| RobStore Android Debug | 254 | 0 | 0 |
| RobStore Android Release | 254 | 0 | 0 |
| Python distribución, firma, legacy, respaldo y alta | 80 | 0 | 0 |
| Total RobStore | 588 | 0 | 0 |
| Cuadrante Android Debug + Release + Python | 154 | 0 | 0 |

Comandos de RobStore (JDK17 para Gradle, JAVA_HOME_21_X64 para Robolectric):

```sh
./gradlew -I tools/unsigned-validation.gradle test lint lintRelease assembleDebug assembleRelease --no-daemon
HISTORICAL_APK=/ruta/publica/cuadrante-v1.0.0.apk python3 -m unittest discover -s tools -p 'test_*.py' -v
python3 tools/check_rs04_regressions.py
python3 tools/check_rs05_regressions.py
python3 tools/verify_rs04_apks.py --unsigned-debug
python3 tools/check_distribution_secrets.py
git diff --check
```

Retenidos todos los casos auditados: RS03 99/variante, RS04 158, RS05 P01 196 y
P03 254. También se ejecutó actionlint sin errores de sintaxis (sin shellcheck/pyflakes
opcionales). Los checks finales de las PR y su HEAD se consultan en GitHub; los conteos
anteriores corresponden a la campaña local. Lint del producto no tiene cambios y sus
avisos existentes no se convierten en errores ni se suprimen mediante esta PR.

Se verificó la Release oficial publicada v1.0.0, tag/commit, IDs, asset/digest y APK real:
es.robertodorado.cuadrante, code1/name1.0.0, SDK26/36, firma v2/v3, SHA-256
`e514c058212eb1bd11f9747488f82ca22b904de40ce46a250ea93ebe462e362a`, certificado público
`c85a092878e85a35b8ca84132bdf00314da3e90dae80fc137ac52a27d11491e7`.
Se repitió lectura del tag, Release y metadatos de asset al cerrar la auditoría, sin cambios.
Sólo aumentan los contadores de descarga por la lectura del APK.

La campaña incluye los diez casos requeridos: moderna válida, legacy aprobado,
hash/tag/asset/paquete/versión incorrectos, ausencia sin baseline, code<=1 rechazado,
code>1 aceptado y campañas RS05 retenidas. La nueva separación de metadatos/descarga
no permite fallback ante contrato moderno JSON null/lista/texto. El camino del runner
sigue inspeccionando el APK firmado completo. El alta Termux sólo necesita el JAR Java
mantenido del paquete apksigner para verificar el mismo APK de hash obligatorio.

Se añadieron pruebas de ese límite personal: JAR ausente, error GitHub, contrato moderno
que no sustituye evidencia histórica, certificado incorrecto/múltiple/sin v2 y alias sin
entrada privada. La integración JKS de passwords separados conserva la huella al importar
el mismo material efímero. No se generaron identidades Release del propietario.

El archivo android-distribution-engine.yml sigue idéntico al motor auditado b15d775.
Mantener ese pin y elegir ANDROID_DISTRIBUTION_TOOLING_SHA de un commit aprobado de main
que incorpore esta corrección. Una rama de PR no es válida para distribuir.
No se ejecutó el asistente real, prepare o publish ni se configuraron secretos reales.
Faltan la importación privada/backup del propietario, futura versión funcional y prueba
física de actualización con datos. No hay AP_POLICY_V1 ni cambios de formato de datos.
