sidecar_index
=============

Sidecar index components for Solr (4.5.0).

This is a set of Solr components for easy management of "sidecar indexes" - indexes
that extend the main index with additional stored and / or indexed fields. Conceptually
this can be viewed as an extension of the ExternalFileField or as a static join between
documents from two collections. This functionality is useful in applications that require
very different update regimes for the two parts of the index (e.g. main catalogue items
combined with clickthroughs).
