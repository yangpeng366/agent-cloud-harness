import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

test("sparse transcript anchors summary and messages toward the composer", () => {
    const css = readFileSync(new URL("../../main/resources/web/dialogue/app.css", import.meta.url), "utf8");

    assert.match(
        css,
        /\.message-panel__body--stream-only\s*\{[^}]*justify-content:\s*flex-end;/s
    );
    assert.match(
        css,
        /\.message-stream\s*\{[^}]*justify-content:\s*flex-end;/s
    );
    assert.match(
        css,
        /\.message-panel__body--stream-only\s+\.message-stream\s*\{[^}]*margin-top:\s*auto;/s
    );
    assert.match(
        css,
        /\.message-stream\s*\{[^}]*flex:\s*0 1 auto;/s
    );
    assert.match(
        css,
        /\.message-list\s*\{[^}]*align-content:\s*end;[^}]*flex:\s*0 1 auto;[^}]*max-height:\s*100%;/s
    );
    assert.match(
        css,
        /\.thread-drawer__summary\s*\{[^}]*display:\s*flex;[^}]*justify-content:\s*space-between;[^}]*padding:\s*1px 2px 0;/s
    );
});

test("browser acceptance probe guards message body to composer seam", () => {
    const runner = readFileSync(new URL("../../../scripts/dialogue-browser-acceptance-probe-runner.cjs", import.meta.url), "utf8");

    assert.match(
        runner,
        /gapBetweenMessageBodyAndComposer:\s*messagePanelBody\s*&&\s*composerPanel[\s\S]*composerPanel\.getBoundingClientRect\(\)\.top\s*-\s*messagePanelBody\.getBoundingClientRect\(\)\.bottom/s
    );
    assert.match(
        runner,
        /gapBetweenMessageBodyAndComposer\s*>\s*28/s
    );
    assert.match(
        runner,
        /too much space between message body and composer/s
    );
});

test("browser acceptance probe requires pinned latest round output to carry execution signal", () => {
    const runner = readFileSync(new URL("../../../scripts/dialogue-browser-acceptance-probe-runner.cjs", import.meta.url), "utf8");
    const appJs = readFileSync(new URL("../../main/resources/web/dialogue/app.js", import.meta.url), "utf8");

    assert.match(
        runner,
        /document\.querySelector\('\[data-testid="pinned-latest-round-output"\]'\)\?\.textContent\?\.trim\(\)\s*\|\|\s*''/
    );
    assert.match(
        runner,
        /auto-start task did not expose pinned execution summary/
    );
    assert.match(
        runner,
        /pinned latest round output lacks worker\/status\/output signal/
    );
    assert.match(
        runner,
        /\(worker\|执行中\|最近执行\|部分结果\|最近输出\|执行回合\)/
    );
    assert.match(appJs, /data-testid="pinned-latest-round-output"/);
    assert.match(appJs, />最近输出<\/span>/);
    assert.doesNotMatch(appJs, />latest round output<\/span>/);
});

test("browser acceptance probe reselects target task before lifecycle state checks", () => {
    const runner = readFileSync(new URL("../../../scripts/dialogue-browser-acceptance-probe-runner.cjs", import.meta.url), "utf8");

    assert.match(
        runner,
        /const pauseClickState\s*=\s*await evaluate\(page,\s*async\s*\(expectedTaskId\)\s*=>\s*\{[\s\S]*selectedBefore\s*!==\s*expectedTaskId/s
    );
    assert.match(
        runner,
        /window\.__dialogueProbeControlActionState\s*=\s*async\s*\(expectedTaskId,\s*action\)\s*=>\s*\{[\s\S]*selectedBefore\s*!==\s*expectedTaskId/s
    );
    assert.match(
        runner,
        /document\.querySelector\(`#taskThread \[data-task-id="\$\{CSS\.escape\(expectedTaskId\)\}"\]`\)/
    );
    assert.match(runner, /card\.click\(\);[\s\S]*setTimeout\(resolve,\s*320\)/s);
});

test("browser acceptance lifecycle checks keep hash aligned with selected task", () => {
    const runner = readFileSync(new URL("../../../scripts/dialogue-browser-acceptance-probe-runner.cjs", import.meta.url), "utf8");

    assert.match(runner, /const hashTaskId\s*=\s*new URLSearchParams\(String\(window\.location\.hash \|\| ''\)\.replace/);
    assert.match(runner, /selectedTaskId:\s*document\.querySelector\('#taskThread \[data-task-id\]\.is-active'\)[\s\S]*hashTaskId/s);
    assert.match(runner, /result\.selectedTaskId === expectedTaskId[\s\S]*result\.hashTaskId === expectedTaskId[\s\S]*\/paused\/i\.test\(result\.selectedStatus\)/s);
    assert.match(runner, /result\.selectedTaskId === expectedTaskId[\s\S]*result\.hashTaskId === expectedTaskId[\s\S]*\/active\|scheduler\|intake\|continue\|waiting_human\|human_gate\|failed\|done\/i\.test\(result\.selectedStatus\)/s);
    assert.match(runner, /function forceSelectTask\(page,\s*taskId\)[\s\S]*task card not found[\s\S]*hashTaskId === expectedTaskId/s);
});

test("browser acceptance probe has explicit ui seam lifecycle mode without weakening real resume", () => {
    const runner = readFileSync(new URL("../../../scripts/dialogue-browser-acceptance-probe-runner.cjs", import.meta.url), "utf8");
    const wrapper = readFileSync(new URL("../../../scripts/Run-DialogueBrowserAcceptanceProbe.ps1", import.meta.url), "utf8");

    assert.match(wrapper, /\[ValidateSet\('real',\s*'ui_seam'\)\]/);
    assert.match(wrapper, /\[string\]\$LifecycleMode\s*=\s*'real'/);
    assert.match(wrapper, /lifecycleMode\s*=\s*\$LifecycleMode/);
    assert.match(runner, /const lifecycleMode\s*=\s*payload\.lifecycleMode\s*\|\|\s*'real'/);
    assert.match(runner, /currentLifecycleMode\s*===\s*'ui_seam'[\s\S]*__dialogueProbeConfigureSyntheticControlAction\?\.\('resume',\s*expectedTaskId\)/s);
    assert.match(runner, /trackedFetchEntry\.phase\s*=\s*'synthetic_ui_seam'/);
    assert.match(runner, /if\s*\(lifecycleMode\s*===\s*'ui_seam'\)\s*\{[\s\S]*skipped_after_lifecycle:\s*true[\s\S]*return;/s);
    assert.match(runner, /mode:\s*lifecycleMode,[\s\S]*pause:\s*refreshedPauseAction,[\s\S]*resume:\s*afterResumeAction/s);
});
