#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const DEFAULT_PORT = 18432;
const DEFAULT_REPORT = path.resolve(__dirname, '../.tmp/provider-discovery-smoke/report.json');
const DEFAULT_WORK_DIR = path.resolve(__dirname, '../.tmp/provider-discovery-smoke');
const DEFAULT_JDK21_PATHS = [
  'C:\\Program Files\\Java\\jdk-21.0.9+10\\bin\\java.exe',
  'C:\\Program Files\\Java\\jdk-21\\bin\\java.exe',
  '/usr/lib/jvm/java-21-openjdk/bin/java',
  '/opt/homebrew/opt/openjdk@21/bin/java',
];

function defaultJavaPath() {
  if (process.env.JAVA21_HOME) {
    const candidate = path.join(process.env.JAVA21_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }
  for (const candidate of DEFAULT_JDK21_PATHS) {
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }
  if (process.env.JAVA_HOME) {
    const candidate = path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }
  return 'java';
}

function parseArgs(argv) {
  const parsed = {
    port: DEFAULT_PORT,
    reportPath: DEFAULT_REPORT,
    workDir: DEFAULT_WORK_DIR,
    jarPath: path.resolve(__dirname, '../target/agent-cloud-harness-0.1.0-SNAPSHOT-shaded.jar'),
    javaPath: defaultJavaPath(),
    keepRunning: false,
  };

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--port' && argv[i + 1]) parsed.port = Number(argv[++i]);
    else if (arg === '--report' && argv[i + 1]) parsed.reportPath = path.resolve(argv[++i]);
    else if (arg === '--work-dir' && argv[i + 1]) parsed.workDir = path.resolve(argv[++i]);
    else if (arg === '--jar' && argv[i + 1]) parsed.jarPath = path.resolve(argv[++i]);
    else if (arg === '--java' && argv[i + 1]) parsed.javaPath = argv[++i];
    else if (arg === '--keep-running') parsed.keepRunning = true;
  }
  return parsed;
}

function log(message) {
  const stamp = new Date().toLocaleString('zh-CN', { hour12: false });
  console.log(`[${stamp}] ${message}`);
}

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

function yamlQuote(value) {
  return `"${String(value || '').replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
}

function writeSmokeProviderConfig(workDir, probeBinary) {
  const config = '\uFEFF' + [
    'providers:',
    '  - id: smoke_agent',
    '    display_name: Smoke Agent',
    '    protocol: native_cli_text',
    '    binary: smoke-agent-missing-binary',
    '    args: ["run"]',
    '    capabilities: ["coding", "research"]',
    '    model_tier: small',
    '    selection_priority: 62',
    '    env:',
    '      SMOKE_AGENT_MODE: local',
    '  - id: inferred_agent',
    '    display_name: Inferred Agent',
    '    binary: inferred-agent-missing-binary',
    '    args: ["run"]',
    '    capabilities: ["reading"]',
    '  - id: unsupported_app_server',
    '    display_name: Unsupported App Server',
    '    protocol: app_server_json_rpc',
    `    binary: ${yamlQuote(probeBinary)}`,
    '    dispatch_probe_args: ["--version"]',
    '    capabilities: ["coding"]',
    '  - id: unsupported_mcp',
    '    display_name: Unsupported MCP',
    '    protocol: mcp',
    `    binary: ${yamlQuote(probeBinary)}`,
    '    dispatch_probe_args: ["--version"]',
    '    capabilities: ["tool_use"]',
    '',
  ].join('\n');
  fs.writeFileSync(path.join(workDir, 'providers.yaml'), config, 'utf8');
}

async function sleep(ms) {
  await new Promise((resolve) => setTimeout(resolve, ms));
}

async function fetchJson(url) {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`request failed: ${response.status} ${url}`);
  }
  return response.json();
}

async function waitForHealth(baseUrl, child, timeoutMs = 45000) {
  const deadline = Date.now() + timeoutMs;
  let lastError = null;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      throw new Error(`harness exited before health passed: exit=${child.exitCode}`);
    }
    try {
      const payload = await fetchJson(`${baseUrl}/api/v1/health`);
      if (payload && payload.status === 'up') {
        return;
      }
    } catch (error) {
      lastError = error;
    }
    await sleep(500);
  }
  throw lastError || new Error('health check timed out');
}

function findById(items, key, id) {
  return (items || []).find((item) => item && item[key] === id);
}

function assertCondition(report, name, passed, details) {
  report.steps.push({ name, passed, details });
  console.log(`${passed ? 'OK' : 'FAIL'} ${name}`);
  if (details) console.log(`   ${details}`);
  if (!passed) {
    throw new Error(`${name}: ${details || 'assertion failed'}`);
  }
}

async function run() {
  const args = parseArgs(process.argv.slice(2));
  if (!fs.existsSync(args.jarPath)) {
    throw new Error(`jar not found: ${args.jarPath}`);
  }

  ensureDir(args.workDir);
  ensureDir(path.dirname(args.reportPath));
  writeSmokeProviderConfig(args.workDir, args.javaPath);

  const stdoutPath = path.join(args.workDir, 'server.out.log');
  const stderrPath = path.join(args.workDir, 'server.err.log');
  const dbPath = path.join(args.workDir, 'agent_cloud.db');
  fs.rmSync(dbPath, { force: true });
  fs.writeFileSync(stdoutPath, '');
  fs.writeFileSync(stderrPath, '');

  const baseUrl = `http://localhost:${args.port}`;
  const child = spawn(args.javaPath, [
    '--enable-preview',
    `-Dserver.port=${args.port}`,
    `-Ddb.path=${dbPath}`,
    '-jar',
    args.jarPath,
  ], {
    cwd: args.workDir,
    windowsHide: true,
    stdio: ['ignore', fs.openSync(stdoutPath, 'a'), fs.openSync(stderrPath, 'a')],
  });

  const report = {
    startedAt: new Date().toISOString(),
    baseUrl,
    workDir: args.workDir,
    javaPath: args.javaPath,
    pid: child.pid,
    steps: [],
  };

  try {
    log(`started harness pid=${child.pid} baseUrl=${baseUrl}`);
    await waitForHealth(baseUrl, child);

    const agentsPayload = await fetchJson(`${baseUrl}/api/v1/agents`);
    const workersPayload = await fetchJson(`${baseUrl}/api/v1/workers`);
    const readinessPayload = await fetchJson(`${baseUrl}/api/v1/workers/smoke_agent/readiness`);
    const inferredReadinessPayload = await fetchJson(`${baseUrl}/api/v1/workers/inferred_agent/readiness`);
    const unsupportedAppServerPayload = await fetchJson(`${baseUrl}/api/v1/agents/unsupported_app_server`);

    const agent = findById(agentsPayload.data, 'provider_id', 'smoke_agent');
    const worker = findById(workersPayload.data, 'worker_id', 'smoke_agent');
    const readiness = readinessPayload.data;
    const inferredAgent = findById(agentsPayload.data, 'provider_id', 'inferred_agent');
    const inferredWorker = findById(workersPayload.data, 'worker_id', 'inferred_agent');
    const inferredReadiness = inferredReadinessPayload.data;
    const unsupportedAppServer = findById(agentsPayload.data, 'provider_id', 'unsupported_app_server');
    const unsupportedMcp = findById(agentsPayload.data, 'provider_id', 'unsupported_mcp');
    const unsupportedWorker = findById(workersPayload.data, 'worker_id', 'unsupported_app_server')
      || findById(workersPayload.data, 'worker_id', 'unsupported_mcp');

    assertCondition(report, 'agent discovered', Boolean(agent), 'smoke_agent appears in /api/v1/agents');
    assertCondition(report, 'worker discovered', Boolean(worker), 'smoke_agent appears in /api/v1/workers');
    assertCondition(report, 'agent metadata marks discovery',
      agent?.metadata?.provider_discovery === true,
      JSON.stringify(agent?.metadata || {}));
    assertCondition(report, 'worker capabilities projected',
      Array.isArray(worker?.capabilities)
        && worker.capabilities.includes('coding')
        && worker.capabilities.includes('research'),
      JSON.stringify(worker?.capabilities || []));
    assertCondition(report, 'worker list readiness matches runtime readiness',
      worker?.ready === readiness?.ready && readiness?.ready === false,
      `worker.ready=${worker?.ready} readiness.ready=${readiness?.ready}`);
    assertCondition(report, 'dynamic native backend supported',
      readiness?.checks?.['executor_backend:provider_native_cli'] === true,
      JSON.stringify(readiness?.checks || {}));
    assertCondition(report, 'missing binary reported through provider readiness',
      readiness?.checks?.['provider:smoke_agent'] === false
        && /binary not found: smoke-agent-missing-binary/.test(readiness?.reason || ''),
      readiness?.reason || '');
    assertCondition(report, 'protocol inferred for binary-only provider',
      inferredAgent?.metadata?.provider_protocol === 'native_cli_text'
        && inferredAgent?.metadata?.provider_protocol_inferred === true,
      JSON.stringify(inferredAgent?.metadata || {}));
    assertCondition(report, 'inferred provider includes startup protocol probe diagnostics',
      inferredAgent?.metadata?.provider_protocol_probe_mode === 'startup_help_probe'
        && Array.isArray(inferredAgent?.metadata?.provider_protocol_probe_command_shape)
        && inferredAgent.metadata.provider_protocol_probe_command_shape.includes('--help')
        && inferredAgent?.metadata?.provider_protocol_probe_exit_code === -1
        && inferredAgent?.metadata?.provider_protocol_probe_success === false
        && inferredAgent?.metadata?.provider_protocol_probe_suggested_parser === 'unknown',
      JSON.stringify(inferredAgent?.metadata || {}));
    assertCondition(report, 'inferred provider projected to worker inventory',
      Boolean(inferredWorker)
        && Array.isArray(inferredWorker?.capabilities)
        && inferredWorker.capabilities.includes('reading'),
      JSON.stringify(inferredWorker || {}));
    assertCondition(report, 'inferred provider readiness reports configured binary',
      inferredReadiness?.checks?.['provider:inferred_agent'] === false
        && /binary not found: inferred-agent-missing-binary/.test(inferredReadiness?.reason || ''),
      inferredReadiness?.reason || '');
    assertCondition(report, 'unsupported app-server provider visible in agent inventory',
      unsupportedAppServer?.ready === false
        && unsupportedAppServer?.metadata?.provider_discovery_supported === false
        && /built-in codex app-server/.test(unsupportedAppServer?.readiness_reason || ''),
      JSON.stringify(unsupportedAppServer || {}));
    assertCondition(report, 'unsupported app-server provider includes startup probe diagnostics',
      unsupportedAppServer?.metadata?.provider_protocol_probe_mode === 'unsupported_startup_probe'
        && Array.isArray(unsupportedAppServer?.metadata?.provider_protocol_probe_command_shape)
        && unsupportedAppServer.metadata.provider_protocol_probe_command_shape.includes('--version')
        && unsupportedAppServer?.metadata?.provider_protocol_probe_exit_code === 0
        && unsupportedAppServer?.metadata?.provider_protocol_probe_success === true,
      JSON.stringify(unsupportedAppServer?.metadata || {}));
    assertCondition(report, 'unsupported mcp provider visible in agent inventory',
      unsupportedMcp?.ready === false
        && unsupportedMcp?.metadata?.provider_protocol === 'mcp'
        && /not implemented/.test(unsupportedMcp?.readiness_reason || ''),
      JSON.stringify(unsupportedMcp || {}));
    assertCondition(report, 'unsupported mcp provider includes startup probe diagnostics',
      unsupportedMcp?.metadata?.provider_protocol_probe_mode === 'unsupported_startup_probe'
        && Array.isArray(unsupportedMcp?.metadata?.provider_protocol_probe_command_shape)
        && unsupportedMcp.metadata.provider_protocol_probe_command_shape.includes('--version')
        && unsupportedMcp?.metadata?.provider_protocol_probe_exit_code === 0
        && unsupportedMcp?.metadata?.provider_protocol_probe_success === true,
      JSON.stringify(unsupportedMcp?.metadata || {}));
    assertCondition(report, 'unsupported providers are not registered as workers',
      !unsupportedWorker,
      JSON.stringify(unsupportedWorker || {}));
    assertCondition(report, 'unsupported provider detail is readable',
      unsupportedAppServerPayload?.data?.provider_id === 'unsupported_app_server'
        && unsupportedAppServerPayload?.data?.provider_type === 'unsupported',
      JSON.stringify(unsupportedAppServerPayload?.data || {}));

    report.completedAt = new Date().toISOString();
    report.passed = true;
    fs.writeFileSync(args.reportPath, JSON.stringify(report, null, 2));
    log(`report written: ${args.reportPath}`);
  } catch (error) {
    report.completedAt = new Date().toISOString();
    report.passed = false;
    report.error = error.stack || error.message;
    fs.writeFileSync(args.reportPath, JSON.stringify(report, null, 2));
    throw error;
  } finally {
    if (!args.keepRunning && child.exitCode === null) {
      child.kill('SIGTERM');
      await sleep(800);
      if (child.exitCode === null) {
        child.kill('SIGKILL');
      }
    }
  }
}

run().catch((error) => {
  console.error(error.stack || error.message);
  process.exit(1);
});
