import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const appJs = readFileSync("src/main/resources/web/dialogue/app.js", "utf8");

test("dialogue recovery badges render Chinese failure labels", () => {
    assert.match(appJs, /`失败 · \$\{preview\(failureClass, 22\)\}`/);
    assert.match(appJs, /`失败 · \$\{preview\(failureClass, 28\)\}`/);
    assert.doesNotMatch(appJs, /`failure · \$\{preview\(failureClass,/);
});

test("dialogue message meta renders Chinese continuity and task labels", () => {
    assert.match(appJs, />会话续跑</);
    assert.match(appJs, />任务绑定</);
    assert.match(appJs, /`任务 · \$\{preview\(taskId, 18\)\}`/);
    assert.match(appJs, /`任务 · \$\{preview\(summary\.latestTaskId, 14\)\}`/);
    assert.match(appJs, /`\$\{summary\.count\} 条消息`/);
    assert.doesNotMatch(appJs, />session continuity</);
    assert.doesNotMatch(appJs, />task-bound</);
    assert.doesNotMatch(appJs, /`task · \$\{preview\(/);
    assert.doesNotMatch(appJs, /`\$\{summary\.count\} msgs`/);
});

test("dialogue provider run file labels stay operator readable", () => {
    assert.match(appJs, />运行目录</);
    assert.match(appJs, /\{ label: "运行文件", value: paths\.join/);
    assert.match(appJs, /\["最后输出", "last_message"/);
    assert.match(appJs, /\["事件日志", "events"/);
    assert.match(appJs, /\["标准输出", firstNonBlank/);
    assert.doesNotMatch(appJs, />run dir</);
    assert.doesNotMatch(appJs, /\{ label: "run files", value: paths\.join/);
    assert.doesNotMatch(appJs, /\["last message", metadata\.provider_last_message_path/);
});

test("dialogue recovery job panel renders operator labels", () => {
    assert.match(appJs, />恢复任务</);
    assert.match(appJs, /请求 \$\{escapeHtml\(plan\.requestId\)\}/);
    assert.doesNotMatch(appJs, />Recovery Job</);
});

test("dialogue judgment and cognition chips avoid raw English control labels", () => {
    assert.match(appJs, /`下一步 · \$\{preview\(nextStep,/);
    assert.match(appJs, /`当前 · \$\{current\}`/);
    assert.match(appJs, /动作：\$\{humanizeToken\(continuityAction\)/);
    assert.match(appJs, /路由：\$\{humanizeToken\(routeSource\)/);
    assert.match(appJs, /状态：\$\{humanizeToken\(executionStatus\)/);
    assert.match(appJs, /需要检索归档/);
    assert.match(appJs, /提示词一致/);
    assert.match(appJs, /`\$\{label\}：一致`/);
    assert.match(appJs, />跟进 \$\{escapeHtml\(parentTask\.title/);
    assert.match(appJs, /`跟进 \$\{followupParent\.title/);
    assert.match(appJs, /summarizeJudgmentSurface\("执行判断"/);
    assert.match(appJs, /summarizeJudgmentSurface\("完成判断"/);
    assert.doesNotMatch(appJs, /`next · \$\{preview\(nextStep,/);
    assert.doesNotMatch(appJs, /`current · \$\{current\}`/);
    assert.doesNotMatch(appJs, /action: \$\{humanizeToken\(continuityAction\)/);
    assert.doesNotMatch(appJs, /route: \$\{humanizeToken\(routeSource\)/);
    assert.doesNotMatch(appJs, /status: \$\{humanizeToken\(executionStatus\)/);
    assert.doesNotMatch(appJs, /archive retrieval requested/);
    assert.doesNotMatch(appJs, /prompt aligned/);
    assert.doesNotMatch(appJs, /`\$\{label\}: aligned`/);
    assert.doesNotMatch(appJs, /follow-up of/);
    assert.doesNotMatch(appJs, /summarizeJudgmentSurface\("exec judge"/);
    assert.doesNotMatch(appJs, /summarizeJudgmentSurface\("done judge"/);
});

test("dialogue mounted context object chips avoid raw English labels", () => {
    assert.match(appJs, /保留状态：\$\{humanizeToken\(retention\)/);
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
