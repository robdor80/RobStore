# Android personal: desarrollar y distribuir desde el móvil

## Regla del proyecto

El móvil es el dispositivo principal de control. Codex Cloud prepara código, pruebas,
documentación y PR; GitHub ejecuta CI, compila, firma y publica con autorizaciones del
propietario. No se requiere MSI, Android Studio local ni un ordenador encendido.
Esta guía sustituye la dependencia local de la primera entrega P04 (`8524f6d`).
No se han configurado secretos definitivos, creado Releases, etiquetas o publicado APK
oficiales durante la preparación de esta infraestructura.

## Uso habitual desde Android

1. Pedir los cambios a Codex Cloud y revisar la PR y sus evidencias desde el navegador.
2. Esperar tests Debug/Release, lint y comprobaciones de seguridad de la CI habitual.
   Los APK temporales de PR siguen descargándose desde Actions para pruebas manuales.
3. Una vez auditado/aprobado el cambio, el propietario autoriza por separado su incorporación
   a `main`. **Esta tarea no fusiona nada.** Revisar/aprobar también versión y notas,
   sustituyendo las provisionales y retirando su marcador `NOT_FOR_PUBLICATION`.
4. En GitHub web → Actions → **Android distribution (owner manual)** → Run workflow,
   elegir `main`, operación **prepare**, canal y etiqueta. El SHA fuente se captura del
   commit seleccionado de main; opcionalmente se puede pegar el SHA completo aprobado.
5. Aprobar el entorno `android-release` o `android-dev` desde el navegador del móvil.
   GitHub compila sin secretos y luego firma en otro runner. Revisar el resumen, descargar
   el candidato y conservar run ID y SHA-256. Preparar **no crea tags ni Releases**.
6. Para publicar ese candidato concreto, ejecutar de nuevo el workflow con **publish**,
   el mismo SHA fuente/canal/tag, el ID del run prepare, su SHA-256 exacto y el texto
   `PUBLICAR <etiqueta exacta>`. Aprobar además el entorno **android-publish**.
7. La publicación verifica otra vez origen, CI, contrato, firma, hash y versión creciente;
   crea el commit de cierre y etiqueta, sube el mismo APK, comprueba el digest de GitHub y
   sólo entonces hace visible la Release. No recompila ni vuelve a firmar el candidato.
8. Desde RobStore, actualizar el catálogo respetando sus intervalos de caché. La nueva
   Release estable aparece si cumple el contrato; instalar/actualizar requiere las acciones
   y permisos explícitos existentes. No hay instalación silenciosa.

Usar la web de GitHub en Android (vista de escritorio si una pantalla no aparece en la
app móvil). No se presume que la app nativa de GitHub ofrezca todas las pantallas de
Secrets, Environments, dispatch y aprobación. No hace falta una terminal para publicar
versiones una vez completada la preparación inicial de claves.

## Arquitectura común y reutilizable

- Entrada manual: `.github/workflows/android-distribute.yml`, igual en todos los proyectos.
- Motor reutilizable: `robdor80/RobStore/.github/workflows/android-distribution-engine.yml`.
  Otros repositorios lo llaman **fijado a un SHA completo auditado**, nunca `@main`.
- Herramientas comunes: `mobile_distribution.py`, `unsigned-distribution.gradle` y las
  primitivas de verificación de `distribution.py`. No copiar una implementación distinta
  de publicación a cada aplicación.
- Configuración pequeña por aplicación: `.github/android-distribution.json` con repositorio,
  paquetes, módulo, SDK, nombre, descripción, icono fuente PNG y ruta de notas versionadas.
- Una pareja independiente de claves por aplicación: Release y DEV. Se reutilizan esas
  mismas claves en todas las versiones posteriores de **esa aplicación**. Reutilizar el
  procedimiento no significa compartir una clave universal entre aplicaciones.
- Estándar soportado: módulo Android `app` (o nombre configurable), buildTypes `release` y
  `debug` con paquete `.debug` y nombre `-DEV`, Gradle Wrapper ejecutable versionado,
  minSdk >=26 y targetSdk <=36, APK estándar sin splits. Proyectos fuera de ese estándar
  requieren adaptar/revisar el build antes de acceder a claves; no comandos arbitrarios
  obtenidos de entradas del workflow.

Alta de otro proyecto: Codex añade el JSON, notas, CI `android.yml` y la plantilla
`tools/templates/android-distribute.yml.template`, sustituyendo el SHA por la versión
aprobada del motor. El propietario ejecuta **el mismo asistente** Android descrito abajo.
No hay que escribir otra infraestructura ni introducir manualmente cuatro secretos en
cada pantalla. Cada repositorio necesita sus propios permisos/identidades una vez.
Una actualización del motor se hace revisando y cambiando el pin del workflow y la variable
`ANDROID_DISTRIBUTION_TOOLING_SHA` juntos; no requiere regenerar claves ni respaldos.
Para RobStore, la entrada llama al motor local aprobado en el mismo commit de `main`.
Las herramientas se descargan del SHA de RobStore configurado, que debe pertenecer a su
historial de `main` aprobado. Una versión aún sólo en la PR no puede firmar en producción.

## Preparación inicial personal, una vez por aplicación

Se hace personalmente en **Android**, sin Codex observando la terminal ni recibiendo
archivos/contraseñas. Termux se necesita sólo para alta/recuperación, no para compilar,
probar ni publicar normalmente. Instalar Termux de su fuente oficial mantenida y preparar:

```sh
pkg update
pkg install git python gh openjdk-21 gnupg pinentry
termux-setup-storage
export GPG_TTY=$(tty)
gh auth login --web
```

Autenticarse personalmente como `robdor80`; no compartir códigos de acceso ni tokens.
Clonar el repositorio de herramientas y situarse en **un commit auditado ya incorporado
a `main`**. Esta entrega no autoriza esa fusión. El script no se ejecuta en Cloud/CI/Codex.
`keytool` del JDK 21 de Android sirve para crear PKCS12; las compilaciones CI siguen en JDK17.

Ejecutar el asistente común con el repositorio y el SHA auditado reales, por ejemplo
usando variables personales `REPOSITORIO` y `SHA_HERRAMIENTAS` (no son secretos):

```sh
python tools/android_owner_setup.py --repository "$REPOSITORIO" --private-directory "$HOME/.android-signing/mi-aplicacion" --tooling-sha "$SHA_HERRAMIENTAS"
```

Para una aplicación que deba aparecer en el catálogo, añadir `--catalog`. El repositorio
ya debe ser público por decisión expresa del propietario; el asistente no cambia su
visibilidad y añade `robstore-app` conservando los demás topics. RobStore no necesita ese
topic para su propia autoactualización P03.

El asistente:

1. Exige sesión Android Termux personal, cuenta correcta, repositorio original con `main`
   y pin de herramientas auditado. Rechaza Cloud, CI, directorios existentes y claves
   dentro de Git o almacenamiento compartido. No sobrescribe identidades.
2. Solicita personalmente dos contraseñas distintas del gestor, sin eco, de al menos
   20 caracteres; genera claves **distintas** RSA 3072, SHA256withRSA, PKCS12, validez
   10000 días. No hay contraseñas predeterminadas, muestras ni argumentos con contraseñas.
3. Mantiene los PKCS12 en el directorio privado de Termux, modo 0700/0600, y registra
   huellas SHA-256 **públicas**. PKCS12 comparte contraseña de clave/almacén para cada canal.
4. Genera un backup `.tar.gpg` con ambos PKCS12 y registro público, cifrado AES-256 con
   GPG y passphrase propia, larga, única y guardada en el gestor. GPG pide esa passphrase
   directamente: no se guarda en GitHub, Python, argumentos ni en el backup en claro.
5. Se detiene para que el propietario suba el archivo **cifrado** a una carpeta privada
   de **Google Drive**, por ejemplo `Android/Firmas/<repositorio>`, sin enlaces públicos.
   Usar el selector de archivos/Termux: compartir únicamente el `.tar.gpg`, nunca los
   PKCS12. Si se necesita pasarlo a Downloads, copiar sólo el archivo cifrado.
6. Exige la ruta de una copia cifrada **descargada desde Drive**, distinta del original;
   compara su SHA-256 y descifra en memoria para comprobar recuperación exacta del backup.
   No envía claves a Codex ni usa Drive como fuente de secretos en cada compilación.
7. Sólo después de esa comprobación y de escribir personalmente `CONFIGURAR GITHUB`, crea
   los tres entornos protegidos, limita despliegues a la rama `main` y exige revisión de
   `robdor80`. Envía los secretos mediante stdin a `gh secret set`, cifrados por GitHub;
   no los imprime ni usa portapapeles, shell history o parámetros con contraseñas.
8. Configura huellas públicas y pin del motor; activa `ANDROID_DISTRIBUTION_ENABLED=true`
   al final. No ejecuta un workflow, tag o Release. La política permite que el propietario
   que dispara el run apruebe su entorno: es necesario para una cuenta personal de un
   único propietario. Los demás usuarios no pueden disparar/reintentar distribución.

Una passphrase GPG y las contraseñas PKCS12 no se recuperan desde GitHub Secrets: GitHub
no permite leer su valor. Conservarlas y la recuperación del gestor fuera del móvil.
Mantener también una segunda copia cifrada independiente (soporte fuera del teléfono),
2FA/passkeys y recuperación de las cuentas de Google/GitHub. No dejar la única recuperación
junto al teléfono que puede perderse. No cambiar claves al cambiar de teléfono.

Si falla una operación de red tras crear las identidades, conservar el directorio y usar
`--resume` con **las mismas claves y contraseñas**, repitiendo el ensayo Drive. Se validan
huellas y protecciones existentes antes de reponer secretos; no se sustituyen identidades
con huellas diferentes. Si se interrumpe la generación antes de completar el registro,
detenerse y revisar el material ya creado; no borrar/recrear una clave ya distribuida.
El asistente es para alta/recuperación de estas identidades, no para importar a ciegas
keystores históricos de otra aplicación o de alias diferentes.

Si se pierde o sustituye el móvil: instalar Termux/herramientas, recuperar personalmente
cuentas y gestor, descargar el `.tar.gpg` de Drive y restaurar en un directorio nuevo:

```sh
python tools/android_owner_setup.py --repository "$REPOSITORIO" --private-directory "$HOME/.android-signing/mi-aplicacion-recuperada" --tooling-sha "$SHA_HERRAMIENTAS" --restore-backup "$COPIA_CIFRADA_DESCARGADA"
```

GPG solicita la passphrase personalmente. Se aceptan únicamente los tres archivos esperados,
sin enlaces o traversal, se restauran en modo privado y se verifican las huellas con keytool
y con las ya configuradas en GitHub antes de reponer secretos. No se regeneran certificados.
Después se repite el ensayo Drive y se conserva una copia cifrada independiente.

## Configuración GitHub creada por el propietario

| Entorno | Secretos | Variables públicas |
| --- | --- | --- |
| `android-release` | SIGNING_KEYSTORE_B64, SIGNING_STORE_PASSWORD, SIGNING_KEY_PASSWORD, SIGNING_ALIAS | CERTIFICATE_SHA256, OTHER_CERTIFICATE_SHA256 |
| `android-dev` | Los mismos nombres, valores DEV independientes | Huella DEV y huella Release como otra |
| `android-publish` | Ninguna clave de firma | RELEASE_CERTIFICATE_SHA256, DEV_CERTIFICATE_SHA256 |

Variables de repositorio: `ANDROID_DISTRIBUTION_TOOLING_SHA` y
`ANDROID_DISTRIBUTION_ENABLED`. No configurar secretos de producción a nivel de repositorio
ni pasarlos con `secrets: inherit` a PR/builds. Los entornos existen sólo después del alta
personal. El guard comprueba su política antes de permitir que se solicite el job sensible;
una ausencia, API denegada o protección incorrecta bloquea, sin crear un entorno sin revisión.
Los ajustes de entornos, reviews y permisos necesitan ser compatibles con el plan GitHub:
para el catálogo se usan repositorios públicos, donde estas protecciones están disponibles.
No se ha probado esa configuración real con credenciales del propietario en esta entrega.

## Seguridad y procedencia del binario

El workflow sólo admite `workflow_dispatch`, propietario y reintento del propietario,
`refs/heads/main`, SHA completo igual al HEAD actual y última CI Android de ese SHA correcta.
No admite `push`, PR, fork, tag, código sólo en rama de trabajo ni CI en curso/fallida.
Los jobs guard/build tienen permisos de lectura; build no tiene entorno ni claves. El job
sign tiene revisión propia, token de lectura y sólo checkout de herramientas centrales
fijadas; instala el SDK **antes** de exponer secretos. No ejecuta Gradle, plugins ni código
del proyecto con claves presentes. Firma APK v2, verifica pin, otro pin distinto, paquete,
SDK, alineación y no depurable. RobStore mantiene además todas las comprobaciones RS-04.
Los PKCS12 temporales se eliminan antes del upload; se suben únicamente APK y metadatos
públicos. No se usa caché Gradle ni build scans en los jobs de firma/publicación.

El job publish tiene otra revisión y `contents: write`; no recibe keystores y no ejecuta
Gradle. Sólo acepta el artefacto inmutable de un run prepare correcto del mismo workflow,
repositorio, propietario, canal y commit, más el hash concreto revisado por el propietario.
Rechaza paquetes/certificados/versiones/recibos incoherentes, bytes cambiados, códigos iguales
o inferiores a cualquiera de las publicaciones del canal, etiquetas existentes y main que
haya cambiado. Una concurrencia común serializa prepare/publish y no cancela una ejecución activa.
Esperar a que termine cada operación: GitHub puede sustituir runs pendientes si se envían
varios a la vez; no usar la cola como autorización de publicaciones en lote.
No se mueve una etiqueta ni se sobrescribe un asset mediante `--clobber`.

Orden exacto:

`S aprobado y limpio → APK unsigned sin secretos → APK firmado A → hash/contrato/recibo → aprobación de A → commit C → etiqueta C → draft + assets de A → comprobación del digest → Release visible`.

C se crea con Git Data API, padre S, mismo árbol salvo archivos de cierre de raíz:
`robstore-update.json`, `robstore-distribution.json` y, para estable, `robstore.json` e icono
`robstore/icon.png`. No se mueve `main`. El contrato queda en el commit señalado por el tag,
como exige P03; el APK procede exactamente de **S**, incluidos metadatos VCS de AGP. Los
archivos de cierre no son entradas del build Android estándar. No se recompila C ni se
cambia código, recursos, notas o herramientas entre preparación y publicación. El API de
comparación verifica el cierre antes de crear la etiqueta. El recibo registra S, run ID,
canal, pin y hash. Este es el flujo oficial mobile-first; no mezclarlo con el preflight
local histórico que admite sólo los dos archivos de cierre de P04 inicial.

Si falla después de crear el tag/draft o subir parcialmente, se conserva para inspección
manual, nunca se reintenta moviendo el tag ni se publica un draft incompleto. Registrar el
incidente, verificar sus bytes/commit y decidir explícitamente recuperación o retirada.
No borrar automáticamente una etiqueta que pudiera haber sido observada. Un cambio de main
durante la fase final deja el draft sin publicar. GitHub no ofrece una transacción única
para todos estos recursos; repetir una publicación requiere revisar ese estado parcial.

## Catálogo, versionado, DEV y límites

Cada versión aumenta versionCode por paquete, con versionName legible. El tag estable es
`vX.Y.Z`; DEV usa `dev-X.Y.Z-DEV-N`. No se incrementa ahora `1 / 0.1.0`. El propietario decide
la primera versión real tras la auditoría. La herramienta consulta todas las publicaciones
anteriores por canal y paquete; una lectura incompleta/contrato inválido bloquea.

Para aparecer en RobStore: repositorio original público de `robdor80`, topic `robstore-app`,
Release publicada estable, contrato `robstore.json` e icono en su tag, APK exacto firmado
con versión creciente y digest de GitHub coherente. El motor genera el contrato desde el
APK ya firmado, incluidos versionCode/versionName reales. RobStore ya descubre la última
estable publicada al refrescar el catálogo y reutiliza P02/RS-04 para instalar o actualizar.
No hay que añadir cada versión al código de RobStore. Caché, cuotas y confirmaciones siguen
vigentes; no se promete aparición instantánea sin refresco ni instalación automática.

Las prereleases DEV se preparan/publican por separado y no aparecen como estables del
catálogo. La autoactualización DEV de RobStore sigue **deshabilitada** hasta una distribución
persistente real y autorización/auditoría posterior de su activación. Artefactos temporales
de CI no la habilitan. DEV temporal con otro certificado puede requerir desinstalación
única y perder datos; Android decide la compatibilidad. Perder la clave Release puede
impedir futuras actualizaciones compatibles. No se implementa rotación de firmas.

P05 (actualización física, conservación de datos, cancelación/rechazo, terminación de proceso,
accesibilidad) permanece pendiente de autorización. La preparación del pipeline y fixtures
no certifica una firma definitiva ni una publicación real. Todo lo sensible aún requiere
el alta personal Android y las aprobaciones descritas; no se ejecutan en esta tarea.

Para aplicaciones con una Release anterior a P04 y una identidad privada ya
existente, consultar [transición e importación histórica de Cuadrante](RS-05-CUADRANTE-LEGACY.md).
No ejecutar el modo de generación de Release para Cuadrante.
