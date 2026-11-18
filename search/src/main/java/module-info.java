/// Full-text search: an inverted index in memory or on disk, with live updates, BM25 ranking and multilingual
/// analysis.
///
/// JSpecify is required transitively: its annotations are part of the signatures this module exports.
module be.imgn.search {
    requires transitive org.jspecify;
    requires be.imgn.gaia.core;

    exports be.imgn.search;
}
