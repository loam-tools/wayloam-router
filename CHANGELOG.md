# Changelog

## Unreleased

### Added
- Shared offline runtime with bundled profiles, explicit dataset identity and Android storage integration.
- Bicycle-graph anchor matching, alternative-anchor retries and bounded recovery while preserving chosen stops.
- Atomic, checksummed disk caching for complete routes and completed sections, with LRU size limits.
- Forward-boundary reconnection, progressive section geometry, typed failures and streaming GPX export.
- Executable cold/warm benchmark trials with dataset hashes, JSONL measurements, GPX artifacts and failure exit codes.
- Pinned Gradle wrapper and JVM-only builds without an Android SDK.
- Regression coverage for cancellation, cache recovery, retry continuity, tile corruption and source changes.

### Fixed
- Failed map refreshes preserve the previous installation until replacement verification succeeds.
- Partial downloads track their source version and use conditional HTTP ranges.
- Saved hashes detect same-size local corruption even without a remote checksum.
- Dateline-crossing estimates avoid requesting nearly all longitude tiles.
- Native profile parsing runs off the calling thread; a bounded queue prevents overlapping native searches.
- Dynamic distant endpoint matching and beeline fallback are disabled; disconnected section geometry is rejected.
- Runtime documentation and CI now reflect embedded engine execution and verified downloads.

### Rollout gate
- This library is not yet the WAYLOAM app's default router. Repeated long-distance fresh-process trials,
  geographic route review and Android device performance measurements remain required.

## [0.1.0-dev] - 2026-09-12

### Added
- Loam-owned routing API and segmented route result model.
- Cancellable long-route coordinator with progress events.
- Deterministic cache identity for engine, profile and routing-data versions.
- BRouter v1.7.10 adapter boundary and initial Direct/Touring/Bikepacking presets.
- `.rd5` tile naming and local routing-data store.
- Android app-private routing storage layout.
- Canonical long-distance benchmark catalogue.
- Unit-test, CI and benchmark workflow foundation.

### Not yet implemented
- Actual embedded BRouter core execution.
- Routing-data download/verification manager.
- Graph-aware intermediate anchor selection.
- Persistent route cache.
- Real `.rd5` benchmark execution.

