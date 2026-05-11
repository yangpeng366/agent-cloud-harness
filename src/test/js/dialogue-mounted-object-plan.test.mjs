import test from "node:test";
import assert from "node:assert/strict";
import { isTrueFlag } from "../../main/resources/web/dialogue/mounted-object-plan.js";

test("mounted object plan treats only literal boolean true as enabled", () => {
    assert.equal(isTrueFlag(true), true);
    assert.equal(isTrueFlag(false), false);
    assert.equal(isTrueFlag("true"), false);
    assert.equal(isTrueFlag(null), false);
    assert.equal(isTrueFlag(undefined), false);
});
