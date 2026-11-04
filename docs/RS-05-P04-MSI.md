# RS-05 P04 — Guía operativa personal en el MSI

Esta guía permite a ChatGPT orientar al propietario **sin recibir secretos**. No ejecutar
publicación durante P04. Seguir después de auditoría y autorización local del propietario.
Nunca pegar en un chat el archivo privado, contraseñas, keystores ni su contenido.

## 1. Preparar un entorno local confiable

1. Confirmar el repositorio `robdor80/RobStore`, rama y commit aprobado de P04.
   Trabajar exclusivamente en el MSI, sin terminal cloud ni escritorio compartido.
2. Disponer de Git, Python 3.11+, JDK 17, Java 21 para Robolectric, Android SDK 36,
   build-tools 35.0.0 y GitHub CLI autenticado personalmente. Mantener `JAVA_HOME`
   en JDK 17 y `JAVA_HOME_21_X64` en Java 21; `ANDROID_HOME` apunta al SDK.
3. Comprobar `git status --short`, `git rev-parse HEAD`, `java -version`, `gh auth status`
   (no compartir credenciales) y `git remote -v`. Auditar plugins/init scripts Gradle.
4. Ejecutar validación sin claves:
   `./gradlew -I tools/unsigned-validation.gradle test lint lintRelease assembleDebug assembleRelease --no-daemon`.
   En PowerShell usar `.\gradlew.bat` en lugar de `./gradlew`.
   Ejecutar `python tools/check_rs04_regressions.py`,
   `python tools/verify_rs04_apks.py --unsigned-debug`,
   `python -m unittest discover -s tools -p test_distribution.py -v` y
   `python tools/check_distribution_secrets.py`.
5. No ejecutar un script remoto ni copiar secretos a Actions. Los ejemplos siguientes
   usan variables de rutas/huellas públicas que el propietario establece localmente;
   no contienen contraseñas. Las instrucciones de build de distribución usan Python,
   que selecciona el wrapper adecuado al sistema.

## 2. Crear personalmente las dos identidades (una sola vez)

Crear directorio privado fuera del checkout, protegerlo con ACL de Windows y BitLocker.
En PowerShell establecer `$privateDir` a esa ruta elegida personalmente. No usar una
ruta sincronizada ni dentro de RobStore. Crear dos almacenes separados, sin sobrescribir
ninguna clave ya existente. `keytool` solicita las contraseñas interactivamente:

```powershell
keytool -genkeypair -storetype PKCS12 -keystore "$privateDir/robstore-release.p12" -alias robstore-release -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000
keytool -genkeypair -storetype PKCS12 -keystore "$privateDir/robstore-dev.p12" -alias robstore-dev -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000
```

Introducir datos de titular reales que se desee incluir en el certificado público;
no escribir información privada innecesaria: el certificado es público. Usar contraseñas
únicas generadas en un gestor; no añadir `-storepass`/`-keypass` con valores al comando.
Comprobar la fecha de expiración y guardar alias/tipo de almacén en el gestor.

Obtener las huellas **públicas**:

```powershell
keytool -list -v -storetype PKCS12 -keystore "$privateDir/robstore-release.p12" -alias robstore-release
keytool -list -v -storetype PKCS12 -keystore "$privateDir/robstore-dev.p12" -alias robstore-dev
```

Registrar SHA-256, quitar los `:` y usar minúsculas en la configuración. Deben ser distintos.
No confundir SHA-256 del **certificado** con SHA-256 del **APK**. Si ya hay clave oficial,
compararla con el registro conservado; no generar otra para reemplazarla.

## 3. Configurar firma y recuperación

1. Crear con editor local un archivo Java Properties privado UTF-8 sin BOM en `$privateDir`.
   Introducir los campos `release.storeFile`, `release.storePassword`, `release.keyAlias`,
   `release.keyPassword`, `release.certificateSha256`, y los cinco equivalentes `dev.*`.
   Usar las rutas absolutas reales con `/`, contraseñas personales y huellas públicas.
   No hay plantilla con contraseñas de ejemplo. Respetar escapes de Java Properties
   descritos en [el informe](RS-05-P04.md).
2. En esa sesión PowerShell, establecer sólo la ruta:
   `$env:ROBSTORE_SIGNING_FILE = "$privateDir/signing.properties"`.
   No establecer contraseñas como argumentos ni pegarlas en instrucciones de ChatGPT.
3. Aplicar ACL al archivo para que sólo lo lea el propietario y cuentas administrativas
   imprescindibles. Verificar que `.gitignore` no sustituye la custodia fuera de Git.
4. Hacer dos copias cifradas en soportes físicamente separados. Incluir ambos keystores,
   configuración, alias y huellas. Guardar recuperación del cifrado en ubicación separada.
   Ensayar restauración local y comparar certificados, sin cambiar claves originales.
5. Ante pérdida del MSI, recuperar las copias y verificar SHA-256 antes de firmar.
   **Perder la clave Release puede impedir actualizar instalaciones existentes**.
   No asumir que regenerar una clave o recuperar el mismo alias conserva la firma.

## 4. Aprobar versión y congelar el código

1. Consultar todas las publicaciones oficiales del canal y el mayor versionCode.
   Para primera estable, revisar también cualquier instalación previa del mismo paquete.
2. Aprobar `versionName` Release `X.Y.Z` y versionCode estrictamente mayor al anterior;
   DEV añade automáticamente `-DEV`. Editar `app/build.gradle.kts` sólo con esa aprobación.
   No subir arbitrariamente el número durante P04.
3. Preparar notas de versión en un archivo versionado antes de congelar el código.
   Revisar tests/CI del commit y guardar herramientas/huellas públicas en el registro local.
4. Crear el commit fuente **S**, subirlo/revisarlo en la rama autorizada y exigir árbol
   limpio. No crear tag. Desde este paso no cambiar código, recursos, documentación,
   configuración de build ni herramientas. No debe haber init scripts no auditados.

## 5. Construir un candidato firmado una sola vez

Establecer `$releaseCert` o `$devCert` a las huellas **públicas verificadas localmente**.
Elegir `$candidateDir`, un directorio **nuevo** fuera del repositorio y separado de las
claves. Para Release:

```powershell
python tools/distribution.py build --channel release --certificate $releaseCert --output $candidateDir
```

Para DEV persistente (no habilita autoactualización):

```powershell
python tools/distribution.py build --channel dev --certificate $devCert --output $candidateDir
```

Guardar el APK final, SHA256SUMS, contrato y recibo. No volver a compilar ni re-firmar
ese candidato después de obtener su hash. No usar el APK Debug temporal de Actions.
Revisar firma y certificado adicionalmente con las herramientas del SDK:

```powershell
& "$env:ANDROID_HOME/build-tools/35.0.0/apksigner.bat" verify --verbose --print-certs $apkFinal
& "$env:ANDROID_HOME/build-tools/35.0.0/zipalign.exe" -c -p 4 $apkFinal
Get-FileHash -Algorithm SHA256 $apkFinal
```

`$apkFinal` es la ruta del APK dentro de `$candidateDir`. Comparar huella con keytool y
hash con SHA256SUMS. La firma definitiva sólo se valida al hacer esto en el MSI con la
clave real; las fixtures cloud no lo acreditan.

## 6. Incorporar contrato y procedencia

Para primera publicación del paquete, en S:

```powershell
python tools/distribution.py prepare --channel release --certificate $releaseCert --candidate $candidateDir --first-publication
```

En futuras publicaciones sustituir `--first-publication` por `--previous $previousContract`,
archivo descargado del **tag de la publicación oficial anterior del mismo paquete**.
Para DEV usar `--channel dev --certificate $devCert` en todos los pasos.

Revisar `git diff`: únicamente `robstore-update.json` y `robstore-distribution.json`.
El contrato debe contener esquema 1, canal, paquete, versión interna real, nombre exacto
del asset y hash del APK ya firmado. No introducir valores provisionales manualmente.
Crear el commit de cierre **C** sólo con esos dos archivos:

```powershell
git add -- robstore-update.json robstore-distribution.json
git commit -m "Prepara contrato de distribucion verificado"
```

C señala un APK compilado desde S. El árbol restante debe ser idéntico: el validador
lo comprobará. No cambiar ni una nota después de S; si hay que corregir algo, abandonar
candidato, crear nuevo S y repetir con directorio nuevo. No subir APK al repositorio.
Subir C a la misma rama autorizada para revisión mediante el flujo normal, sin tags.

## 7. Validación previa y punto de parada

Establecer `$tag` al nombre previsto, `vX.Y.Z` para Release o
`dev-X.Y.Z-DEV-N` para DEV, con N igual al versionCode real:

```powershell
python tools/distribution.py verify --channel release --certificate $releaseCert --apk $apkFinal --contract robstore-update.json
python tools/distribution.py preflight --channel release --certificate $releaseCert --apk $apkFinal --tag $tag --first-publication
```

Omitir `--first-publication` en futuras publicaciones. `preflight` requiere conexión a
GitHub, revisa el commit disponible, todos los contratos publicados del canal, código
creciente y ausencia de tag local/público. Un fallo de red no permite continuar offline.
Guardar su salida pública y comparar C con el commit revisado de GitHub.

**Detenerse aquí durante P04.** No ejecutar creación/push de etiquetas ni subir assets.
Después, con autorización expresa de publicación y revisión final, el propietario:

1. Repite preflight justo antes de publicar y serializa la operación para evitar carreras.
2. Crea una etiqueta anotada (preferiblemente firmada con su identidad Git) sobre C y
   verifica que el tag resuelve exactamente a C. Nunca etiqueta S ni mueve un tag previo.
3. Prepara en `robdor80/RobStore` las notas, versión y **el mismo APK conservado**, más
   SHA256SUMS; Release estable sin prerelease, DEV exclusivamente prerelease. Puede
   adjuntar recibo/contrato como información adicional, pero P03 exige el contrato en C.
4. Revisa todo antes de publicar, sin rebuild. Comprueba después el tag, contrato desde
   `refs/tags/<tag>` y hash/firma del APK descargado; conserva registro local de S/C/tag.
5. No anuncia continuidad DEV hasta completar distribución real y pruebas autorizadas.

Estos pasos describen el procedimiento futuro; no autorizan una Release durante P04.
No hay automatismo de publicación. ChatGPT debe presentar el resultado concreto para la
aprobación de publicación posterior, sin pedir ni recibir secretos.

## 8. DEV temporal, activación y P05

Mantener `devDistributionConfigured=false` hasta disponer de distribución DEV persistente
real y verificada y autorizar por separado su activación/auditoría. Una firma temporal
anterior distinta puede requerir desinstalar una vez: **pueden perderse los datos locales**.
No prometer migración automática ni compartir certificado con Release. Conservar el
artefacto DEV temporal de Actions para pruebas manuales; no es una fuente persistente.
P05 queda pendiente de autorización e incluirá pruebas físicas de actualización, rechazo,
cancelación, reinicio/conservación de datos y accesibilidad.
