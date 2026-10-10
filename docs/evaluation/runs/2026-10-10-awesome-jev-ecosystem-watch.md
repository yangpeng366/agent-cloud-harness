# 2026-10-10 Awesome Jev Ecosystem Watch

## Scope

This read-only patrol checked the public `yibie/awesome-jev` ecosystem after the local mirror stopped at `7fce0f1` (round 12 writeback on 2026-10-09). Local was unable to advance because `git fetch` to `github.com:443` timed out twice during the round; the round is recorded from API-only diffs against the public `main` at `39ac115` and against the local `7fce0f1`.

## Verified observations

- GitHub Compare reports remote `main` at `39ac115` (18 commits ahead of `7fce0f1`; 8 changed files). Local could not be advanced this round due to a network timeout to `github.com:443`; the next round will retry the fetch.
- New `scripts/maintainer/merge-prs.sh` (132 lines) plus a 6-line SKILL update record the maintainer's standard for relocating a README-only entry back to the right `categories/<name>.md` file. This is a maintenance workflow signal worth noting: the catalog has an explicit cleanup path for orphaned entries.
- New `dsh-jev-plugin` entry into `categories/scoring-ranking.md` (`d90f4ec`): the `dsh` axis continues to accumulate fourth-or-later independent samples since the 09-28 round. The harness/model discriminator now has enough material to anchor an awareness note if and when the operator wants one.
- `RSI-Jev` bumped twice in 24 hours: `v6.1-VL 4B and 27B` on 2026-10-09, and a re-version on 2026-10-03. The volatility matches the pattern flagged in the prior round: treat only the latest line as the canonical claim.
- `34 passed` offline `pytest` and `audit-tags.py` still clean against the local `7fce0f1` tree; README regeneration produces zero drift locally.
- `tags.json` did not change in this delta; the `openclaw` vocabulary from round #2 remains the newest agent value.

## Candidate follow-ups

| Name | Source | Why watch |
|---|---|---|
| `System-One Control Bench` | `d4f760c` (2026-10-08) | First benchmark entry that names the `System-One Control` surface; the title is broad enough that a single read is warranted before any borrow. |
| `Bud-Decision-Studio` | `e7a2d9a` (2026-10-09) | README-only entry relocated by the maintainer; the cleanup commit `3b43f4f` shows the relocation contract. |
| `dsh-jev-plugin` | `d90f4ec` (2026-10-09) | Independent fourth `dsh` instance; if the operator wants to write a harness/model awareness note, the count is now enough. |
| `RSI-Jev v6.1-VL 4B/27B` | `f112ef2` (2026-10-09) | RSI-Jev line continues to move; treat the latest as canonical, ignore prior versions. |

## Borrowing decision

- Keep `TYPESAFE_API_KEY`-free fallback behavior unchanged. The new `dsh-jev-plugin` and the maintainer's relocation workflow reinforce the operator's existing choices rather than replacing them.
- No new entry warrants an immediate ACH/OpenEyes change this round; reading the merged descriptions of PR `#353` (Exemocaro) and PR `#356` (dittops) is queued for the next round.

## Operational note

The local mirror could not be advanced because `git fetch` to `github.com:443` timed out twice (21 s each). The next round will retry; if the network is still down, the round will fall back to API-only and skip the local checksum re-run.

## Next check

Recheck after the next remote `main` push; retry the local fetch and re-run the offline 34-test suite + audit + README build when the network is available.