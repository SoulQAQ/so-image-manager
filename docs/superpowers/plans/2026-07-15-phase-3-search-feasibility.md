# SoIM Phase 3 Search Feasibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Room v4 full-field local search engine with FTS4, bounded substring/typo/pinyin indexes, progressive results, a Chinese Compose search surface, and a repeatable 10k/50k/100k feasibility gate before any provider traffic is implemented.

**Architecture:** `SearchProjectionPlanner` converts one transaction-current effective image projection plus MediaStore metadata into a bounded immutable index plan. `RoomSearchProjectionWriter` applies that plan through the same `AppDatabase` transaction used by canonical activation/correction. `RoomImageSearchRepository` runs deterministic structured, FTS4, substring, typo, and pinyin stages under explicit candidate/time/cancellation budgets and exposes stable progressive result tiers to Compose.

**Tech Stack:** Kotlin 2.0.21, Room 2.6.1 `@Fts4`, platform SQLite on minSdk 29, Paging 3.3.2, coroutines 1.8.1, pinyin4j 2.5.1, Jetpack Compose Material 3.

## Global Constraints

- Keep `minSdk = 29`, `targetSdk = 36`, and `compileSdk = 36`.
- Use platform SQLite FTS4 through Room `@Fts4`; do not add FTS5 or bundled SQLite.
- Normalize queries with NFKC, `Locale.ROOT` lowercasing, and Unicode whitespace collapse; reject queries over 128 Unicode code points.
- Split normalized free-text source into 512-code-point chunks with 127-code-point overlap.
- Generate complete tone-free full-pinyin and initials streams before splitting each stream into 512-character chunks with 127-character overlap.
- Lexical aliases are at most 128 characters, with at most two full-pinyin pronunciations and one initials alias per term.
- Use Unicode bigrams for CJK-containing search units and Latin/digit trigrams for non-CJK search units; one-character CJK and one/two-character Latin queries never perform caption substring scans.
- Enforce at most 768 relationships per image across `ImageSearchTerm` mappings, free-text source chunks, and image-owned long-text alias chunks before mutating index tables. Shared lexical aliases and deduplicated grams count toward storage but not this per-image relationship number.
- Typo matching is disabled below three code points; use Damerau-Levenshtein distance 1 for lengths 3-5 and 2 for lengths 6-128.
- Each query token evaluates at most 512 gram-ranked terms, accepts at most 64 typo-corrected terms, and contributes at most 5,000 image candidates.
- Structured plus FTS4 has a 1-second budget, substring adds 2 seconds, typo/pinyin adds 2 seconds, and the whole query stops after 5 seconds.
- Replacement-query cancellation is checked after every database page and every 256 in-memory candidates; the reference-device P95 target is 200 ms.
- Later progressive stages cannot outrank an already published higher tier. The first page contains 40 images.
- Live fixture averages must stay within 1.5 KiB/image for FTS4 and 4.5 KiB/image for search relations, preserving the full 10 KiB/image live-data ceiling.
- The deterministic fixture uses 10k, 50k, and 100k subsets; 80% analyzed, 5% long captions, 4-24 tags, 1-4 categories, 4-32 tokens, 20% CJK terms, 10% polyphonic CJK terms, and 10% user corrections/tombstones.
- Phase 3 contains no provider profile, secret, HTTP client, model request, durable model job, or batch-processing code.
- Phase 4 handoff priority is OpenAI Responses first; no other provider preset may delay the first AI-capable test APK once OpenAI Responses and the declarative custom protocol pass their shared safety/canonical gates.
- Ordinary development builds keep published version `0.4.1 / versionCode 7`; version changes occur only through the test-APK publisher.

---

### Task 1: Search Text, Chunk, Gram, Typo, and Pinyin Domain Engine

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/cn/soul2/imageai/search/SearchLimits.kt`
- Create: `app/src/main/java/cn/soul2/imageai/search/SearchTextNormalizer.kt`
- Create: `app/src/main/java/cn/soul2/imageai/search/OverlappingChunker.kt`
- Create: `app/src/main/java/cn/soul2/imageai/search/SearchGramGenerator.kt`
- Create: `app/src/main/java/cn/soul2/imageai/search/DamerauLevenshtein.kt`
- Create: `app/src/main/java/cn/soul2/imageai/search/PinyinTransliterator.kt`
- Create: `app/src/main/resources/cn/soul2/imageai/search/polyphonic_phrases.tsv`
- Test: `app/src/test/java/cn/soul2/imageai/search/SearchTextNormalizerTest.kt`
- Test: `app/src/test/java/cn/soul2/imageai/search/OverlappingChunkerTest.kt`
- Test: `app/src/test/java/cn/soul2/imageai/search/SearchGramGeneratorTest.kt`
- Test: `app/src/test/java/cn/soul2/imageai/search/DamerauLevenshteinTest.kt`
- Test: `app/src/test/java/cn/soul2/imageai/search/PinyinTransliteratorTest.kt`

**Interfaces:**
- Produces: `SearchTextNormalizer.normalizeQuery(raw: String): NormalizedSearchQuery`.
- Produces: `OverlappingChunker.chunkByCodePoints(text, size = 512, overlap = 127)` and `chunkByCharacters(...)`.
- Produces: `SearchGramGenerator.grams(text: String): Set<String>`.
- Produces: `DamerauLevenshtein.withinDistance(left, right, maximum): Int?`.
- Produces: `PinyinTransliterator.lexicalAliases(term): List<PinyinAlias>` and `completeStreams(text): PinyinStreams`.

- [x] **Step 1: Add failing normalization and query-limit tests.** Assert NFKC equivalence, `Locale.ROOT` folding, whitespace collapse, invalid UTF-16 rejection, blank-query behavior, exactly 128 accepted code points, and 129 rejected code points with `SearchValidationException`.

- [x] **Step 2: Run the normalization tests and verify RED.**

Run: `./gradlew testDebugUnitTest --tests "cn.soul2.imageai.search.SearchTextNormalizerTest"`

Expected: compilation failure because `SearchTextNormalizer` does not exist.

- [x] **Step 3: Implement frozen constants and normalization.** Use these exact constants:

```kotlin
object SearchLimits {
    const val QUERY_CODE_POINTS = 128
    const val SOURCE_CHUNK_CODE_POINTS = 512
    const val SOURCE_CHUNK_OVERLAP = 127
    const val ALIAS_CHUNK_CHARACTERS = 512
    const val ALIAS_CHUNK_OVERLAP = 127
    const val LEXICAL_ALIAS_CHARACTERS = 128
    const val LEXICAL_ALIAS_COUNT = 3
    const val RELATIONSHIPS_PER_IMAGE = 768
    const val GRAM_TERM_CANDIDATES = 512
    const val TYPO_TERMS = 64
    const val IMAGE_CANDIDATES = 5_000
}
```

- [x] **Step 4: Add boundary-first chunk tests.** Cover empty input, exact 512, 513, 896, surrogate pairs, a 128-code-point query crossing every 512/127 boundary, and reconstruction proving no suffix loss.

- [x] **Step 5: Implement deterministic code-point and character chunkers.** Every chunk records `ordinal`, `startOffset`, and text. Reject invalid `size <= overlap`, negative values, and invalid UTF-16.

- [x] **Step 6: Add gram and short-query tests.** CJK input emits unique Unicode bigrams. Latin/digit input emits unique trigrams. Mixed text emits both relevant forms. Inputs shorter than their required gram size emit an empty set.

- [x] **Step 7: Implement bounded gram generation.** Normalize separators to a single ASCII space for aliases; never emit duplicate grams from one search unit.

- [x] **Step 8: Add Damerau-Levenshtein tests.** Cover insertion, deletion, substitution, adjacent transposition, Unicode code points, early exit over the limit, and the exact length-to-distance rule.

- [x] **Step 9: Implement banded Damerau-Levenshtein.** Work on code-point arrays, allocate `O(min(left,right))` rows, and stop when a complete row minimum exceeds `maximum`.

- [x] **Step 10: Add pinyin4j and failing transliteration tests.** Add `pinyin4j = "2.5.1"` and `pinyin4j = { group = "com.belerweb", name = "pinyin4j", version.ref = "pinyin4j" }`. Tests cover `重庆`, `银行`, `长安`, non-CJK passthrough, tone removal, `v` normalization, at most two full forms plus one initials form, full-caption streams longer than 128 characters, and 512/127 pinyin chunk boundaries.

- [x] **Step 11: Implement phrase-first transliteration.** Load the UTF-8 TSV once into an immutable longest-match trie. The TSV format is `phrase<TAB>space-separated-syllables`; include the query-corpus polyphonic phrases and their primary pronunciations. Fall back to pinyin4j for characters outside a phrase. Lexical alternate expansion is character-local, deduplicated, and stops after two full forms; complete free-text streams use only the primary phrase-aware pronunciation.

- [x] **Step 12: Run all Task 1 tests and commit.**

Run: `./gradlew testDebugUnitTest --tests "cn.soul2.imageai.search.*"`

Expected: all Task 1 tests pass.

Commit: `feat: add bounded search text engine`

---

### Task 2: Room v4 Search Schema and Explicit Migration

**Files:**
- Create: `app/src/main/java/cn/soul2/imageai/data/db/entity/SearchDocumentEntity.kt`
- Create: `app/src/main/java/cn/soul2/imageai/data/db/entity/SearchDocumentFtsEntity.kt`
- Create: `app/src/main/java/cn/soul2/imageai/data/db/entity/SearchTermEntity.kt`
- Create: `app/src/main/java/cn/soul2/imageai/data/db/entity/ImageSearchTermEntity.kt`
- Create: `app/src/main/java/cn/soul2/imageai/data/db/entity/SearchTermAliasEntity.kt`
- Create: `app/src/main/java/cn/soul2/imageai/data/db/entity/SearchSourceChunkEntity.kt`
- Create: `app/src/main/java/cn/soul2/imageai/data/db/entity/SearchTextAliasChunkEntity.kt`
- Create: `app/src/main/java/cn/soul2/imageai/data/db/entity/SearchGramEntity.kt`
- Create: `app/src/main/java/cn/soul2/imageai/data/db/dao/SearchIndexDao.kt`
- Modify: `app/src/main/java/cn/soul2/imageai/data/db/AppDatabase.kt`
- Modify: `app/src/main/java/cn/soul2/imageai/data/db/AppDatabaseMigrations.kt`
- Modify: `app/src/main/java/cn/soul2/imageai/data/db/AppDatabaseFactory.kt`
- Modify: `app/src/main/java/cn/soul2/imageai/data/db/RoomConverters.kt`
- Modify: `app/src/test/java/cn/soul2/imageai/data/db/MediaSchemaContractTest.kt`
- Modify: `app/src/androidTest/java/cn/soul2/imageai/data/db/AppDatabaseMigrationTest.kt`
- Create: `app/src/androidTest/java/cn/soul2/imageai/data/db/SearchForeignKeyTest.kt`

**Interfaces:**
- Produces Room schema version 4 and `MIGRATION_3_4`.
- Produces internal `SearchIndexDao` transaction primitives and bounded candidate queries.

**Schema:**
- `search_document(rowid=image_local_id PK/FK CASCADE, file_name, album, caption, tags, categories, search_tokens, media_text)`.
- `search_document_fts` is `@Fts4(contentEntity = SearchDocumentEntity::class)` and indexes every text column except rowid.
- `search_term(term_id INTEGER PK AUTOINCREMENT, normalized_key UNIQUE, display_value, unit_type)`.
- `image_search_term(image_local_id, term_id, field_mask, ownership, weight, PRIMARY KEY(image_local_id,term_id,field_mask), both FKs CASCADE)`.
- `search_term_alias(alias_id INTEGER PK AUTOINCREMENT, term_id FK CASCADE, alias_type, alias_text, UNIQUE(term_id,alias_type,alias_text))`.
- `search_source_chunk(chunk_id INTEGER PK AUTOINCREMENT, image_local_id FK CASCADE, field, ordinal, normalized_text, UNIQUE(image_local_id,field,ordinal))`.
- `search_text_alias_chunk(alias_chunk_id INTEGER PK AUTOINCREMENT, image_local_id FK CASCADE, field, alias_type, ordinal, alias_text, UNIQUE(image_local_id,field,alias_type,ordinal))`.
- `search_gram(gram, owner_type, owner_id, PRIMARY KEY(gram,owner_type,owner_id))`; owner types are `TERM`, `TERM_ALIAS`, `SOURCE_CHUNK`, and `TEXT_ALIAS_CHUNK`. DAO deletion is owner-aware because SQLite cannot express a polymorphic FK.

- [x] **Step 1: Change schema contract tests to require exactly version 4 and all eight search tables plus FTS shadow tables.** Continue explicitly rejecting FTS5.

- [x] **Step 2: Run schema tests and verify RED.**

Run: `./gradlew testDebugUnitTest --tests "cn.soul2.imageai.data.db.MediaSchemaContractTest"`

Expected: failure because latest exported schema is 3.

- [x] **Step 3: Implement Room entities and converters.** Store enums as stable uppercase text. Add indexes for `(normalized_key)`, `(image_local_id,field_mask,weight)`, `(term_id,alias_type)`, `(image_local_id,field,ordinal)`, `(image_local_id,field,alias_type,ordinal)`, and `(gram,owner_type)`.

- [x] **Step 4: Raise AppDatabase to version 4 and export `app/schemas/cn.soul2.imageai.data.db.AppDatabase/4.json`.** Add `searchIndexDao()` as an internal accessor covered by the raw-DAO architecture contract.

- [x] **Step 5: Write failing `3 -> 4` and direct `1 -> 4` migration tests.** Seed available/unavailable media, canonical active/inactive analyses, corrections, diagnostics, sync checkpoint, and sync run. Verify every existing row survives.

- [x] **Step 6: Implement `MIGRATION_3_4` with SQL byte-for-byte equivalent to Room clean creation.** Create the content table before FTS4 and use the exact Room-generated FTS4 trigger definitions copied from exported schema 4. Register `1 -> 2 -> 3 -> 4`; do not use destructive fallback.

- [x] **Step 7: Add clean/migrated FK tests.** Purging an image removes its document, terms mappings, source chunks, aliases, and grams. Removing one image does not remove a shared lexical term still mapped by another image. Orphan polymorphic grams are removed by the writer transaction and maintenance assertion.

- [x] **Step 8: Run schema, migration compilation, and unit tests; commit.**

Run: `./gradlew testDebugUnitTest compileDebugAndroidTestKotlin`

Expected: success and exported schema 4 present.

Commit: `feat: add Room v4 search schema`

---

### Task 3: Bounded Search Projection Planner and Atomic Writer

**Files:**
- Create: `app/src/main/java/cn/soul2/imageai/search/SearchField.kt`
- Create: `app/src/main/java/cn/soul2/imageai/search/SearchProjectionPlan.kt`
- Create: `app/src/main/java/cn/soul2/imageai/search/SearchProjectionPlanner.kt`
- Create: `app/src/main/java/cn/soul2/imageai/search/RoomSearchProjectionWriter.kt`
- Modify: `app/src/main/java/cn/soul2/imageai/analysis/SearchProjectionWriter.kt`
- Modify: `app/src/main/java/cn/soul2/imageai/analysis/CanonicalMetadataRepository.kt`
- Modify: `app/src/main/java/cn/soul2/imageai/AppContainer.kt`
- Test: `app/src/test/java/cn/soul2/imageai/search/SearchProjectionPlannerTest.kt`
- Test: `app/src/test/java/cn/soul2/imageai/search/RoomSearchProjectionWriterTest.kt`

**Interfaces:**
- Consumes: `EffectiveProjectionSnapshot` plus the transaction-current `ImageEntity`.
- Produces: `SearchProjectionPlan(document, terms, mappings, aliases, sourceChunks, textAliasChunks, grams, relationshipCount)`.
- Changes `SearchProjectionWriter` to a two-step transaction-local contract:

```kotlin
sealed interface SearchProjectionPreparation {
    interface Ready : SearchProjectionPreparation
    data class Blocked(val code: String, val detail: String) : SearchProjectionPreparation
}

interface SearchProjectionWriter {
    fun prepareForImage(
        imageLocalId: Long,
        snapshot: EffectiveProjectionSnapshot,
    ): SearchProjectionPreparation

    fun replaceForImage(preparation: SearchProjectionPreparation.Ready)
}
```

`RoomSearchProjectionWriter.Prepared(plan: SearchProjectionPlan)` is the opaque immutable `Ready` implementation. `NoOp` prepares a singleton ready token and applies no writes.

- [x] **Step 1: Write projection truth-table tests.** Cover MediaStore filename/album/dimensions/size/time, effective caption, tags, categories, active-only search tokens, user ownership/weight, tombstone suppression, and historical-token exclusion.

- [x] **Step 2: Write complete-field boundary tests.** Use a 4 KiB UTF-8 caption whose source substring, full-pinyin query, and initials query each cross every 512/127 boundary. Assert all suffixes remain indexed and no alias is truncated.

- [x] **Step 3: Write relationship preflight tests.** Exactly 768 image-term/source-chunk/text-alias-chunk relationships is accepted. 769 returns `SearchProjectionPreparation.Blocked(code = "SEARCH_INDEX_LIMIT")` before DAO mutation. Duplicate mappings and chunks do not consume duplicate relationships; shared aliases and grams remain separately deduplicated for storage.

- [x] **Step 4: Run planner tests and verify RED.**

Run: `./gradlew testDebugUnitTest --tests "cn.soul2.imageai.search.SearchProjectionPlannerTest"`

Expected: compilation failure because planner types do not exist.

- [x] **Step 5: Implement the pure planner.** Field masks are fixed bits: filename `1`, album `2`, caption `4`, tag `8`, category `16`, search token `32`, media text `64`. Ownership is `MEDIA`, `AI`, or `USER`; weight order is user `600`, exact structured `500`, filename/album `450`, caption `300`, token `350`.

- [x] **Step 6: Write writer replacement/rollback tests.** Replacement deletes every old image-owned mapping/chunk/alias-chunk/gram, upserts shared terms/aliases, inserts the new document, and removes unreferenced lexical rows. Injected failure rolls back document, FTS, and all fuzzy tables.

- [x] **Step 7: Implement `RoomSearchProjectionWriter`.** `prepareForImage` builds the complete immutable plan and performs the 768 preflight without DAO mutation. `replaceForImage` accepts only that writer's `Prepared` token and performs replacement through the database-bound DAO created by `AppContainer`; neither method may call `withTransaction`, launch a coroutine, perform file/network I/O, or retain mutable plan state after return.

- [x] **Step 8: Extend canonical activation blocking.** Inside the canonical Room transaction, build the effective snapshot and call `prepareForImage` before changing the active pointer or deleting effective rows. `Blocked` stores an inactive `SEARCH_INDEX_LIMIT` diagnostic and leaves active pointer, effective projection, generation, and old search index unchanged. For `Ready`, replace pointer/effective rows and then call `replaceForImage` before commit. Corrections that would exceed the cap fail locally and preserve the current transaction state.

- [x] **Step 9: Replace the no-op writer in AppContainer and run canonical/index integration tests.**

Run: `./gradlew testDebugUnitTest --tests "cn.soul2.imageai.analysis.*" --tests "cn.soul2.imageai.search.*"`

Expected: active analysis, effective projection, document, FTS4, terms, grams, and pinyin aliases always share one generation.

- [x] **Step 10: Commit.**

Commit: `feat: index canonical projections atomically`

---

### Task 4: Progressive Search Repository, Ranking, Caps, and Cancellation

**Files:**
- Create: `app/src/main/java/cn/soul2/imageai/search/ImageSearchRepository.kt`
- Create: `app/src/main/java/cn/soul2/imageai/search/RoomImageSearchRepository.kt`
- Create: `app/src/main/java/cn/soul2/imageai/search/SearchQuery.kt`
- Create: `app/src/main/java/cn/soul2/imageai/search/SearchResult.kt`
- Create: `app/src/main/java/cn/soul2/imageai/search/SearchStage.kt`
- Create: `app/src/main/java/cn/soul2/imageai/search/SearchRanker.kt`
- Create: `app/src/main/java/cn/soul2/imageai/search/SearchCancellation.kt`
- Modify: `app/src/main/java/cn/soul2/imageai/data/db/dao/SearchIndexDao.kt`
- Test: `app/src/test/java/cn/soul2/imageai/search/SearchRankerTest.kt`
- Test: `app/src/test/java/cn/soul2/imageai/search/RoomImageSearchRepositoryTest.kt`

**Interfaces:**

```kotlin
fun interface ImageSearchRepository {
    fun search(request: SearchRequest): Flow<SearchProgress>
}

data class SearchRequest(val rawQuery: String, val generation: Long, val pageSize: Int = 40)
data class SearchProgress(
    val generation: Long,
    val items: List<SearchResult>,
    val completedStages: Set<SearchStage>,
    val isRefining: Boolean,
    val partialReasons: Set<SearchPartialReason>,
)
```

- [ ] **Step 1: Write ranking tests.** Lock tier order `USER`, `EXACT_STRUCTURED`, `FTS4`, `SUBSTRING`, `TYPO`, `PINYIN`; stable tie-break is field weight descending, match score descending, image sort time descending, MediaStore ID descending, local ID descending.

- [ ] **Step 2: Write repository corpus tests.** Cover exact, FTS prefix, CJK bigram substring, Latin trigram substring, typo distances, full pinyin, initials, polyphonic lexical alias, long-caption boundary aliases, one-character CJK rules, one/two-character Latin rules, empty results, historical-token exclusion, and tombstone restore.

- [ ] **Step 3: Write cap and timeout tests with an injected monotonic clock.** Assert 512/64/5000 caps emit the matching partial reason; staged deadlines emit `STRUCTURED_TIMEOUT`, `SUBSTRING_TIMEOUT`, or `FUZZY_TIMEOUT` without an unrestricted fallback scan.

- [ ] **Step 4: Write replacement-generation cancellation test.** A request with generation N must stop after N+1 starts. Check cancellation after every DAO page and every 256 verification candidates.

- [ ] **Step 5: Run repository tests and verify RED.**

Run: `./gradlew testDebugUnitTest --tests "cn.soul2.imageai.search.RoomImageSearchRepositoryTest"`

Expected: compilation failure because repository types do not exist.

- [ ] **Step 6: Implement structured and FTS4 stages.** Escape FTS syntax through an allowlisted tokenizer; never concatenate raw user text into SQL. Query exact terms and FTS MATCH separately, page IDs in batches of 200, and hydrate only the current top 40 plus bounded prefetch candidates.

- [ ] **Step 7: Implement substring, typo, and pinyin stages.** Rank gram candidates in SQL by shared gram count, verify actual containment/edit distance in Kotlin, stop at caps, and attach field-specific hit reasons. Long-text alias chunks map directly to image candidates; lexical aliases join through their terms.

- [ ] **Step 8: Implement progressive Flow.** Publish structured/FTS immediately, coalesce lower-tier updates to at most once every 200 ms, preserve higher-tier positions, mark `isRefining`, and use `withTimeoutOrNull` only around individual stages plus an outer 5-second deadline.

- [ ] **Step 9: Add degradation tests.** Missing/corrupt fuzzy tables retain exact and healthy FTS stages with `INDEX_DEGRADED`; an FTS failure retains exact structured results and exposes `REBUILD_REQUIRED`.

- [ ] **Step 10: Run Task 4 tests and commit.**

Run: `./gradlew testDebugUnitTest --tests "cn.soul2.imageai.search.*"`

Expected: all search unit/integration tests pass.

Commit: `feat: add progressive local image search`

---

### Task 5: Chinese Search Surface for the Test APK

**Files:**
- Create: `app/src/main/java/cn/soul2/imageai/ui/search/SearchViewModel.kt`
- Create: `app/src/main/java/cn/soul2/imageai/ui/search/SearchScreen.kt`
- Create: `app/src/main/java/cn/soul2/imageai/ui/search/SearchResultGrid.kt`
- Modify: `app/src/main/java/cn/soul2/imageai/ui/screens/HomeScreen.kt`
- Modify: `app/src/main/java/cn/soul2/imageai/ui/app/SoImageManagerApp.kt`
- Modify: `app/src/main/java/cn/soul2/imageai/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/cn/soul2/imageai/AppContainer.kt`
- Test: `app/src/test/java/cn/soul2/imageai/ui/search/SearchViewModelTest.kt`
- Test: `app/src/androidTest/java/cn/soul2/imageai/ui/search/SearchScreenTest.kt`

**Interfaces:**
- Consumes: `ImageSearchRepository.search(SearchRequest)` and `GalleryRepository.observeImage` hydration.
- Produces: a top-app-bar search icon, focused Chinese search field, stable 3-column result grid, progressive indicator, hit reasons, partial-result message, clear/back controls, and image-detail navigation.

- [ ] **Step 1: Write ViewModel debounce/cancellation tests.** Empty text shows recent-search idle state, non-empty text waits 250 ms, each edit increments generation, obsolete emissions are discarded, clear cancels the query, and process recreation restores the query through `SavedStateHandle`.

- [ ] **Step 2: Implement SearchViewModel.** Do not expose raw DAO entities. UI state contains query, results, refining flag, partial reasons, empty state, and fatal/rebuild action only.

- [ ] **Step 3: Write Compose tests.** Verify all visible strings are Chinese, IME search action, clear icon semantics, refining progress does not resize the grid, empty state, partial reason, result click, and back navigation. Ensure text and icons do not overlap at 360x640 and 1280x800 dp test constraints.

- [ ] **Step 4: Implement the search UI.** Home top bar uses a search icon with content description `搜索图片`; opening it navigates to `search`. The result grid reuses `GalleryImageTile`, uses three phone columns/five large-screen columns, and never displays an inactive search box before the repository exists.

- [ ] **Step 5: Wire AppContainer and navigation.** Construct `RoomImageSearchRepository` from the private database and expose only `ImageSearchRepository`. Pass it through `MainActivity` to `SoImageManagerApp`.

- [ ] **Step 6: Run ViewModel and Compose compilation tests; commit.**

Run: `./gradlew testDebugUnitTest compileDebugAndroidTestKotlin`

Expected: success.

Commit: `feat: add Chinese image search surface`

---

### Task 6: Deterministic 10k/50k/100k Fixture and Benchmark Gate

**Files:**
- Create: `app/src/androidTest/java/cn/soul2/imageai/search/SearchFixtureGenerator.kt`
- Create: `app/src/androidTest/java/cn/soul2/imageai/search/SearchCorrectnessCorpus.kt`
- Create: `app/src/androidTest/java/cn/soul2/imageai/search/SearchScaleBenchmarkTest.kt`
- Create: `app/src/androidTest/assets/search-query-corpus-v1.json`
- Create: `docs/benchmarks/phase-3-search-template.md`

**Interfaces:**
- Produces deterministic fixture seed `0x534F494D00000003L` and subsets of 10,000, 50,000, and 100,000 rows.
- Produces machine-readable metrics and a Markdown summary containing commit, device, Android build, cold/warm mode, database bytes, table/index page stats, P50/P95, heap, PSS, and cancellation latency.

- [ ] **Step 1: Write generator distribution tests.** Assert exact analyzed/long-caption/CJK/polyphonic/correction/history percentages and field count ranges. Assert identical seed produces byte-identical canonical inputs and corpus expectations.

- [ ] **Step 2: Implement the generator and at least 200 correctness queries.** Include every class required by the frozen design, especially active/historical tokens, tombstones, high-frequency grams, short queries, empty results, and every long-pinyin chunk boundary.

- [ ] **Step 3: Build indexes in bounded transactions.** Commit every 500 images, checkpoint progress, report rows/second, and verify restart resumes without duplicate mappings or grams.

- [ ] **Step 4: Measure physical storage.** Use `dbstat` when available; otherwise use page count before/after each data class plus index inventory. Fail if FTS exceeds 1.5 KiB/indexed image, fuzzy relations exceed 4.5 KiB/indexed image, or total live data exceeds 10 KiB/image.

- [ ] **Step 5: Measure correctness, latency, memory, and cancellation.** Run one warm-up corpus, then at least 30 measured samples per class. Fail reference-device gates above 500 ms/1 s structured P50/P95, 3 s/5 s full P50/P95, 200 ms cancellation P95, 96 MB incremental heap, or 300 MB process PSS.

- [ ] **Step 6: Run 10k on API 29 emulator and compile 50k/100k gates.** Timing claims are not taken from the emulator.

- [ ] **Step 7: Run 100k on a Pixel 4a-class Android 13 physical device.** If any correctness, storage, latency, or memory gate fails, stop Phase 3 and reopen schema/index design before provider traffic.

- [ ] **Step 8: Commit benchmark code and measured artifact.**

Commit: `test: add Phase 3 search feasibility gate`

---

### Task 7: Publisher Gate, Recovery, and Phase 3 Development APK

**Files:**
- Modify: `scripts/publish-test-apk.ps1`
- Modify: `app/src/test/java/cn/soul2/imageai/architecture/PublishContractTest.kt`
- Modify: `docs/superpowers/plans/2026-07-15-phase-3-search-feasibility.md`

- [ ] **Step 1: Update publisher contract tests first.** Require Room schema 4, FTS4/search tables, absence of FTS5/provider/HTTP classes, successful benchmark artifact for the current commit, and unchanged semantic-version publisher behavior.

- [ ] **Step 2: Update `Test-LatestRoomSchema`.** Validate all Phase 2/3 tables and FTS4 shadow/trigger presence. Reject `fts5`, missing relationship indexes, and a benchmark artifact whose commit differs from HEAD.

- [ ] **Step 3: Run the complete gate.**

Run: `./gradlew clean testDebugUnitTest testReleaseUnitTest lintDebug compileDebugAndroidTestKotlin assembleDebug assembleRelease`

Expected: zero test failures, zero lint errors, Debug signed, Release unsigned.

- [ ] **Step 4: Run connected smoke tests on API 29 and API 33+ devices.** Verify migration, initial index build, Chinese exact/substring/typo/full-pinyin/initial search, cancellation, detail navigation, process restart, and gallery deletion cleanup.

- [ ] **Step 5: Independent review and development checkpoint.** Review schema/migration parity, active-only index transaction, alias completeness, caps, cancellation, benchmark validity, and user-visible Chinese UI. Fix all Critical/Important findings.

- [ ] **Step 6: Commit and push Phase 3.**

Commit: `feat: complete Phase 3 local search feasibility`

- [ ] **Step 7: Do not publish the user test APK yet.** Phase 4 must add provider/model/custom protocol configuration and the one-image analysis path before advancing to `0.5.0`. Phase 3 may produce an internal installable development APK only.

## Review Checkpoints

1. Freeze Room v4 only after clean-create schema, `MIGRATION_3_4`, direct `1 -> 4`, and fresh/migrated FK behavior agree.
2. Review source/full-pinyin/initial chunk boundary truth tables before the writer mutates Room.
3. Review active-only token replacement, tombstone restoration, rollback, and 768-relationship blocking before repository work.
4. Review query caps, stable tiers, timeout/degradation, and cancellation before adding Compose search.
5. Phase 3 passes only after the 100k correctness/storage/latency/memory artifact is produced on the reference physical device; compilation alone is not acceptance.
