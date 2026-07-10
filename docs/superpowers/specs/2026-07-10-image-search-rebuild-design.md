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

The rebuild retains the current platform baseline unless a separately reviewed dependency change requires an update: minSdk 26, targetSdk 36, compileSdk 36, Java 17, Kotlin 2.0.21, and Jetpack Compose with Material 3.

Before distributing any rebuilt APK:

- Revoke the API key previously committed to the repository.
- Remove all build-time injection of a shared long-lived API key.
- Ensure release builds do not use the debug keystore.
- Disable cleartext traffic unless a user explicitly configures an HTTP endpoint and accepts the warning.
- Prevent secrets and full request bodies from appearing in release logs.

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

Initial synchronization records:

- Content URI and MediaStore identity.
- Display name and MIME type.
- Dimensions and file size.
- Capture, modification, and import timestamps.
- System album information and favorite state when available.
- A quick fingerprint derived from stable metadata.

SHA-256 is lazy. It is calculated when an image enters AI processing, when backup restore needs stronger reassociation, or when a likely duplicate requires confirmation. This prevents the first scan of a large library from reading every original file in full.

Removed or inaccessible MediaStore items are marked unavailable before deletion from the local index. This preserves user-created relationships long enough for restore or reassociation and avoids destructive reactions to transient permission changes.

## 7. Persistent Data Model

The logical model contains the following independently owned records:

- **Image** - MediaStore identity, metadata, availability, fingerprint, optional SHA-256, and current processing state.
- **ImageAnalysis** - versioned structured result, raw extension data, model, protocol, prompt, schema version, and timestamps.
- **UserCorrection** - user caption, tags, categories, and modification metadata.
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

## 8. Model and Protocol System

### 8.1 Provider and Model Profiles

A provider profile contains:

- User-facing name and Base URL.
- Authentication mode and Android Keystore credential reference.
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

Protocol configuration cannot run JavaScript, shell commands, or arbitrary executable code. Request templating and response extraction operate over an allowlisted declarative expression set.

### 8.3 Canonical Result

The required first-release canonical result is:

- `caption`
- `tags`
- `categories`
- `searchTokens`

Unknown fields may be retained as extension JSON. Required fields are validated before the result is committed. Malformed output is a classified attempt failure and can trigger repair, retry, or fallback according to the task route.

### 8.4 Protocol Debugger

The settings UI provides a protocol debugger that can:

- Select one user-chosen test image.
- Render a redacted request preview.
- Run a single test request outside the batch queue.
- Display HTTP status, timing, raw response, extracted fields, and validation errors.
- Confirm that the canonical result can be stored.

Secrets are redacted from UI, logs, exported diagnostics, and crash reports.

## 9. Task Routing, Concurrency, and Account Protection

Image analysis and natural-language query parsing have independent task routes. Each route selects a primary model and an ordered fallback chain.

Every outgoing request must obtain leases in this order:

```text
global quota -> provider quota -> model quota -> request
```

`ProcessingJob` rows are the durable queue. WorkManager schedules a small number of unique coordinator workers; it does not create one WorkRequest per image. A coordinator atomically claims jobs from Room and dispatches them within the acquired quotas. Lease and claim transactions prevent two workers or a restarted process from running the same attempt concurrently.

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

Temporary compressed image data is not retained after an attempt completes. A failed attempt records an explanation and never produces a partial active analysis.

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

1. Parse explicit filters and locally recognizable operators.
2. Run exact tag, category, album, and structured-field queries.
3. Run FTS5 token and prefix retrieval over normalized searchable text.
4. Run full substring matching over a normalized per-image search blob.
5. Run typo-tolerant token matching over a normalized token dictionary.
6. Run full-pinyin and pinyin-initial matching generated locally at index time.
7. Merge, deduplicate, rank, and annotate results with hit reasons.

Exact and FTS results can appear first. Slower fuzzy stages update the same result stream without blocking the UI. A new query cancels obsolete fuzzy work.

### 11.2 Natural-Language Query Parsing

When configured, a text model may translate natural language into a safe query object. The model cannot emit raw SQL. The result is validated against an allowlisted query schema containing fields, operators, ranges, sorting, and text terms. If parsing fails or the network is unavailable, the original query proceeds through local search.

### 11.3 Themes and Saved Searches

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

The package does not include original images, thumbnail cache, temporary files, or API keys. After restore, credentials must be re-entered. Images are reassociated using MediaStore identity, quick fingerprints, and lazy SHA-256 where stronger confirmation is required.

Restore is staged: validate package, import into a temporary database, verify referential integrity, then atomically replace or merge according to an explicit user choice. A failed restore does not damage the active database.

Running and waiting jobs are restored in a paused state. Historical attempts remain available for diagnostics, but no restored network task resumes until the user reviews the current model credentials and explicitly continues processing.

## 13. Storage and Performance Budgets

The app does not create permanent image copies.

For 100,000 images without OCR:

- Expected persistent index and structured metadata: approximately 200 to 600 MB.
- Default clearable thumbnail cache ceiling: 256 MB.
- Temporary processing ceiling: 64 MB, reclaimed after work completes.
- Typical total working footprint: approximately 350 to 900 MB depending on metadata density and cache use.

Acceptance budgets:

- Average persistent index target: at most 10 KB per image.
- Cache and persistent storage are reported separately.
- Users can clear cache without losing analysis or search data.
- The Settings screen reports database, cache, temporary, and backup sizes.

Performance validation uses 10,000, 50,000, and 100,000-record fixtures.

- Normal search should display an initial page in approximately one second on the representative test device.
- A complete 100,000-image full-field fuzzy search may take three to five seconds.
- Long-running search, synchronization, backup, and processing work must remain cancellable and must not block Compose rendering.
- Paging prevents full-library UI materialization in memory.

These are user-experience budgets, not claims that every device will produce identical timings. Benchmark results must name the device and dataset.

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
- Canonical result validation.
- Query normalization and safe query parsing.
- Substring, typo-tolerant, pinyin, and pinyin-initial matching.
- Result merging, deduplication, ranking, and hit explanations.
- Rate-limit lease acquisition and release.
- Retry, backoff, circuit-breaker, and fallback decisions.
- Theme query and Home module configuration behavior.

### 15.2 Integration Tests

- Room schema, foreign keys, migrations, and transactional analysis activation.
- FTS and fuzzy-index synchronization after analysis and user corrections.
- MediaStore synchronization and unavailable-image reconciliation.
- WorkManager restart recovery, duplicate execution prevention, and constraint changes.
- Backup validation, export, restore, and failure rollback.
- Keystore credential-reference behavior without exposing secret material.

### 15.3 UI and Device Tests

- Permission onboarding and degraded permission states.
- Four-destination navigation and process recreation.
- Home module editing and single-module waterfall adaptation.
- Task pause, resume, retry, cooldown, and model fallback presentation.
- Protocol debugger success and failure states.
- Search cancellation and progressive-result updates.
- Compose screenshot regression for core screens and light/dark themes.

### 15.4 Milestone Gate

Every milestone must produce an installable APK and a documented emulator or physical-device smoke test. Completion is based on observed behavior and passing tests, never only on source-file presence.

## 16. Delivery Decomposition

The rebuild is too large for one implementation plan. It will be delivered as independently testable subprojects in this order:

1. **Security and build baseline** - remove shared secret injection, establish release configuration, test infrastructure, exported Room schemas, and a minimal Compose shell.
2. **Media library foundation** - permissions, MediaStore synchronization, Room image identity, paging, and a real Library screen.
3. **Model protocol and processing foundation** - Keystore profiles, protocol presets, custom mapping, debugger, persistent jobs, limits, and one-image end-to-end analysis.
4. **Batch processing and task control** - full policy engine, fallback chains, account protection, task UI, and restart recovery.
5. **Search and correction loop** - canonical analysis storage, FTS, substring, typo, pinyin, query parsing, result explanations, and user corrections.
6. **Themes and customizable Home** - AI/user themes, saved searches, modules, sorting, reordering, and single-module waterfall behavior.
7. **Backup, scale validation, and release hardening** - export/restore, 10k/50k/100k benchmarks, storage reporting, security audit, and physical-device release gate.

Each subproject receives its own detailed implementation plan with file-level tasks, tests, and review checkpoints. The first releasable vertical slice is complete only when a user can authorize the gallery, index real images, configure a model, analyze an image, search the persisted result, and reopen the app without losing state.

## 17. Success Criteria

The rebuild succeeds when:

- A new user can configure a supported or custom model protocol without editing project files.
- A 9,000 to 15,000-image gallery can be indexed and browsed reliably.
- Growth toward 100,000 images does not require an architectural rewrite.
- Processing is durable, rate-limited, observable, and controllable.
- Search works locally across every indexed field and supports substring, typo, pinyin, and pinyin-initial matching.
- AI reruns preserve user corrections and prior valid results.
- Home can be personalized without losing the search-first product identity.
- Export and restore preserve valuable generated metadata without copying original images or exposing API keys.
- Security, storage, performance, and physical-device tests are explicit release gates.
