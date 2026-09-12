# Benchmarks

The CLI runs the **same offline runtime** used by the Android adapter. It produces JSON Lines, one row per cold/warm trial. A nonzero exit code means at least one trial failed. Printing the catalogue is not an acceptance test.

## Canonical routes

| ID | Route | Tier |
|---|---|---|
| `heidelberg-frankfurt` | Heidelberg → Frankfurt | smoke |
| `heidelberg-hamburg` | Heidelberg → Hamburg | regional |
| `heidelberg-copenhagen` | Heidelberg → Copenhagen | long |
| `heidelberg-oslo` | Heidelberg → Oslo | long |
| `heidelberg-barcelona` | Heidelberg → Barcelona | long |
| `lisbon-helsinki` | Lisbon → Helsinki | stress |

Coordinates live in `BenchmarkCatalog`; no geocoder is involved. All requests use the selected Direct, Touring or Bikepacking profile.

## Commands

```bash
./gradlew -PincludeAndroid=false :router-benchmark:installDist
router-benchmark/build/install/router-benchmark/bin/router-benchmark --catalogue
router-benchmark/build/install/router-benchmark/bin/router-benchmark \
  --root /path/to/routing-root --data-version your-manifest-id \
  --route heidelberg-oslo --profile TOURING --iterations 3 \
  --output benchmark-artifacts/results.jsonl
```

The root contains `segments4/*.rd5`; profiles are installed from the pinned engine resources. Normal benchmark execution never downloads maps.

For an explicit data-preparation run, add:

```text
--prepare true --source https://brouter.de/brouter/segments4/ --max-download-mb 10240
```

Preparation resolves remote sizes before downloading and refuses transfers above the advertised-size budget. Missing remote tiles are omitted from the estimate; a missing region needed by actual routing remains a typed failure. Record and retain `routing-data-manifest.jsonl` alongside the map files when comparing runs. Public upstream data changes over time; a label alone is not an immutable dataset. Each benchmark also hashes the exact set of installed routing files before timed trials.

Options use `--name value` pairs:

| Option | Default |
|---|---|
| `--root` | Required |
| `--data-version` | Required |
| `--route` | `heidelberg-frankfurt`; accepts `all` |
| `--profile` | `TOURING` |
| `--iterations` | `3`, allowed range 1–10 |
| `--output` | `benchmark-results.jsonl` |
| `--section-timeout-ms` | `90000` |
| `--route-timeout-ms` | `600000` |
| `--memory-mb` | `512` native search budget for desktop benchmarks |
| `--prepare` | `false` |
| `--max-download-mb` | `10240` |

The Android runtime defaults to 128 MiB per native search. Compare results at the same memory and time settings. Neither setting is a hard cap on total process RSS.

## What is measured

Every cold trial clears the persistent route cache. Its paired warm trial creates a new runtime instance and must reopen the same geometry from disk without routing. This verifies cache persistence; **it is not a fresh JVM or cold operating-system page cache**. Restart the CLI/process and control the OS cache separately for cold-start performance experiments.

Records include engine commit, profile version, dataset label and hash, planner version, time and memory budgets, route ID, iteration, cache state, distance, ascent, duration, section count, first-section timing, cache hits, engine calls, retries, skipped anchors, geometry checksum, total wall time, and a failure code/section when applicable.

`heap_used_bytes_after_run` is JVM heap usage sampled after a trial. It is **not peak heap or peak RSS**. Device memory and process-level measurements remain a separate acceptance requirement. Map preparation and initial dataset hashing are outside timed trials; runtime construction is included.

The first successful cold trial also writes a GPX track for inspecting geographic detours and continuity. Benchmark routes are fixed public city coordinates; no personal trip is uploaded by the runtime.

## CI and acceptance

Normal CI builds the CLI, runs deterministic tests with a locally generated `.rd5` fixture from the pinned BRouter source, exercises all three presets and persisted runtime reopen, and checks that an empty dataset creates failed JSON records. It does not download continent-scale map data.

The manually triggered **Router Benchmarks** workflow performs explicit data preparation and repeated cold/warm real-engine trials. It uploads measurements, GPX tracks and the downloaded data manifest even after a trial fails.

Before switching WAYLOAM's default router:

1. The canonical routes through Barcelona must complete repeatedly, particularly Oslo and Barcelona across fresh-process cold starts.
2. Geometry must be inspected for detours, barriers, endpoint matching and rider-selected stop preservation.
3. Active cancellation, missing/corrupt maps and warm offline reopen must be deterministic.
4. Lisbon → Helsinki must complete or return a bounded attributable error.
5. Routing must meet measured time/memory budgets on the supported Android device class.

Graph-verified anchors are a local heuristic. They do not establish globally optimal paths, worldwide coverage, ferry availability or state-of-the-art performance. Keep before/after artifacts for further routing optimizations.
