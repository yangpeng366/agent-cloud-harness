import test from "node:test";
import assert from "node:assert/strict";
import { buildProviderDeprioritizationPlan } from "../../main/resources/web/dialogue/provider-deprioritization-plan.js";

test("provider deprioritization plan humanizes recent transient failure reason", () => {
    const plan = buildProviderDeprioritizationPlan({
        provider_deprioritized: true,
        deprioritized_provider: "claude",
        deprioritization_reason: "recent transient provider failures"
    });

    assert.equal(plan.providerDeprioritized, true);
    assert.equal(plan.deprioritizedProvider, "claude");
    assert.equal(plan.chip, "recovery避开 claude");
    assert.equal(plan.headline, "恢复阶段会优先避开 claude");
    assert.equal(plan.detail, "最近窗口内出现了临时 provider 失败，恢复建议会先尝试其他 provider。");
});

test("provider deprioritization plan stays empty when recovery hint absent", () => {
    const plan = buildProviderDeprioritizationPlan({
        provider_deprioritized: false,
        deprioritized_provider: "claude"
    });

    assert.equal(plan.providerDeprioritized, false);
    assert.equal(plan.chip, "");
    assert.equal(plan.headline, "");
});
