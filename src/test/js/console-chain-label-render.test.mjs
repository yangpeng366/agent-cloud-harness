import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const appJs = readFileSync(new URL("../../main/resources/web/console/app.js", import.meta.url), "utf8");

test("console task timeline and chain context use Chinese operator labels", () => {
    assert.match(appJs, /迭代链 \$\{String\(chainIndex \+ 1\)\.padStart\(2, "0"\)\}/);
    assert.match(appJs, /chainTaskCountLabel\(chain\.tasks\.length\)/);
    assert.match(appJs, /const roundLabel = chainRoundLabel\(taskIndex\);/);
    assert.match(appJs, /const currentRound = chainRoundLabel\(currentIndex\);/);
    assert.match(appJs, /const roundLabel = chainRoundLabel\(index\);/);
    assert.match(appJs, /<span class="message__parent">跟进 /);
    assert.match(appJs, /执行方 \$\{task\.assigned_worker \|\| task\.assignedWorker\}/);
    assert.match(appJs, /下一步：\$\{escapeHtml\(preview\(task\.next_step \|\| task\.nextStep, 92\)\)\}/);
    assert.match(appJs, /工具：\$\{escapeHtml\(String\(toolCount\)\)\}/);
    assert.match(appJs, /产物：\$\{escapeHtml\(String\(artifactCount\)\)\}/);
    assert.match(appJs, /<p class="eyebrow">迭代链快照<\/p>/);
    assert.match(appJs, /return index === 0 \? "首轮" : `第 \$\{index \+ 1\} 轮`;/);
    assert.match(appJs, /return `\$\{count\} 个任务`;/);

    assert.doesNotMatch(appJs, /Iteration Chain/);
    assert.doesNotMatch(appJs, /Chain Snapshot/);
    assert.doesNotMatch(appJs, /follow-up of/);
    assert.doesNotMatch(appJs, /message__hint">Next: /);
    assert.doesNotMatch(appJs, /message__hint">Tools: /);
    assert.doesNotMatch(appJs, /message__hint">Artifacts: /);
    assert.doesNotMatch(appJs, /taskIndex === 0 \? "root" : `round \$\{taskIndex \+ 1\}`/);
    assert.doesNotMatch(appJs, /currentIndex === 0 \? "root" : `round \$\{currentIndex \+ 1\}`/);
    assert.doesNotMatch(appJs, /index === 0 \? "root" : `round \$\{index \+ 1\}`/);
    assert.doesNotMatch(appJs, /`\$\{chain\.tasks\.length\} tasks`/);
});
