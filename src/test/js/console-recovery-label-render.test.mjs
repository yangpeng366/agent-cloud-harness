import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { buildExecutionSurfaceSummaryPlan } from "../../main/resources/web/dialogue/execution-surface-summary-plan.js";

const appJs = readFileSync(new URL("../../main/resources/web/console/app.js", import.meta.url), "utf8");

test("console route chips avoid raw English route labels", () => {
    assert.match(appJs, /`模式：\$\{humanizeToken\(modelMode\) \|\| modelMode\}`/);
    assert.match(appJs, /`偏好：\$\{preferredWorkerHint\}`/);
    assert.match(appJs, /学习记忆：已应用/);
    assert.match(appJs, /学习记忆：已观察未应用/);
    assert.match(appJs, /路由\/执行：一致/);
    assert.match(appJs, /路由\/执行：不一致/);
    assert.doesNotMatch(appJs, /`mode: \$\{humanizeToken\(modelMode\) \|\| modelMode\}`/);
    assert.doesNotMatch(appJs, /`hint: \$\{preferredWorkerHint\}`/);
    assert.doesNotMatch(appJs, /"learning: applied"/);
    assert.doesNotMatch(appJs, /"learning: observed, not applied"/);
    assert.doesNotMatch(appJs, /"route\/execution aligned"/);
    assert.doesNotMatch(appJs, /"route\/execution diverged"/);
});

test("console execution surface summary uses operator Chinese labels", () => {
    const plan = buildExecutionSurfaceSummaryPlan({
        worker_id: "codex",
        execution_status: "partial_timeout"
    });

    assert.equal(plan.label, "执行回合");
    assert.equal(plan.value, "执行方 codex · 部分结果待确认");
    assert.equal(plan.value.includes("worker codex"), false);
});

test("console provider run file summary uses Chinese labels", () => {
    assert.match(appJs, /\["运行目录", firstNonBlank\(surface\.provider_run_dir, surface\.providerRunDir\)\]/);
    assert.match(appJs, /\["最后输出", firstNonBlank\(surface\.provider_last_message_path, surface\.providerLastMessagePath\)\]/);
    assert.match(appJs, /\["事件日志", firstNonBlank\(surface\.provider_event_log_path, surface\.providerEventLogPath\)\]/);
    assert.match(appJs, /\["标准输出", firstNonBlank\(surface\.provider_stdout_path, surface\.providerStdoutPath\)\]/);
    assert.match(appJs, /\["运行元数据", firstNonBlank\(surface\.provider_run_metadata_path, surface\.providerRunMetadataPath\)\]/);
    assert.match(appJs, /return \{ label: "运行文件", value: paths\.join\(" · "\) \};/);
    assert.doesNotMatch(appJs, /\["run", firstNonBlank\(surface\.provider_run_dir, surface\.providerRunDir\)\]/);
    assert.doesNotMatch(appJs, /return \{ label: "run files", value: paths\.join\(" · "\) \};/);
});

test("console execution boundary and alignment chips avoid raw English labels", () => {
    assert.match(appJs, /`执行回合：\$\{executionId\}`/);
    assert.match(appJs, /`执行方：\$\{workerId\}`/);
    assert.match(appJs, /alignmentChip\(\s*"路由\/执行"/);
    assert.match(appJs, /alignmentChip\(\s*"执行\/判断提示词"/);
    assert.match(appJs, /alignmentChip\(\s*"执行\/完成提示词"/);
    assert.match(appJs, /return `\$\{label\}：一致`;/);
    assert.match(appJs, /return `\$\{label\}：不一致`;/);
    assert.doesNotMatch(appJs, /`exec: \$\{executionId\}`/);
    assert.doesNotMatch(appJs, /`worker: \$\{workerId\}`/);
    assert.doesNotMatch(appJs, /alignmentChip\(\s*"route\/execution"/);
    assert.doesNotMatch(appJs, /alignmentChip\(\s*"exec\/judge prompt"/);
    assert.doesNotMatch(appJs, /alignmentChip\(\s*"exec\/done prompt"/);
    assert.doesNotMatch(appJs, /return `\$\{label\}: aligned`;/);
    assert.doesNotMatch(appJs, /return `\$\{label\}: diverged`;/);
});

test("console judgment and cognition chips avoid raw English control labels", () => {
    assert.match(appJs, /提示词 \$\{humanizeToken\(promptMode\) \|\| promptMode\}/);
    assert.match(appJs, /上下文已渲染/);
    assert.match(appJs, /上下文未使用/);
    assert.match(appJs, /上下文已注入/);
    assert.match(appJs, /预算已截断/);
    assert.match(appJs, /动作：\$\{humanizeToken\(continuityAction\)/);
    assert.match(appJs, /路由：\$\{humanizeToken\(routeSource\)/);
    assert.match(appJs, /状态：\$\{humanizeToken\(executionStatus\)/);
    assert.match(appJs, /需要检索归档/);
    assert.match(appJs, /提示词一致/);
    assert.match(appJs, /原因：\$\{preview\(reason, 48\)\}/);
    assert.doesNotMatch(appJs, /prompt \$\{humanizeToken\(promptMode\) \|\| promptMode\}/);
    assert.doesNotMatch(appJs, /mounted rendered/);
    assert.doesNotMatch(appJs, /mounted unused/);
    assert.doesNotMatch(appJs, /mounted injected/);
    assert.doesNotMatch(appJs, /budget truncated/);
    assert.doesNotMatch(appJs, /action: \$\{humanizeToken\(continuityAction\)/);
    assert.doesNotMatch(appJs, /route: \$\{humanizeToken\(routeSource\)/);
    assert.doesNotMatch(appJs, /status: \$\{humanizeToken\(executionStatus\)/);
    assert.doesNotMatch(appJs, /archive retrieval requested/);
    assert.doesNotMatch(appJs, /prompt aligned/);
    assert.doesNotMatch(appJs, /reason: \$\{preview\(reason, 48\)\}/);
});

test("console mounted context object chips avoid raw English labels", () => {
    assert.match(appJs, /保留状态：\$\{humanizeToken\(retention\) \|\| retention\}/);
    assert.match(appJs, /已从归档恢复/);
    assert.match(appJs, /需要刷新外部事实/);
    assert.match(appJs, /候选目标：\$\{candidatePaths/);
    assert.match(appJs, /下一步：\$\{nextFollowups/);
    assert.match(appJs, /引用：\$\{refs\.length\}/);
    assert.doesNotMatch(appJs, /retention: \$\{retention\}/);
    assert.doesNotMatch(appJs, /"rehydrated"/);
    assert.doesNotMatch(appJs, /"archive retrieval"/);
    assert.doesNotMatch(appJs, /"external refresh"/);
    assert.doesNotMatch(appJs, /"context reopen"/);
    assert.doesNotMatch(appJs, /`refs: \$\{refs\.length\}`/);
    assert.doesNotMatch(appJs, /`targets: \$\{candidatePaths/);
    assert.doesNotMatch(appJs, /`next: \$\{nextFollowups/);
});
