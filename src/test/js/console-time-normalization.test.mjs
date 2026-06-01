import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const appJs = readFileSync(new URL("../../main/resources/web/console/app.js", import.meta.url), "utf8");

test("console normalizes epoch-second timestamps before sorting and rendering", () => {
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

test("console createdAtMillis delegates to timestampMs", () => {
    assert.match(
        appJs,
        /function createdAtMillis\(task\)\s*\{\s*return timestampMs\(task\?\.created_at \|\| task\?\.createdAt \|\| 0\);\s*\}/
    );
});

test("console provider detail fetches and renders worker dispatch probe metadata", () => {
    assert.match(
        appJs,
        /apiOrNull\(`\/api\/v1\/workers\/\$\{encodeURIComponent\(workerId\)\}\/readiness\?mode=dispatch`\)/
    );
    assert.match(appJs, /function renderWorkerDispatchReadiness\(readiness,\s*workerId\)/);
    assert.match(appJs, /dispatch_preflight_metadata \|\| readiness\.dispatchPreflightMetadata/);
    assert.match(appJs, /cli_profile \|\| readiness\.cliProfile/);
    assert.match(appJs, /function renderCliProfileBadges\(profile\)/);
    assert.match(appJs, /supports_yolo/);
    assert.match(appJs, /provider_failure_class/);
    assert.match(appJs, /provider_retryable/);
    assert.match(appJs, /dispatch_preflight_probe_args/);
    assert.match(appJs, /dispatch_preflight_command_shape/);
    assert.match(appJs, /function workerIdForProvider\(providerId\)/);
    assert.match(appJs, /return "openclaw-native";/);
});

test("console provider detail can run provider preflight from the page", () => {
    assert.match(appJs, /async function runAgentPreflight\(providerId\)/);
    assert.match(
        appJs,
        /api\(`\/api\/v1\/agents\/\$\{encodedProviderId\}\/preflight`,\s*\{\s*method: "POST",\s*body: "\{\}"/s
    );
    assert.match(appJs, /data-provider-action="preflight"/);
    assert.match(appJs, />运行 Preflight</);
    assert.match(appJs, /renderProviderPreflightDiagnostics\(agent\)/);
    assert.match(appJs, /function renderProviderPreflightDiagnostics\(agent\)/);
    assert.match(appJs, /provider preflight result/);
    assert.match(appJs, /dispatch_preflight_exit_code/);
    assert.match(appJs, /dispatch_preflight_output_preview/);
});

test("console provider detail renders startup protocol probe diagnostics", () => {
    assert.match(appJs, /renderProviderStartupProtocolProbe\(agent\)/);
    assert.match(appJs, /function renderProviderStartupProtocolProbe\(agent\)/);
    assert.match(appJs, /startup protocol probe/);
    assert.match(appJs, /provider_protocol_probe_mode/);
    assert.match(appJs, /provider_protocol_probe_command_shape/);
    assert.match(appJs, /provider_protocol_probe_suggested_parser/);
    assert.match(appJs, /provider_protocol_inferred/);
    assert.match(appJs, /该探测只作为 discovery 诊断证据/);
});

test("console recovery job panel renders operator labels", () => {
    assert.match(appJs, />恢复任务</);
    assert.match(appJs, /请求 \$\{escapeHtml\(plan\.requestId\)\}/);
    assert.doesNotMatch(appJs, />Recovery Job</);
});
