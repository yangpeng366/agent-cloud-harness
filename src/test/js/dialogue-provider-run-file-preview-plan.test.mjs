import test from "node:test";
import assert from "node:assert/strict";
import {
    formatProviderRunFilePreview,
    formatProviderRunFilePreviewError
} from "../../main/resources/web/dialogue/provider-run-file-preview-plan.js";

test("provider run file preview formats metadata and content", () => {
    const preview = formatProviderRunFilePreview({
        kind: "stdout",
        path: "D:\\runs\\codex\\stdout.log",
        size_bytes: 12,
        limit_bytes: 65536,
        truncated: false,
        content: "codex stdout"
    });

    assert.equal(preview, "stdout · D:\\runs\\codex\\stdout.log · 12 bytes\n\ncodex stdout");
});

test("provider run file preview includes truncation limit", () => {
    const preview = formatProviderRunFilePreview({
        kind: "events",
        path: "D:\\runs\\codex\\events.jsonl",
        sizeBytes: 80000,
        limitBytes: 65536,
        truncated: true,
        content: "partial events"
    });

    assert.equal(preview.includes("80000 bytes"), true);
    assert.equal(preview.includes("truncated at 65536 bytes"), true);
});

test("provider run file preview error stays visible in preview box", () => {
    const preview = formatProviderRunFilePreviewError(new Error("provider run file not found"), "prompt");

    assert.equal(preview, "prompt 读取失败\n\nprovider run file not found");
});
