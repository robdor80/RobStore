# RS-05: transición de Cuadrante 1.0.0 sin reescribir su publicación

## Auditoría y límite de esta entrega

Se comprobaron por red los main de RobStore `b15d775833c4167c45d79b5b6bb0254db6b0caea`
y Cuadrante_App `a7e27c1c34c8cd8ee7425ba766f1fa7a5b7066b7`: no habían avanzado.
La Release estable publicada tiene ID `433418870`, tag ligero `v1.0.0` fijado al commit
`8513721961fd980347bf63c7bc3ab6470cf1d329`; su único APK tiene ID `707829735`,
nombre `cuadrante-v1.0.0.apk`, tamaño 9868158 y digest GitHub obligatorio:

`e514c058212eb1bd11f9747488f82ca22b904de40ce46a250ea93ebe462e362a`.

Se descargaron esos bytes y se verificaron con aapt, apksigner y zipalign: paquete
`es.robertodorado.cuadrante`, código 1, versión `1.0.0`, SDK 26/36, no depurable,
firma v2/v3, un firmante RSA 4096. SHA-256 **público del certificado**:

`c85a092878e85a35b8ca84132bdf00314da3e90dae80fc137ac52a27d11491e7`.

Esto no acredita que el propietario haya recuperado su clave privada. Esa comparación
se hará personalmente en Android. No se ejecuta el asistente ni se configuran secretos,
preparan candidatos, publican Releases, crean/mueven tags o fusionan PR en esta entrega.
AP_POLICY_V1 queda fuera del alcance. Las claves de los tests son efímeras y se destruyen.

## Baseline declarativa y cerrada

`tools/legacy-publications.json` pertenece a las herramientas centrales revisadas, nunca
a entradas de workflow ni al código de la aplicación. Sólo aprueba esta publicación
histórica concreta. No se añade ningún archivo al tag/Release antiguo.

Para el tag aprobado se verifica de nuevo contra GitHub la Release publicada estable,
repositorio/URL, IDs, tag y commit inmutable, ausencia del contrato moderno mediante un
listado exitoso del commit y el antiguo robstore.json. Después se exige asset exacto,
estado uploaded, tamaño y digest SHA-256 presente y exacto. Se descarga por ID, se comprueba
el hash local, firma/pin, paquete, código, nombre y SDK reales del APK. Sólo ese resultado
es la versión anterior al comparar crecimiento. La baseline no puede desaparecer del
listado de Releases estables; tampoco puede moverse el tag ni sustituirse el asset.
Un fallo de API bloquea, igual que una Release sin contrato y sin aprobación explícita.

El camino moderno continúa leyendo robstore-update.json como antes. No se debilita su
política. Código nuevo <=1 se rechaza para Cuadrante; >1 supera la baseline, pero también
debe superar todas las demás publicaciones del mismo canal/paquete. El pin histórico
se exige al inspeccionar cualquier candidato Release de Cuadrante, aun si una variable
GitHub se configurara por error con otro certificado.

La indisponibilidad o retirada del APK histórico bloqueará prepare/publish: es intencionado.
Cada comprobación descarga unos 10 MB; no hay caché que sustituya la evidencia de GitHub.
Cambiar la baseline requiere otra revisión de las herramientas centrales.

## Importación personal desde Android/Termux

**Sólo Roberto, sin terminal observada/controlada por Codex. Nunca Cloud, CI ni Actions.**
No ejecutar el alta normal para Cuadrante: el asistente ahora la rechaza porque existe una
baseline histórica. `--import-release` importa la entrada privada JKS/PKCS12 existente,
con su alias y contraseñas originales, a un PKCS12 privado con alias `android-release`.
Cambiar contenedor/alias/contraseña no cambia la clave ni el certificado. Se verifica
PrivateKeyEntry y se exige la misma huella que el APK oficial antes de crear DEV, respaldar
o cargar secretos. La recuperación --resume/--restore-backup también repite esa verificación.

Preparar Termux mantenido desde su fuente oficial:

```bash
pkg update
pkg install git python gh openjdk-21 gnupg pinentry apksigner
export GPG_TTY=$(tty)
gh auth login --web
```

apksigner de Termux usa Java; el alta no requiere aapt/zipalign x86 ni Android Studio.
Recuperar personalmente el keystore histórico y el gestor de contraseñas en el HOME privado
de Termux, fuera de cualquier Git y de almacenamiento compartido. No enviarlos a Codex.
Antes del alta se puede reproducir manualmente la comparación pública:

```bash
set -euo pipefail
umask 077
DIRECTORIO_VERIFICACION=$(mktemp -d "$HOME/cuadrante-verificacion.XXXXXX")
gh release download v1.0.0 --repo robdor80/Cuadrante_App \
  --pattern cuadrante-v1.0.0.apk --dir "$DIRECTORIO_VERIFICACION"
printf '%s  %s\n' \
  e514c058212eb1bd11f9747488f82ca22b904de40ce46a250ea93ebe462e362a \
  "$DIRECTORIO_VERIFICACION/cuadrante-v1.0.0.apk" | sha256sum -c -
apksigner verify --verbose --print-certs \
  "$DIRECTORIO_VERIFICACION/cuadrante-v1.0.0.apk" > "$DIRECTORIO_VERIFICACION/firma-publica.txt"
HUELLA_APK=$(awk '/^Signer #[0-9]+ certificate SHA-256 digest:/ {print $NF}' \
  "$DIRECTORIO_VERIFICACION/firma-publica.txt")
# Sólo rutas y alias, nunca contraseñas. keytool solicitará la contraseña sin eco.
read -r -p 'Ruta privada del keystore histórico: ' KEYSTORE_HISTORICO
read -r -p 'Alias histórico: ' ALIAS_HISTORICO
keytool -exportcert -keystore "$KEYSTORE_HISTORICO" -alias "$ALIAS_HISTORICO" \
  -file "$DIRECTORIO_VERIFICACION/certificado-historico.der"
HUELLA_KEYSTORE=$(sha256sum "$DIRECTORIO_VERIFICACION/certificado-historico.der" | cut -d ' ' -f 1)
test "$HUELLA_APK" = c85a092878e85a35b8ca84132bdf00314da3e90dae80fc137ac52a27d11491e7
test "$HUELLA_KEYSTORE" = "$HUELLA_APK"
printf '%s\n' 'Certificados coincidentes. Se puede continuar con el alta personal.'
```

Cualquier error obliga a abortar y recuperar la clave correcta. Nunca generar otra
Release para superar un fallo. Los archivos de esa verificación son públicos.

Después de revisar y fusionar la adaptación de RobStore, seleccionar explícitamente un
SHA completo auditado de su main que contenga esta herramienta y baseline. Clonar RobStore,
hacer checkout de ese SHA y ejecutar desde su raíz (SHA_HERRAMIENTAS es público):

```bash
git checkout --detach "$SHA_HERRAMIENTAS"
python tools/android_owner_setup.py --repository robdor80/Cuadrante_App \
  --private-directory "$HOME/.android-signing/cuadrante" \
  --tooling-sha "$SHA_HERRAMIENTAS" --import-release --catalog
```

El script verifica que checkout y pin coincidan y que el SHA pertenezca al main aprobado.
Solicita ruta/alias y contraseñas mediante prompts privados. Nunca passwords en argv,
logs o Git. Las contraseñas de destino PKCS12 deben ser distintas para Release/DEV y tener
>=20 caracteres. Sólo DEV puede generarse nueva, independiente de la Release histórica.
No sobrescribe directorios ni entornos preexistentes en modo importación. Si una importación
falla deja el material sólo en el directorio privado: detenerse e inspeccionarlo personalmente;
no borrar ni reemplazar la identidad distribuida. Si hay registro completo, recuperar con
--resume o --restore-backup, según ANDROID-MOBILE-FIRST.md, nunca volver a generar Release.

Antes de configurar GitHub exige backup AES-256 GPG, subida personal a Drive privado,
descarga con otro nombre y recuperación byte a byte. Mantener otra copia cifrada independiente
y la recuperación del gestor fuera del teléfono. Tras CONFIGURAR GITHUB valida/requiere
entornos android-release/android-dev/android-publish revisados por Roberto y limitados a
main; comprueba pins existentes de ambos canales y publicación, rechaza secretos sin pin,
desactiva distribución durante las escrituras y sólo la activa al completar todo.
Secretos únicamente en android-release/android-dev; android-publish sólo recibe huellas.
Nunca usar secrets: inherit ni introducir claves en Gradle, artifacts, commits o PR.

## Pins y orden de incorporación

El workflow de Cuadrante llama al motor fijado a
`b15d775833c4167c45d79b5b6bb0254db6b0caea`, que ya pertenece al historial auditado de main.
Esta adaptación **no modifica** android-distribution-engine.yml: ese mismo motor acepta
ANDROID_DISTRIBUTION_TOOLING_SHA y carga las herramientas centrales del pin verificado
por guard. Mantener el motor auditado y seleccionar herramientas nuevas aprobadas es una
pareja compatible: el archivo del motor sigue idéntico byte a byte. No usar las herramientas
b15d775 para Cuadrante: carecen de baseline/importación. No seleccionar un SHA que sólo esté
en la PR; guard lo rechazará. Una actualización futura del motor exige revisar ambos pins.

Primero revisar/fusionar RobStore y después Cuadrante; esta tarea deja ambas PR abiertas.
El propietario fija ANDROID_DISTRIBUTION_TOOLING_SHA mediante el asistente al commit revisado
que incorpora esta adaptación. Si se hace squash debe usar el SHA resultante revisado,
no asumir que el HEAD de la rama siga perteneciendo al historial de main.
No hace falta cambiar claves para actualizar pins. Las notas provisionales y versionCode=1
siguen bloqueando preparar una nueva Release. La prueba física y el alta personal siguen
pendientes, no se certifican con fixtures.

## Prueba futura de actualización conservando datos

Conservar v1.0.0 instalada con ajustes e incidencias conocidas. Una futura PR funcional
incrementará versionCode (>1), versionName y redactará notas reales sin el marcador.
Tras su fusión y Android CI PASS para ese HEAD de main: prepare manual, aprobación de
android-release, firma aislada con la clave importada y candidato con run ID/hash.
Descargar ese candidato y probar actualización sobre la instalación existente, sin
uninstall, pm clear, downgrade, cambio de paquete/certificado ni borrado de DataStore.
Reiniciar y comprobar app_settings.preferences_pb, incidents.preferences_pb e incidents_v1.
La futura AP_POLICY_V1 decidirá su migración; aquí no se cambia formato ni almacenamiento.

Sólo después de la prueba física y autorización explícita, el propietario podrá ejecutar
publish con ese run ID, hash y PUBLICAR <tag>, aprobar android-publish y comprobar la Release
estable. El cierre generará robstore.json desde el APK firmado real (paquete, asset, versiones,
nombre, descripción, icono), sin inventar un contrato futuro en main. El robstore.json de
v1.0.0/main histórico permanece intacto. RobStore refrescará catálogo: instalado code 1,
publicado superior, Actualización disponible; descargar, entregar al instalador Android,
aceptar actualización y reiniciar Cuadrante comprobando los mismos datos. Requiere prueba
real con el teléfono y la clave histórica, que esta tarea no puede sustituir.
