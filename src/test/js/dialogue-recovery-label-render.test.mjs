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
