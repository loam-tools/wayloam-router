# Routing data

WAYLOAM Router uses BRouter `.rd5` routing segments. Upstream BRouter divides routing data into 5° × 5° latitude/longitude tiles.

## Storage

The Android adapter uses app-private storage:

```text
no_backup/wayloam-router/
├── segments4/   .rd5 routing tiles
├── profiles2/   pinned BRouter profiles
└── cache/       Loam route cache / metadata
```

No shared-storage permission should be required for normal routing data.

## Tile naming

A tile is named from its south-west corner. Examples:

- Heidelberg → `E5_N45.rd5`
- Oslo → `E10_N55.rd5`
- Lisbon → `W10_N35.rd5`

`router-data` owns this calculation so the app never needs to understand BRouter filenames.

## Download manager requirements

The verified data manager implements:

1. resolve required/missing tiles;
2. download into a temporary file;
3. validate HTTP status and expected metadata;
4. verify integrity when a checksum manifest is available;
5. atomically move the completed file into `segments4/`;
6. never expose a partially downloaded `.rd5` file to the engine;
7. persist source/data version and download timestamp;
8. allow unused regions to be removed without touching active route cache unexpectedly.

## Versioning

BRouter routing data and lookup/profile definitions can evolve together. WAYLOAM must therefore record enough metadata to reject incompatible combinations instead of interpreting a routing failure as “no route”.

The route-cache key includes a `dataVersion`. A routing-data update must not silently reuse a route calculated against a different data version unless we explicitly decide that compatibility is safe.

## Initial coverage strategy

The envelope resolver is conservative and selects the shortest longitude arc across the dateline: it returns all tiles intersecting the bounding box of supplied points. This is intentionally simple and can over-download for diagonal continent-scale routes.

The production strategy should be corridor-based:

```text
planned/snap-aware anchors
      ↓
small geographic corridor
      ↓
intersecting .rd5 tiles + safety margin
```

Future diagnostics should record which `.rd5` files were actually opened. That evidence can drive a tighter tile prefetch algorithm.

## Data source

BRouter publishes planet-wide segment files and documents building `.rd5` files from OpenStreetMap/Geofabrik data. WAYLOAM Router should keep the source configurable rather than hardcoding a single host into core routing logic.


See [runtime integration](INTEGRATION.md) for source-version tracking, failed-refresh preservation, local checksum limitations, explicit download planning and serialization with active routing.
