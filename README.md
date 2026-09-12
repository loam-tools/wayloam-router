# WAYLOAM Router

Private routing-engine project for **WAYLOAM**, maintained by **Loam Tools**.

WAYLOAM Router is being built as an embedded, offline-first long-distance bicycle router for Android. The goal is to remove continent-scale trip planning from public demo routing services and make routing deterministic, cancellable, cacheable and measurable on the rider's device.

## Status

**v0.1 bootstrap — not yet used by the WAYLOAM app.**

The repository currently establishes the API, long-route coordinator, `.rd5` routing-data model, BRouter integration boundary, Android adapter, benchmark catalogue and CI. The embedded BRouter core will be integrated behind that boundary before this engine becomes WAYLOAM's default.

## Upstream baseline

The initial engine baseline is pinned to:

- upstream: `abrensch/brouter`
- release: `v1.7.10`
- commit: `4d2639af77ea5ed9c30d3e400764eb6f9e8522da`
- license: MIT

Upstream BRouter code is not copied into this repository yet. When vendored/extracted, its MIT license and notices must remain intact.

## Architecture

```text
WAYLOAM Android
      │
      ▼
 router-android
      │
      ▼
  router-api
      │
      ▼
 router-core ───────────────┐
      │                     │
      ├── long-route        ├── route cache
      │   coordinator       └── progress / diagnostics
      │
      ├─────────────► router-data ───► .rd5 tiles
      │
      └─────────────► router-brouter ─► embedded BRouter core

router-benchmark ─► reproducible long-distance benchmark catalogue
```

## Modules

| Module | Responsibility |
|---|---|
| `router-api` | Stable request/result/progress model exposed to WAYLOAM |
| `router-core` | Long-route coordination, section planning, stitching and cache contracts |
| `router-data` | BRouter `.rd5` tile identifiers, local availability and future download/validation |
| `router-brouter` | Thin adapter around the pinned embedded BRouter engine |
| `router-android` | Android storage/lifecycle integration without leaking Android into the core API |
| `router-benchmark` | Benchmark routes, runner and machine-readable results |

## Routing profiles

The first three WAYLOAM presets are intentionally based on upstream BRouter profiles rather than permanent forks:

- **Direct** — `fastbike` baseline; paved/efficient bicycle travel.
- **Touring** — `trekking` baseline; quiet roads, cycleways and practical long-distance travel.
- **Bikepacking** — `trekking` baseline with stricter unsafe-road/step handling and later surface-aware tuning.

Profile parameters and the upstream profile version are part of the route-cache key so behavior stays reproducible.

## Long-distance rule

A continent-scale request is never treated as one opaque operation. The coordinator plans independently retryable sections, emits progress after every completed section, stitches geometry/metrics, and caches finished work. Network-aware anchor selection will replace the bootstrap geometric planner before app integration.

## Integration gate

WAYLOAM Router will not replace the current online stack until it can reliably complete and reproduce at least:

- Heidelberg → Frankfurt
- Heidelberg → Hamburg
- Heidelberg → Copenhagen
- Heidelberg → Oslo
- Heidelberg → Barcelona
- Lisbon → Helsinki (stress route)

For each route we record duration, distance, section count, cold/warm cache state, routing-data version and memory when available.

## Build

Requirements:

- JDK 17
- Gradle 9.6+
- Android SDK 36 for `router-android`

```bash
gradle test
```

The benchmark catalogue can be inspected with:

```bash
gradle :router-benchmark:run
```

## Related repositories

- `loam-tools/wayloam` — Android app
- `loam-tools/wayloam-releases` — public alpha APKs/update manifests

This repository is private. Third-party code retains its original licensing; see `THIRD_PARTY_NOTICES.md`.
