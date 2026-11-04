package es.robertodorado.robstore.download

import es.robertodorado.robstore.catalog.*
import es.robertodorado.robstore.update.SelfUpdateCandidate

internal fun validArtifact(app: ReleaseArtifact): Boolean = when (app) {
    is CatalogApp -> CatalogValidation.app(app)
    is SelfUpdateCandidate -> app.valid()
    else -> false
}
