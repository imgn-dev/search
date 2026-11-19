/// Full-text search: an inverted index in memory or on disk, with live updates, BM25 ranking and multilingual
/// analysis.
///
/// JSpecify and gaia-core are required transitively: JSpecify's annotations and gaia's `CharSource` are part of
/// the signatures this module exports.
module be.imgn.search {
    requires transitive org.jspecify;
    requires transitive be.imgn.gaia.core;

    exports be.imgn.search;
}
