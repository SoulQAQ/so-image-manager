# So Image Manager Core Rebuild Design

**Date:** 2026-07-10
**Status:** Approved design, pending written-spec review
**Branch:** `codex/rebuild-core`

## 1. Product Definition

So Image Manager is a local-first Android application for finding and organizing device images with user-selected AI models. Its primary job is not to replace the system gallery. It builds a durable local index over the system gallery, enriches that index with structured AI metadata, and makes the result searchable.

The first release targets users with 9,000 to 15,000 images. The architecture must remain usable as the library grows to 50,000 and ultimately 100,000 images.

The product principles are:

- Original images remain in MediaStore and are never copied, moved, renamed, or deleted by the app.
- Room is the single source of truth for indexed metadata, processing state, user corrections, themes, and layout configuration.
- AI credentials belong to the user. The app is BYOK and calls configured services directly.
- Core browsing and local search remain usable without network access.
- Model-generated data is versioned and never silently overwrites user corrections.
- Every visible feature must be backed by an end-to-end tested data flow. Creating classes or screens is not completion.

## 2. Confirmed Scope

### 2.1 First-Release Capabilities

- Full-gallery authorization and MediaStore indexing.
- Initial and incremental gallery synchronization.
- Pure Jetpack Compose interface with no product dependency on H5 or WebView.
- Search-first home screen with configurable content modules.
- Persistent AI processing queue with pause, resume, retry, and restart recovery.
- Structured image metadata containing caption, tags, categories, and search tokens.
- Full-field local search, including substring, typo-tolerant, full-pinyin, and pinyin-initial matching.
- Optional model-assisted conversion of natural-language queries into safe structured filters.
- User corrections that survive model reruns.
- AI-recommended themes and user-created dynamic themes.
- Built-in model protocol presets and a declarative custom HTTP JSON protocol.
- Per-task model routing with fallback chains.
- Detailed concurrency, rate, retry, budget, and circuit-breaker controls.
- Versioned export and restore of indexed data and configuration without copying original images.

### 2.2 Deferred Capabilities

- OCR is a later feature direction and is not a first-release task or storage requirement.
- Embedding-based semantic search.
- Reverse image search and visually similar image discovery.
- Face recognition and person clustering.
- Duplicate and near-duplicate cleanup.
- Cloud synchronization, accounts, billing, and an official model proxy.
- Modification or deletion of original images.

The protocol result schema may preserve unknown extension fields for forward compatibility, but first-release UI and processing do not depend on OCR or any deferred field.

## 3. Restart Strategy

Rebuild the core inside the existing Android repository.

Retain only assets and code that can be verified independently, such as Android project configuration, selected Compose theme resources, image preprocessing concepts, and secure `WebViewAssetLoader` history where it remains useful as reference. The existing H5 product path, in-memory gallery state, synchronous JS bridge, disconnected Room services, and non-consuming queue are not architectural foundations for the rebuild.

Existing demo data does not require migration. Database evolution begins from a new exported Room schema and uses explicit migrations after the new baseline ships.

The rebuild raises the minimum supported version to Android 10 (`minSdk 29`). API 26 is Android 8.0 and is no longer a target for the rebuilt product. The remaining baseline is targetSdk 36, compileSdk 36, Java 17, Kotlin 2.0.21, and Jetpack Compose with Material 3.

Before distributing any rebuilt APK:

- Revoke the API key previously committed to the repository.
- Remove all build-time injection of a shared long-lived API key.
- Ensure release builds do not use the debug keystore.
- Apply the explicit network transport policy below to every provider request and redirect.
- Prevent secrets and full request bodies from appearing in release logs.

### 3.1 Network Transport Policy

Dynamic model profiles must support user-operated LAN gateways that may only expose HTTP. Android Network Security Config cannot add arbitrary hosts at runtime, so the release manifest permits cleartext at the platform layer while the dedicated model HTTP client rejects it by default at the application layer.

An HTTP provider profile is usable only after the user enables cleartext for that exact scheme, host, and port and accepts a persistent warning. The approval is stored on the profile and is not inherited by copied or redirected profiles. HTTPS remains the default for every preset.

Automatic redirects are disabled. The model client may manually follow at most three redirects under these rules:

- HTTPS-to-HTTP downgrade is always rejected.
- A same-scheme, same-host, same-port redirect may retain authentication.
- A host or port change requires an explicit destination allowlist on the provider profile.
- Authorization, API-key, cookie, and user-declared secret headers are stripped on every cross-origin redirect and are never automatically reattached.
- The final resolved URL is validated again before any body or credential is sent.

All model traffic uses this dedicated client. Other application networking does not gain an API for bypassing the profile policy merely because the manifest technically permits cleartext.

## 4. Information Architecture

The bottom navigation has four stable destinations:

1. **Home** - fixed search entry, index status, and configurable image modules.
2. **Library** - browsing by time, system album, tag, category, and processing state.
3. **Tasks** - synchronization, analysis queues, retries, cooldowns, and failure details.
4. **Settings** - providers, protocols, routing, processing policies, privacy, storage, backup, and appearance.

Search results and image details are pushed destinations and do not occupy bottom-navigation slots.

### 4.1 Home Modules

The search control and gallery-index status are fixed. Content below them is modular.

A module can source images from:

- Recent images.
- A system album.
- Favorites.
- A user-created or AI-recommended theme.
- A saved search.
- A processing state such as unprocessed or failed.

Each module independently stores:

- Source and source identifier.
- Sort field and direction.
- Time range and optional filters.
- Layout style and grid density.
- Result limit.
- Display order and visibility.

When exactly one image module is visible, it expands into the remaining screen and uses a continuous waterfall-style layout. With two or more modules, Home uses ordered sections. Users can add, hide, configure, and reorder modules. The initial default is a single recent-images module.

## 5. System Architecture

```text
Compose UI
    |
ViewModel / Use Case
    |
Repository
    |
+---+----------------+----------------+----------------+
| Room Database      | MediaStore     | WorkManager    |
| Model Engine       | Search Engine  | Backup Engine  |
+--------------------+----------------+----------------+
```

### 5.1 Boundaries

- **Media Library** owns MediaStore observation, permission state, initial synchronization, incremental synchronization, and missing-item reconciliation.
- **Database** owns persistent entities, transactions, migrations, and observable query interfaces.
- **Processing** owns durable jobs, attempts, scheduling, rate limits, pause/resume, and lifecycle recovery.
- **Model Engine** owns provider profiles, protocol rendering, transport, response mapping, output validation, task routes, and fallback selection.
- **Search Engine** owns query normalization, structured filtering, full-text retrieval, fuzzy retrieval, result merging, and hit explanations.
- **Theme Engine** owns safe dynamic query definitions and AI recommendations.
- **Backup Engine** owns versioned export, validation, restore, and MediaStore reassociation.
- **UI Features** consume use-case interfaces and do not directly construct database, MediaStore, HTTP, or worker objects.

## 6. Image Identity and Synchronization

Device identity is based on MediaStore volume plus MediaStore ID, not a file path or a UI list index.

### 6.1 Permission Matrix

| Android version | API | Gallery permission and behavior |
|---|---:|---|
| Android 10-12L | 29-32 | Request `READ_EXTERNAL_STORAGE`; query MediaStore through scoped-storage-safe content APIs and never depend on `DATA` paths. |
| Android 13 | 33 | Request `READ_MEDIA_IMAGES`; denied permission leaves the app usable for configuration and previously retained metadata, but no inaccessible image is displayed. |
| Android 14+ | 34-36 | Request `READ_MEDIA_IMAGES` with `READ_MEDIA_VISUAL_USER_SELECTED`; represent the result as full, partial, or denied access. Partial access indexes only the user-selected subset and exposes a system re-selection action. |

`ACCESS_MEDIA_LOCATION` is a separate, optional permission requested only when the user enables location indexing. Denial disables EXIF location enrichment without affecting the rest of the gallery.

Initial synchronization records:

- Content URI and MediaStore identity.
- Display name and MIME type.
- Dimensions and file size.
- Capture, modification, and import timestamps.
- System album information and favorite state when available.
- A quick fingerprint derived from stable metadata.

SHA-256 is lazy during ordinary synchronization. It is calculated when an image enters AI processing, when a likely duplicate requires confirmation, and during backup preflight for every accessible image whose export record carries valuable AI or user metadata. This prevents the first scan of a large library from reading every original file in full while still making portable backups strongly reassociable.

### 6.2 Synchronization Strategy

`ContentObserver` events schedule a debounced incremental scan but are not treated as a complete change log. The app stores a per-volume synchronization checkpoint:

- API 30+ uses MediaStore generation values to detect changes since the last successful scan.
- API 29 uses MediaStore version plus modification timestamps and therefore performs a more conservative reconciliation.
- Every supported version runs a periodic full-ID reconciliation, and also reconciles after permission changes, app upgrades, restore, volume mount, and a long interval without execution.
- A synchronization run checkpoints only after its database transaction commits. Cancellation or process death repeats the uncommitted range safely.

### 6.3 Availability State and Retention

Unavailable images record a reason rather than sharing one boolean state:

- `PERMISSION_REVOKED`
- `SELECTION_REMOVED`
- `VOLUME_UNMOUNTED`
- `MEDIA_MISSING`
- `TRANSIENT_IO`

Permission loss, selected-photo removal, and an unmounted volume never imply deletion. Their records remain and become visible again when access returns. A transient I/O error does not change availability until a later reconciliation confirms the condition.

`MEDIA_MISSING` requires absence in two completed full reconciliations separated by at least 24 hours. Confirmed missing records remain recoverable for 30 days. After that retention window, records without user corrections or pinned relationships may be purged; records with valuable user data move to an orphaned-record review state and require explicit user deletion. Settings reports unavailable and orphaned counts so records cannot grow invisibly.

## 7. Persistent Data Model

The logical model contains the following independently owned records:

- **Image** - MediaStore identity, metadata, availability, fingerprint, optional SHA-256, and current processing state.
- **ImageAnalysis** - versioned structured result, explicitly mapped extension data, model, protocol, prompt, schema version, and timestamps.
- **UserCorrection** - tri-state user caption, tag/category additions and tombstones, and modification metadata.
- **EffectiveImageMetadata** - transactionally materialized caption, tags, and categories derived from current MediaStore data, active analysis, and corrections.
- **Tag** and **ImageTag** - normalized tags, source, confidence, and user/AI ownership.
- **ProcessingJob** - durable requested work and scheduling policy.
- **ProcessingAttempt** - actual model, provider, protocol, timing, response status, error classification, and retry decision.
- **ProviderProfile** - endpoint, authentication reference, headers, timeouts, and provider-level limits.
- **ModelProfile** - model ID, declared capabilities, default parameters, and provider relationship.
- **ProtocolDefinition** - preset type or declarative custom request/response mapping.
- **PromptTemplate** - task-specific versioned prompt and output-schema contract.
- **TaskRoute** - task type, primary model, fallback models, and retry/fallback policy.
- **Theme** - safe dynamic-query definition, ownership, recommendation state, and display metadata.
- **SavedSearch** - normalized query definition and optional original query text.
- **HomeModule** - source, query, sorting, layout, result limit, visibility, and order.
- **AppSetting** - non-secret processing, privacy, storage, and appearance preferences.

AI results and user corrections are separate. Reprocessing creates a new analysis record and changes the active analysis pointer only after a complete successful transaction. A failed attempt never destroys the prior active result.

### 7.1 Effective Metadata Projection

Every image exposes one effective projection to UI, themes, and search. It is derived deterministically from MediaStore metadata, the active successful AI analysis, and user corrections.

Caption correction is tri-state:

- `INHERIT` uses the active AI caption.
- `SET(value)` uses the user value, including after an AI rerun.
- `CLEARED` intentionally exposes no caption and suppresses every AI caption until the user returns the field to `INHERIT`.

Tags and categories use normalized user additions plus normalized tombstones:

- The active AI values form the base set.
- A user addition is included with user ownership and overrides an AI value with the same normalized key.
- A user deletion of an AI value creates a tombstone. The tombstone suppresses the same normalized value in future analyses.
- Restoring a deleted value is an explicit action that removes its tombstone; merely receiving the value from a later model does not restore it.
- User-added values remain until explicitly removed, independently of model output.

MediaStore fields such as file name, time, album, dimensions, and favorite state are authoritative for their own fields and cannot be overwritten by AI. User caption, tag, and category decisions take priority over the active AI analysis. Historical analyses are retained only for comparison and rollback; they are not searched.

Activating an analysis is one Room transaction that:

1. Inserts the successful analysis if it is not already stored.
2. Changes the active-analysis pointer.
3. Rebuilds effective caption, tag, and category projection rows.
4. Rebuilds all FTS and fuzzy-search rows for that image.
5. Publishes the new database version to observers only after commit.

Applying or reverting a user correction uses the same projection-and-index transaction. This guarantees that UI, themes, exact tag queries, and text search cannot observe different merge states.

## 8. Model and Protocol System

### 8.1 Provider and Model Profiles

A provider profile contains:

- User-facing name and Base URL.
- Authentication mode and encrypted credential reference.
- Custom headers with secret values represented by credential references.
- Connection, read, and write timeouts.
- Provider-wide scheduling and protection limits.

A model profile declares:

- Provider and model ID.
- Text and vision capabilities.
- Supported image input form and size constraints.
- Default generation parameters.
- Model-specific concurrency and rate limits.

### 8.2 Protocols

The app ships presets for:

- OpenAI Chat Completions.
- OpenAI Responses.
- Anthropic Messages.
- Gemini `generateContent`.

The generic protocol supports declarative configuration of:

- HTTP method and endpoint path.
- Headers.
- JSON request template.
- Prompt, model, parameter, and image placement.
- Base64 or data-URL image encoding.
- Response field extraction.
- Error-code and error-body mapping.

The minimum protocol language is deliberately small:

- The request body is a valid JSON template, not free-form source code.
- Typed placeholders may occupy JSON values: `model`, `prompt`, `image.base64`, `image.dataUrl`, and allowlisted `parameter.*` values.
- Endpoint and header strings may interpolate only scalar placeholders.
- Response locations use RFC 6901 JSON Pointer with array indexes.
- Canonical mapping may use only these bounded transforms: `trim`, `coalesce`, `joinTextBlocks`, `extractFirstJsonObject`, `asStringList`, and `mapTagArray(namePointer, scorePointer)`.
- Preset adapters may implement provider-specific details internally, but custom profiles cannot add loops, recursion, regular expressions, scripts, or arbitrary functions.
- First release accepts non-streaming JSON responses only. Streaming protocols are a later protocol-version capability.

### 8.3 Resource Limits

Protocol validation rejects configurations or responses outside these hard limits before batch use:

- URL length: 2,048 characters.
- Headers: at most 32, with at most 8 KB per value and 64 KB total.
- Request template: 64 KB; rendered prompt: 32 KB.
- Preprocessed image: default maximum edge 1,600 px and 1.5 MB; configurable up to a hard maximum edge of 4,096 px and 8 MB.
- Complete rendered request body: 12 MB.
- Response body: 2 MB, JSON depth 32, and 50,000 parsed nodes.
- Any mapped source string: 128 KB.
- Canonical caption: 4 KB.
- Tags: at most 128 entries, each at most 128 characters.
- Categories: at most 32 entries, each at most 128 characters.
- Search tokens: at most 256 entries, each at most 128 characters.
- Persisted extension JSON: 64 KB.
- User caption: 4 KB. Effective tags, including AI and user additions: 256; effective categories: 64. User values use the same 128-character per-entry limit.
- Suppression history per image: at most 512 normalized tag tombstones and 128 category tombstones. Reaching the cap requires the user to restore or delete old suppression decisions before adding more; tombstones are never discarded silently.

HTTP bodies are read through bounded sources and JSON is parsed with depth and node counters. Limit violations are permanent protocol-output failures unless a fallback model is configured.

### 8.4 Canonical Result

The required first-release canonical result is:

- `caption`
- `tags`
- `categories`
- `searchTokens`

Only explicitly mapped unknown fields may be retained as extension JSON; the raw provider response is not an analysis field. Extension JSON is capped at 64 KB by the protocol limit and defaults to a 16 KB persistence budget per active analysis. Required fields are validated before the result is committed. Malformed output is a classified attempt failure and can trigger repair, retry, or fallback according to the task route.

### 8.5 Credential Storage

Android Keystore stores a non-exportable AES-256-GCM master key. API keys and secret header values are encrypted with that key; only ciphertext, nonce, version, and credential ID are stored in the application credential store. Secrets are decrypted only for the request that needs them and are not materialized in Room entities.

If the Keystore key is invalidated by device security changes, restore, or platform failure, affected credentials enter `LOCKED` state and all dependent routes pause. The user must re-enter the secrets. The app never falls back to plaintext, and backups never contain the master key or credential ciphertext.

### 8.6 Protocol Debugger

The settings UI provides a protocol debugger that can:

- Select one user-chosen test image.
- Render a redacted request preview.
- Run a single test request outside the batch queue.
- Display HTTP status, timing, raw response, extracted fields, and validation errors.
- Confirm that the canonical result can be stored.

The debugger bypasses durable batch scheduling only; it still acquires global, provider, and model quota, uses the dedicated transport client, and applies the same endpoint, redirect, timeout, budget, and response limits. Secrets are redacted from UI, logs, exported diagnostics, and crash reports. The debugger reads at most the normal 2 MB response limit, displays at most a redacted 256 KB preview in memory, and does not automatically persist the raw response. Leaving the debugger clears the preview.

## 9. Task Routing, Concurrency, and Account Protection

Image analysis and natural-language query parsing have independent task routes. Each route selects a primary model and an ordered fallback chain.

Every outgoing request must obtain leases in this order:

```text
global quota -> provider quota -> model quota -> request
```

`ProcessingJob` rows are the durable queue. WorkManager schedules a small number of unique coordinator workers; it does not create one WorkRequest per image. A coordinator atomically claims jobs from Room and dispatches them within the acquired quotas. Lease and claim transactions prevent two workers or a restarted process from running the same attempt concurrently.

### 9.1 Android Execution Model

The queue guarantees durable at-least-once execution. It does not claim exactly-once delivery to an external model provider. A request can be accepted and billed by a provider even if Android kills the process before the response is committed.

Coordinator workers use bounded execution slices. A normal background slice stops claiming new jobs before eight minutes, checkpoints all state, and asks WorkManager to schedule the next slice. A user-started batch or any batch estimated to require more than eight minutes calls `setForeground` before its first network request and exposes a persistent progress notification with pause and open-task actions. Full-library processing is always treated as long-running work while requests are actively being sent.

Foreground execution is started only through WorkManager, not by directly starting a background service. The manifest declares the foreground service types and permissions required by the current target SDK, including the data-sync type where applicable. On Android 12+, background-start restrictions are respected. On Android 13+, if notification permission is denied, the app disables unattended high-throughput mode, explains the limitation, and degrades to conservative bounded background slices or processing while the app is visible.

### 9.2 Claim and Lease Protocol

An atomic claim stores job ID, attempt UUID, worker owner ID, boot-session identity, lease generation, wall-clock deadline, and monotonic heartbeat value.

- Heartbeat interval: 15 seconds while preprocessing or a request is active.
- Lease TTL: 120 seconds, renewed by the heartbeat transaction.
- Reclaim: only after the deadline and two missed heartbeat opportunities.
- Same-boot expiry decisions use monotonic elapsed time, not wall time.
- A boot-session change expires every lease from the previous boot.
- A detected wall-clock jump cannot shorten or extend a same-boot lease; wall time exists only for cross-process diagnostics and conservative post-boot recovery.

Committing an attempt verifies the attempt UUID and lease generation. A stale worker cannot activate a result after its lease has been reclaimed.

### 9.3 Pause, Cancellation, and Unknown Outcomes

- **Pause** stops new claims and new HTTP dispatch immediately. By default, already-sent requests may finish within their configured timeout and can commit a valid result.
- **Stop now** cancels in-flight OkHttp calls after an explicit warning. If the request body was fully sent but no validated response was stored, the attempt becomes `UNKNOWN_OUTCOME`, not an ordinary retryable failure.
- WorkManager stop, process death, and device restart stop dispatch. Claims recover through lease expiry; they are never reset merely because a UI process disappeared.
- A route may send the attempt UUID as a provider idempotency key when that protocol supports one, but the app does not assume providers honor it.
- Retrying `UNKNOWN_OUTCOME` can duplicate billing. Automatic retry requires an explicit route policy; otherwise the Tasks screen asks the user to retry, skip, or switch model and displays the duplicate-charge risk.

Attempt-specific temporary files live under a registered cache directory and use partial-file suffixes until complete. The attempt removes them in `finally`. A startup and periodic janitor deletes partial files immediately and complete files whose owning attempt has no valid lease or has been inactive for six hours. Cache cleanup is idempotent and never touches MediaStore originals.

Configurable controls include:

- Global maximum concurrency.
- Provider maximum concurrency.
- Model maximum concurrency.
- Requests per minute.
- Minimum request interval.
- Burst capacity.
- Per-batch, daily, and monthly request ceilings.
- Batch size.
- Wi-Fi, charging, battery, and execution-window constraints.
- Task priority.
- Retry count, backoff bounds, and cooldown duration.
- Whether models under one provider share a quota pool.

The settings UI offers conservative, balanced, and custom modes. Conservative is the default. Advanced settings expose every underlying value.

Protection behavior is deterministic:

- Respect `Retry-After` when present.
- Apply exponential backoff with jitter to rate limits, timeouts, and classified transient service failures.
- Open a circuit after a configurable consecutive-failure threshold.
- Pause immediately on authentication failure, exhausted balance, invalid model, or invalid protocol configuration.
- Acquire new provider and model leases before switching to a fallback.
- Apply lowered user limits immediately to new requests without forcibly terminating in-flight requests.
- Persist counters, cooldowns, jobs, and attempts so process and device restarts do not create a retry storm.

The Tasks screen shows active concurrency, recent request rate, daily usage, cooldown state, current model, fallback use, queue progress, and estimated remaining work.

## 10. AI Processing Flow

```text
MediaStore synchronization
-> write or update Image
-> apply user processing policy
-> enqueue durable ProcessingJob
-> obtain global/provider/model leases
-> preprocess selected image in temporary storage
-> render protocol request
-> send request
-> map and validate canonical result
-> transactionally write analysis, tags, active version, and search index
-> delete temporary bytes
-> publish observable state to Compose
```

The first full-library run requires explicit user confirmation and runs in controllable batches. New images can be configured for manual processing, automatic processing, or Wi-Fi-only automatic processing.

Temporary compressed image data is not retained after an attempt completes. A failed attempt records an explanation and never produces a partial active analysis. If a worker loses its lease after the provider request, only bounded structured status and timing may be retained on the stale attempt; the late raw body is discarded and cannot change the active analysis.

## 11. Search Design

Search prioritizes recall while presenting fast results progressively. Search covers:

- File name.
- Caption.
- Tags.
- Categories.
- Search tokens.
- System album.
- Favorite and processing state.
- Capture time, import time, dimensions, and file size.
- Location metadata when MediaStore exposes it and the user permits its use.

### 11.1 Retrieval Stages

The first release uses Room `@Fts4` backed by the platform SQLite FTS4 implementation available across the minSdk 29 matrix. It does not depend on platform FTS5 and does not introduce `androidx.sqlite` bundled SQLite. A Room-managed external-content FTS4 entity indexes one effective `SearchDocument` row per image. Updating that content row inside the effective-projection transaction keeps FTS4 synchronized through the Room-generated schema and triggers.

Fuzzy retrieval uses explicit Room tables rather than repeated full-image `%LIKE%` scans:

- `SearchTerm` stores deduplicated normalized terms and bounded whole-field terms for caption and file name.
- `ImageSearchTerm` maps an image to terms with field mask, ownership, and weight.
- `SearchTermAlias` stores bounded full-pinyin and pinyin-initial aliases.
- `SearchGram` maps Unicode bigrams or Latin/digit trigrams to terms and aliases.

Normalized local text queries are limited to 128 Unicode code points. Captions and other searchable free-text fields are split into 512-code-point chunks with 127-code-point overlap, so every allowed substring remains inside at least one chunk rather than disappearing at a truncation boundary. Each image may contribute at most 768 weighted term relationships, enough for the effective-value hard maxima plus MediaStore fields and free-text chunks. If a future schema adds more searchable values, it must raise and re-benchmark the cap or define an explicit product-visible exclusion; it cannot silently drop fields. Duplicate grams within one term are stored once. Extension JSON is never added automatically to search.

Retrieval proceeds as follows:

1. Parse explicit filters and locally recognizable operators.
2. Run exact tag, category, album, MediaStore-field, and user-correction queries.
3. Run FTS4 token and prefix retrieval over the effective `SearchDocument`.
4. Generate bigram/trigram candidates from `SearchGram`, verify actual substring containment on matching terms, then join through `ImageSearchTerm`.
5. Generate typo candidates from shared grams and verify them with bounded Damerau-Levenshtein distance.
6. Repeat substring and typo candidate lookup against pinyin aliases.
7. Merge, deduplicate, rank, and annotate results with hit reasons.

### 11.2 Short Queries, Typo Bounds, and Pinyin

Short queries intentionally use stricter rules to prevent unbounded recall:

- One CJK character searches exact and prefix matches in tags, categories, albums, and file names. It does not scan every caption.
- Two or more CJK characters use the bigram index.
- One or two Latin/digit characters use exact and prefix matching only.
- Three or more Latin/digit characters use trigram substring candidates.
- Typo matching is disabled below three characters.

Damerau-Levenshtein distance is capped by normalized query length: distance 1 for length 3-5 and distance 2 for length 6 or greater. Each query token evaluates at most 512 gram-ranked term candidates, accepts at most 64 corrected terms, and contributes at most 5,000 image candidates before normal ranking. Reaching a cap produces a partial-stage diagnostic rather than expanding work silently.

Pinyin is generated locally from normalized Chinese terms. A phrase dictionary selects the primary pronunciation. A polyphonic term may store at most two alternate full-pinyin forms and one initials form; aliases are limited to 128 characters and three aliases per term. Non-Chinese text is NFKC-normalized and lowercased but receives no pinyin alias. These limits are included in the per-image storage budget and benchmark fixtures.

### 11.3 Progressive Results, Timeouts, and Degradation

Ranking uses stable match tiers: user value, exact structured match, FTS4 match, substring match, typo match, then pinyin match. A later stage cannot move an item above an already published higher tier. Lower tiers update in batches no more frequently than every 200 ms; items may reorder only within the currently incomplete lower tier. The UI marks results as refining until all enabled stages finish.

On the 100,000-record reference fixture, stages have these hard budgets:

- Structured and FTS4 stages: 1 second combined.
- Substring candidate and verification stage: 2 additional seconds.
- Typo and pinyin stages: 2 additional seconds.
- Overall query: 5 seconds.

Each stage is optional after its timeout. Timeout returns the completed tiers with a visible partial-results reason; it never converts to an unrestricted table scan. Cancellation is keyed by query generation, checked after every database page and every 256 in-memory candidates, and must stop obsolete work within 200 ms on the reference device.

An index build or migration failure degrades in order: exact structured search remains available, then FTS4 if healthy, while the unavailable fuzzy tier is reported with a rebuild action. Search never blocks the UI while rebuilding.

### 11.4 Natural-Language Query Parsing

When configured, a text model may translate natural language into a safe query object. The model cannot emit raw SQL. The result is validated against an allowlisted query schema containing fields, operators, ranges, sorting, and text terms. If parsing fails or the network is unavailable, the original query proceeds through local search.

### 11.5 Themes and Saved Searches

Themes and saved searches store safe query definitions rather than static image ID lists. They update automatically as the gallery or analysis data changes.

- Users can create themes from text and filters.
- AI can recommend themes from existing structured metadata.
- Recommended themes remain suggestions until the user saves or pins them.
- Users can edit both user-created and saved AI-recommended themes.

## 12. Backup and Restore

The export package includes:

- Database content required to restore images, analyses, corrections, tags, themes, saved searches, tasks, and home layout.
- Non-secret provider, model, protocol, prompt, route, and processing configuration.
- Export format version and Room schema version.
- Integrity metadata required to reject partial or corrupt packages.

The package does not include original images, thumbnail cache, temporary files, API keys, encrypted credentials, or the Keystore master key. After restore, credentials must be re-entered.

### 12.1 Portable Identity

Export preflight calculates SHA-256 for every accessible image that has an active analysis, user correction or tombstone, or pinned image relationship. The export UI reports hash progress separately from archive writing.

- A SHA-256 and byte-size match is a strong match and may automatically inherit image-specific analysis and user metadata.
- MediaStore identity, file name, time, dimensions, and quick fingerprint are candidate signals only. They may suggest a weak match but cannot automatically attach captions, location, corrections, tombstones, or pinned relationships on a different installation.
- A weak match enters a review queue. The user must confirm it before sensitive or user-authored relationships are attached.
- If an original is inaccessible during export, the package records why no strong hash exists. Export may continue after showing the count, but those records remain weak-match-only after restore.
- A many-to-one or one-to-many hash match is never resolved silently. Exact duplicate content is presented as a grouped confirmation.

### 12.2 Package Security and Limits

Backups are password-encrypted by default because captions, tags, album names, timestamps, location, prompts, and model output can be sensitive. The encrypted format is a versioned authenticated envelope containing a compressed payload: PBKDF2-HMAC-SHA-256 with at least 600,000 iterations, a random 256-bit salt, and AES-256-GCM with a random 96-bit nonce. KDF parameters are stored in the authenticated header so a later format can raise or replace them. Plaintext export is available only as an advanced action with an explicit privacy warning.

Import rejects a package before database work when any of these limits fail:

- Compressed package size exceeds 2 GB.
- Declared total uncompressed size exceeds 4 GB.
- Entry count exceeds 256, an individual database entry exceeds 2 GB, or a non-database entry exceeds 64 MB.
- Aggregate or per-entry decompression ratio exceeds 100:1.
- An entry has an absolute path, `..` traversal, duplicate normalized path, symlink, device file, or an undeclared name.
- Authenticated manifest, per-entry length, checksum, schema version, or referential-integrity validation fails.

The importer streams through bounded buffers and never extracts archive paths directly into application storage. It checks available space before staging and requires enough room for the encrypted package, staging database, active database transaction/WAL growth, and a fixed 128 MB safety margin.

### 12.3 Restore Transaction and Conflict Rules

Restore first decrypts and validates into a private staging database with no running workers. Both replace and merge use the same maintenance gate: pause dispatch, cancel scheduled coordinators, wait for active Room transactions, and prevent UI repositories from writing until completion. The active Room database file is never replaced while open, and backup code never manipulates its `-wal` or `-shm` files.

**Replace content** validates the staging schema and then, in one transaction on the active Room database, deletes app-owned restorable rows and imports the staged rows. **Merge** imports selected rows through the conflict rules below in one transaction. A failure rolls back the active database. After commit, Room checkpoints its own WAL, rebuilds derived FTS/fuzzy rows from effective projections, releases the maintenance gate, and reopens normal navigation. Before replace mode, the UI offers a normal portable export of the current state and reports the extra time and space it requires; transactional rollback remains the correctness mechanism.

Merge rules are deterministic:

- Current MediaStore identity, availability, permissions, and device-derived fields remain authoritative.
- Immutable analyses and attempts are deduplicated by stable UUID plus content hash; nonidentical records are both retained. The local active analysis remains active. An imported active analysis is selected only when no local active analysis exists for a strong image match.
- Local caption state, tag/category additions, and tombstones win for the same normalized field. Imported corrections fill fields with no local decision. Conflicts are recorded for optional post-restore review rather than resolved by unreliable cross-device timestamps.
- Identical themes, saved searches, prompts, protocols, and model profiles deduplicate by canonical content hash. A stable-ID collision with different content imports a renamed copy with a new ID.
- Imported routes and model profiles are disabled until credentials are re-entered and endpoint policy is revalidated. Device, permission, transport-approval, and security settings never import. Portable UI preferences and Home layout import only when selected; local values win in merge mode.
- Task and attempt history imports as archived diagnostics. Every imported running, waiting, cooldown, or unknown-outcome job becomes paused with no live lease and cannot resume until the user reviews credentials, routes, constraints, and duplicate-billing risk.

Replace mode applies the same credential and job-pausing rules. A failed restore does not damage the active database, and a process death before commit leaves the import uncommitted and restartable from the staging manifest.

## 13. Storage and Performance Budgets

The app does not create permanent image copies.

The 10 KB-per-image target applies only to the live searchable dataset and is split so regressions have an owner:

| Live data class | Average budget per indexed image |
|---|---:|
| MediaStore identity, availability, and core metadata | 1.5 KB |
| Active canonical analysis and effective user projection | 2.5 KB |
| FTS4 document and index | 1.5 KB |
| Search terms, image-term mappings, ngrams, and pinyin aliases | 4.5 KB |
| **Total live searchable data** | **10.0 KB** |

These are fixture-wide averages after SQLite page and index overhead, not permission for an individual provider response to bypass the hard limits in section 8. Prompts, protocol definitions, and configuration versions are content-addressed and referenced rather than copied into every analysis or attempt. Raw requests, raw responses, and image bytes are never part of live analysis storage.

Historical and diagnostic data has separate default retention:

- At most two prior successful analyses per image and 256 MB total analysis history, whichever limit is reached first. Oldest unpinned history is evicted first; if protected history fills the cap, the user must export, delete, or explicitly raise the cap.
- Explicit extension JSON is disabled unless a protocol maps extension fields. When enabled it has a default 16 KB per-analysis ceiling and the 64 KB hard maximum from section 8; its measured bytes still count against the active-analysis or history budget.
- Attempts retain structured status and redacted diagnostics for 30 days, at most 20 attempts per job and 64 MB globally. An attempt diagnostic preview is at most 8 KB; provider raw bodies are not retained.
- Operational logs use a 14-day, 16 MB rolling cap. Completed task summaries use a 90-day, 64 MB cap unless pinned.
- Backup files selected by the user are reported but are not counted as application database usage.

Before dispatching a paid request, retention preflight ensures the resulting active analysis can commit and the previous active record can be handled under the history policy. If protected history has filled its cap, the route pauses before network dispatch and asks the user to export, delete, or raise the cap; a paid valid response is never rejected merely because diagnostics or history is full.

For 100,000 images without OCR, live persistent metadata therefore ranges from roughly 400 MB for a partially analyzed, low-density library to an acceptance ceiling near 1.0 GB for the reference fully searchable fixture. Default retained history and diagnostics can add up to 400 MB. The clearable thumbnail cache defaults to a 256 MB ceiling, and temporary processing uses at most 64 MB. Settings reports each class separately and supports clearing cache, expired history, attempts, and diagnostics without losing the active analysis or user corrections.

### 13.1 Repeatable Performance Benchmark

The primary reference device is a physical Pixel 4a-class device (Snapdragon 730G, 6 GB RAM) running its documented Android 13 build. API 29 behavior is validated separately on an Android 10 emulator and at least one API 29 physical device before release; emulator timings are not used as performance claims.

The canonical 100,000-image fixture has deterministic seed and distributions: every record has file name, album, time, size, and dimensions; 80% have a caption of 20-160 normalized characters; analyzed records have 4-24 tags and 1-4 categories; 20% of searchable terms contain CJK text, 10% of CJK terms exercise polyphonic aliases, 10% of images have user corrections, and two historical analyses exist for 25% of images. The same generator produces 10,000 and 50,000 subsets.

The versioned query corpus contains at least 200 queries covering exact fields, prefix, common and rare CJK substring, Latin substring, typos at each allowed edit distance, full pinyin, initials, short-query rules, empty-result cases, high-frequency grams, filters, and mixed-field queries. Results are checked for correctness as well as latency.

Benchmarks report cold-app and warm-database runs separately. A cold run force-stops the app and opens the database without a search warm-up; a warm run follows one unmeasured corpus pass. Each query class has at least 30 measured samples and reports P50 and P95. The first page is 40 items.

Acceptance on the 100,000-image reference fixture is:

- First structured/FTS4 page: P50 at most 500 ms and P95 at most 1 second.
- All enabled fuzzy stages complete: P50 at most 3 seconds and P95 at most 5 seconds.
- Cancellation after a replacement query: P95 at most 200 ms.
- Search incremental Java/Kotlin heap: at most 96 MB; whole-process peak PSS during search: at most 300 MB.
- No benchmark run blocks Compose rendering or materializes the full result set; paging and bounded candidates remain enabled.

Long-running synchronization, backup, and processing benchmarks also report completion time, peak PSS, and P95 cancellation latency. Published benchmark artifacts include commit, schema version, device build, fixture seed, analyzed percentage, database size by table/index, query corpus version, and cold/warm methodology.

## 14. Error Handling and Observability

Errors are classified into user-actionable, retryable, permanent configuration, provider-account, local-data, and permission categories.

Every failure shown to the user includes:

- What operation failed.
- Which provider and model were used when relevant.
- Whether retry or fallback will occur.
- The next permitted action.
- A redacted diagnostic identifier.

The app must not:

- Retry indefinitely.
- Silently discard a task.
- Expose credentials or full sensitive payloads.
- Replace valid analysis with partial output.
- Treat lost MediaStore permission as confirmation that original images were deleted.

Release logging records state transitions and redacted diagnostics, not image bytes, secrets, or full provider responses.

## 15. Testing Strategy

### 15.1 Unit Tests

- Protocol request rendering and response mapping.
- Protocol URL validation, cleartext profile approval, redirect limits, HTTPS downgrade rejection, and cross-origin credential stripping.
- Protocol body, image, JSON depth/node, mapped-field, and extension-JSON limits.
- Canonical result validation and extension-field retention.
- Caption `INHERIT`/`SET`/`CLEARED`, tag/category tombstones, explicit restoration, and rerun projection precedence.
- Query normalization, safe query parsing, short-query rules, substring candidates, typo bounds, pinyin alternatives, and pinyin initials.
- Candidate caps, stage timeouts, stable progressive tiers, partial-result reasons, deduplication, ranking, and hit explanations.
- Rate-limit acquisition/release, same-boot monotonic lease expiry, boot-change expiry, stale-generation rejection, and heartbeat recovery.
- Retry, backoff, circuit-breaker, and fallback decisions.
- Pause, stop-now, and `UNKNOWN_OUTCOME` retry/billing decisions.
- Theme query and Home module configuration behavior.

### 15.2 Integration Tests

- Room schema, foreign keys, migrations, and transactional analysis activation with effective projection and every search index.
- FTS4 and fuzzy-index synchronization after active-analysis changes, tombstones, restoration, and user corrections.
- Search timeout/degradation behavior when an index is missing, corrupt, rebuilding, or over its candidate cap.
- MediaStore generation/version checkpointing, partial-photo selection changes, permission loss, remount, confirmed deletion, and periodic full reconciliation.
- WorkManager stop, process death, reboot, stale lease, foreground transition, notification denial, duplicate execution, and constraint changes.
- Late provider response after lease loss and idempotent commit rejection.
- Backup password authentication, manifest/checksum validation, traversal/symlink/duplicate-entry rejection, size and decompression-ratio limits, and unavailable-original export.
- Strong-hash reassociation, weak-match confirmation, duplicate-content review, replace rollback, and merge conflicts for analyses, corrections/tombstones, themes, profiles, settings, and paused jobs.
- Keystore envelope encryption, key invalidation, locked-route behavior, and absence of secret material from backup and diagnostics.

### 15.3 UI and Device Tests

- Permission onboarding on API 29, 33, 34, and 36, including denied and selected-photos-only states.
- Four-destination navigation and process recreation.
- Home module editing and single-module waterfall adaptation.
- Task pause, stop-now, unknown-outcome warning, resume, retry, cooldown, notification denial, and model fallback presentation.
- Protocol debugger success and failure states.
- Search cancellation, stable progressive-result updates, timeout reason, and index-rebuild degradation.
- Restore maintenance mode, weak-match review, conflict summary, failure recovery, and credential re-entry.
- Compose screenshot regression for core screens and light/dark themes.

### 15.4 Milestone Gate

Every milestone must produce an installable APK and a documented emulator or physical-device smoke test. Completion is based on observed behavior and passing tests, never only on source-file presence.

Immediately after the persistent schema and search-index prototype exist, a mandatory 100,000-record synthetic gate runs before provider batching work proceeds. It must build the full FTS4/ngram/pinyin fixture within 30 minutes on the reference device, keep index storage within the section 13 live-data ceiling, satisfy the section 13.1 search latency and memory budgets, and pass result-correctness checks. Failure reopens the schema/search design at that milestone; it is not deferred to release hardening.

## 16. Delivery Decomposition

The rebuild is too large for one implementation plan. It will be delivered as independently testable subprojects in this order:

1. **Security and build baseline** - remove shared secret injection, raise minSdk to 29, establish release and network configuration, test infrastructure, exported Room schemas, and a minimal Compose shell.
2. **Media and canonical storage foundation** - permission matrix, MediaStore synchronization, Room image identity, immutable canonical analyses, active-analysis pointer, correction/tombstone records, transactional effective projection, paging, and a real Library screen.
3. **Search schema and 100k feasibility gate** - FTS4 document, term/ngram/pinyin tables, deterministic fixture generator, index builder, repository benchmark, correctness corpus, cancellation, and the mandatory 100,000-record storage/latency/memory gate. This milestone proves the architecture before model batch work.
4. **Model protocol and one-image vertical path** - Keystore envelope credentials, protocol presets, custom mapping and limits, transport policy, debugger, a minimal durable job, and one-image end-to-end analysis committed through the canonical storage and effective-projection transaction.
5. **Batch processing and task control** - coordinator slicing, foreground execution, claim/lease protocol, full quota policy, fallback chains, account protection, unknown-outcome handling, task UI, and restart/reboot recovery.
6. **Search and correction product loop** - production search UI, progressive stages, filters, safe natural-language query parsing, result explanations, correction editing, tombstone restoration, and saved searches. The underlying storage and indexes already exist from milestones 2 and 3.
7. **Themes and customizable Home** - AI/user themes, modules, sorting, reordering, and single-module waterfall behavior.
8. **Backup, scale regression, and release hardening** - encrypted export/restore, reassociation/conflict UI, repeat 10k/50k/100k benchmarks, storage reporting and retention, security audit, API 29/33/34/36 coverage, and physical-device release gates.

Each subproject receives its own detailed implementation plan with file-level tasks, tests, and review checkpoints. The first releasable vertical slice is complete only when a user can authorize the gallery, index real images, configure a model, analyze an image, search the persisted result, and reopen the app without losing state.

## 17. Success Criteria

The rebuild succeeds when:

- A new user can configure a supported or custom model protocol without editing project files.
- A 9,000 to 15,000-image gallery can be indexed and browsed reliably.
- The 100,000-record feasibility gate passes before batch processing is built, so growth does not depend on a late architectural rewrite.
- Processing is durable, rate-limited, observable, and controllable.
- Search works locally across every indexed field and supports substring, typo, pinyin, and pinyin-initial matching.
- AI reruns preserve user corrections and prior valid results.
- Home can be personalized without losing the search-first product identity.
- Export and restore preserve valuable generated metadata without copying original images or exposing API keys.
- Security, storage, performance, and physical-device tests are explicit release gates.
