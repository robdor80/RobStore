# Evidencias P04 mobile-first

Adaptación del requisito tras la entrega local `8524f6d899416c37313911bce5a6f0db6e5a2643`.
Las evidencias antiguas se conservan en `../rs05-p04/`; su dependencia de MSI ya no es el
flujo oficial. Producto Android, Gradle, SDK, FileProvider y código P03/RS-04 no cambian.

## Validación local

- `python3 -m unittest discover -s tools -p test_mobile_distribution.py -v`: 24 casos
  (políticas, fixtures con APK real, backup GPG y transacción GitHub simulada).
- Los 17 tests de herramientas y 6 integraciones anteriores se conservan en CI.
- `./gradlew -I tools/unsigned-validation.gradle assembleRelease --no-daemon`: PASS,
  crea el unsigned Release requerido por fixtures sin clave de producción.
- `./gradlew -I tools/unsigned-distribution.gradle assembleDebug --no-daemon`: PASS,
  `aapt dump badging` confirma paquete `.debug`, `versionCode=1`, `0.1.0-DEV`, SDK26/36 y
  ausencia de `application-debuggable`. No se ha usado una clave privada para ese build.
- `actionlint .github/workflows/*.yml`: workflows habituales y nuevos validados.
- `git diff --check` y análisis del historial/diff para claves y secretos.

Los tests rechazan push/PR/PR_target, rama/tag/fork, otros actores/reintentos, fuente main
obsoleta, CI fallida/en curso, herramientas no aprobadas, entornos sin review o que permitan
otras ramas/tags, run de candidato ajeno/fallido, hash del propietario distinto, recibo y
APK alterados, certificado incorrecto o compartido y códigos iguales/inferiores.

La firma real de fixtures se realiza con dos certificados RSA3072/PKCS12, TEST ONLY,
validez 2 días y contraseña aleatoria temporal. Se ejecuta `apksigner` fuera de Gradle;
no se reciben certificados del propietario. Se verifica contrato del APK final y generación
del manifiesto de catálogo con las mismas versiones/paquete/asset. Se prueba también el
perfil genérico de una segunda aplicación mediante una fixture, sin modificar otro repo.

GPG AES256 cifra un archivo de prueba y recupera una copia local que representa el transporte
Drive. Cambiarla provoca rechazo. Se comprueban restauración privada y rechazo de traversal antes de extraer. No se accede a Drive ni se suben claves del propietario:
el round-trip real en su cuenta queda para el alta personal Android. Los ficheros privados
de fixture y passphrases aleatorias están en TemporaryDirectory y se eliminan al terminar.

Los tests de publicación sustituyen **todas** las escrituras y uploads por dobles:
se comprueban padre S del commit C, tag nuevo, draft inicial, digest y paso final a visible;
fallo de upload deja el draft y no ejecuta la publicación final. Se prohíbe reconstruir o
firmar durante esa transacción. Dry-plan no escribe nada y ejecutar requiere habilitación
expresa. No se han creado commits de distribución, tags públicos, Releases ni subido APK
oficiales. El asistente de alta definitiva se prueba únicamente en su rechazo temprano de
Cloud; no se ejecuta su alta personal ni se configuran secretos/entornos/variables reales.

## CI del HEAD final

La CI ordinaria mantiene las campañas completas Debug/Release, lint, compilación, regresiones,
SDK, FileProvider, firma Debug temporal y artefacto instalable de PR interna. Se añade la
suite mobile-first a ese job sin secretos. Los workflows oficiales sólo son manual/call,
no forman parte del push/PR, no se disparan en esta tarea y fallan si falta el alta protegida.

Resultados finales y enlaces por SHA se registran en la descripción de
[PR Draft #6](https://github.com/robdor80/RobStore/pull/6), evitando otra campaña completa
por actualizar únicamente enlaces documentales. La entrega queda para auditoría, sin
merge y sin avance a P05. Los tests no acreditan firma definitiva, entorno real de producción,
backup real de claves en Drive ni compatibilidad física Android.
