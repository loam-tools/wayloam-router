# BRouter upstream policy

WAYLOAM Router starts from BRouter `v1.7.10`, commit `4d2639af77ea5ed9c30d3e400764eb6f9e8522da`.

## Rule

Keep Loam-specific behavior outside upstream BRouter source whenever possible.

Prefer this order:

1. use an upstream profile parameter;
2. implement behavior in `router-core`, `router-data` or the adapter;
3. contribute a generally useful fix upstream;
4. patch upstream source locally only when the first three options cannot solve the problem.

## Updating BRouter

For an upstream bump:

1. record the new tag and exact commit;
2. review upstream release notes and routing/profile/data-format changes;
3. update third-party notices if required;
4. regenerate/review profile presets against the new profile definitions;
5. run unit tests;
6. run the canonical route benchmark corpus with the old and new engines;
7. compare route metrics/road classes, not just wall time;
8. update `BRouterBaseline` only after the comparison is accepted.

## Local patches

Any local patch to vendored BRouter source must be isolated and documented with:

- reason;
- upstream file/commit;
- whether an upstream issue/PR exists;
- benchmark or regression test protecting the change.

Avoid mass reformatting vendored code because it makes future upstream diffs unnecessarily difficult.
