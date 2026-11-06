# RS-05 — publicación con un botón

Esta entrega sustituye el procedimiento manual de preparación/publicación descrito
en las entregas RS-05 anteriores. Una orden expresa de RobPublisher dispara
`android-distribute.yml` sobre `main`. GitHub ejecuta tests, lint y compilación sin
secretos; firma en un trabajo separado con las claves existentes; verifica el APK
y publica automáticamente la Release. No hay segunda orden ni revisión de entorno.
Los pushes y las PR siguen sin publicar.

El motor ya no ofrece operaciones separadas `prepare`/`publish`, confirmaciones
escritas ni inputs de hash o ejecución. El único input de RobPublisher es `channel` (`release` por defecto, o `dev`). El commit lo selecciona
GitHub al recibir la orden; la versión y la etiqueta se obtienen del APK:
`v<versionName>` para Release y `dev-<versionName>-<versionCode>` para DEV.
El hash y el identificador de ejecución se transmiten internamente. No se solicitan
al usuario contraseñas, secretos, hashes, etiquetas ni identificadores por actualización.
La versión debe estar incrementada en el proyecto y las notas versionadas en
`notesPath`, como parte del cambio de la aplicación.

Se mantienen los paquetes, certificados y claves de cada canal, los pins públicos,
el acceso limitado a `main`, la firma sin ejecutar Gradle y la verificación del APK
antes y después de subirlo. Una subida fallida conserva una Release borrador y nunca
la hace pública. No se reconstruye ni se vuelve a firmar al publicar.

## Migración única de repositorios actuales

Tras fusionar esta PR, realizar una sola vez por repositorio:

1. En **Settings → Environments**, abrir `android-release`, `android-dev` y
   `android-publish`. Desactivar **Required reviewers**, eliminar el **Wait timer**
   y desactivar las reglas de protección de despliegue que exijan intervención.
   Guardar. Mantener la política de ramas personalizada con una única rama `main`.
   No borrar ni recrear los entornos: conservan sus secretos y variables.
2. Instalar el wrapper actualizado de `tools/templates/android-distribute.yml.template`
   en `.github/workflows/android-distribute.yml` de cada aplicación. Sustituir
   `REPLACE_WITH_AUDITED_COMMIT_SHA` por el commit **ya fusionado** de RobStore que
   contiene esta entrega. En RobStore se usa el wrapper local incluido en esta PR.
3. Actualizar la variable de repositorio `ANDROID_DISTRIBUTION_TOOLING_SHA` al mismo
   commit fusionado. Mantener `ANDROID_DISTRIBUTION_ENABLED=true`. Son ajustes únicos
   de esta migración, no datos que se pidan en cada publicación.

La retirada de aprobaciones también puede automatizarse con el modo de migración:

```sh
python3 tools/android_owner_setup.py --repository robdor80/Cuadrante_App --environments-only
```

Este modo usa una sesión administradora existente y funciona sin Termux, directorio
privado, keystores, contraseñas ni escritura de secretos. Actualiza los tres entornos
sin borrarlos ni recrearlos; conserva sus variables y las políticas de rama. Sirve
para esta migración única, nunca para publicar cada versión. El asistente completo
queda reservado al alta inicial o a una recuperación deliberada: al guardar secretos
sólo crea los que faltan, nunca sobrescribe los ya configurados.

No ejecutar de nuevo el alta de claves para migrar aplicaciones existentes. Las claves
Release y DEV configuradas se reutilizan automáticamente. En particular, la Release
original de Cuadrante sigue fijada al certificado histórico del registro central;
una clave diferente bloquea la firma, sin regeneración ni sustitución de identidad.

### Estado de los entornos actuales

La inspección de los repositorios mostró que RobStore no tiene estos tres entornos
y Cuadrante sí, con revisores obligatorios. La conexión disponible devuelve
`403 Resource not accessible by integration` al modificar los entornos de Cuadrante.
Los intentos fueron rechazados y no cambiaron aprobaciones, claves ni secretos.
La retirada efectiva queda pendiente de ejecutar la migración única con acceso
administrador. No se ha lanzado ningún workflow de distribución para comprobarlo.

El motor admite la regla técnica `branch_policy` que devuelve GitHub al limitar
los despliegues a `main`; rechaza revisores y temporizadores antes de acceder a
claves. Esta distinción evita que una restricción de rama se interprete como una
aprobación humana.

## Adaptación mínima de RobPublisher

El código de la aplicación RobPublisher no está en este repositorio. Se entrega
`tools/robpublisher_dispatch.py` como adaptador ejecutable y contrato de integración.
Su botón **Publicar actualización** realiza exactamente una petición usando su autenticación
GitHub persistente del propietario `robdor80`:

```http
POST /repos/{owner}/{repo}/actions/workflows/android-distribute.yml/dispatches
Content-Type: application/json

{"ref":"main","inputs":{"channel":"release"}}
```

Equivalente para un cliente que pueda ejecutar el adaptador:

```sh
python3 tools/robpublisher_dispatch.py --repository robdor80/RobStore
```

RobPublisher muestra «Orden aceptada» al recibir éxito; esto no significa todavía
«Release publicada». Puede consultar automáticamente Actions y la Release para
mostrar el resultado, sin pedir al usuario ningún identificador. La credencial
persistente necesita permiso para despachar Actions y leer su estado; las escrituras
de la Release usan el `GITHUB_TOKEN` del trabajo de publicación, no una contraseña
introducida en el botón. El adaptador utiliza la sesión existente de `gh` para clientes que ejecuten procesos.
Una aplicación RobPublisher nativa envía la misma petición HTTP con su credencial
persistente; no necesita instalar ni abrir Termux para publicar. El botón no debe
mostrar controles de firma, certificados, secretos, hashes ni ejecuciones.
Después de la publicación, RobStore usa el descubrimiento existente de Releases
estables y el manifiesto `robstore.json` generado automáticamente. El topic
`robstore-app` se configura una sola vez al dar de alta la aplicación.

No añadir un diálogo de confirmación ni repetir automáticamente un POST cuya
respuesta se haya perdido. Deshabilitar el botón mientras su petición está en curso.
El workflow serializa las órdenes y rechaza versiones/etiquetas ya publicadas.

## Validación sin publicación

Las pruebas de política, transacción y adaptador simulan GitHub; comprueban que
un fallo de subida no publica la Release y que el cliente envía una única orden.
Las pruebas de firma usan únicamente claves efímeras de fixture. El flujo Android
de PR ejecuta estas pruebas sobre su APK de prueba, sin claves de producción.
No ejecutar `robpublisher_dispatch.py`, `gh workflow run` ni el workflow de distribución
para validar esta entrega. Crear esta PR no configura entornos, no cambia claves,
no crea etiquetas ni publica Releases.


## Límite técnico pendiente en Cuadrante

Eliminar revisores no corrige un fallo de `apksigner`. Esta entrega conserva la
firma y la verificación técnica obligatorias y no cambia el parser de certificados.
El fallo actual de Cuadrante sigue pendiente de diagnóstico/corrección independiente
del cambio de autorizaciones; no se declara resuelto sin una comprobación técnica
específica. Un fallo de compilación, firma o verificación detiene el flujo antes de
hacer pública la Release. No se pide al propietario que aporte otro certificado,
regenere claves o complete manualmente la firma como alternativa.
