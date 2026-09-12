# WAYLOAM Router architecture

## Objective

Provide WAYLOAM with deterministic long-distance bicycle routing that runs on the rider's device and does not depend on public routing-server capacity.

## Layers

```text
WAYLOAM
  │
  ▼
router-android
  │  Android files, lifecycle, future download scheduling
  ▼
router-api
  │  stable requests / results / progress
  ▼
router-core
  ├── section planning
  ├── cancellation
  ├── stitching
  ├── cache identity
  └── diagnostics contract
       │
       ├────────► router-data
       │           └── .rd5 discovery / availability / validation
       │
       └────────► router-brouter
                   └── narrow adapter around pinned BRouter core
```

## Why sections

A 1,500–3,000 km route should not be one opaque routing call. WAYLOAM Router decomposes the journey into independently cancellable/retryable sections, then combines their geometry and metrics.

Sections give us:

- progressive results;
- bounded failure domains;
- partial cache reuse;
- local rerouting without recalculating the remaining continent;
- useful diagnostics when one geographic area is problematic.

The bootstrap `FixedDistanceSectionPlanner` only limits great-circle span. It is intentionally not the final production anchor selector. Before app integration, intermediate anchors must be snapped/selected using routing-graph knowledge so a generated anchor cannot land in water or an inaccessible area.

## API boundary

`router-api` must not expose BRouter, Android, HTTP or filesystem implementation classes. WAYLOAM consumes only Loam-owned models.

That boundary lets us:

- upgrade BRouter without changing app screens;
- test route orchestration on the JVM;
- replace data-download/storage policy independently;
- benchmark fake and real section engines using the same coordinator.

## BRouter boundary

`router-brouter` owns the smallest possible adapter around upstream BRouter. The first baseline is BRouter `v1.7.10` at commit `4d2639af77ea5ed9c30d3e400764eb6f9e8522da`.

Do not spread upstream BRouter classes into other modules. If an upstream type is needed by `router-core` or WAYLOAM, translate it into a Loam-owned model at the adapter boundary.

## Route identity

A cached route is valid only for the same:

- start/end/via points;
- WAYLOAM route profile;
- section-planning settings;
- embedded engine version;
- profile version;
- routing-data version.

Changing stage targets in the app is intentionally **not** part of this key because stage splitting is a product-layer concern and should not recalculate the base route.

## Cancellation

Routing is coroutine-driven at the Loam API. The coordinator checks cancellation between sections. The embedded BRouter adapter must additionally map cancellation into the engine's own termination mechanism so an active section can stop quickly instead of only stopping before the next section.

## Progressive output

`RoutingEvent` exposes section-level progress. The Android app can render a growing route without waiting for the final section. The event contract is deliberately coarse in v0.1; node-level search telemetry belongs in diagnostics, not in the product API.

## Rerouting direction

Full-route recalculation is the fallback, not the first choice. Ride-mode rerouting should eventually calculate:

```text
current location → sensible reconnection point
```

then reuse the cached remaining route after that point.

## Non-goals for v0.1

- turn-by-turn voice UX;
- weather-aware costs;
- campground/resupply scoring inside the shortest-path engine;
- custom `.rd5` binary format;
- broad permanent fork of upstream BRouter.

Those can follow only after local long-distance routing is reliable and benchmarked.
