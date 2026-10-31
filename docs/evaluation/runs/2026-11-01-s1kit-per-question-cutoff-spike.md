# 2026-11-01 s1kit Per-Question Cutoff Spike

## Scope

Follow-up on the 2026-10-31 round: the two items carried in `[NEXT-FOCUS]`. (1) Can s1kit's per-question confidence cutoff be expressed in `JevContextScorerConfig` without touching the global thresholds? (2) Has `typesafe-ai/skills` published anything after v0.6.2? Both were answered by inspection plus one offline spike; a third signal showed up while checking the upstream default branch and is recorded here too.

## Spike setup

- Clone: `git clone --depth 1 ssh://git@ssh.github.com:443/Eliot5566/s1kit.git` into a temp directory, HEAD `9948b2d` ("Release 0.2.0: init, parallel runs, retries and a GitHub Action (#11)"). `github.com:443` is still unreachable from this host.
- Runtime dependency of the package is **PyYAML only** (`pyproject.toml`), so `calibrate` runs offline with no model and no API key. The project's own suite passes here: `python -m pytest -q` -> **30 passed**.
- The input is a `results.jsonl` in the package's own `Record` schema (`provider, case, question, expected, label, confidence, correct, latency_ms, error`), written by `s1kit test`. The spike synthesised that file with two providers ("cheap", "strong") and five questions so that each question had a different separability, then ran the real CLI:
  `python -m s1kit calibrate results.jsonl --target 0.95 --out thresholds.yaml`.

## What the spike showed

**1. The calibration key is `(provider, question)`, not `question`.** `calibrate()` groups by `(r.provider, r.question)` and returns

```yaml
target_precision: 0.95
providers:
  cheap:
    keyword/team:   {threshold: 0.71,  precision: 1.0, coverage: 1.0,  n: 20}
    keyword/urgent: {threshold: 0.70,  precision: 1.0, coverage: 0.05, n: 20}
    keyword/mood:   null
    keyword/rare:   null
    keyword/broken: null
```

Per provider **and** per question. Our config shape is per question only, so the provider axis is the part that does not fit as-is.

**2. "No cutoff reaches the target" is a first-class state, not an error.** `threshold_for()` returns `None`, the CLI prints `no cutoff reaches 95%, always escalate`, and the YAML keeps the key with a null value. Three ways to land there: the question is simply too hard, the question has no usable records, or every record errored (errored records are dropped before scoring, so an all-error question ends up with `n = 0`). Any of them means "never answer this from this provider".

**3. At one target precision, coverage differs by 20x across questions of the same provider.** `cheap/keyword/team` is 100% precise at a cutoff of 0.71 and answers 100% of cases; `cheap/keyword/urgent` is also 100% precise but only at 0.70 and answers 5% of cases. A single global threshold cannot express both: a cutoff that keeps the urgent question safe (>= 0.70) is indistinguishable from the team one, and a cutoff high enough to be safe everywhere would reject most of the team traffic. This is the direct, reproduced form of the argument that was only asserted from a README example in the previous round.

**4. The router escalates the whole batch when any single question lacks a cutoff.** `Router._confident()` walks every question of the call and returns `False` as soon as one question is below its cutoff **or has no cutoff at all** (`cutoff is None`). In the trace below the cheap provider answered at 0.55 confidence and was rejected; and because `keyword/rare` has a null cutoff for both providers, even a cheap provider that clears every numeric cutoff still escalates:

```
cheap meets every cutoff     -> provider=strong escalations=1
cheap below cutoff           -> provider=strong escalations=1
cutoff map: cheap {team: 0.71, urgent: 0.70, mood: None, rare: None}, strong {team: 0.855, urgent: 0.66, mood: 0.71, rare: None}
```

The last provider is always accepted (`if is_last or self._confident(...)`), so the router is fail-open at the end of the chain: escalation stops at the most expensive model, it never fails the call. That matches our own fail-open discipline.

**5. The batch semantics are the part that does not transfer.** Our judgment layer asks several questions per call (Router / Tool Gate / Verifier). s1kit's rule is "all questions must clear their cutoff, otherwise escalate everything", which means one question that cannot be calibrated drags the whole call to the expensive model. Copying the threshold table without changing this rule would silently multiply cost. The rule that fits our shape is per-question judgement with an aggregate of the strictest outcome ("any question that cannot be answered confidently -> that gate escalates"), not a whole-batch OR.

## Mapping onto `JevContextScorerConfig` (proposal only, nothing implemented)

A per-question table can be added **next to** the existing global thresholds, not instead of them:

```yaml
# proposed, not implemented
perQuestionCutoffs:
  mounted_context_keep: {threshold: 0.71, precision: 1.0, coverage: 1.0, n: 20, alwaysEscalate: false}
```

Suggested semantics, each needing maintainer sign-off:

1. Question has a calibrated cutoff -> judge with it.
2. Question has `alwaysEscalate: true` (the null case) -> go straight to the confirm / human channel, do not answer from this provider.
3. Question has no entry (never calibrated) -> fall back to the existing global `keepThreshold`; if the global threshold is also missing, treat as "cannot answer" rather than "answer".
4. Escalation is decided per question; the call-level outcome is the strictest of the per-question outcomes.

This keeps `keepThreshold = 0.5` and the `lowStakes` / `highStakes` pair as the fallback tier, so FEAT-03 checklist item 4 stays open with the same wording and one more option on the table (global threshold vs ranking-with-AUC vs per-question cutoff).

## Two other signals from the same window

- **`typesafe-ai/skills` has not moved past v0.6.2.** The repo's only tags are `v0.6.2` (`302eea66`, 2026-10-23) and `v0.5.7`; the commit list ends at the v0.6.2 release and there is no GitHub release object. The twenty-one official cookbooks are still the v0.6.2 text, so the cookbook surface did not change in this window.
- **`browser-use/jev-ultrafast` default branch moved, but only in the README.** New head `8251e272` (2026-10-31 17:35, PR #238, "docs: replace the Cloud waitlist callout with BU Ultrafast GA"). The callout now reads: **"BU Ultrafast, Browser Use Cloud's own hosted agent, is live on every plan. Set `model: bu-ultrafast` to use it."** Star/fork/issue snapshot: 23985 stars / 1837 forks / 212 open issues; still no tags and no GitHub releases. The code line is unchanged since 2026-09-18 (the only commits on `main` are 2026-09-17/18 plus this docs commit), so the previous round's "the default branch is stale" reading was imprecise: it was the *code* that is stale, the README was refreshed. The same README keeps the sentence "A `DONE` choice still requires independent outcome verification", which is the Verifier role stated by the upstream project itself. A hosted `bu-ultrafast` model is a second serving option next to `api.typesafe.ai/v1/systemone`, but it is bound to a Browser Use Cloud account, so it is not a local substitute for our key.

## Landing in this repo

- `docs/evaluation/runs/2026-11-01-s1kit-per-question-cutoff-spike.md` — this note.
- `docs/evaluation/runs/README.md` — dated entry in the Jev Patrol group.
- `docs/evaluation/PROGRESS.md` — one-line result.
- `docs/JEV_CONTEXT_SCORING_PLAN.md` — reproduced-evidence pointer under §11 (checklist item 4); thresholds, checklist and semantics unchanged.

No code, no config and no checklist item was changed in this round.

## Next check

Decide with the maintainer whether FEAT-03 §11 item 4 should carry a third option (per-question calibrated cutoff) next to the absolute threshold and the ranking-with-AUC option; if yes, write the table format down before touching `JevContextScorerConfig`. Keep watching `typesafe-ai/skills` for a release past v0.6.2, and `browser-use/jev-ultrafast` for the first tagged release (there is none yet) rather than for more README edits.
