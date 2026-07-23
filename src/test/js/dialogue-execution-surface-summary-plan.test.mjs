import test from "node:test";
import assert from "node:assert/strict";
import { buildExecutionSurfaceSummaryPlan } from "../../main/resources/web/dialogue/execution-surface-summary-plan.js";

test("execution surface summary includes partial timeout diagnostics", () => {
    const plan = buildExecutionSurfaceSummaryPlan({
        worker_id: "codex",
        execution_status: "partial_timeout",
        provider_timeout_kind: "max_duration",
        provider_abort_reason: "user_interrupted",
        provider_activity_timeout_ms: 180000,
        provider_turn_max_duration_ms: 900000,
        partial_output_chars: 640,
        partial_timeout_min_output_chars: 200
    });

    assert.equal(plan.label, "执行回合");
    assert.equal(
        plan.value,
        "执行方 codex · 部分结果待确认 · 达到最大时长 · 活动超时 3m · 最大时长 15m · 已有输出 640/200 字符 · 中断原因 用户中断"
    );
    assert.equal(plan.value.includes("partial timeout"), false);
    assert.equal(plan.value.includes("max duration"), false);
});

test("execution surface summary accepts legacy activity timeout alias", () => {
    const plan = buildExecutionSurfaceSummaryPlan({
        provider_turn_activity_timeout_ms: "45000",
        provider_turn_max_duration_ms: "120000"
    });

    assert.equal(plan.label, "执行回合");
    assert.equal(plan.value, "活动超时 45s · 最大时长 2m");
});

test("execution surface summary localizes mounted context diagnostics", () => {
    const plan = buildExecutionSurfaceSummaryPlan({
        prompt_mode: "mounted_primary",
        mounted_context_rendered: true,
        mounted_render_used: false,
        mounted_context_injected: true,
        mounted_context_panel_count: 3,
        mounted_context_non_empty_panel_count: 2,
        mounted_context_rendered_object_count: 5,
        mounted_context_hidden_object_count: 1,
        mounted_context_rendered_selection_trace_count: 4,
        mounted_context_hidden_selection_trace_count: 2,
        mounted_context_budget_truncated: true,
        mounted_active_count: 6,
        mounted_evidence_count: 7,
        mounted_archive_count: 8
    });

    assert.equal(
        plan.value,
        "提示词 mounted primary · 上下文已渲染 · 上下文未使用 · 上下文已注入 · 3 个面板 · 2 个非空 · 对象 5/1 · 选择轨迹 4/2 · 预算已截断 · 6 条活跃上下文 · 7 条证据 · 8 条归档"
    );
    assert.equal(/mounted rendered|panels|objects|traces|budget truncated/.test(plan.value), false);
});
