# Runtime integration

## Android

Consume `router-android` and its transitive `router-runtime` dependency from this build. The AAR alone is not a fat bundle: include its Gradle dependencies as well. The supported minimum is Android API 26; builds use Java 17 and SDK 36.

```kotlin
val root = RouterStorage.create(context).root()
val router = EmbeddedWayloamRouter.create(
    LocalRouterConfig(root = root, dataVersion = installedDatasetVersion)
)
```

`create` is suspending and installs the exact bundled `fastbike`, `trekking` and `lookups.dat` resources on an IO dispatcher. `RouterStorage` uses the app's **no-backup directory**, so large downloaded maps and private route caches are not included in Android cloud backup. An app migrating from the old `filesDir/wayloam-router` location must explicitly move its existing maps; this engine has not yet shipped as the WAYLOAM default.

For local source integration, include this repository as a Gradle composite build, and substitute the required modules (group `tools.loam.wayloam.router`) with the matching projects. Keep the BRouter submodule initialized at its pinned commit.

## Data preparation

Route calculation is offline. Use `estimatedTiles(request)` for a conservative geographic estimate and `missingStopTiles(request)` to check rider-selected stops. A route that detours around a barrier can require additional neighboring tiles; an estimate is not an exact graph-coverage guarantee.

The host can resolve sizes with `BRouterHttpDataSource.artifact(tile)`, show the transfer total, freeze the resulting artifacts in `StaticRoutingDataManifest`, then use `VerifiedRoutingDataManager.ensureTiles(tiles)`. Manage one installer per storage root and serialize map updates/removal with route calculation. Do not modify data files while the native engine is using them.

A verified file has a locally recorded SHA-256. When the upstream source provides no trusted digest, this detects subsequent local corruption but does not authenticate the original download independently of HTTPS. ETag/Last-Modified checks and conditional ranges prevent stitching bytes from different remote versions. Failed refreshes preserve the old installation.

Supply a meaningful `dataVersion` from your installed manifest. The runtime additionally fingerprints file names, sizes and modification times, conservatively invalidating cache entries after installed maps change. It does not hash gigabytes of map files on every warm reopen. Use the data manager's verification before accepting externally changed files; changing a file while preserving its length and modification time requires a new dataset version.

## Progress and cancellation

`RoutingEvent` covers preparation, section count, current section, retries, skipped automatic anchors, section cache hits, completed section geometry and final completion. Cache hits also emit `Completed`. Callbacks run on the routing dispatcher; hand events to a Flow/state layer and render on the main thread. Exceptions thrown by your callback propagate to the caller.

Cancel the coroutine that owns `route`. Parent cancellation remains `CancellationException`; the router does not turn it into an ordinary failure. Request deadlines become `RoutingException(TIMEOUT)`. Completed sections may remain cached after cancellation, but an interrupted section is never committed as complete.

One native search runs at a time per process, with a bounded queue. Engine parsing runs off the UI thread. Cancellation terminates an active search and removes cancelled queued work. Graph matching checks cancellation between bounded local scans. Network reads use transport timeouts and interruption checks; a blocked socket may take up to its configured read timeout to unwind.

## Error handling

| Code | Host action |
|---|---|
| `MISSING_DATA` | Offer the named missing region download |
| `INCOMPATIBLE_DATA` | Verify/replace the region and matching lookup data |
| `MISSING_PROFILE` | Repair bundled profile installation |
| `NO_ROUTE` | Let the rider move a stop or add an explicit via point |
| `TIMEOUT` | Retry with appropriate budgets or more explicit stops |
| `DISCONNECTED_ROUTE` | Retain the earlier route; never draw a connecting line as a valid road |
| `ENGINE_BUSY` | Wait or cancel the obsolete request |
| `ENGINE_ERROR` | Preserve the original error for local diagnostics |

Section failures include `sectionIndex` when attributable. Initial points are matched within 1 km; the adapter disables BRouter's larger dynamic matching and beeline fallback so stops cannot silently jump across a large gap.

## Cache and stage planning

The cache keys include exact coordinates, profile/engine/data identities and planner configuration. Section keys omit distant stops and indices, so successful sections survive a later failure or a local stop edit. Time limits and daily stage targets do not change route identity. Split a `RouteResult` into daily stages in the app without calling the router again.

`FileRouteCache` uses atomic writes, payload checksums, a bounded binary reader, LRU eviction and a default 256 MiB total budget. Corruption becomes a cache miss; a full cache does not invalidate a successful calculation. Cache contents are disposable private data. Persist a user's saved trip separately if it must survive cache eviction.

## Reconnection

```kotlin
val recovered = PartialRerouter(router).reconnect(
    original = savedResult,
    current = currentLocation,
    profile = originalProfile,
    nextSegmentIndex = firstRemainingSegmentIndex,
)
```

The caller must supply the original profile and accurate ride progress. By default, only the first remaining section boundary is considered. The caller can explicitly extend `lastAllowedRejoinSegmentIndex` to try up to three forward boundaries within the connection radius, stopping before any mandatory visit. The new connector is routed normally; the remaining sections and metrics are reused exactly. Extending that range can skip intervening stops, so only extend it across optional boundaries. This API does not reconstruct missed mandatory visits.

If no boundary is close enough, offer full rerouting. Arbitrary point-level suffix clipping is not implemented because distance, ascent and duration must not be guessed from a geometry fraction.

## Export

`RouteExport.writeGpx(result, writer, name)` streams GPX 1.1 geometry and available elevation. The host owns and closes the UTF-8 writer. Export contains the user's route coordinates; share only through the host's explicit export flow.
