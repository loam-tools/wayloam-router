# Routing profiles

WAYLOAM exposes three product profiles while BRouter remains the low-level routing engine.

The first implementation uses pinned upstream BRouter profiles plus explicit parameters. This is preferable to immediately maintaining three full `.brf` forks because upstream bug fixes remain easy to adopt.

## Direct

Base: `fastbike`

Initial parameter intent:

```text
allow_steps=false
allow_ferries=true
consider_traffic=0.3
consider_elevation=true
```

Use case: efficient paved bicycle travel when reaching the destination quickly matters more than maximizing quiet/scenic detours.

## Touring

Base: `trekking`

Initial parameter intent:

```text
allow_steps=false
allow_ferries=true
avoid_unsafe=true
consider_traffic=true
consider_elevation=true
```

Use case: long-distance road/cycleway touring with a stronger preference for practical and lower-stress bicycle infrastructure.

## Bikepacking

Base: `trekking`

Initial parameter intent:

```text
allow_steps=false
allow_ferries=true
avoid_unsafe=true
consider_traffic=true
consider_forest=true
consider_elevation=true
```

Use case: mixed-surface trips where tracks and rural routing are acceptable but unsafe roads and carrying a loaded bike up steps should be strongly discouraged.

## Profile versioning

Each preset has a deterministic version key containing:

- upstream BRouter version;
- base profile name;
- sorted parameter values.

That key participates in caching and benchmark records.

## Tuning rule

Do not tune profiles from a single visually pleasing route. Profile changes need a route corpus containing:

- dense urban exits;
- long cycleways;
- rural roads;
- gravel/tracks;
- steep terrain;
- ferries/bridges where relevant;
- known bad or unsafe segments.

A proposed profile change should compare route distance, elevation, surface/road classes and known qualitative failures before becoming the default.

## Future profile work

After the upstream adapter is wired, diagnostics should expose route-level road/surface tag summaries. That will let Loam Tools tune Bikepacking and Touring with evidence rather than using arbitrary penalties.
