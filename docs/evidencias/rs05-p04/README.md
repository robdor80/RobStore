# Evidencias RS-05 P04

Base remota/PR comprobada: `1347de8da4fbd28a009811b93f0322b04676e28a`.
La CI del commit final, sus XML/lint y el artefacto DEV temporal se enlazan en
[PR Draft #6](https://github.com/robdor80/RobStore/pull/6).

## Campaña Android local

JDK 17 para Gradle/compilación; JDK 21 para Robolectric API 36; SDK 36/build-tools 35.0.0.
SDK/JDK y configuración de proxy se instalaron fuera del repositorio. Un primer intento
falló en el bootstrap del runtime Maven de Robolectric por DNS/proxy, antes de poder
completar las clases afectadas. Se corrigió sólo el entorno, sin editar tests/producto.

Campaña completada mediante:

```sh
./gradlew -I tools/unsigned-validation.gradle -I /tmp/rs05-proxy.gradle test lint lintRelease assembleDebug assembleRelease --no-daemon -Dorg.gradle.java.installations.paths=/tmp/rs05-jdk17,/usr/lib/jvm/java-21-openjdk-amd64
```

El segundo init script es exclusivamente el adaptador de red local (proxy TLS y repositorio
Maven de Robolectric), no se requiere en MSI/Actions con conectividad normal y no contiene
credenciales. No se versiona ni modifica Gradle de aplicación para resolver la red.

| Comprobación | Resultado |
| --- | --- |
| Debug | 254/254 PASS; 0 fallos, errores u omitidos |
| Release | 254/254 PASS; 0 fallos, errores u omitidos |
| Lint Debug | 0 errores, 9 avisos anteriores |
| Lint Release | 0 errores, 9 avisos anteriores |
| Avisos lint por variante | 7 NewerVersionAvailable + 2 UsableSpace |
| Ensamblado Debug/Release | PASS sin claves privadas |
| APK RS-04 | Identidades, SDK, permisos, FileProvider, zipalign y hash PASS; ambos sin firma |
| Regresión RS-03 | 99/99 por variante |
| Regresión RS-04 | 158/158 por variante |
| Regresión P01 | 196/196 por variante |
| Regresión P03, incluidos P02 y DEV cerrado | 254/254 por variante |

La importación de `File` se hizo explícita después de detectar el sombreado de `java` en
Kotlin DSL al endurecer el control de ruta absoluta. La configuración final compila y se
comprueba con las ejecuciones dirigidas siguientes; la CI vuelve a validar el HEAD final.
No se cambia código Kotlin, recursos, permisos ni tests Android durante P04.

## Herramientas y fixtures

```sh
python3 -m unittest discover -s tools -p test_distribution.py -v
python3 -m unittest discover -s tools -p test_distribution_signed.py -v
python3 tools/check_signing_configuration.py
python3 tools/check_rs04_regressions.py
python3 tools/check_rs05_regressions.py
python3 tools/check_distribution_secrets.py
actionlint .github/workflows/android.yml
git diff --check
```

17 tests unitarios Python y 6 pruebas de integración con APK real firmado: PASS.
Incluyen digest frente a contenido conocido, metadatos reales, certificados correcto/
incorrecto, paquete cruzado, APK sin firma, byte modificado, contratos incoherentes,
versiones iguales/inferiores, origen de contratos por etiqueta, canales, tags existentes,
primera publicación explícita y comprobación online del código anterior. Un Git temporal
**de fixture**, sin conexión remota, valida preparación del contrato y comparación de
árboles tras commits de cierre; no se modifica ningún otro repositorio real.

Las pruebas de firmado usan dos certificados **TEST ONLY EPHEMERAL**, RSA 3072/PKCS12,
validez 2 días y contraseña aleatoria de ejecución. No se usan los certificados del
propietario. Los directorios privados temporales se eliminan al salir, también ante error;
no se conservan ni suben claves, contraseñas o keystores.

Las invocaciones Gradle dirigidas comprueban:

| Caso | Resultado final |
| --- | --- |
| Sin configuración Release | Bloqueado |
| Sin configuración DEV | Bloqueado |
| Activación implícita por propiedad de entorno | Bloqueada |
| Ejecución CI | Bloqueada |
| Assemble combinado con tests | Bloqueado |
| Configuration cache | Bloqueada antes de leer secretos |
| Certificado distinto al pin | Bloqueado |
| Mismo certificado declarado para ambos canales | Bloqueado |
| Contraseña incorrecta | Bloqueada sin detalles privados |
| Override tardío para quitar la firma | Bloqueado |
| Build Release persistente con fixture | PASS; APK, identidad y certificado verificados |
| Build DEV persistente con fixture | PASS; APK, identidad y certificado verificados |

Ambos APK rechazan el pin del otro canal. La contraseña de fixture no aparece en la salida
capturada de ninguna herramienta. En la fase final también se comprueban los logs daemon.
El init script normal `unsigned-validation.gradle` actúa antes de configurar P04 y no
representa un override tardío. Se corrigió ese supuesto del harness y se reejecutó sólo la
fase de firmado (`--phase signing`); las nueve negativas ya superadas no se repitieron.
El script permite reproducir todas juntas (por defecto) o sólo `guards`/`signing`.

Tras las fixtures se restaura únicamente el ensamblado normal sin claves y se repite su
verificador RS-04; no otra campaña de tests completa. Las fixtures nunca constituyen
artefactos oficiales. No se añade contrato provisional a la raíz del repositorio.

Análisis del historial Git alcanzable: sin ficheros de clave privada ni bloques PEM privados;
revisión del diff: sin contraseñas literales. `actionlint` y whitespace: PASS. Lectura real
mediante GitHub CLI compatible con 2.46: cero contratos oficiales publicados en ambos canales.
Los enlaces del resultado final CI se mantienen en la PR para evitar otra ejecución completa
motivada únicamente por editar un enlace documental.

## Límite

No se valida la firma definitiva: falta la operación personal en MSI con claves reales.
No se crean Releases/tags públicos ni se suben APK oficiales. No se activa DEV, incrementa
versión, altera RS-04/P03, fusiona la PR ni avanza a P05. Las pruebas físicas requieren
autorización posterior y no se deducen de simulaciones ni de estos certificados efímeros.
