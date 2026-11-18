/// Indexing: the in-memory buffer of analysed documents, its conversion into segments, deletions, the snapshots
/// searchers read, and the storages that keep segments on the heap or in files and make commits durable. Internal.
@NullMarked
package be.imgn.search.internal.index;

import org.jspecify.annotations.NullMarked;
