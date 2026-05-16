# GitHub First Release Index Audit

> Snapshot generated from current git status --short, focused on staged vs unstaged drift inside the current first-release slices.

## Repository Baseline

- staged_only: 1
- staged_and_unstaged: 0
- unstaged_only: 0
- untracked_only: 0

### staged_only

- M  docs/GITHUB_FIRST_RELEASE_STAGE_FILE_LIST_2026-05-11.md

### staged_and_unstaged

- none

### unstaged_only

- none

### untracked_only

- none

## chat-first / facade product line

- staged_only: 0
- staged_and_unstaged: 0
- unstaged_only: 0
- untracked_only: 0

### staged_only

- none

### staged_and_unstaged

- none

### unstaged_only

- none

### untracked_only

- none

## acceptance harness and operator docs

- staged_only: 0
- staged_and_unstaged: 0
- unstaged_only: 0
- untracked_only: 0

### staged_only

- none

### staged_and_unstaged

- none

### unstaged_only

- none

### untracked_only

- none

## Evidence-only working logs

- staged_only: 0
- staged_and_unstaged: 0
- unstaged_only: 1
- untracked_only: 12

### staged_only

- none

### staged_and_unstaged

- none

### unstaged_only

-  M docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-10.md

### untracked_only

- ?? docs/DIALOGUE_CHAT_FACADE_ACCEPTANCE_RECORD_2026-05-11.md
- ?? docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_all_2026-05-11.md
- ?? docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_baseline_2026-05-11.md
- ?? docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_harness_2026-05-11.md
- ?? docs/GITHUB_FIRST_RELEASE_COMMIT_DRY_RUN_product_2026-05-11.md
- ?? docs/GITHUB_FIRST_RELEASE_DRY_RUN_2026-05-11.md
- ?? docs/GITHUB_FIRST_RELEASE_INDEX_AUDIT_2026-05-11.md
- ?? docs/GITHUB_FIRST_RELEASE_PRECHECK_2026-05-11.md
- ?? docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_all_2026-05-11.md
- ?? docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_baseline_2026-05-11.md
- ?? docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_harness_2026-05-11.md
- ?? docs/GITHUB_FIRST_RELEASE_STAGE_PREVIEW_product_2026-05-11.md

## Deferred or excluded files

- staged_only: 0
- staged_and_unstaged: 0
- unstaged_only: 0
- untracked_only: 1

### staged_only

- none

### staged_and_unstaged

- none

### unstaged_only

- none

### untracked_only

- ?? docs/GOAL_RUNTIME_LANDING_DIFF_2026-05-11.md

## Unmatched

- staged_only: 0
- staged_and_unstaged: 0
- unstaged_only: 0
- untracked_only: 0

### staged_only

- none

### staged_and_unstaged

- none

### unstaged_only

- none

### untracked_only

- none

## Current Reading

- staged_only means the file is already in the index and currently has no extra working-tree drift.
- staged_and_unstaged means the file is in the index, but the working tree has diverged since it was staged; do not assume the current file content matches the staged slice.
- unstaged_only means the file still belongs to a slice, but has not been staged yet.
- untracked_only means the file is new and not yet staged.

## Still Not Done

- This audit does not replace the real /dialogue/ A-H manual acceptance pass.
- This audit does not replace filling a real public GitHub repository URL into README.md.
- This audit does not prove that GitHub Actions has run green on a real remote repository.

