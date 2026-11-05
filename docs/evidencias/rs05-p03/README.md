# Evidencias locales P03

Los TSV reúnen **254 casos distintos por variante**, todos PASS (0 fallos, errores u omitidos en el resultado final de cada caso).

- `full`: casos de la campaña completa inicial de 253 por variante, antes de la protección adicional de referencia de etiqueta. Los resultados de los casos afectados se sustituyen por sus reejecuciones dirigidas.
- `final-targeted`: 42 casos por variante en la última ejecución: 40 nuevos de P03 y dos casos de ciclo de vida P02 adaptados a la consulta propia de arranque.

Las campañas completas iniciales detectaron un mock restringido al paquete del catálogo (Debug) y un supuesto de ausencia de consultas de arranque (Release). Se aislaron los retornos mediante caché propia fresca y se mantuvo la exigencia de cero red en esos retornos. El contador nuevo de la primera prueba se corrigió a cuatro observaciones. No se borraron ni renombraron casos previos. La revisión posterior del origen añadió la prueba que exige `refs/tags/` y prohíbe sustituir la etiqueta por una rama homónima.

Se verificó que permanecen los 196 casos P01 y los 99 RS-03 en ambas variantes; la campaña conserva todos los casos P02 y RS-04. No se repitió una campaña local completa después de las correcciones dirigidas.

Los XML de una campaña completa única sobre el HEAD final, informes lint, capturas Robolectric y verificación del APK se conservan en el artefacto de GitHub Actions enlazado desde la PR Draft #6. Los timestamps/duraciones/XML de CI difieren de la evidencia local. Ninguna prueba instala APK en un dispositivo físico.
