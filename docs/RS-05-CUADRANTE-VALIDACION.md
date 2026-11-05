# Evidencia local de la adaptación RS-05 de Cuadrante

Bases remotas verificadas sin avance: RobStore b15d775833c4167c45d79b5b6bb0254db6b0caea;
Cuadrante_App a7e27c1c34c8cd8ee7425ba766f1fa7a5b7066b7.

| Campaña | PASS | FAIL | SKIP |
| --- | ---: | ---: | ---: |
| RobStore Android Debug | 254 | 0 | 0 |
| RobStore Android Release | 254 | 0 | 0 |
| Python distribución/firma/backup/legacy | 72 | 0 | 0 |
| Total tests RobStore | 580 | 0 | 0 |

Comandos: con JDK17 para Gradle, JAVA_HOME_21_X64 para Robolectric y Android SDK36/build-tools35:

```sh
./gradlew -I tools/unsigned-validation.gradle test lint lintRelease assembleDebug assembleRelease --no-daemon
python3 -m unittest discover -s tools -p 'test_*.py' -v
python3 tools/check_rs04_regressions.py
python3 tools/check_rs05_regressions.py
python3 tools/verify_rs04_apks.py --unsigned-debug
python3 tools/check_distribution_secrets.py
git diff --check
```

Retenidos todos los casos auditados RS03 (99/variante), RS04 (158/variante),
RS05 P01 (196/variante) y P03 (254/variante). Lint sin errores: 13 avisos existentes
por variante (dependencias/actualizaciones y UsableSpace). Ensamblados ambos APK sin firma.
La primera ejecución local estaba bloqueada por ausencia de JDK17; se instaló fuera de
Git y se repitió la campaña completa correctamente sin cambiar el proyecto.

Los 23 tests nuevos de test_legacy_publications cubren Release moderna, baseline exacta,
SHA obligatorio, tag/asset/repositorio/IDs/estabilidad/commit, paquete/versión, contrato ausente
sin aprobación, código <=1 rechazado y >1 aceptado, posteriores Releases modernas que también
limitan crecimiento, APIs fallidas, bytes incorrectos, certificado histórico y alta personal.
Se añadieron además dos integraciones reales de importación con identidades **efímeras**:
PKCS12 y JKS de alias diferente y passwords separados, conservando certificado tras normalizar.
Las campañas previas de firma y recuperación cifrada Drive siguen pasando.

Se ejecutó también el comparador increasing en modo de sólo lectura contra GitHub real:
volvió a descargar/verificar el APK histórico, rechazó el número 1 y aceptó el número 2
como valor de prueba frente al historial actual. No se construyó un candidato ni se ejecutó
prepare/publish. Se comprobó la ausencia de cambios en el archivo del motor reutilizable
respecto a b15d775, manteniendo compatible el pin auditado con las herramientas nuevas.

Cuadrante, en su PR separada: 70 Debug + 70 Release + 11 Python = 151 PASS, 0 FAIL, 0 SKIP;
lint sin errores (10 avisos existentes por variante), identidades/SDK/versión/ausencia de firma
verificados y contrato generado desde APK real firmado sólo con fixture efímero.

No se ejecutó android_owner_setup.py como herramienta de alta; sólo se probaron sus
funciones en fixtures aislados/mocks. Ningún keystore/certificado privado de Roberto fue
recibido, generado, inspeccionado o configurado. Firma histórica/importación/backup del
propietario y actualización física con datos siguen pendientes según la guía.
Las CI de las PR se consultan en GitHub; estos conteos son de la campaña local completa.
