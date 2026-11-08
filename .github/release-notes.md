# RobStore 0.1.3

- RobStore ya no aparece como una aplicación más del catálogo. Su actualización sigue disponible exclusivamente desde Ajustes y la entrada antigua desaparece también de la caché.
- Descarga de actualizaciones propias más eficiente: se comprueba solo la Release seleccionada en cada revalidación, sin consultar de nuevo todas las publicaciones históricas.
- Mensajes claros para los errores de GitHub (HTTP 403/429/404/5xx), servidor de APK, conexión, tiempo de espera y falta de espacio.
- Se conserva la validación del manifiesto oficial, SHA-256 y firma mediante el instalador de Android.
- Un corte de conexión permite un reintento automático limitado, tras comprobar de nuevo la publicación. Las descargas incompletas se eliminan.
- Si GitHub falla justo antes de instalar, se puede volver a comprobar e instalar sin descargar otra vez el APK verificado.
- Los reintentos de RobPublisher conservan las etiquetas y los archivos ya subidos cuando coinciden con el mismo código, firma y APK; bloquean sustituciones incompatibles.
