# Comprobación de los informes de GitHub Actions

Commit de implementación: `bd327626dbb1c6d266981113bbff349810f7d187`.
Run PR: https://github.com/robdor80/RobStore/actions/runs/41166530676
Job: https://github.com/robdor80/RobStore/actions/runs/41166530676/job/124435192671
Artefacto: https://github.com/robdor80/RobStore/actions/runs/41166530676/artifacts/13029467971

Los siguientes fragmentos proceden del resumen generado desde XML dentro del job
(paso Summarize audit evidence), consultado tras terminar en success:

```text
| es.robertodorado.robstore.AppIdentityTest | 4 | 4 | 0 | 0 | 0 |
| es.robertodorado.robstore.CatalogFormattingTest | 8 | 8 | 0 | 0 | 0 |
| es.robertodorado.robstore.LauncherIconTest | 3 | 3 | 0 | 0 | 0 |
| es.robertodorado.robstore.RobUiTest | 33 | 33 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.CatalogCacheTest | 4 | 4 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.CatalogDiscoveryTest | 22 | 22 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.CatalogIconLoaderTest | 4 | 4 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.CatalogRepositoryTest | 10 | 10 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.GitHubClientTest | 8 | 8 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.HttpTransportTest | 3 | 3 | 0 | 0 | 0 |
| **Total** | 99 | 99 | 0 | 0 | 0 |
| es.robertodorado.robstore.AppIdentityTest | 4 | 4 | 0 | 0 | 0 |
| es.robertodorado.robstore.CatalogFormattingTest | 8 | 8 | 0 | 0 | 0 |
| es.robertodorado.robstore.LauncherIconTest | 3 | 3 | 0 | 0 | 0 |
| es.robertodorado.robstore.RobUiTest | 33 | 33 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.CatalogCacheTest | 4 | 4 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.CatalogDiscoveryTest | 22 | 22 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.CatalogIconLoaderTest | 4 | 4 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.CatalogRepositoryTest | 10 | 10 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.GitHubClientTest | 8 | 8 | 0 | 0 | 0 |
| es.robertodorado.robstore.catalog.HttpTransportTest | 3 | 3 | 0 | 0 | 0 |
| **Total** | 99 | 99 | 0 | 0 | 0 |
Ejecutadas / superadas / fallos / errores / omitidas: **198 / 198 / 0 / 0 / 0**.
`lint-results-debug.xml`: 0 errores, 15 avisos.
- Warning: `OldTargetApi` (build.gradle.kts:15)
- Warning: `AndroidGradlePluginVersion` (gradle-wrapper.properties:4)
- Warning: `AndroidGradlePluginVersion` (libs.versions.toml:2)
- Warning: `GradleDependency` (libs.versions.toml:4)
- Warning: `GradleDependency` (libs.versions.toml:5)
- Warning: `GradleDependency` (libs.versions.toml:12)
- Warning: `GradleDependency` (libs.versions.toml:13)
- Warning: `GradleDependency` (libs.versions.toml:18)
- Warning: `NewerVersionAvailable` (libs.versions.toml:3)
- Warning: `NewerVersionAvailable` (libs.versions.toml:3)
- Warning: `NewerVersionAvailable` (libs.versions.toml:3)
- Warning: `NewerVersionAvailable` (libs.versions.toml:7)
- Warning: `NewerVersionAvailable` (libs.versions.toml:10)
- Warning: `NewerVersionAvailable` (libs.versions.toml:11)
- Warning: `NewerVersionAvailable` (libs.versions.toml:14)
```

Los dos grupos de suites corresponden, en orden, a Debug y Release. La CI reproduce
198 superadas, cero fallos, errores u omisiones. Lint tiene cero errores y **15 avisos**,
frente a los siete NewerVersionAvailable del entorno local. Los ocho adicionales
(OldTargetApi, AndroidGradlePluginVersion y GradleDependency) apuntan exclusivamente
a configuración de SDK/build/dependencias sin modificar respecto al HEAD auditado.
No hay avisos en las líneas de UI corregidas. No se ha igualado todo el inventario
de SDK/metadatos del runner con el entorno local; se conservan ambas mediciones
sin ocultar la diferencia ni introducir supresiones o actualizaciones fuera de alcance.

Los artefactos originales conservan los XML/HTML y el resumen completo por run.
Para el HEAD final de documentación, consultar los checks actuales de la PR y los
enlaces específicos de su cuerpo; este archivo identifica expresamente el commit
de implementación y no atribuye su éxito a un commit posterior.
