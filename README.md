# WAYLOAM Router

Offline bicycle routing for long journeys, maintained by **Loam Tools**.

WAYLOAM Router embeds the pinned BRouter engine in a JVM/Android library. Route calculation runs locally against downloaded OpenStreetMap routing data. The same runtime powers the Android integration API and an executable benchmark CLI.

## What works

- **Three cycling presets:** Direct, Touring and Bikepacking, based on versioned upstream profiles.
- **Graph-verified automatic anchors:** candidate points are matched against the bicycle graph; unreachable automatic points can be retried or skipped while preserving every rider-selected stop.
- **Bounded calculation:** request deadlines, per-search time and memory budgets, coroutine cancellation and a bounded native worker queue.
- **Progressive geometry:** completed sections can be displayed as the rest of the route is calculated.
- **Persistent caching:** checksummed, atomic, size-bounded route and section caches survive process restarts. A retry reuses sections already completed.
- **Partial rerouting:** connect to a forward section boundary and reuse the remaining route and its exact metrics.
- **Verified map downloads:** resumable transfers, source-version checks, SHA-256 verification, atomic replacement and preservation of installed data after a failed refresh.
- **GPX export and reproducible measurements:** real cold/warm benchmark trials, exact dataset hashes, timings, cache diagnostics and typed failures.

## Readiness

The engine is a working library, **not yet the default router in the WAYLOAM Android app**. Graph matching proves that an anchor lies on a routable way; it does not prove global connectivity or optimality across seas and mountain ranges. Automatic-anchor recovery is bounded and can still return a specific no-route, data or budget error.

The long-distance acceptance gate remains mandatory: repeated Heidelberg → Oslo and Heidelberg → Barcelona cold runs, the wider benchmark corpus, and measurements on the supported Android device class. Passing the small offline fixture does not establish those performance claims. See [benchmarks](docs/BENCHMARKS.md).

## Build

Requirements: **JDK 17**. The checked-in wrapper pins Gradle 9.6.0 and verifies its distribution checksum. Clone recursively to include the pinned BRouter source and fixtures:

```bash
git clone --recurse-submodules https://github.com/loam-tools/wayloam-router.git
cd wayloam-router
./gradlew -PincludeAndroid=false test :router-benchmark:installDist
```

Windows: use `gradlew.bat`. Android additionally requires SDK 36:

```bash
./gradlew :router-android:assembleDebug
```

## Use the local runtime

```kotlin
val router = EmbeddedWayloamRouter.create(
    LocalRouterConfig(root = routingRoot, dataVersion = installedDatasetVersion)
)
val result = router.route(
    RouteRequest(start = origin, end = destination, profile = RouteProfile.TOURING)
) { event ->
    // Forward events to the UI through your app's state/Flow layer.
}
```

Place verified map data in `routingRoot/segments4`. The factory installs bundled profiles and creates the cache. It performs **no network requests**. Download planning and installation are explicit host operations through `router-data` and `router-http`.

See [integration](docs/INTEGRATION.md) for Android storage, errors, progress, cancellation and rerouting.

## Run a real benchmark

```bash
router-benchmark/build/install/router-benchmark/bin/router-benchmark \
  --root /path/to/routing-root --data-version your-dataset-id \
  --route heidelberg-oslo --profile TOURING --iterations 3 \
  --output benchmark-artifacts/results.jsonl
```

With no arguments or `--catalogue`, the CLI lists the six canonical routes. Map preparation requires the explicit `--prepare true` option and a download budget. Failed trials return a nonzero exit status and an attributable JSON record.

## Modules

| Module | Responsibility |
|---|---|
| `router-api` | Requests, results, progressive events, typed failures and GPX export |
| `router-core` | Graph-anchor planning, coordination, stitching, cache and reconnection |
| `router-data` | Tile identities, verification, resumable installation and storage |
| `router-http` | HTTP metadata and conditional range transfers |
| `router-brouter` | Pinned native engine, graph matching, bundled profiles and error translation |
| `router-runtime` | Ready-to-use local runtime shared by JVM and Android |
| `router-android` | Android storage and runtime dependency |
| `router-benchmark` | Executable acceptance corpus and machine-readable results |

## Upstream and data

BRouter is pinned to **v1.7.10**, commit `4d2639af77ea5ed9c30d3e400764eb6f9e8522da`, as a Git submodule. Upstream source is compiled without local patches; Loam-specific behavior stays in adapters. Its MIT license is preserved in [third-party notices](THIRD_PARTY_NOTICES.md).

Routing data derives from **© OpenStreetMap contributors**, available under the ODbL. Applications must provide appropriate attribution; see [data documentation](docs/DATA.md).

This repository is private. It supports [WAYLOAM](https://github.com/loam-tools/wayloam) and the separate [release repository](https://github.com/loam-tools/wayloam-releases).
