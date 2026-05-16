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
