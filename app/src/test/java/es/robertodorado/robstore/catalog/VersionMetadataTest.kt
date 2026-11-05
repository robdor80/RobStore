package es.robertodorado.robstore.catalog

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VersionMetadataTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun legacyV1IncludingCuadranteRemainsValidWithoutInferringVersions() {
        val manifest = ManifestValidator.parse(javaClass.getResourceAsStream("/rs05/cuadrante-v1.0.0.json")!!.use { it.readBytes() })
        assertEquals("es.robertodorado.cuadrante", manifest.packageName)
        assertEquals(1, manifest.schemaVersion)
        assertNull(manifest.versionCode); assertNull(manifest.versionName)
        val transport = FixtureTransport().apply {
            releases = listOf(fixtureRelease(tag = "v1.0.0"))
            assets = listOf(fixtureAsset(url = "https://github.com/robdor80/Demo/releases/download/v1.0.0/app-release.apk"))
        }
        val app = CatalogDiscovery(GitHubClient(transport, MemoryCache())) { TEST_NOW }.fetch().apps.single()
        assertNull(app.release.versionCode); assertNull(app.release.versionName)
    }

    @Test fun extendedV1VersionsComeOnlyFromPublishedTag() {
        val transport = FixtureTransport().apply {
            manifest = fixtureManifest("versionCode" to JsonPrimitive(Long.MAX_VALUE), "versionName" to JsonPrimitive("1.2 Beta"))
        }
        val app = CatalogDiscovery(GitHubClient(transport, MemoryCache())) { TEST_NOW }.fetch().apps.single()
        assertEquals(Long.MAX_VALUE, app.release.versionCode)
        assertEquals("1.2 Beta", app.release.versionName)
        assertEquals(listOf("edition-october"), transport.requests.filter { it.url.encodedPath.endsWith("robstore.json") }.map { it.url.queryParameter("ref") })
        assertFalse(transport.requests.any { it.url.queryParameter("ref") == "main" })
        assertNull(ManifestValidator.parse(fixtureManifest("versionName" to JsonPrimitive("9.9.9"))).versionCode)
        assertNull(ManifestValidator.parse(fixtureManifest("versionCode" to JsonPrimitive(1))).versionName)
    }

    @Test fun rejectsInvalidJsonTypesRangesAndVersionNames() {
        val badCodes = listOf("0", "-1", "01", "+1", "9223372036854775808", "1.0", "1e2", "\"1\"", "true", "null", "[]", "{}")
        val badNames = listOf("1", "true", "null", "[]", "{}", "\"\"", "\"   \"", JsonPrimitive("x".repeat(121)).toString(), JsonPrimitive("a\nb").toString())
        for ((field, values) in listOf("versionCode" to badCodes, "versionName" to badNames)) {
            values.forEach { value ->
                assertThrows("$field=$value", InvalidMetadata::class.java) {
                    ManifestValidator.parse((fixtureManifest().decodeToString().dropLast(1) + ",\"$field\":$value}").toByteArray())
                }
            }
        }
    }

    @Test fun oldCacheWithoutNewFieldsKeepsEntireCatalogAndNewCacheRoundTrips() {
        val directory = temp.newFolder()
        val cache = FileCatalogCache(directory)
        val snapshot = fixtureSnapshot()
        val json = catalogJson.parseToJsonElement(catalogJson.encodeToString(snapshot)).jsonObject
        val oldApps = JsonArray(json.getValue("apps").jsonArray.map { element ->
            val app = element.jsonObject
            JsonObject(app + ("release" to JsonObject(app.getValue("release").jsonObject.filterKeys { it !in listOf("versionCode", "versionName") })))
        })
        File(directory, "catalog-v1.json").writeText(JsonObject(json + ("apps" to oldApps)).toString())
        assertEquals(snapshot, cache.load())
        val app = snapshot.apps.single()
        val updated = snapshot.copy(apps = listOf(app.copy(release = app.release.copy(versionCode = Long.MAX_VALUE, versionName = "2.0"))))
        cache.save(updated)
        assertEquals(updated, FileCatalogCache(directory).load())
        cache.save(updated.copy(apps = listOf(app.copy(release = app.release.copy(versionCode = -1)))))
        assertNull(cache.load())
        cache.save(updated.copy(apps = listOf(app.copy(release = app.release.copy(versionName = "x".repeat(121))))))
        assertNull(cache.load())
    }
}
