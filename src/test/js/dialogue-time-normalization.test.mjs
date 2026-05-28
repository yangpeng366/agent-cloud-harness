import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const appJs = readFileSync(new URL("../../main/resources/web/dialogue/app.js", import.meta.url), "utf8");

test("dialogue normalizes epoch-second timestamps before sorting and rendering", () => {
    assert.match(
        appJs,
        /\.sort\(\(a,\s*b\)\s*=>\s*timestampMs\(b\.updated_at\s*\|\|\s*b\.updatedAt\s*\|\|\s*0\)\s*-\s*timestampMs\(a\.updated_at\s*\|\|\s*a\.updatedAt\s*\|\|\s*0\)\)/s
    );
    assert.match(
        appJs,
        /\.sort\(\(a,\s*b\)\s*=>\s*timestampMs\(a\.created_at\s*\|\|\s*a\.createdAt\s*\|\|\s*0\)\s*-\s*timestampMs\(b\.created_at\s*\|\|\s*b\.createdAt\s*\|\|\s*0\)\)/s
    );
    assert.match(
        appJs,
        /function formatTime\(value\)\s*\{[\s\S]*?const normalized = normalizeTimestampValue\(value\);[\s\S]*?const date = new Date\(normalized\);/
    );
    assert.match(appJs, /function normalizeTimestampValue\(value\)/);
    assert.match(appJs, /function timestampMs\(value\)/);
});

test("dialogue createdAtMillis normalizes epoch seconds before Date construction", () => {
    assert.match(
        appJs,
        /function createdAtMillis\(task\)\s*\{\s*const date = new Date\(normalizeTimestampValue\(task\?\.created_at \|\| task\?\.createdAt \|\| 0\)\);/
    );
});
