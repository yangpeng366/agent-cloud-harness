import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const appJs = readFileSync("src/main/resources/web/dialogue/app.js", "utf8");

test("dialogue recovery badges render Chinese failure labels", () => {
    assert.match(appJs, /`失败 · \$\{preview\(failureClass, 22\)\}`/);
    assert.match(appJs, /`失败 · \$\{preview\(failureClass, 28\)\}`/);
    assert.doesNotMatch(appJs, /`failure · \$\{preview\(failureClass,/);
});
