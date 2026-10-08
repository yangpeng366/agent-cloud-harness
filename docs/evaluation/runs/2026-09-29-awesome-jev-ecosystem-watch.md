# 2026-09-29 Awesome Jev Ecosystem Watch

## Scope

This read-only patrol checked the public `yibie/awesome-jev` ecosystem after the local mirror stopped at `bece57c`; no remote branch was fetched, no pull request was modified, and no ACH implementation or provider configuration changed.

## Verified observations

- GitHub Compare reports remote `main` at `fd4df757` (48 commits ahead of `bece57c`, 15 changed files); the public `Catalog checks` run for open PR #307 passed.
- `scripts/audit-tags.py` now fails when an `agent` value exists in `tags.json` without a matching `SUPPORT` rule, closing the prior fail-open vocabulary gap; `scripts/jev-ray.py` now keys cached answers by model as well as URL/state/questions.
- The three `dsh` entries are unchanged in the remote delta (only README index regeneration mentions them), and `categories/robotics-physical.md` is unchanged; the harness/model split and actuator-classification patterns have not gained a second independent ecosystem observation.
- `catalog-checks-comment.yml` intentionally comments only on failure, or resolves an existing failure report after a pass; PR #307 has a successful check and no earlier bot report, so its lack of comment is expected.

## Candidate, not adoption

- Open PR [#307](https://github.com/yibie/awesome-jev/pull/307) points to public MIT project [TetraJev](https://github.com/FeiLiuEM/tetrajev): two frozen local readers produce four deterministic readings, fit-free fusion and agreement routing auto-release only strict/unanimous decisions while split or low-confidence cases abstain to human review.
- Its own initial-release README claims single-24-GB-GPU serving and published coverage/precision tables, but it is a one-star project and the catalog PR remains unmerged; treat it only as a reproducibility candidate, not a Jev replacement or an ACH backend recommendation.
- Secondary watch: open PR [#305](https://github.com/yibie/awesome-jev/pull/305) describes `Jev Deep Research`, which parallelizes `Choice` source-line selection and `Noul` evidence checks in 20/40/60-document batches.

## Borrowing decision

Keep `TYPESAFE_API_KEY`-free fallback behavior unchanged. If a future local-decision backend experiment is authorized, require a common decision contract, explicit abstain-to-human path, model-qualified cache keys, and a held-out precision/coverage comparison before any route is enabled.

## Next check

Recheck #305/#307 merge state and their public repositories; promote only if the artifacts remain public and the same abstention/contract pattern appears in a second independent project. Recheck dsh and robotics deltas after the next remote main update.