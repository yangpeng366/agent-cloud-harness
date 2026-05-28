import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const appJs = readFileSync(new URL("../../main/resources/web/console/app.js", import.meta.url), "utf8");

test("console runtime health renders provider recovery deprioritization window", () => {
    assert.match(appJs, /function buildRuntimeHealthDeprioritizationPlan\(input\)/);
    assert.match(appJs, /metadata\.deprioritized_providers/);
    assert.match(appJs, /metadata\.deprioritizedProviders/);
    assert.match(appJs, /当前恢复降级窗口：\$\{deprioritizedProviders\.join\(", "\)\}/);
    assert.match(appJs, /最近窗口内出现临时 provider 失败，恢复建议会先尝试其他 provider。/);
});

test("console route and provider rows render provider recovery avoidance details", () => {
    assert.match(appJs, /function buildConsoleProviderDeprioritizationPlan\(input\)/);
    assert.match(appJs, /source\.recovery_provider_deprioritized/);
    assert.match(appJs, /source\.provider_deprioritized/);
    assert.match(appJs, /recovery避开 \$\{deprioritizedProvider\}/);
    assert.match(appJs, /恢复阶段会优先避开 \$\{deprioritizedProvider\}/);
});
