# Pruebas nuevas y de regresión

Las diez nuevas definiciones se ejecutan tanto en Debug como en Release: veinte
ejecuciones nuevas, además de las 178 anteriores. Ningún método de prueba previo
se ha eliminado; no se añaden `Ignore`, filtros permanentes ni exclusiones.

| Clase | Método nuevo | Verificación |
| --- | --- | --- |
| RobUiTest | `noSnapshotIsPendingUntilARealEmptySnapshotArrives` | Ausencia de datos pendiente; vacío solo tras descubrimiento RS-02 completo con respuesta HTTP vacía simulada. |
| RobUiTest | `thirtyAppsKeepStaleErrorAndLimitedNoticesVisible` | 30 apps, primer aviso y primera app visibles sin scroll; aviso antes de app 1; app 30 virtualizada; semántica LiveRegion Polite. |
| RobUiTest | `thirtyAppsKeepNoticesVisibleWithEnlargedFont` | Los mismos tres avisos con fuente 200 %, ancho disponible 320 dp. |
| RobUiTest | `expiredAndMissingRefreshTimesDoNotAnnounceFutureCheck` | Fecha pasada, igual al reloj, cero y negativa; nunca se anuncia una hora vencida como futura. |
| RobUiTest | `visibleDeadlineExpiresWithoutPeriodicWorkOrRefresh` | Vencimiento en pantalla con reloj controlado y avance virtual; sin callback de sincronización. |
| RobUiTest | `futureGateWithoutDiagnosticIsVisibleBeforeApps` | Bloqueo estructurado visible aunque no haya texto de diagnóstico. |
| RobUiTest | `resumeRechecksExpiredGateWithoutNetwork` | Reanudar recalcula el bloqueo vencido sin refrescar el catálogo. |
| RobUiTest | `settingsKeepsTechnicalWarningsAndDistinguishesRefreshTiming` | Diagnóstico íntegro, caché/error y estados temporalmente bloqueado, permitido y desconocido. |
| CatalogFormattingTest | `refreshDeadlineUsesDeterministicClockAcrossTimeZones` | Clock.fixed, límites futuro/igual/pasado/inválido en UTC, Madrid, Los Ángeles y Tokio. |
| CatalogFormattingTest | `blockedDeadlineRetainsLocalizedFormattingAcrossDstAndDateRollover` | Salto de horario de verano de Madrid, día anterior en Los Ángeles y locales español/US. |

Los nombres de clase completos y resultados individuales están en los TSV
[Debug](tests-debug.tsv) y [Release](tests-release.tsv), procedentes de los XML.

## Regresiones conservadas

- **RS-02: 51 casos por variante, 102 ejecuciones**: `CatalogCacheTest` (4),
  `CatalogDiscoveryTest` (22), `CatalogIconLoaderTest` (4),
  `CatalogRepositoryTest` (10), `GitHubClientTest` (8), `HttpTransportTest` (3).
  Se conservan sin cambios sus archivos respecto al HEAD auditado.
- **Identidad y launcher: 7 casos por variante**: `AppIdentityTest` (4),
  `LauncherIconTest` (3). IDs, nombre DEV, entrada Compose e iconos oficiales.
- **UI: 33 casos por variante**. Se mantienen navegación Home → AppDetail → Home,
  flecha/Atrás de Android, app retirada, metadatos sin instalación, carga inicial,
  reintento, caché con fallo, vacío válido con fallo, bloqueo del callback durante
  refresco, icono válido/fallback, líneas/elipsis, anchos 320/600/1200 dp y fuente 200 %.
- **Formato: 8 casos por variante**. Fechas locales, cambios de día y DST,
  fechas inválidas, tamaños APK y los nuevos límites temporales.

Las expectativas de tres aserciones de navegación que usaban el estado sin datos
se actualizan a «Pendiente de primera sincronización». Las aserciones de aviso
Home comprueban el mensaje compacto; los textos técnicos originales siguen
comprobándose en Ajustes. El render de caché con error ya no desplaza hasta el final.
Los fixtures y el transporte HTTP simulado pertenecen exclusivamente a tests.

## Incidencia del primer intento local

El primer `./gradlew test --no-daemon` ejecutó 59 entradas Debug: 55 superadas y
4 fallidas al inicializar clases de Robolectric (`UnknownHostException` al obtener
su dependencia Android de Maven). Release no llegó a ejecutarse en ese intento.
El proceso de tests no había heredado el proxy requerido por este entorno.
Se corrigieron las propiedades del proxy y la confianza CA **fuera del repositorio**
y se repitió el comando completo: **198/198, cero fallos, errores u omisiones**.
No se deshabilitó ninguna prueba ni se relajaron aserciones para evitar el fallo.
