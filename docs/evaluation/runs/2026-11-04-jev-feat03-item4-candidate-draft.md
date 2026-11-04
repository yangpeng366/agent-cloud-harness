# 2026-11-04 FEAT-03 §11 Item 4 — Candidate C Draft + Weekly Jev Ecosystem Snapshot

## Scope

Three things, in this order:

1. Draft the third option for FEAT-03 plan §11 item 4 in the shape a maintainer can actually sign off — proposed checklist wording, table structure, and the open decision points.
2. Run the offline shape experiment that answers the question the 2026-11-01 note left open: can the s1kit rule be copied at all, given our own call shape?
3. The weekly star/fork + issue snapshot, and the standing question "has `typesafe-ai/skills` published anything after v0.6.2?".

Nothing was implemented: no code, no config, no checklist wording changed. The draft lives in the plan as a proposal with a decision table attached.

## 1. Weekly snapshot (2026-11-04, GitHub REST API)

| repo | stars | forks | open issues | open PRs | default branch head | tags / releases |
|---|---|---|---|---|---|---|
| `yibie/awesome-jev` | 2536 | 373 | 0 | 1 (#398 *Update mu: 40 decision points*, qybaihe) | `51906421` (2026-10-31, unchanged) | — |
| `browser-use/jev-ultrafast` | 24150 | 1853 | 53 | 158 | `8251e272` (2026-10-31, README-only) | no tags, no release objects |
| `typesafe-ai/skills` | 2875 | 184 | 0 | 0 | `302eea66` (2026-10-23) | `v0.6.2`, `v0.5.7`; no release objects |

Delta against the 2026-10-31 round: awesome-jev 2515→2536★ / 374→373 fork (one fork was removed), main unchanged, so the catalogue is still **679 entries / 15 categories**. jev-ultrafast 23964→24150★ / 1836→1853 fork, still **no tag and no release**; the code line has not moved since 2026-09-18, the only commit on `main` after that is the README-only BU Ultrafast GA commit from 2026-10-31. Its 11 branches are 7 `codex/*` experiment branches, 2 `dependabot/*`, one `readme-bu-ultrafast-ga`, and `main`.

The mirror stayed put: `git fetch ssh://git@ssh.github.com:443/yibie/awesome-jev.git main` took 4.9 s and returned `519064216c249d38a8cf1ee63bd7c2a6b1c5d323` — byte-identical to the `main` sha the API reports. Local `main` == `origin/main`, working tree clean. The usual four-piece suite is green: `pytest scripts/tests` → **34 passed**; `audit-tags.py` → **0 notices**; `build-readme.py` → **zero README drift**.

## 2. Has `typesafe-ai/skills` moved past v0.6.2? — No. But the *docs* surface moved, and the old "21 cookbooks" phrasing was not measurable

The pinned repo has not moved: `main` is still `302eea66` ("Release v0.6.2", 2026-10-23), the only other tag is `v0.5.7`, there are **no commits after the release commit**, and GitHub has **no release objects** for the repo. The whole tree is 9 entries, of which 2 are markdown (`README.md`, `skills/typesafe-ai/SKILL.md`).

What did change is the surface those files point at, and the older rounds' "21 official cookbooks, unchanged" was not a number anyone could reproduce. Measured properly:

- `skills/typesafe-ai/SKILL.md` (v0.6.2) links **11** cookbook pages and **3** pattern pages (`composite-scoring`, `fan-out`, `intent-routing`).
- `https://docs.typesafe.ai/llms.txt` — the documentation index the skill itself points at — lists **25** unique cookbook pages.
- **14** of those 25 are not linked from the pinned skill at all: `basic_memory`, `cart_from_primitives`, `classification_using_confidence`, `consistency_choice_cookbook`, `consistency_noul_cookbook`, `entity_alignment`, `feeder_watch`, `hierarchical_classification`, `intelliprompter`, `llm_guardrails`, `ocr_confidence_bounding_boxes`, `reading_scanned_documents`, `skill_suggestion`, `twitch_chat_filter`.

Two of those 14 are already cited by our own plan (§1.1a: `llm_guardrails` for the guardrail `ThresholdDict`, `skill_suggestion` for `scoreRankingTopK`). So the pinned tag is only an index, not the source of truth for the cookbook text — the docs index is. Worth keeping in mind for the "官方口径" question, and worth replacing the old vague count with the measurable one (25 pages in `llms.txt`, 11 linked from the pinned skill).

## 3. Upstream signals read this round

- **`browser-use/jev-ultrafast` PR #232** (2026-10-25, R3-da, **open, not merged**) moves `choose()` off the hardcoded hosted endpoint onto an env var: `TYPESAFE_BASE_URL` (base includes the version segment, matching `TEXT_MODEL_BASE_URL`; unset/blank/whitespace falls back to the hosted endpoint), with the OpenRouter route written out as `TYPESAFE_BASE_URL=https://openrouter.ai/api/v1` + `TYPESAFE_MODEL=~typesafe/jev-latest`. #175 proposes the same variable on another branch. This is the same direction as our §11 item 2 (`baseUrl` configurable, OpenRouter proxy) — and our code already got there first: `JevContextScorerConfig.enabledDefaults()` reads `TYPESAFE_BASE_URL`, defaulting to `https://api.typesafe.ai`.
- **`parallel_questions` cookbook** (docs.typesafe.ai) states that batching 13 questions into one call is **12.2x cheaper and 10.0x faster** with no change in answers, and — the part that matters for item 4 — that "each question is scored on its own against the document, so its answer doesn't depend on what else is in the request". Per-question independence is an official property, so a per-question threshold table is not a local invention.
- **`confidence-routing` pattern** still says the same thing our §1.1a absorbed (0.6 floor, 0.85 for high-stakes actions). No change.
- **`yibie/awesome-jev` PR #398** is open (read-only; no comment, no vote).

## 4. Correction to the 2026-11-01 premise — it does not hold in our code

The 2026-11-01 note argued that s1kit's whole-batch escalation would cost us, because "our judgment layer asks several questions per call (Router / Tool Gate / Verifier)". In the code as it stands that is **not true**:

- `JevContextScorer.decideOneHttp` builds a `questions` map with exactly **one** key (`item.id()`) and one fixed instruction ("Is this content relevant and concrete enough to keep verbatim in context? ...").
- `JevContextScorer.decideAll` loops over items and issues **one HTTP call per item**.
- `JevPrefilteredJudgmentService.tryPrefilter` builds exactly **one** item (`judgment:<taskId>`, source `judgment`) and calls `decide` once.

So there is no multi-question call today, the aggregate rule has nothing to aggregate, and the s1kit "whole-batch OR" pathology cannot bite our current implementation. What *is* real today is the difference in **where an un-calibrated question goes** (next tier vs confirm channel), which is decision point D1 below.

The premise becomes live the moment we adopt the official `parallel_questions` shape (13 questions of three answer types in one call, 12.2x cheaper). That is exactly why rule (d) in the draft — judge per question, aggregate the strictest, never whole-batch OR — is a precondition for adopting batching, not a stylistic preference.

## 5. Shape experiment (offline, deterministic, reproducible)

Offline script, no network and no API key. It replays the cutoff map recorded from `s1kit calibrate` on 2026-11-01 (provider `cheap`: `team` 0.71, `urgent` 0.70, `mood` null, `rare` null) over 20 synthetic confidences per question, and compares three policies. Cost weights are assumed, not measured: cheap = 1, strong = 4, confirm = 2.

```python
GLOBAL_KEEP = 0.50
COST = {"cheap": 1, "strong": 4, "confirm": 2}
CUTOFFS = {"team": 0.71, "urgent": 0.70, "mood": None, "rare": None}
# 20 synthetic confidences per question (team above 0.71, urgent mostly below 0.70,
# mood straddling 0.5, rare all below 0.5) -- full arrays in the transcript.

def judge(q, conf, mode):
    cutoff = CUTOFFS[q]
    if cutoff is not None:
        return "cheap" if conf >= cutoff else "strong"
    if mode == "always_escalate":
        return "confirm"
    if mode == "global_fallback":
        return "cheap" if conf >= GLOBAL_KEEP else "strong"
    return "strong"   # s1kit single-question semantics

def whole_batch():
    # s1kit Router._confident: every question must have a cutoff AND clear it,
    # otherwise the WHOLE call escalates; the chain tail is accepted unconditionally.
    for i in range(N):
        ok = all(CUTOFFS[q] is not None and CONFIDENCES[q][i] >= CUTOFFS[q] for q in QUESTIONS)
        ...
```

Result (80 question-decisions per scenario):

```
[TODAY] one question per HTTP call -> the aggregate rule is moot; only the
        escalation target of an UNCALIBRATED question differs:
  s1kit single-question (no cutoff -> strong)    cheap=20  strong=60  confirm=0   cost=260  | team=18/2/0, urgent=2/18/0, mood=0/20/0, rare=0/20/0
  candidate C / D1-B (no cutoff -> confirm)      cheap=20  strong=20  confirm=40  cost=180  | team=18/2/0, urgent=2/18/0, mood=0/0/20, rare=0/0/20
  candidate C / D1-A (no cutoff -> global 0.50)  cheap=28  strong=52  confirm=0   cost=236  | team=18/2/0, urgent=2/18/0, mood=8/12/0, rare=0/20/0

[PROSPECTIVE] one call carries all 4 questions -> the aggregate rule bites:
  P0 s1kit whole-batch OR                        cheap=0   strong=80  confirm=0   cost=320  | team=0/20/0, urgent=0/20/0, mood=0/20/0, rare=0/20/0
  P1 per-question, uncalibrated -> confirm       cheap=20  strong=20  confirm=40  cost=180  | team=18/2/0, urgent=2/18/0, mood=0/0/20, rare=0/0/20
  P2 per-question, uncalibrated -> global 0.50   cheap=28  strong=52  confirm=0   cost=236  | team=18/2/0, urgent=2/18/0, mood=8/12/0, rare=0/20/0
```

What it does and does not show:

- **Robust claim**: under whole-batch OR, one un-calibrated question drags **all 80** question-decisions to the expensive tier (`cheap=0`), while per-question judging keeps the calibratable questions on the cheap path (20 or 28 of 80). This is the reason the draft forbids whole-batch OR.
- **Not robust**: the D1-A vs D1-B cost order (236 vs 180) depends on the assumed confirm/strong cost ratio; flip the ratio and the order flips. The argument for D1-A must therefore rest on the fallback contract, not on the cost number.
- **D1-A's hidden price**: D1-A answers 8 of the 20 `mood` items from the cheap provider using an *un-calibrated* global threshold — that is precisely the precision guarantee the calibration was meant to buy. D1-B answers none of them and puts all 40 `mood`/`rare` items on the confirm channel.
- **Today vs prospective**: under today's one-question-per-call shape, all three policies collapse to the same per-question behaviour; only the escalation target of an un-calibrated question differs (strong vs confirm). The aggregate rule matters only once batching arrives.

## 6. The draft that landed in the plan

`docs/JEV_CONTEXT_SCORING_PLAN.md` §11 now carries a `2026-11-04 候选 C 草案` block, appended after the existing 2026-11-01 evidence note, containing:

- the three-way candidate table for item 4 (A absolute threshold / B ranking + AUC / C per-question fitted cutoff), with the upstream basis of each;
- the **proposed wording** of item 4-C (five clauses: table key, hit/miss semantics, `alwaysEscalate` handling, per-question judgement with strictest aggregate, and the "calibration artifact, not policy" placement rule);
- the **table structure** in three forms — a human-readable markdown table, the machine-readable YAML shape, and a per-question decision table mapping each state to a destination and its effect on the call-level verdict;
- the **decision points D1-D5** with a recommended default each: D1 un-calibrated question → fall back to the global threshold (keeps the §3 fallback contract) vs go straight to confirm; D2 `alwaysEscalate` → confirm channel vs human; D3 where the table lives → same file as the config (keeps item 9) vs external YAML; D4 primary key → **instruction + source tag** vs instruction only (our `JevRequestItem.source` is the axis that actually varies today: `judgment` / `panel` / `tool_call` / `tool_result` / `user_msg`); D5 relation to the §1.1a three-tier thresholds → fitted table wins, three tiers remain the fallback;
- the list of places that would need a second look if C is adopted (§1.1, §1.1a, §3, §4, §5, §9).

The block is explicitly labelled as a proposal: the current wording of item 4, the threshold values, and the checklist semantics are untouched.

## 7. Two corrections to the plan document

- **§11 status drift**: the plan still reads as "maintainer must sign off before anything starts", while `docs/INITIATIVES/FEAT-03.md` §14 records the 2026-09-21 sign-off and the card's current status is *验收通过*. Candidate C is therefore a **change proposal**, not a missing signature. The block says so in its first paragraph rather than editing the checklist.
- **§1.1 typo**: `baseUrl` default was written `"https://api.typesafeai.com"` (missing dot). Corrected to `"https://api.typesafe.ai"`, matching `JevContextScorerConfig.DEFAULT_BASE_URL` and the FEAT-03 §14 record. No threshold or semantics touched.

## 8. Landing in this repo

- `docs/evaluation/runs/2026-11-04-jev-feat03-item4-candidate-draft.md` — this note.
- `docs/JEV_CONTEXT_SCORING_PLAN.md` — §11 candidate C block (proposal + table structure + D1-D5); §1.1 `baseUrl` typo corrected. No threshold, checklist or config change.
- `docs/evaluation/runs/README.md` — dated entry in the Jev Patrol group.
- `docs/evaluation/PROGRESS.md` — one-line result.

No `src/` file was created or touched; `JevContextScorerConfig` is unchanged.

## 9. Next check

- Maintainer decision needed on D1-D5; once picked, the table format can be frozen and only then does `JevContextScorerConfig` become a candidate for an edit (constant edit under code review, item 9).
- If the official `parallel_questions` batching shape is adopted in Step 2/3, rule (d) (per-question judgement, strictest aggregate) becomes mandatory rather than optional — worth checking against the harness judgment layer before any batching change.
- Whether `docs.typesafe.ai` gains a cookbook that changes the per-question threshold story (the index is at 25 pages today; the pinned skill links 11).
- `browser-use/jev-ultrafast` first tag/release (still none), and whether PR #232/#175 (configurable `TYPESAFE_BASE_URL`) lands — our `enabledDefaults()` already implements the same idea, so a landing there is confirmation rather than news.
- Keep the standing four-piece: ff-only mirror sync, 34 tests, audit, zero README drift.
