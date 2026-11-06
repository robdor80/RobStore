# RS-05 — publicación con un botón

Esta entrega sustituye el procedimiento manual de preparación/publicación descrito
en las entregas RS-05 anteriores. Una orden expresa de RobPublisher dispara
`android-distribute.yml` sobre `main`. GitHub ejecuta tests, lint y compilación sin
secretos; firma en un trabajo separado con las claves existentes; verifica el APK
y publica automáticamente la Release. No hay segunda orden ni revisión de entorno.
Los pushes y las PR siguen sin publicar.

El único input es `channel` (`release` por defecto, o `dev`). El commit lo selecciona
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

Alternativa por API para retirar revisores y temporizadores, con la sesión GitHub
administradora ya configurada (repetir cambiando el repositorio):

```sh
repo=robdor80/RobStore
for environment in android-release android-dev android-publish; do
  gh api --method PUT "repos/$repo/environments/$environment" --input - <<'JSON'
{"wait_timer":0,"prevent_self_review":false,"reviewers":[],"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}
JSON
done
```

Este comando no modifica secretos ni variables ni las políticas de ramas existentes.
Las reglas personalizadas de aplicaciones, si existen, se desactivan en la pantalla
del entorno. El motor detecta reglas pendientes antes de compilar o acceder a claves.
No ejecutar de nuevo el asistente de claves para esta migración. El asistente de
alta crea los entornos nuevos sin revisores ni temporizador; conserva las protecciones
existentes contra la sustitución de identidades durante una recuperación.

## Adaptación mínima de RobPublisher

El código de la aplicación RobPublisher no está en este repositorio. Se entrega
`tools/robpublisher_dispatch.py` como adaptador ejecutable y contrato de integración.
Su botón **Publicar** realiza exactamente una petición usando su autenticación
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
introducida en el botón. El adaptador utiliza la sesión existente de `gh`.

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
