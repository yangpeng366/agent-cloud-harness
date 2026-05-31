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

    assert.match(
        runner,
        /document\.querySelector\('\[data-testid="pinned-latest-round-output"\]'\)\?\.textContent\?\.trim\(\)\s*\|\|\s*''/
    );
    assert.match(
        runner,
        /pinned latest round output lacks worker\/status\/output signal/
    );
    assert.match(
        runner,
        /\(worker\|执行中\|最近执行\|部分结果\|最近输出\|执行回合\)/
    );
});
