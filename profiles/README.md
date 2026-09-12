# WAYLOAM routing profiles

WAYLOAM product profiles are currently represented as **presets over pinned upstream BRouter profiles** rather than copied `.brf` forks.

The executable preset definitions live in `router-brouter` (`WayloamProfiles`).

| WAYLOAM profile | BRouter base |
|---|---|
| Direct | `fastbike` |
| Touring | `trekking` |
| Bikepacking | `trekking` |

When the embedded core is vendored, the exact upstream base profiles from the pinned BRouter release must be bundled and covered by `THIRD_PARTY_NOTICES.md`.

If a future requirement cannot be represented cleanly through upstream profile parameters, add a Loam-owned `.brf` variant here and document exactly why the fork is necessary.
