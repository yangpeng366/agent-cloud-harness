# 2026-10-24 Awesome Jev Ecosystem Watch

## Scope

Read-only patrol of the public `yibie/awesome-jev` catalogue plus a weekly metric snapshot. The local mirror `D:\gitAll\awesome-jev` advanced from `7d8405c` (2026-10-21 round) to `ddbda70` (remote `main`, pushed 2026-10-24T00:11Z) — 18 upstream commits. The advance used SSH-over-443 (`ssh://git@ssh.github.com:443/yibie/awesome-jev.git`); `github.com:443` is unreachable from this host, and `FETCH_HEAD` was cross-checked against the GitHub API's reported `main` sha before the ff-only merge.

## Verified observations

- Local mirror is now identical to upstream `main`: `main == origin/main == ddbda70`, ff-only merge clean, working tree clean.
- Offline suite on the new tip: `pytest scripts/tests` 34 passed; `scripts/audit-tags.py` 0 notice(s); `scripts/build-readme.py` followed by `git diff --quiet` gives zero drift.
- Catalogue grew 640 -> 656 entries across the 15 seeded categories (Scientific Pipelines still 0). 17 new entries landed, among them `opencode-jev-safe-compact`, `solvi`, `SpotAsk`, `ReadyCode Reader`, `ClauJev`, `jevPipe`, `stalwart-jev`, the two `Trio-Spark` demos, `Jev grievance triage`, `Jev Moderation for Laravel`, `Jev for industrial automation`, `minimind-jev`, `Photo Ferret`.
- 5 PRs merged in the window: `#381` (Jag), `#382` (Trio-Spark demos), `#384` (opencode-jev-safe-compact), `#385` (SpotAsk), `#386` (solvi).
- **Star/fork snapshot (2026-10-24)**: `yibie/awesome-jev` 2454 stars / 372 forks / 1 open issue + 3 open PRs. `browser-use/jev-ultrafast` **23546 stars / 1778 forks**, 4437 KB Python, MIT, last push 2026-09-30, 207 open issues. The tracking row still carried `13.6k star / 838 fork / 2.3MB Python` — that snapshot is 9.9k stars, 940 forks and 2.1 MB stale, and is corrected this round.
- **Open-PR cluster**: `#387 / #388 / #389` (jeff-act, jevquery, jeff-ink) all come from `choice404/awesome-jev`, created within 5 seconds of one another on 2026-10-23 20:31Z. Under CONTRIBUTING rule 4 (bulk drops count as one submission, merge at most one per author per pass) and the upstream farm-blacklist rule, at most one of the three should be merged and the rest queued. Read-only observation; no comment was posted.
- Open issue `#321` ("Inclusion question: Trio-Spark, an independent typed-decision model", 3 comments) is still open even though `#382` merged Trio-Spark demos into the catalogue. Worth one maintainer look.
- `browser-use/jev-ultrafast` open issues: `#227` date inputs are never enumerated (2026-10-21), `#225` / `#224` HTTP 451 on "Choose next" (duplicate EN/CN pair, 2026-10-19), `#223` request for an opt-in keep_open mode.
- Upstream skill absorbed three new rules this window: (1) farm blacklist — the same description appearing 3 or more times within 7 days rejects the whole class, and "same description" is more reliable than "same account"; (2) count entries from the README's own `## Current coverage` lines because the awesome-jev generator prints nothing on success; (3) `\bAI\b` also matches `aim`, so AI-participation counts need a manual look at the hit lines. A supply-chain gate arrived with it (from upstream issue `#14`): a listed repo whose tree commits `.zip / .exe / .msi / .dll / .bat / .ps1 / .scr` blobs is treated as a possible lure and must be checked before listing.

## Borrowing decision

1. **`opencode-jev-safe-compact` -> FEAT-03 threshold design (landed this round).** The upstream entry states that a `Noul` score drifts with the shape of the state while its AUC holds at 0.70-0.83, and that the plugin therefore prunes "from the ranking" instead of from an absolute threshold. That is direct evidence against the absolute `keepThreshold = 0.5` still open in `docs/JEV_CONTEXT_SCORING_PLAN.md` §11 item 4 (and against the same absolute threshold inherited from `fast-jev-compaction`). Recorded as evidence under that checklist item; the checklist semantics are unchanged and still need maintainer sign-off. Caveat: the repo is brand new (0 stars, MIT, TypeScript, pushed 2026-10-22) — a lead, not proof.
2. **`solvi` -> decision trace (candidate, not adopted).** Its pipeline is hard checks -> calibrated confidence thresholds -> human hand-off, with every decision stored as a hash-chained replayable trace. The first three steps match the existing "deterministic policy before judgment" rule; hash-chaining `runtime_facts.jev_*` / `judgment_trace` is a concrete upgrade but is not adopted this round.
3. **`ClauJev` -> Verifier role (corroboration).** Claude does the work and Jev checks it before the change lands, keeping request, verdict and probability per check. Third independent confirmation of the Router / Tool Gate / Verifier split already used in this repo.
4. **Upstream farm rule -> our own sweep screen (adopted as process).** Before proposing any entry, check for the same description 3+ times in 7 days and treat the class as a whole.
5. **Upstream supply-chain gate -> our own pre-listing check (adopted as process).** `gh api "repos/OWNER/REPO/git/trees/HEAD?recursive=1"` filtered on those binary extensions must return 0 for a repo to be listed.
6. **Upstream counting rule -> our snapshot method (adopted).** Entry totals are read from the README `## Current coverage` lines (656), not from a regex over the whole README.

## Landing in this repo

- `docs/evaluation/runs/2026-10-24-awesome-jev-ecosystem-watch.md` — this note.
- `docs/evaluation/runs/README.md` — new dated entry in the Jev Patrol group.
- `docs/evaluation/PROGRESS.md` — one-line result under 已完成.
- `docs/JEV_CONTEXT_SCORING_PLAN.md` — upstream-evidence note attached to §11 item 4 (no threshold change, no checklist change).

## Operational note

`github.com:443` is unreachable from this host: plain `git fetch` timed out after 21 s, `ghproxy.com` reset the connection after 195 s, and `curl --resolve` to every tested GitHub edge address timed out. `api.github.com` and `ssh.github.com:443` do answer. The working route is SSH-over-443 with the existing local key (`git@ssh.github.com` authenticates as `yangpeng366`); the fetched tip sha matched the API-reported `main` sha, so the object graph is GitHub-native and verified. The first successful fetch through this route took 7.6 s.

## Next check

Re-read the merged description of `opencode-jev-safe-compact` and its three reproducible `/v1/systemone` scripts against the FEAT-03 real-key path, which is still blocked on a missing `TYPESAFE_API_KEY`; recheck `yibie/awesome-jev` stars/forks and the `choice404` PR cluster outcome; keep the ff-only + 34-test + audit + README-drift routine.