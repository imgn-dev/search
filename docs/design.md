# Design

Full-text search for Java: an inverted index kept in memory or on disk, with live updates, BM25 ranking and
multilingual analysis of European languages. Performance is critical: every choice below favours speed, and
benchmarks decide between alternatives.

## Goals

- Ranked search: BM25 scores, term, phrase and boolean queries, top-k results.
- A query language, parsed into the same queries the API builds.
- Analysed input: documents and queries go through the same per-field analysis.
- Documents with text and keyword fields, any field possibly repeated (several tags, for instance).
- Live updates: add, update and delete documents from several threads while searchers run; crash-safe commits.
- Two storages, one format: an in-memory index bounded by the heap, and a file index that may be larger than RAM.
- Stored fields: opt-in per field, the original text is kept compressed in the index and returned with the hits.
- Multilingual: a language-neutral field for every language, plus stemmed fields for European languages.
- Speed: fast queries first, then fast indexing, measured by JMH on a realistic corpus.
- Dependencies: the JDK and gaia in the core; third-party code only in optional modules.

## Non-goals

- Languages outside Europe, and scripts other than Latin, Greek and Cyrillic. Text in other scripts is indexed
  crudely (see [Tokenization](#tokenization)); a tokenizer for such a script can be added later.
- Rebuilding an index under a new schema: the caller adds the documents again.
- Sharing a file index between processes: one process opens it at a time.
- Highlighting.
- Distribution over several machines.

## Modules

| Module | Java module | Contents | Dependencies |
|---|---|---|---|
| `search` | `be.imgn.search` | Index, analysis, queries, storage, `LanguageDetector` | JDK, gaia-core, gaia-fast |
| `search-snowball` | `be.imgn.search.snowball` | The European language analyzers: elision and Snowball stemmers generated from Snowball v3.2.0 | `search` |
| `search-langdetect` | `be.imgn.search.langdetect` | Our language detector and its model | `search` |
| `search-benchmarks` | none | JMH benchmarks, not published | all of the above |

The training tool of `search-langdetect` lives in the repository, unpublished.

The European languages, each with a Snowball stemmer: Basque, Catalan, Czech, Danish, Dutch, English,
Esperanto, Estonian, Finnish, French, German, Greek, Hungarian, Irish, Italian, Lithuanian, Norwegian, Polish,
Portuguese, Romanian, Russian, Serbian, Spanish, Swedish and Ukrainian.

## Overview

```
Document ─► Analysis ─► Writer buffers (pooled) ─refresh─► Segments (immutable)
                                                            │
                        Storage: heap, or memory-mapped file ◄┘
Query ─► Analysis ─► Searcher (snapshot of segments) ─► postings iterators ─► BM25 ─► top-k
```

An index is a list of immutable segments plus the writer's buffers. Both storages share one segment format and
one read path: every segment is read through a `java.lang.foreign.MemorySegment`. An in-memory index holds
only heap segments and a file index only mapped ones, so each sees a single segment class in its hottest loops.

## Analysis

Analysis turns a field's text into terms (UTF-8 bytes) at positions. It pushes each term to a sink rather than
returning token objects, so it allocates nothing per token.

### Tokenization

The core's tokenizer is written by hand for the Latin, Greek and Cyrillic scripts:

- A word is a run of letters and digits (`Character.isLetterOrDigit`), with the combining marks that follow
  them, so a decomposed accent stays on its letter. The soft hyphen (U+00AD) inside a word is dropped.
- An apostrophe between two letters belongs to the word: `l’arbre` and `aujourd'hui` are one token. `’`
  (U+2019) is read as `'`: NFKC does not unify them, and Snowball knows only `'`.
- Other punctuation, hyphens included, separates words: `COVID-19` gives `covid` and `19`, and `e-mail` gives
  `e` and `mail`.
- A word longer than 255 characters, such as an encoded blob, is skipped.
- Runs of other scripts are split the same way, which is crude but harmless.

### Normalization

Each token is:

1. normalized with NFKC (`java.text.Normalizer`), which also folds full-width and half-width forms;
2. lowercased character by character (`Character.toLowerCase`), never with the default locale;
3. case-folded where lowercasing is not enough: `ß` becomes `ss`, so `STRASSE` matches `straße`, and the final
   sigma `ς` becomes `σ`;
4. stripped of its accents, only for text fields that ask for it: NFD then dropping combining marks, plus a
   table for the letters NFD does not decompose (`ø` → `o`, `ł` → `l`, `đ` → `d`, `æ` → `ae`, `œ` → `oe`,
   `ð` → `d`, `þ` → `th`).

ASCII tokens skip normalization and are lowercased in place: they are the common case in European text.

### Neutral and language terms

One tokenization gives a text field two kinds of terms:

- the neutral terms, for every value: the normalized tokens, each split at its apostrophes, so `l’arbre` gives
  `l` and `arbre` at consecutive positions and a search for `arbre` finds it without a language;
- the language terms, for a value in a language that has an analyzer: the lowercased tokens, through elision
  (French, Italian, Catalan, Irish: `l’arbre` gives `arbre`), the language's Snowball stemmer, then case
  folding and accent removal, since Snowball expects the original letters.

A keyword field makes each value one term, normalized, lowercased and case-folded: no word splitting, no
stemming, no positions.

A field repeated in a document is analysed value by value. In a text field, the positions of each value start
100 after the last position of the previous one, so a phrase never matches across two values; BM25 counts the
field's length over all its values.

### Language routing

Routing applies to text fields only. A text field `body` produces internal fields:

- `body`: the neutral terms, for every value, with positions when the field keeps them;
- `body.fr`: the French terms, for the values in French, without positions, and so on for each language.

Phrases are matched on the neutral field only, so positions are stored once.

Routing is per value: a document whose `title` has a French and an English value puts both in `title`, the
first in `title.fr` and the second in `title.en`.

A language is identified by its primary language subtag (`Locale.getLanguage()`): `fr-BE` and `fr-CA` are
`fr`. Norwegian is `no`, and `nb` and `nn` map to it. Languages are stored and returned in this form.

A value's language is, in order:

1. the language given with the value;
2. otherwise, the language given with the document;
3. otherwise, the language detected for the document;
4. otherwise none.

A value without a language, or in a language without an analyzer in the schema, goes to the neutral field only.

### Language detection

```java
public interface LanguageDetector {
    /// The likely languages of the text, most likely first. Empty when the text is in none of the languages the
    /// detector knows.
    List<Guess> detect(CharSequence text);

    record Guess(Locale language, double probability) {}
}
```

- A language given by the caller always wins. The detector runs once per document, over the text values that
  have no language when the document has none either; its result becomes the document's language. Values are
  not detected one by one: a title of a few words is too short to detect reliably.
- Below the schema's confidence threshold, the document gets no language.
- Without a detector in the schema, only the languages given by the caller are used.
- `search-langdetect` provides our detector. Another implementation, such as one around OpenNLP's
  `LanguageDetectorME`, plugs into the same interface in its own module.

Our detector:

- Knows the European languages only: telling languages apart is useful only to pick a stemmer.
- Narrows the candidates by script: Greek text is Greek; Cyrillic text is Russian, Ukrainian or Serbian.
- Scores the candidates with naive Bayes over character 1- to 3-grams, with log-probabilities and smoothing.
- Answers "none of these" when the best candidate's average log-likelihood per n-gram is below a floor set
  during training: probabilities normalized over the known languages alone are high even for an unknown
  language.
- Reads at most about 2 KB, sampled across the text, so its cost is bounded.
- Detects documents only: a query is too short to detect reliably, so its language comes from the caller.
- Ships its model as a binary resource, generated by the training tool from an openly licensed corpus.
- Is tested for a minimum accuracy per language at 50, 200 and 1,000 characters on held-out sentences, and for
  rejecting text in languages it does not know.

## Schema

A `Schema` lists:

- the fields, each a text field (name, whether positions are kept, whether accents are removed, whether values
  are stored) or a keyword field (name, whether values are stored). A field name is made of letters, digits,
  `_` and `-`: the `.` is reserved for language fields;
- the default fields, searched by query-language words without a field;
- the language analyzers, registered explicitly, for instance all of `search-snowball`'s;
- the language detector and its confidence threshold, optional.

Each language analyzer has an identifier that names its algorithm and version, such as
`snowball-3.2.0/french`. A file index records its fields with their options, and the identifier of each
language analyzer it used. It refuses to open with a schema that changes a field, or that changes or lacks one
of those analyzers, for instance without `search-snowball` or with another Snowball release: the terms already
indexed would no longer match the queries. The fields must be the same, in the same order: adding or removing
one is refused too, since stored records refer to their fields by position. A new language analyzer is
accepted: it applies to the documents added from then on. The analyzers a commit records are those recorded
before plus those of the writer's schema, so the set only grows. The default fields, the detector and its threshold are not recorded: they decide how new
documents are routed and how queries are read, never what the indexed terms mean.

## Documents

The input and the retrieved document are two types: a retrieved document holds only the stored fields, so
fetching it, changing it and adding it again would silently lose the others.

A `Document` is what the writer indexes: an immutable record of a `String` key, unique in the index, a
`@Nullable Locale` language, and an ordered list of fields, a sealed `Field` with two records,
`Text(String name, String value, @Nullable Locale language)` and `Keyword(String name, String value)`. It is not
a map: a field may appear several times, and the order of its values is kept. `Document.builder(key)` builds
one; its `language` methods take a `Locale`, and leaving them out leaves the language `null`.

`Optional` is used only as a return type, never as a record component, field or parameter: an absent language
is `null` in the records, and a `StoredDocument` method returns `Optional` where nothing may be found.

The document's language is the default of its text values; a text value's own language, when given, overrides
it (see [Language routing](#language-routing)). Keyword values have no language: they are never stemmed.

`add` checks the document against the schema: a field the schema does not declare, or of another kind, throws
`IllegalArgumentException`. Adding a document whose key exists replaces it.

A `StoredDocument` is what the searcher returns: read only, with no builder and no conversion to `Document`.

- `key()`;
- `language()`: the document's language, given or detected, as an `Optional<Locale>`;
- `values(name)`: the field's stored values in input order, empty when the document has none;
- `value(name)`: the first of them, as an `Optional<String>`;
- `texts(name)`: a text field's stored values with their languages, as
  `record StoredText(String value, @Nullable Locale language)`, where `language` is `null` for a value
  without one.

Languages come back as their primary subtag, the form used for routing: a document given `fr-BE` returns `fr`.
`values`, `value` and `texts` throw `IllegalArgumentException` for a field the schema does not declare as
stored, and `texts` for a keyword field.

## Segment format

All numbers are little-endian. A segment holds at most 2³¹ − 1 documents, numbered from 0; the documents of a
snapshot are numbered by the segments' bases plus their local numbers, and an index holds at most 2³¹ − 1
documents, deleted ones included until a merge drops them. `add` throws `IllegalStateException` beyond that.
No segment holds two documents with the same key.

On disk a segment is one file, plus its deletions; its sections are:

| Section | Encoding |
|---|---|
| Term dictionary | Per internal field, sorted terms in blocks of about 32 with shared prefixes stored once. Per term: document frequency, highest frequency and lowest norm (the term's score bound), and the offsets of its postings, skip table and positions. The first term and offset of each block form the block index. |
| Postings | Per term: document numbers as gaps, and frequencies, in blocks of 128, each bit-packed with the width of its largest value. The last, partial block is written as variable-length integers. |
| Skip table | Per term with more than one block, one fixed-size entry per block: last document number, offsets of the block's postings and positions, and the block's highest frequency and lowest norm. Binary-searched by `advance`. |
| Positions | Per posting, for the neutral text fields that keep them: positions as gaps, bit-packed in blocks of 128 like the postings. |
| Norms | Per document and neutral text field, the field's length in one byte, quantized logarithmically. Language fields have none: they use the neutral field's. |
| Keys | Document number to key, and key to document number (a term dictionary of keys). |
| Stored fields | Each document's language and stored values, with each value's language when it differs, as UTF-8, in blocks of about 16 KB compressed with `java.util.zip.Deflater` at `BEST_SPEED`. An index maps each document number to its block. |
| Statistics | Per neutral text field: the number of documents that have it and their total length. |

Score bounds are stored as raw frequencies and norms, not scores: BM25 depends on the snapshot's statistics,
which change with every refresh and merge, so a query turns bounds into scores with its own statistics.

Deletions are a bitset in a separate file, under a generation number: deleting documents writes a new
generation, since the segment never changes.

Every file starts with a magic number and a format version and ends with a CRC32C checksum
(`java.util.zip.CRC32C`). Opening checks the header and footer of segment files, and the whole manifest and
deletion files, which are small. The manifest records the size and checksum of each segment file, so that
opening checks a segment's footer against it without reading the file. A segment's checksum is computed while it is written and checked in full by
`index.check()`, on demand: checking it on open would read the whole index, and for an index larger than RAM
evict the page cache.

## Storage

- **Memory**: segments are `byte[]`, read through `MemorySegment.ofArray`. A segment is limited to 1 GiB, so
  that merges never approach the 2 GiB limit of an array.
- **Files**: segments are mapped with `FileChannel.map` into an `Arena.ofShared()`. The data stays in the OS
  page cache, not the heap, so the index may be larger than RAM. A segment is limited to 5 GiB. Closing the
  arena unmaps the file; a later read throws `IllegalStateException` rather than crashing the JVM.

Reads use the unaligned layouts in little-endian order, such as
`ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)`: the aligned ones throw on a heap segment,
whose alignment is one byte, and at odd offsets of a mapped one, and the default order is the platform's.

A file index's directory holds the segment files, their deletion files, `manifest-<generation>` files and a
`write.lock` file. Creating an index writes `manifest-0`, empty, so a directory with a manifest is an index
and one without is not, even after a crash. `Index.open` creates an index in a missing directory, or in one
holding nothing but a lock or temporary files; it refuses any other directory without a manifest.

`Index.open` takes `FileChannel.tryLock` on `write.lock` for as long as the index is open. Whether the lock is
held by another process (`tryLock` returns `null`) or by this one (`OverlappingFileLockException`), it throws
`IndexLockedException`, an `IOException`. The locks of the process are also tracked by the lock file's real path, checked
before any channel is opened: on POSIX systems, closing any channel on a file releases every lock the process
holds on it, so a second `Index.open` in the same process must not open one.

File names carry their numbers without leading zeros, in at most 18 digits; a file named otherwise is not the
index's, and is left alone.

Methods that write files throw `IOException`, like gaia's I/O, on both storages, so that one `Writer` type
serves both; searching a snapshot does not.

## Writing

`index.writer()` returns the index's one `Writer`, with a memory budget of 64 MiB for its buffers;
`index.writer(memoryBudget)` sets another. A second call while the writer is open throws
`IllegalStateException`. The writer is thread-safe and indexing scales with threads.

### Buffers

The writer keeps a pool of buffers. Each `add` takes a free buffer, or creates one, uses it alone, and puts it
back: there are as many buffers as concurrent `add`s at their peak, whatever the number of threads, and no
buffer belongs to a thread.

A buffer holds terms in a hash table of term identifiers over large shared `byte[]` blocks, and appends
postings to per-term slices of shared `int[]` blocks, so its memory use is counted exactly against the budget
and its objects are few. When the buffers together exceed the budget, the largest becomes a segment, written by
the thread that puts it back.

### Sequence numbers and deletes

Every `add` and `delete` takes a sequence number, and every document keeps the number of its `add`. A delete,
or the replacement done by an `add`, applies to the documents of its key with a lower number, so concurrent
operations on one key end as they were ordered.

- When a buffer becomes a segment, a document whose key a later operation in the same buffer replaced or
  deleted is dropped.
- Deletes are buffered by key, not applied one by one. A refresh resolves them per segment in one sorted pass
  over its keys, comparing sequence numbers, into a new generation of its deletion bitset.
- A segment keeps the sequence numbers of its documents, on the heap, until a refresh has resolved every delete
  older than its newest document. From then on every later delete is newer than all its documents, and the
  numbers are dropped.
- A new bitset generation copies only the 4 KB pages that change, so open searchers keep their own and a
  refresh costs little in a large segment.

### Refresh and commit

- `refresh()` takes a sequence number as a cut: it waits for the `add`s in progress to put their buffers back,
  turns every buffer into a segment and resolves the buffered deletes. Operations numbered after the cut wait
  for the next refresh. The next searchers see every change up to the cut, without it being durable. In a file
  index these segments are written to files without `force`, and mapped like the others.
- `commit()` refreshes and makes every change durable (files only):
  1. write the deletion files not yet written, and `force` them with the segment files written since the
     last commit; then `force` the directory, so that their names survive a power cut before a manifest lists
     them: a rename may persist before the creations that precede it;
  2. write `manifest-<n+1>`, the list of live segments with their size, checksum and deletion generation, to a
     temporary file, `force` it, rename it with `ATOMIC_MOVE`, and `force` the directory, so the new name
     survives a power cut. Forcing a directory is skipped where it cannot be opened;
  3. release the previous manifest's files.
- `close()` commits, stops the merges and releases the writer.

After an `IOException` the writer is closed: the index stays at its last commit, and a new writer can be
taken. A commit that fails once its manifest is renamed may leave the index at that commit: the next
writer reads the newest manifest again. A writer that failed never renames a manifest, even from a commit
already in progress on another thread, and a new writer waits for such a commit to end before it cleans the
directory up. Opening a file index reads the newest manifest; a bad checksum there throws, rather than silently
falling back to an older commit.

### Merges

A tiered policy merges segments of similar sizes, up to the maximum segment size, and drops deleted documents.
A block of stored fields without deleted documents is copied still compressed. Only segments whose sequence
numbers were dropped are merged.

Merges run on background threads owned by the writer, at most two at a time, and never block `add`, `delete`
or searches. A merge whose segments total more than 256 MiB never takes the last free thread, so small merges
never wait behind a large one.

A merge reads its segments with the deletions current when it starts. When it ends, the writer replays onto
the merged segment the deletes that reached its inputs meanwhile, through the mapping from old to new document
numbers, then swaps the segments. The merged segment becomes visible at the next refresh, and durable at the
next commit. In a file index a merge writes its sections, which it fills side by side, to temporary
files, `<segment>.<n>.tmp`, then copies them into the segment file, so that it holds little on the heap
whatever its size; a flush, bounded by the memory budget, keeps its sections on the heap.

### Files

One component counts the references to each file: from the last committed manifest, from each open snapshot,
and from the writer's segments not yet committed, merge outputs included. A file is deleted when its count
drops to zero; a snapshot's files are unmapped before that. Only the latest manifest is kept. A writer, on
opening, deletes through the same component the files no manifest lists and nothing references: leftovers of
a crash or of a failed writer.

## Searching

`index.searcher()` returns a `Searcher` over a snapshot: the segments and deletions current at that moment.
It is thread-safe: each search call has its own iterators and buffers, and decompressors come from a small
pool per searcher. It must be closed, which releases its references to the files.

Queries:

- `term(field, text)`: the text goes through the field's analysis; when it gives several tokens, they form a
  phrase on a neutral field that keeps positions and an `all` elsewhere, searched in the neutral field only,
  as phrases are; when it gives none, the term is dropped from its parent query;
- `phrase(field, text)`: consecutive positions, on text fields that keep them; on a keyword field, the text
  is one term;
- `all(...)`, `any(...)`: boolean combinations; one whose children were all dropped matches nothing;
- `not(...)`: only excludes from its siblings in an `all`, and on its own matches nothing; `not(not(q))` is `q`.

`search` checks the fields of the query against the schema: an unknown field, or a phrase on a text field that
drops positions, throws `IllegalArgumentException`.

`search(query, k)` searches the neutral fields. `search(language, query, k)` also searches, for each text
field, the language's field, for the terms that give one token; for a language without an analyzer it is
`search(query, k)`.

### Scoring

Scores are BM25 (k1 = 1.2, b = 0.75), as `float`, from the frequencies, the norms and the statistics of the
neutral field, summed over the snapshot's segments: document count and total length. Each term's document
frequency comes from its own internal field, also summed over the segments.

- A term searched in a field's neutral and language variants scores the best of the two. Both use the neutral
  field's norms, document count and average length, so only frequencies and document frequencies differ: the
  language variant counts the forms that its stem conflates, as it should.
- `all` and `any` add the scores of the children that match.
- A phrase is scored like a term whose frequency is the number of times the phrase occurs and whose inverse
  document frequency is the sum of its words'.

### Evaluation

Each term opens a `DocIterator` (`nextDoc()`, `advance(target)`) that decodes a block of 128 at a time into
reusable `int[]` arrays and finds blocks through the skip table.

Every query has a score bound, computed from stored frequencies and norms with the snapshot's statistics: a
term's from its highest frequency and lowest norm, in the whole term or in a block; an `all` or `any`'s as the
sum of its children's; the best of a neutral and language pair as the larger of the two; a phrase's from the
lowest of its words' highest frequencies.

- An `all` iterates its rarest child and advances the others. Once the top-k is full, it skips the blocks where
  the sum of its children's block bounds cannot beat the lowest score kept (block-max).
- An `any` uses MaxScore: children whose bounds together cannot beat the lowest score kept stop leading the
  iteration and are only checked on the candidates of the others.
- A phrase is an `all` checked against positions.

Since a space means AND and a word expands into an `any` over the default fields and their variants, the
common query is an `all` of `any`s, and both mechanisms apply to it.

`search` returns `List<Hit>`, best first; equal scores keep document order. `Hit` is a final class, not a
record: it gives its `key()` and `score()`, and hides the document's number and the searcher it comes from, so
`searcher.document(hit)` reads the stored fields without a key lookup. A hit from another searcher throws
`IllegalArgumentException`.

### Query language

The query language is parsed into the queries above; it adds nothing the API cannot build.

```
renard "le renard" title:fox tags:animal -brouillon (chat OR chien)
```

| Syntax | Meaning |
|---|---|
| `word` | A term, searched in each default field: an `any` of `term(field, word)` |
| `"some words"` | A phrase, searched in each default field that keeps positions; one term in a default keyword field |
| `field:word`, `field:"some words"` | A term or a phrase in that field only |
| `a b`, `a AND b` | Both: a space means AND |
| `a OR b` | Either |
| `-a`, `NOT a` | Not `a` |
| `( … )` | Grouping, at most 100 deep |
| `\` | Escapes the next character |

- `NOT` binds tighter than `AND`, which binds tighter than `OR`.
- `AND`, `OR` and `NOT` are operators in capitals only: `and`, `or` and `not` are words.
- `-` is an operator only at the start of a word, directly followed by what it excludes: `e-mail` is a word.
  The word right after it is never an operator: `-AND` excludes the word `and`.
- `name:` starts a field only when the schema declares `name`: `12:30` is a word. What follows it is a word or
  a phrase, never an operator.
- An `all` or `any` of one query is that query: `(a)` is `a`, and a word is a plain `term` when it has one field
  to search.
- A word or phrase whose text gives no term in any of its fields, such as `!!!`, is dropped, and so is a part
  left with nothing to search: `hello !!!` searches `hello`. Without that, the `any` of its dropped terms would
  match nothing, and so would its whole conjunction.
- A part of the query made only of exclusions matches nothing on its own: `x OR -b` is `x`, and `-a` alone
  matches nothing.
- A query that is left with nothing to match, such as `!!!` or an empty query, matches nothing: it parses to
  `any(term(field, ""))` on the schema's first field, which matches nothing even inside an `all`.

Parsing needs the schema, for the default fields and the field kinds:

- `schema.parse(text)` is strict: a syntax error, a phrase where no field keeps positions, a word or phrase
  without a field when the schema has no default fields, or a part made only of exclusions throws
  `QuerySyntaxException`, which gives the character position. For queries written by developers or in
  configuration.
- `schema.parseLenient(text)` never throws: a character that would be an error is read as text, and a part
  that cannot match is dropped, a word without a field when the schema has no default fields included. A
  phrase that no field keeps positions for searches its words, as a `term` does. For a search box shown to end
  users.

## Performance

- Benchmarks from the first commit, in `search-benchmarks`: indexing rate per thread count, refresh and commit
  latency, query latency per query shape (rare and common terms, `all`, `any`, phrases, top-10 and top-1000),
  merge time, detection rate. The corpus is real text in several European languages, such as Wikipedia
  extracts, downloaded by the owner and never committed.
- Comparisons with other engines, Lucene first, are run outside the repository.
- Hot paths allocate nothing per token or per posting: analysis pushes into a sink, iterators decode into
  reusable arrays, and a search call reuses its iterators across segments.
- Decoding reads whole blocks from the `MemorySegment` and unpacks them in plain loops the JIT can vectorize.
- Each index has one segment class, heap or mapped, so the decoding loops stay monomorphic.
- The block index of each term dictionary is loaded on the heap when a segment opens, so a term lookup is a
  binary search on the heap and one block scan in the segment.
- Analysis tokenizes once for both the neutral and the language terms; positions and norms are stored for the
  neutral fields only.
- `Deflater` and `Inflater` instances are pooled, and closed with the writer or searcher.

## API sketch

```java
var schema = Schema.builder()
        .text("title", Positions.KEEP, Accents.KEEP, Stored.YES)
        .text("body", Positions.KEEP, Accents.KEEP, Stored.NO)
        .keyword("tags", Stored.YES)
        .defaultFields("title", "body")
        .languages(SnowballAnalyzers.all())
        .languageDetector(new NaiveBayesDetector(), 0.8)
        .build();

try (var index = Index.open(path, schema)) {              // or Index.inMemory(schema)
    try (var writer = index.writer()) {                   // closing the writer commits
        writer.add(Document.builder("doc-42")
                .language(Locale.FRENCH)
                .text("title", "Le renard")                // French, from the document
                .text("title", "The fox", Locale.ENGLISH)  // this value only
                .text("body", "...")
                .keyword("tags", "animal")
                .keyword("tags", "forêt")
                .build());
        writer.add(Document.builder("doc-43")              // language detected
                .text("title", "The quick fox")
                .build());
        writer.delete("doc-17");
        writer.refresh();                                 // visible to the next searchers
    }

    try (var searcher = index.searcher()) {
        Query query = schema.parseLenient("renards tags:forêt");
        // The same as Query.all(Query.any(Query.term("title", "renards"), Query.term("body", "renards")),
        //         Query.term("tags", "forêt"))
        List<Hit> hits = searcher.search(Locale.FRENCH, query, 10);
        StoredDocument first = searcher.document(hits.getFirst());
        List<String> tags = first.values("tags");
        List<StoredText> titles = first.texts("title");    // with their languages
    }
}
```

## Testing

- Unit tests per layer: tokenization and normalization, codec round trips (stored fields included),
  iterators, score bounds never below the scores they bound, scoring against hand-computed BM25.
- Query language: every construct parsed to its API query; strict errors at the right position; the lenient
  parser fed random input never throws.
- Every index test runs on both storages.
- File index tests use temporary directories: mapping files needs the default file system.
- Concurrency: several threads adding, replacing and deleting the same keys while refreshes, merges and
  searches run; the final index holds exactly the last operation of each key. Searches from several threads on
  one searcher return what a single thread does.
- Top-k: block-max and MaxScore return the same hits as exhaustive scoring.
- Crash tests: file operations go through an internal layer that a test replaces with one that fails, or
  simulates a power cut that loses whatever was not forced, or keeps only the renames among the changes to the
  directory since its last force, at every write, `force`, rename and delete. After each, reopening gives
  exactly the last commit, or the one in progress if its manifest survived.

## Open questions

1. Training corpus for the language detector: Tatoeba (CC BY 2.0 FR, attribution in `NOTICE`) is proposed.
   The owner downloads the export by hand, so the training tool reads it from a local path given to it.
2. Whether the generated Snowball sources pass the parent's checks (Checkstyle, Palantir format, Error Prone)
   after mechanical fixes, or need an exclusion in `search-snowball`, which the owner decides.
