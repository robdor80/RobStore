# RS-05 — distribuir desde RobPublisher

Después del alta inicial: abrir RobPublisher, seleccionar la aplicación y pulsar
**Publicar actualización**. GitHub compila, firma con su clave existente y publica
la Release. La app muestra el progreso en castellano y conserva el seguimiento al
cerrarse. RobStore encuentra las actualizaciones de otras apps en el catálogo;
su propia actualización se gestiona exclusivamente desde Ajustes.
No se necesitan GitHub web, terminales, aprobaciones, contraseñas, etiquetas, hashes
ni identificadores durante las actualizaciones. Pushes y PR no publican.

## Motor común sin mantenimiento por aplicación

La entrada oficial es `.github/workflows/android-distribute.yml`; su único input
es `channel` (`release` por defecto; `dev` para pruebas). Todas las aplicaciones usan:

```yaml
uses: robdor80/RobStore/.github/workflows/android-distribution-engine.yml@main
secrets: inherit
```

RobStore puede llamar al workflow local. El motor selecciona una vez el código
central de `main` y usa esa misma revisión en los trabajos de la ejecución. Su
revisión interna no es un dato que deba configurar o copiar el propietario.
Actualizar el motor central actualiza todas las aplicaciones. Se eliminan
`ANDROID_DISTRIBUTION_TOOLING_SHA` y `ANDROID_DISTRIBUTION_ENABLED`.

La versión sale del APK compilado. La etiqueta se genera como
`v<versionName>-<versionCode>` o `dev-<versionName>-<versionCode>`, normalizando
los caracteres que GitHub no admite. Así puede repetirse `versionName` sin tener
que elegir otra etiqueta manualmente; la compatibilidad Android depende de `versionCode`. El proyecto debe incrementar `versionCode` para
actualizar Android; esto forma parte del cambio de código, no de la firma. Las
notas se leen del proyecto si existen; si faltan o son provisionales se usa el
nombre de la aplicación y su versión, sin una aprobación editorial adicional.

Se conservan las comprobaciones automáticas de APK, paquete, certificado original,
versión creciente, firma Android, subida y contrato para RobStore. La compilación
no recibe claves. La firma usa `apksigner`; publicar usa el mismo APK firmado.
Las subidas se reintentan automáticamente. Un fallo persistente conserva un
borrador; otra orden desde RobPublisher lo retoma si conserva el mismo código,
certificado y SHA-256 de APK. Se verifican el cierre original y los archivos ya
subidos, sin mover la etiqueta ni sobrescribir assets. Sólo puede eliminarse un
asset vacío cuya subida quedó en estado `starter`. Si cambia la procedencia o hay
una etiqueta sin borrador verificable, se bloquea la operación y se conserva la
evidencia; no se recupera forzando etiquetas ni sustituyendo APK. Una versión ya
pública se conserva y nunca se sobrescribe.

La continuidad con aplicaciones anteriores se obtiene automáticamente de todos los
contratos publicados o, durante la primera adopción de RS-05, de sus APK publicados.
Cada versión publicada sigue siendo un mínimo de `versionCode`, aunque cambie el
orden de sus fechas. No se
exigen registros de aprobación de Releases, IDs de assets, SHA de APK antiguos ni
que la Release histórica permanezca para siempre en GitHub. Para Cuadrante se
conservan únicamente su certificado original y su versión mínima conocida. Una
aplicación que ya tiene APK publicados exige importar su clave existente en el alta;
no se genera otra identidad por desconocer su historial.

## Alta inicial, una sola vez

Preparar `.github/android-distribution.json`, la plantilla oficial del workflow y
el icono de cada proyecto. Después, con una sesión administradora de GitHub ya
configurada en el dispositivo del propietario y JDK/Python/gh disponibles:

```sh
python3 tools/android_owner_setup.py --repository robdor80/MiApp --catalog
```

El asistente genera las claves y contraseñas automáticamente, guarda una copia
local privada y configura GitHub. Las altas nuevas sólo necesitan dos secretos
por canal: keystore y contraseña generada. El alias se calcula automáticamente;
se respetan los alias y contraseñas privadas separados de las altas existentes. No pide repetir contraseñas ni escribir frases.
Una segunda ejecución con el alta completa conserva las claves y no vuelve a
pedir contraseñas ni subir keystores. Los secretos existentes no se sobrescriben.
Termux es una posible herramienta de alta desde Android, no un requisito de
publicación ni de uso habitual de RobPublisher.

**Aplicaciones ya distribuidas:** importar las claves existentes; nunca generar
una Release nueva. Para Cuadrante, por ejemplo:

```sh
python3 tools/android_owner_setup.py --repository robdor80/Cuadrante_App \
  --import-release-keystore /ruta/privada/release-original.p12 \
  --import-dev-keystore /ruta/privada/dev-existente.p12 --catalog
```

Sólo en esta importación se pide localmente la contraseña del keystore existente;
el alias se detecta si hay una única clave. Si su contraseña privada es diferente,
se pide sólo cuando hace falta. No enviar esos archivos o contraseñas a Codex.
La importación normaliza el contenedor sin cambiar el certificado ni la clave.
Cuadrante conserva el certificado Release histórico
`c85a092878e85a35b8ca84132bdf00314da3e90dae80fc137ac52a27d11491e7`.
Si GitHub ya tiene sus claves completas, no volver a importarlas para migrar.

El respaldo es opcional e independiente:

```sh
python3 tools/android_owner_setup.py --repository robdor80/MiApp --backup
```

Crea un archivo local cifrado. Guardarlo donde se prefiera; Drive y su descarga de
comprobación no son requisitos del alta. `--restore-backup archivo.tar.gpg` restaura
una copia local sin cambiar GitHub. El respaldo no se usa para actualizar versiones.

## Migración única de aplicaciones existentes

Después de incorporar este motor y la adaptación de RobPublisher, el propietario
puede aplicar todas las migraciones con su acceso administrador ya configurado:

```sh
python3 tools/migrate_rs05.py
```

Procesa Cuadrante_App, RobStore, RobGit_App y Rob_Apps. Omite los proyectos que aún
no tienen RS-05 y podrán incorporarse con la plantilla nueva durante el alta.
Actualiza una sola vez los wrappers, retira revisores y temporizadores de los tres
entornos y elimina las dos variables obsoletas. No borra ni escribe secretos,
keystores o certificados. No despacha workflows ni publica. Espera a que terminen las ejecuciones anteriores
y cancela automáticamente las órdenes antiguas bloqueadas por revisores, antes de
retirar esas barreras. `--check` muestra los
cambios previstos sin escribir; `--repository owner/repo` limita la selección.
No ejecutarlo de nuevo para cada actualización.

En la conexión actual, GitHub devuelve **HTTP 403** al cambiar entornos de
Cuadrante. Los intentos fueron rechazados. La migración está preparada, pero no
se puede afirmar que esos revisores estén retirados hasta aplicarla con permisos
administrativos. Esto no añade un permiso o confirmación a cada publicación.

## RobPublisher Android

La adaptación está en `robdor80/Rob_Apps`, basada en la rama de la aplicación de
su PR #1. La adaptación está en [Rob_Apps #2](https://github.com/robdor80/Rob_Apps/pull/2),
coordinada con [RobStore #12](https://github.com/robdor80/RobStore/pull/12). Sustituye el formulario de etiquetas, la
revisión del candidato y la confirmación por un botón **Publicar actualización**.
La pantalla muestra espera, compilación, firma, publicación y resultado. No muestra
hashes, certificados o identificadores. Las capturas normales están permitidas;
el token permanece cifrado al guardarse y enmascarado durante su introducción.

La app usa la conexión GitHub persistente una vez configurada, con Contents de
lectura y Actions de lectura/escritura. No requiere permisos de administración o
Secrets para publicar. Envía una única orden `workflow_dispatch` a `main` con el
canal, persiste el seguimiento antes de enviarla y no repite el POST si se pierde
la respuesta. Consulta progreso y Release dentro de la app, sin navegación
obligatoria a GitHub. La aplicación nunca recibe claves de firma.

## Incidencia de Cuadrante y validación

La ejecución `42152734412`, trabajo `127680616177`, recibió los secretos y falló
con `Tool failed: apksigner`. El wrapper antiguo descartó stdout/stderr, por lo que
su log no distingue entre fallo al abrir el almacén, alias, contraseña de clave o
verificación del APK. No existe evidencia para afirmar cuál de ellos ocurrió.

El motor clasifica ahora los errores de `apksigner sign/verify` con mensajes fijos:
contraseña de almacén, contraseña de clave privada, alias sin clave privada,
contenedor inválido, Java o SDK mínimo. No imprime la salida privada. Los keystores
nuevos sólo necesitan una contraseña; se sigue leyendo la contraseña privada
separada de las configuraciones existentes cuando la tienen. Un problema de esos
valores exige corregir **sólo el secreto identificado**, una vez, en el entorno
correspondiente, conservando el keystore y el certificado originales. No se pide
ningún secreto a Codex y no se ofrece regenerar la clave como solución.

El APK oficial v1.0.0 se ha verificado con Android SDK Build Tools 35: firmas v2/v3
válidas y certificado histórico correcto. Esto no demuestra que las credenciales
actuales de Actions abran su keystore: ese diagnóstico sigue pendiente mientras
no pueda ejecutarse con sus claves existentes. No se ha reejecutado una distribución
ni publicado para intentar resolverlo. Las pruebas usan claves efímeras y respuestas
HTTP simuladas. No declarar resuelto el fallo sólo por quitar aprobaciones.


Comprobación adicional sin claves reales: se recompiló el commit exacto
`e140bfa21ec1c51741d938849be43d6de06d2ff6` de la ejecución fallida. El motor pudo
firmar y verificar ese APK (Cuadrante 1.0.1, código 2) con una clave efímera de prueba.
Un fallo real provocado con contraseña de fixture incorrecta se identificó como
`SIGNING_STORE_PASSWORD`, sin mostrar su valor. Esto valida el mecanismo y su
diagnóstico, no las credenciales reales de Cuadrante ni la causa concreta del run antiguo.
