# Benchmarks

WAYLOAM Router is built for long routes, so a benchmark is part of the product contract rather than an optional micro-optimization exercise.

## Canonical routes

| ID | Route | Tier |
|---|---|---|
| `heidelberg-frankfurt` | Heidelberg → Frankfurt | smoke |
| `heidelberg-hamburg` | Heidelberg → Hamburg | regional |
| `heidelberg-copenhagen` | Heidelberg → Copenhagen | long |
| `heidelberg-oslo` | Heidelberg → Oslo | long |
| `heidelberg-barcelona` | Heidelberg → Barcelona | long |
| `lisbon-helsinki` | Lisbon → Helsinki | stress |

Coordinates are stored in `router-benchmark` so results are reproducible even if a geocoder changes.

## Required metadata

Every real-engine benchmark result should record:

```text
router version
BRouter version / commit
profile + profile version
routing-data version
route ID
cold or warm cache
section size/configuration
section count
route distance
route duration estimate
calculation wall time
first-section wall time
peak memory when available
result status / failure section
```

## Reliability gate

Before WAYLOAM switches its default router to this repository:

1. every canonical route through Heidelberg → Barcelona must complete repeatedly with the expected routing data installed;
2. Heidelberg → Oslo and Heidelberg → Barcelona must complete across repeated cold-start runs, not only after a successful warm cache;
3. cancellation must stop an active calculation without corrupting route/data cache;
4. deleting a required `.rd5` tile must produce a deterministic missing-data result rather than a generic no-route failure;
5. a successful cached route must reopen without running the engine again;
6. the stress route must either complete or fail with a bounded, attributable section/data error — never an indefinite spinner.

## Performance

The first embedded-engine milestone establishes a measured baseline before hard performance thresholds are frozen. We care about:

- time to first usable section;
- total cold-route time;
- warm-cache reopen time;
- memory on a supported Android device;
- how runtime scales with section length.

Optimization work should include before/after benchmark artifacts. A faster result that changes route semantics unexpectedly is not automatically accepted.

## CI strategy

Normal pull-request CI runs deterministic unit tests and prints the benchmark catalogue. Real `.rd5` benchmarks belong in a separate workflow/artifact path because routing datasets are large and should not be fetched on every trivial PR.

A later benchmark workflow will use a versioned small test dataset for smoke/regression checks and scheduled device/server benchmarks for the long corpus.
