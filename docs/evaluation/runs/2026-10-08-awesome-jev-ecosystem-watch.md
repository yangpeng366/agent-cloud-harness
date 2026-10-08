# 2026-10-08 Awesome Jev Ecosystem Watch

## Scope

This read-only patrol checked the public `yibie/awesome-jev` ecosystem after the local mirror stopped at `bece57c` (round 11 writeback on 2026-09-29). Local advanced `bece57c -> 0839b92` via `merge --ff-only`; no local fork, no force push.

## Verified observations

- GitHub Compare reports remote `main` at `0839b926` (101 commits ahead of `fd4df757`, the previous-round snapshot); 17 changed files across `categories/`, `README.md`, `tags.json`, `scripts/`, `.github/workflows/catalog-checks.yml`, `CONTRIBUTING.md`.
- Both watch-list PRs are now MERGED on 2026-09-30: `PR #307 TetraJev` (previous-round "candidate, not adoption") and `PR #305 Jev Deep Research`. The next round should re-read the merged descriptions; the borrow is binding.
- `tags.json` gained the `openclaw` agent vocabulary in `21ad9609` (`feat(tags): add openclaw to the agent vocabulary #315`). This is directly relevant to ACH because the operator's local harness on Sobey X240 is OpenClaw.
- Two new `openclaw`-tagged entries: `openclaw-jev-leakguard` (Verification & Guardrails) and `openclaw-jev-trigger` (Agent Decisions). Both deserve a follow-up read before being treated as borrowable.
- `scripts/tests/` grew from 26 to 34 passing tests; the offline `audit-tags.py` and `jev-ray.py` paths still produce zero unknown tags and zero audit notices against the rewritten `tags.json`.
- `build-readme.py` regenerates `README.md` and `CONTRIBUTING.md` to byte-identity with what is committed (`git status --clean` after regeneration, no drift).
- `dsh` and `robotics-physical` categories still have no second independent observation in this delta; the harness/model split and actuator-classification patterns remain single-cluster.

## Candidate follow-ups

| Name | Source | Why watch |
|---|---|---|
| `Jev PowerShell module` | `dfinke/awesome-jev-yibie` #314 (merged 2026-09-30) | First PowerShell-native client of the Jev API; worth a single source review before treating it as if it were reusable from Windows. |
| `Bekko System One` | `hotchpotch/awesome-jev-yibie` #323 (merged 2026-10-01) | Another System One implementation; read description and bias note. |
| `JevRouter` | `BillionsBobby/awesome-jev` #328 (merged 2026-10-01) | Routing layer that names Jev; same mergeable rules as the existing `Harness Router`. |
| `GoEventBus` | merge of PR #342 | Event-bus integration; treat as SDK class, not as a candidate local backend. |
| `jev-judge` | merge of PR #343 (merged 2026-10-05) | Sub-100ms CI/CD evaluation; payload size and gate threshold warrant a closer read. |
| `Rot Guard` | merge of PR #344 (merged 2026-10-06) | First Content Moderation class entry; worth a category-promotion note. |
| `RSI-Jev v5.0-VL 3B` | sweep 2026-10-03 | Repeated RSI-Jev version updates indicate this entry is volatile; treat the latest line as the canonical claim. |
| `TetraJev` (merged as PR #307) | `categories/classification-routing.md` | Previous-round "candidate" is now canonical; review the merged description and its specific claim (DecisionBench bench-v4 etc.) before borrowing. |

## Borrowing decision

- Keep `TYPESAFE_API_KEY`-free fallback behavior unchanged. The new `openclaw` tag and the two `openclaw`-tagged entries reinforce the operator's pre-existing OpenClaw choice rather than replacing it.
- Reclassification of `TetraJev` from candidate to merged entry happens in the next round, not this one, because the upstream merged description is the new evidence.

## Next check

Recheck after the next remote `main` push; re-read the merged descriptions of PR #305, PR #307, and the two `openclaw`-tagged entries before treating them as borrowable.