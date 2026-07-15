# SoIM Phase 2B Canonical Storage Implementation Plan

> Status: implementation checkpoint; JVM gates pass, connected migration and 15k fixture execution pending

**Goal:** Extend the existing Room v2 gallery index to Room v3 with immutable canonical AI analyses, durable user corrections, and one transactionally materialized effective projection per changed image. This phase stores the exact active-only inputs that Phase 3 search will index. It does not add provider HTTP calls, FTS4, ngram/pinyin tables, batch jobs, or search UI.

**Release target:** `0.5.0` starts only after this phase and the Phase 3 search feasibility gate are complete. Ordinary development builds do not change the published `0.4.1` version.

## Non-Negotiable Semantics

- Only the current active successful analysis contributes AI caption, tags, categories, or `searchTokens`. Historical, failed, inactive, and future `INDEX_BLOCKED` analyses never enter the effective projection.
- Caption correction is tri-state: `INHERIT`, `SET(value)`, or `CLEARED`. `SET` and `CLEARED` survive every AI rerun until the user explicitly returns to `INHERIT`.
- Tag and category corrections are normalized additions or tombstones. A tombstone survives reruns and suppresses the same normalized AI value.
- A tag or category tombstone also suppresses an active-analysis `searchToken` with the same normalized key. Removing the tombstone restores the still-active matching token.
- Activating an analysis or changing a correction replaces the active pointer, effective caption, effective terms, and Phase 3 search projection in one Room transaction. Observers never see mixed generations.
- An analysis is immutable after insertion. Reprocessing creates a new analysis ID. Idempotent replay of the same ID and content is accepted; the same ID with different content is rejected.
- MediaStore file name, album, time, dimensions, favorite state, and availability remain authoritative and are not copied into AI correction rows.

## Task 1: Domain Validation and Normalization

**Create:**

- `app/src/main/java/cn/soul2/imageai/analysis/CanonicalAnalysisDraft.kt`
- `app/src/main/java/cn/soul2/imageai/analysis/CanonicalLimits.kt`
- `app/src/main/java/cn/soul2/imageai/analysis/MetadataNormalizer.kt`
- `app/src/test/java/cn/soul2/imageai/analysis/CanonicalAnalysisDraftTest.kt`
- `app/src/test/java/cn/soul2/imageai/analysis/MetadataNormalizerTest.kt`

**Steps:**

1. Write failing tests for NFKC normalization, `Locale.ROOT` case folding, trim and internal-whitespace collapse, empty-value rejection, stable deduplication, and equivalent Chinese/Latin keys.
2. Define a validated draft containing analysis ID, image ID, schema version, caption, tags, categories, `searchTokens`, extension JSON, model/provider/protocol/prompt provenance IDs, and timestamps.
3. Enforce the frozen hard limits before opening a transaction: caption 4 KB UTF-8; 128 raw tags; 32 raw categories; 256 raw tokens; 128 code points per term; extension JSON defaults to a 16 KB persistence budget and can be explicitly raised only up to the 64 KB hard ceiling. Extension JSON is limited to depth 32 and 50,000 value nodes; raw nesting is checked before recursive parsing, the parsed tree is counted iteratively, and the canonicalized output is checked against the persistence budget again.
4. Reject invalid UTF-16, blank normalized terms, malformed extension JSON, negative timestamps, and provenance identifiers outside bounded lengths. Normalized duplicates merge deterministically: retain the first display value and ordinal and retain the maximum valid confidence.
5. Produce normalized immutable term records once. DAOs and UI must not implement independent normalization rules. The repository computes SHA-256 internally over the canonical persisted encoding of every immutable field except the hash itself and analysis ID; JSON object keys are recursively sorted. Reordering set-like inputs cannot change the hash, while ordinal-bearing presentation fields remain explicit inputs where order is retained.

## Task 2: Room v3 Canonical Schema

**Create:**

- `app/src/main/java/cn/soul2/imageai/data/db/entity/ImageAnalysisEntity.kt`
- `app/src/main/java/cn/soul2/imageai/data/db/entity/AnalysisTermEntity.kt`
- `app/src/main/java/cn/soul2/imageai/data/db/entity/ActiveImageAnalysisEntity.kt`
- `app/src/main/java/cn/soul2/imageai/data/db/entity/ImageUserCorrectionEntity.kt`
- `app/src/main/java/cn/soul2/imageai/data/db/entity/UserTermOverrideEntity.kt`
- `app/src/main/java/cn/soul2/imageai/data/db/entity/EffectiveImageMetadataEntity.kt`
- `app/src/main/java/cn/soul2/imageai/data/db/entity/EffectiveImageTermEntity.kt`
- `app/src/main/java/cn/soul2/imageai/data/db/entity/AnalysisActivationDiagnosticEntity.kt`
- `app/src/main/java/cn/soul2/imageai/data/db/dao/AnalysisDao.kt`
- `app/src/main/java/cn/soul2/imageai/data/db/dao/EffectiveMetadataDao.kt`

**Modify:**

- `app/src/main/java/cn/soul2/imageai/data/db/AppDatabase.kt`
- `app/src/main/java/cn/soul2/imageai/data/db/RoomConverters.kt`
- `app/src/test/java/cn/soul2/imageai/data/db/MediaSchemaContractTest.kt`
- `app/src/test/java/cn/soul2/imageai/data/db/RoomSchemaContractTest.kt`

**Schema:**

- `image_analysis`: text analysis ID primary key; image FK with cascade; immutable canonical caption and bounded extension JSON; schema version; internally computed content hash; provider/model/protocol/prompt provenance; creation/completion time. Add indexes on `(image_local_id, completed_at_epoch_millis)` and unique `(analysis_id, image_local_id)` for same-image pointer enforcement.
- `analysis_term`: composite primary key `(analysis_id, kind, normalized_key)`; kind is `TAG`, `CATEGORY`, or `SEARCH_TOKEN`; display value and optional confidence; cascade from analysis. Persistence and reads use stable `(kind, normalized_key)` order rather than a model-controlled ordinal.
- `active_image_analysis`: one row per image; deferred `NO ACTION` composite FK `(analysis_id, image_local_id)` to the matching analysis plus image cascade; changing this row is the only activation mechanism. Active analysis history cannot be deleted independently.
- `image_user_correction`: one row per image; caption mode/value and update metadata. Kotlin validation requires a value only for `SET`; DAOs remain internal so invalid combinations cannot bypass the repository.
- `user_term_override`: composite primary key `(image_local_id, kind, normalized_key)`; kind is only `TAG` or `CATEGORY`; action is `ADD` or `TOMBSTONE`; display value and per-item update metadata are present for additions.
- `effective_image_metadata`: one row for each image that has ever committed an analysis/correction projection generation, including a currently empty `NONE` projection; effective caption, caption source, monotonic projection generation, and update time. The active ID remains owned by `active_image_analysis` and is joined rather than duplicated here.
- `effective_image_term`: composite primary key `(image_local_id, kind, normalized_key)`; kind includes tag, category, and search token; source is `AI` or `USER`; display value, confidence, and nullable `source_analysis_id`. AI rows use a deferred same-image composite FK; user rows have a null source analysis.
- `analysis_activation_diagnostic`: one optional mutable row per inactive analysis with a bounded code such as `EFFECTIVE_TAG_LIMIT`, `EFFECTIVE_CATEGORY_LIMIT`, or later `SEARCH_INDEX_LIMIT`. It is not canonical analysis content.

**Steps:**

1. Add schema-contract tests first. Assert all columns, primary keys, foreign keys, delete/deferred behavior, and required indexes; assert that Phase 3 tables (`search_document`, FTS4, term aliases, grams, and pinyin chunks) do not yet exist.
2. Implement entities with stable uppercase enum storage and database foreign keys. Clean-create and migration use the same Room 2.6.1 schema; Kotlin validators and the canonical repository own caption/action/kind invariants. `AppContainer` keeps `AppDatabase` private, and an architecture contract rejects raw canonical DAO use outside `CanonicalMetadataRepository` and the Room declaration. Avoid JSON arrays for canonical terms because projection, tombstone, ownership, and Phase 3 index joins need normalized rows.
3. Raise `AppDatabase` to version 3 and export `app/schemas/.../3.json`.
4. Keep the existing media indexes and DAOs behaviorally unchanged.

## Task 3: Explicit 2 to 3 Migration

**Modify:**

- `app/src/main/java/cn/soul2/imageai/data/db/AppDatabaseMigrations.kt`
- `app/src/main/java/cn/soul2/imageai/data/db/AppDatabaseFactory.kt`
- `app/src/androidTest/java/cn/soul2/imageai/data/db/AppDatabaseMigrationTest.kt`

**Steps:**

1. Write a failing `2 -> 3` instrumentation migration test using exported schema 2. Seed settings, available and unavailable images, and media sync state.
2. Implement `MIGRATION_2_3` with explicit SQL matching Room clean-create for every table, FK, and index. Do not add migration-only schema divergence, do not use destructive fallback, and do not rewrite existing image rows.
3. Register both `MIGRATION_1_2` and `MIGRATION_2_3`. Verify direct `1 -> 3` open applies the full chain.
4. Validate that existing gallery rows and settings survive, duplicate active pointers fail, cross-image analysis pointers fail, cascades remove dependent AI state only when an image is actually purged, and an empty v2 library migrates cleanly.

## Task 4: Effective Projection Transaction

**Create:**

- `app/src/main/java/cn/soul2/imageai/analysis/CanonicalMetadataRepository.kt`
- `app/src/main/java/cn/soul2/imageai/analysis/EffectiveProjectionBuilder.kt`
- `app/src/main/java/cn/soul2/imageai/analysis/SearchProjectionWriter.kt`
- `app/src/test/java/cn/soul2/imageai/analysis/EffectiveProjectionBuilderTest.kt`
- `app/src/test/java/cn/soul2/imageai/analysis/CanonicalMetadataRepositoryTest.kt`

**Transaction contract:**

1. Validate and normalize the complete draft before the transaction.
2. Insert the immutable analysis and canonical terms, or verify an idempotent existing content hash.
3. Load caption correction and all term overrides for the image.
4. Build the effective projection from only this analysis plus current corrections.
5. Replace the active pointer, effective metadata, and all effective term rows.
6. Invoke `SearchProjectionWriter.replaceForImage(...)` before commit. Phase 2B uses a database-bound no-op implementation; Phase 3 replaces it with FTS4/term/ngram/pinyin writes in the same `withTransaction` block. The writer cannot launch coroutines, perform I/O, or open a second database.

If the canonical analysis plus current user additions exceeds 256 effective tags or 64 effective categories, the transaction stores the immutable analysis and an activation diagnostic but does not change the active pointer, effective rows, projection generation, or Phase 3 writer. The user can reduce additions and explicitly retry activation later.

**Required tests:**

- Rerunning AI retains historical analyses but replaces every active-only effective tag, category, and token.
- A failed or merely inserted inactive analysis changes no pointer or projection.
- Caption `SET` and `CLEARED` survive reruns; returning to `INHERIT` immediately exposes the active caption.
- User additions override same-key AI display/source; unrelated additions survive reruns.
- Tombstones suppress future same-key AI terms and matching search tokens. Restoring a tombstone republishes the current matching AI term and token.
- Historical tokens never remain after active analysis changes.
- Injected writer failure rolls back analysis activation, pointer, effective rows, and corrections as one unit.
- Replaying an identical analysis ID is idempotent; ID/content mismatch is a permanent local-data error.
- Projection generation increases once per committed activation/correction and never for a rollback.
- Replaying the already-active identical analysis is a no-op and does not increase projection generation.

## Task 5: Correction Commands and Read API

**Create:**

- `app/src/main/java/cn/soul2/imageai/analysis/CorrectionCommand.kt`
- `app/src/main/java/cn/soul2/imageai/analysis/EffectiveImageMetadata.kt`

**Modify:**

- `app/src/main/java/cn/soul2/imageai/gallery/GalleryRepository.kt`
- `app/src/main/java/cn/soul2/imageai/gallery/RoomGalleryRepository.kt`
- `app/src/test/java/cn/soul2/imageai/gallery/RoomGalleryRepositoryTest.kt`

**Steps:**

1. Add typed commands for caption inherit/set/clear, add/delete/restore tag, and add/delete/restore category. Do not expose raw DAO writes to UI.
2. Resolve delete semantics in the repository: deleting an AI-backed effective term creates a tombstone; deleting a user-only addition removes the addition; deleting a user override that masks AI creates the tombstone needed to keep it deleted.
3. Enforce 256 effective tags, 64 effective categories, 512 tag tombstones, and 128 category tombstones before writes. Never discard old tombstones silently.
4. Expose an observable detail projection containing active analysis provenance, effective caption/terms, ownership, correction state, and analysis history summary. Keep MediaStore metadata in the existing gallery model.
5. Add read tests proving inactive analyses and suppressed tokens are not returned as effective metadata.

Every correction command runs in one `AppDatabase.withTransaction`: reload active canonical rows and current overrides; apply the command; enforce all set and tombstone limits against transaction-current state; replace correction, effective metadata, and effective terms; call the same `SearchProjectionWriter`; and increment generation only on commit. String shape validation may happen before the transaction, but no count or merge decision may rely on stale state outside it. An image with corrections but no active analysis still materializes user caption/additions. Returning the last field to inherit/removing the last addition retains an empty `NONE` metadata row so the committed projection generation remains strictly monotonic.

## Task 6: Analysis Retention

**Create:**

- `app/src/main/java/cn/soul2/imageai/analysis/AnalysisRetentionPolicy.kt`
- `app/src/test/java/cn/soul2/imageai/analysis/AnalysisRetentionPolicyTest.kt`

**Steps:**

1. Retain the active analysis plus at most two inactive analyses of any activation outcome per image by default. A diagnostic makes an inactive analysis preferred for retention but does not exempt it from the per-image or global budget; when only diagnostics remain, purge the oldest superseded diagnostic and its analysis together. Never delete the active pointer target. A future export lease must use a separate explicit hard-pin mechanism rather than overloading diagnostics.
2. Enforce the frozen 256 MB global historical-analysis budget using a conservative estimate that includes repeated primary/index keys, term row/index overhead, diagnostic payload, and page-level margin. The 15k fixture compares this estimate with measured page growth after sampled reruns; the estimate must not be below physical history growth. Active analyses and effective projection are not counted as disposable history.
3. Run cleanup after both successful activation and blocked activation, at application startup, and from the 24-hour reconciliation worker. Cleanup failure does not roll back the completed activation attempt or fail gallery reconciliation; it emits a bounded warning and waits for the next startup/periodic maintenance attempt.
4. Add fixture assertions for history count, global budget, stable active pointer, and no orphaned canonical terms.

## Task 7: Publisher and Phase Gate

**Modify:**

- `scripts/publish-test-apk.ps1`
- `app/src/test/java/cn/soul2/imageai/architecture/PublishContractTest.kt`
- `app/src/test/java/cn/soul2/imageai/data/db/MediaSchemaContractTest.kt`
- `app/src/androidTest/java/cn/soul2/imageai/data/db/AppDatabaseTest.kt`

**Steps:**

1. Replace the publisher's hard-coded Room v2/four-table gate with the exact Room v3 Phase 2B table set. Continue rejecting FTS5 and all Phase 3 search tables in this phase; canonical analysis tables are now required rather than forbidden.
2. Run debug and release unit suites, lint, AndroidTest compilation, Room migration tests, and database FK/transaction tests.
3. Add a 15,000-image synthetic fixture with deterministic 4-24 tag, 1-4 category, 4-32 token, short/long caption, and correction distributions. Seed the bulk fixture transactionally, then use the canonical repository for sampled rerun, correction, and detail-read measurements. Measure the media-only baseline separately: active canonical delta must stay at or below 2.5 KiB/image and total Phase 2B media plus canonical storage at or below 4 KiB/image. Report elapsed times, physical page bytes, bytes per image, and retention estimate versus measured history growth. This is not the Phase 3 100,000-search benchmark.
4. Phase 2B passes only when active-only `searchTokens`, normalized tombstone suppression, and rollback of the complete projection transaction are proven. Phase 3 must not start before this gate is green.

## Explicitly Deferred to Phase 3+

- Phase 3: `@Fts4` search document, lexical term dictionary, image-term mappings, ngram tables, lexical pinyin aliases, complete-field full-pinyin/initial chunks, search repository, cancellation, deterministic 10k/50k/100k fixtures, and the mandatory storage/latency/memory gate.
- Phase 4: provider/model/protocol/prompt profile tables, Keystore credential envelopes, HTTP transport, redirect policy, debugger, durable one-image job, and real model traffic.
- Phase 5: batch coordinator, hierarchical concurrency/rate/budget leases, fallback, API 35/36 scheduler pauses, notifications, and restart recovery.
- Phase 6: production search UI and correction editor.

## Review Checkpoints

1. Review schema and migration SQL before creating production entities.
2. Review normalization and projection truth tables before repository writes.
3. Review transaction rollback and active-only token tests before Phase 2B sign-off.
4. Freeze the v3 schema only after the exported schema, migration test, publisher gate, and Phase 2B acceptance suite all agree.
