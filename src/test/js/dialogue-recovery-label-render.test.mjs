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
