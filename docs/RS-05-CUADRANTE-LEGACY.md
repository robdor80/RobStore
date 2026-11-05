# RS-05: transición de Cuadrante 1.0.0 e importación de identidad

Esta adaptación no publica, no ejecuta prepare y no modifica la Release histórica.
No implementa AP_POLICY_V1. Las claves del propietario no están disponibles en Cloud.

## Auditoría de origen (2026-11-05 UTC)

- RobStore/main: `b15d775833c4167c45d79b5b6bb0254db6b0caea`.
- Cuadrante_App/main: `a7e27c1c34c8cd8ee7425ba766f1fa7a5b7066b7`.
- Repositorio público, topic `robstore-app`.
- Release estable publicada `433418870`, tag `v1.0.0`, commit histórico
  `8513721961fd980347bf63c7bc3ab6470cf1d329` (ref ligero de tipo commit).
- Asset `707829735`, `cuadrante-v1.0.0.apk`, 9868158 bytes.
- GitHub y bytes descargados: SHA-256
  `e514c058212eb1bd11f9747488f82ca22b904de40ce46a250ea93ebe462e362a`.
- `aapt`: `es.robertodorado.cuadrante`, code 1, name 1.0.0, SDK 26/36.
- `apksigner verify --verbose --print-certs`: v2 y v3 válidas, un firmante,
  certificado público SHA-256
  `c85a092878e85a35b8ca84132bdf00314da3e90dae80fc137ac52a27d11491e7`.

## Aprobación legacy acotada

`tools/approved-legacy-publications.json` es un registro CENTRAL incluido en el
commit inmutable de tooling revisado. La aplicación no puede aportar su propia
excepción. Su revisión y fusión autorizan exclusivamente esa publicación; no es
una opción de dispatch para saltarse contratos.

El motor conserva el camino moderno y el rechazo por defecto ante un contrato
inexistente. Para la entrada aprobada comprueba respuesta GitHub correcta, repo,
Release ID, tag, tipo/ref y commit exactos, estado estable/publicado, asset ID,
nombre, estado, tamaño y digest GitHub obligatorio. Lee el catálogo en el commit
inmutable y descarga el asset por ID. Verifica SHA-256 de los bytes, firma v2,
certificado, alineación, paquete, code/name y SDK del APK real. Sólo entonces
construye en memoria el contrato anterior. No escribe archivos en el tag/Release.
No trata errores de red, permisos, JSON inválido ni contratos modernos inválidos
como ausencia aprobada. Si existe contrato en el árbol histórico, se usa ese
contrato y no se permite eludir su validación.

El suelo de versión se comprueba incluso antes de descargar. La Release aprobada
debe seguir existiendo estable; borrarla o convertirla en borrador no elimina el
suelo. Release code <=1 se rechaza; code 2 puede superar esta comprobación.
DEV mantiene su canal separado. Los candidatos Release y publicación se atan
además al certificado histórico antes de aceptar la firma.

El registro es público, no un almacén de claves. Incorporar otra publicación
legacy requiere otra revisión del tooling central; no se aceptan tags mutables
como única fuente de confianza ni hashes opcionales.

## Activación y pin

Cuadrante llama al workflow reutilizable auditado, sin cambios, fijado a
`b15d775833c4167c45d79b5b6bb0254db6b0caea`. Ese workflow descarga scripts del SHA
completo `ANDROID_DISTRIBUTION_TOOLING_SHA`, que el guard exige pertenecer al
historial aprobado de RobStore/main. Son dos referencias con responsabilidades
distintas: workflow auditado y tooling central aprobado. El tooling original
`b15d775...` NO resuelve el legacy; no usarlo como variable para esta aplicación.

La adaptación base se incorporó externamente mediante la PR #7 a
`ce63ca1a323e0021036d1ca099fa04943f67f398`. Después de revisar e incorporar la
corrección de alta Termux, elegir un SHA completo de
RobStore/main que contenga este registro, el motor y el importador. Ejecutar el
importador desde ESE checkout. No utilizar una rama de PR como tooling ni habilitar
la distribución antes de la fusión. No es necesario modificar el workflow central
ni apuntarlo a `@main`. Las PR no se fusionan en esta tarea.

## Propietario: exclusivamente Android/Termux

NO ejecutar estos pasos desde Codex, Cloud, CI, una terminal controlada por IA ni
compartir capturas/salidas privadas. Mantener el keystore original y su respaldo;
no generar una nueva Release para superar un fallo. La herramienta se niega a
generar Release para un repositorio con baseline histórico.

1. Recuperar el keystore histórico en el HOME privado de Termux, fuera de Git y de
   `/sdcard`/almacenamiento compartido. Recuperar alias y contraseñas con el gestor
   personal. No introducirlas en argumentos, archivos de proyecto o chat.
2. Instalar los paquetes mantenidos de Termux:

   ```bash
   pkg update
   pkg install git python gh openjdk-21 gnupg pinentry apksigner
   export GPG_TTY=$(tty)
   gh auth login --web
   ```

   El paquete apksigner proporciona `$PREFIX/share/java/apksigner.jar`. El alta usa
   explícitamente ese verificador Java para que funcione en ARM sin SDK Linux x86,
   aapt, zipalign ni enlaces simulando ANDROID_HOME. Si falta el JAR se detiene.
   El runner de distribución conserva su verificación completa con SDK35: firma,
   hash, paquete, versión, SDK y alineación. El alta personal verifica el mismo APK
   exacto mediante hash aprobado y certificado; no cambia las reglas del runner.
3. Comprobar que Java y el JAR del paquete están disponibles:

   ```bash
   java -version
   test -f "$PREFIX/share/java/apksigner.jar" || exit 1
   ```

4. Descargar y comprobar personalmente el APK oficial en un directorio privado:

   ```bash
   mkdir -m 700 "$HOME/cuadrante-historical-check"
   cd "$HOME/cuadrante-historical-check"
   gh release download v1.0.0 --repo robdor80/Cuadrante_App \
     --pattern cuadrante-v1.0.0.apk
   printf '%s  %s\n' \
     e514c058212eb1bd11f9747488f82ca22b904de40ce46a250ea93ebe462e362a \
     cuadrante-v1.0.0.apk | sha256sum -c - || exit 1
   java -jar "$PREFIX/share/java/apksigner.jar" verify --verbose --print-certs cuadrante-v1.0.0.apk || exit 1
   ```

   Debe mostrar un único certificado con la huella pública indicada arriba.
5. Leer la huella pública del keystore recuperado, sin passwords en argv:

   ```bash
   set +x
   read -r -p 'Ruta privada del keystore histórico: ' historical_store
   read -r -p 'Alias histórico: ' historical_alias
   read -r -s -p 'Contraseña del keystore: ' CUADRANTE_OWNER_STORE_PASSWORD
   printf '\n'
   export CUADRANTE_OWNER_STORE_PASSWORD
   keytool -exportcert -keystore "$historical_store" -alias "$historical_alias" \
     -storepass:env CUADRANTE_OWNER_STORE_PASSWORD -file historical-public.der || exit 1
   unset CUADRANTE_OWNER_STORE_PASSWORD
   sha256sum historical-public.der
   ```

   Comparar exactamente con el certificado del APK, NO con el SHA del archivo APK.
   Ante cualquier diferencia: ABORTAR. El importador repite obligatoriamente la
   comprobación y prueba acceso a la clave privada al importarla.
6. Desde el checkout central aprobado ejecutar personalmente (sustituir SHA):

   ```bash
   python3 tools/android_owner_setup.py \
     --repository robdor80/Cuadrante_App \
     --private-directory "$HOME/cuadrante-rs05-private" \
     --tooling-sha SHA_COMPLETO_APROBADO_EN_ROBSTORE_MAIN \
     --import-release-keystore "$historical_store" --legacy-tag v1.0.0
   ```

   `--import-release-keystore` es incompatible con generación/restore/resume.
   La copia se normaliza a PKCS12/alias `android-release` con una nueva contraseña
   robusta de respaldo, **con la misma clave/certificado**. No toca el original.
   La contraseña histórica de almacén y de clave se pide con `getpass`; la nueva
   contraseña RS-05 se pide también sin eco (mínimo 20 caracteres). Ninguna va en
   argv. Sólo DEV se genera independiente, localmente por el propietario.
7. La herramienta crea un respaldo AES256/GPG que contiene ambas identidades y
   metadatos públicos. Subir personalmente a Drive privado, descargar con otro
   nombre y verificar recuperación cifrada. Sin esa prueba no hay secretos en
   GitHub. Guardar recuperación del gestor también fuera del móvil.
8. Tras confirmación personal, la herramienta crea/valida `android-release`,
   `android-dev`, `android-publish` con Roberto como revisor y sólo rama main;
   verifica todos los pins existentes, incluidos los de publicación, y rechaza
   secretos preexistentes sin pin. Deshabilita distribución durante la carga y
   sólo la habilita al completar los secretos en entornos protegidos y variables.
   No sustituye una identidad distinta. No escribe secrets a nivel repositorio.
   Una interrupción requiere investigar y `--resume` con las mismas identidades,
   o `--restore-backup` en nuevo directorio; nunca regenerar Release.

## Antes del primer prepare real

Revisar e incorporar las PR complementarias de auditoría y obtener CI Android PASS para
el HEAD real de Cuadrante/main. El propietario debe completar la importación,
respaldo y protecciones anteriores. Implementar la futura funcionalidad y aumentar
versionCode (>1), por ejemplo 2/1.1.0, en otra tarea. Sustituir las notas
`<!-- NOT_FOR_PUBLICATION -->` por notas reales revisadas. Hasta entonces el guard
bloquea prepare; no crear `v1.1.0` para quitar ese bloqueo.

Después: dispatch manual desde main con source_sha igual al HEAD revisado;
prepare firma en runner separado sin Gradle/código de aplicación. Revisar APK,
run ID y SHA-256. Probar físicamente actualización sobre v1.0.0 con datos, sin
uninstall, `pm clear`, downgrade ni cambio de firma. Sólo otra autorización
explícita del propietario permite publish con `PUBLICAR <tag>`.

La futura publicación genera `robstore-update.json` y `robstore.json` desde el
APK firmado real en un commit de cierre exclusivo del tag, sin mover main. No
inventar futuros metadatos en main ni reescribir el robstore.json histórico.
RobStore descubrirá la nueva Release estable con versionCode superior y podrá
mostrar «Actualización disponible» para code 1.

## Datos y límites físicos

Conservar paquete y certificado es obligatorio para actualizar con datos.
Cuadrante protege `app_settings.preferences_pb`, `incidents.preferences_pb`,
`incidents_v1`, `pattern_type`, `shift_sequence`, `reference_date` mediante reglas
de regresión en su CI. No hay migración AP_POLICY_V1 ni borrado de DataStore.

Esta tarea verifica firma pública y metadatos del APK histórico; no conoce ni
prueba acceso a la clave privada real. Sólo Roberto puede comprobar su recuperación
con el importador. La conservación real de datos y aceptación por el instalador
Android requieren el teléfono y un candidato futuro. Un PASS de fixtures no
sustituye esa prueba física ni afirma que los entornos/secrets estén configurados.

El importador exige un checkout central limpio exactamente en `--tooling-sha` y
usa umask 077. No importa sobre un destino existente ni sobre el original.


## Auditoría posterior al avance de main

Esta sesión comenzó en b15d775 (RobStore) y a7e27c1 (Cuadrante). Durante el trabajo
se detectaron fusiones externas: RobStore #7 -> ce63ca1a323e0021036d1ca099fa04943f67f398,
Cuadrante #2 -> f1d918f66940d2699c227f9e67afc4eb130e9c75, ambos con CI main PASS.
No se hicieron esas fusiones desde esta tarea. La corrección actual se basa en esos
main; conserva su baseline, argumentos de importación, configuración y workflow auditado.
La PR #8 también se incorporó externamente a
`50a7ec0f2f0720040467d1187fb07b67ff8b27c0`; esta corrección parte de ese main
y conserva su parser nativo. El alta personal usa el JAR Java explícito. No requiere esa excepción nativa para funcionar en Termux.

Antes de subir secretos se comprueba que los alias importados/recuperados sean entradas
privadas, que las rutas estén fuera de cualquier repositorio Git y que también los entornos
recién creados cumplan efectivamente sus protecciones (relectura de GitHub). Se conserva
el rechazo de pins diferentes o secretos sin identidad y la prueba de respaldo cifrado.
La integración JKS con passwords distintos demuestra que normalizar el contenedor preserva
el certificado; las claves de esas pruebas son efímeras y nunca identidades del propietario.
