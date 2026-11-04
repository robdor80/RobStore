# APK sintético de pruebas

`AndroidManifest.xml` solo declara un paquete ficticio, versión 7, sin código.
`manifest.b64` contiene únicamente ese manifiesto binario compilado con aapt2
35.0.0, no un APK distribuible ni firmado. Los tests construyen un ZIP temporal
con él para ejercitar el parser oficial de Android. No se publica una Release.

Regeneración desde la raíz (SDK local, fuera de Git):

```sh
mkdir -p app/build/rs04-fixture
"$ANDROID_HOME/build-tools/35.0.0/aapt2" link \
  -I "$ANDROID_HOME/platforms/android-36/android.jar" \
  --manifest app/src/test/resources/rs04/AndroidManifest.xml \
  -o app/build/rs04-fixture/synthetic.apk
python3 - <<'PY'
import base64, zipfile
from pathlib import Path
with zipfile.ZipFile('app/build/rs04-fixture/synthetic.apk') as apk:
    data = apk.read('AndroidManifest.xml')
Path('app/src/test/resources/rs04/manifest.b64').write_text(base64.b64encode(data).decode() + '\n')
PY
```

El parser reconoce estructura y metadatos; esta fixture **sin firma** ilustra que
parsear un paquete no acredita su firma. La instalación final queda en Android.
