#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');

let puppeteer;
try {
  puppeteer = require('puppeteer-core');
} catch (e) {
  console.error('puppeteer-core not found, please install: npm install puppeteer-core');
  process.exit(1);
}

const DEFAULT_EDGE_PATHS = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
];

function findBrowserPath() {
  for (const browserPath of DEFAULT_EDGE_PATHS) {
    if (fs.existsSync(browserPath)) {
      return browserPath;
    }
  }
  return '';
}

function log(message) {
  const stamp = new Date().toLocaleString('zh-CN', { hour12: false });
  console.log(`[${stamp}] ${message}`);
}

function parseArgs(argv) {
  const parsed = {
    baseUrl: 'http://localhost:8080',
    reportPath: path.resolve('.tmp/console-provider-window-probe.json'),
    screenshotPath: path.resolve('.tmp/console-provider-window-probe.png'),
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--base-url' && argv[i + 1]) {
      parsed.baseUrl = argv[++i];
      continue;
    }
    if (arg === '--report' && argv[i + 1]) {
      parsed.reportPath = path.resolve(argv[++i]);
      continue;
    }
    if (arg === '--screenshot' && argv[i + 1]) {
      parsed.screenshotPath = path.resolve(argv[++i]);
      continue;
    }
  }
  return parsed;
}

async function httpJson(url, options = {}) {
  const response = await fetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {})
    }
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`${response.status} ${response.statusText} for ${url}: ${text}`);
  }
  const json = await response.json();
  return json.data || json;
}

function responseJson(body) {
  return {
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ data: body })
  };
}

async function createFixture(baseUrl) {
  const session = await httpJson(`${baseUrl}/api/v1/sessions`, {
    method: 'POST',
    body: JSON.stringify({ title: 'console provider window probe session' })
  });
  const task = await httpJson(`${baseUrl}/api/v1/tasks`, {
    method: 'POST',
    body: JSON.stringify({
      title: 'console provider window probe task',
      task_type: 'coding',
      source: 'user',
      priority: 'high',
      intent: 'verify console provider deprioritization surface',
      session_id: session.id,
      auto_start: false
    })
  });
  return { session, task };
}

function buildRuntimeHealthFixture() {
  return {
    checked_at: new Date().toISOString(),
    active_run_count: 1,
    failed_run_count_24h: 3,
    crashed_run_count_24h: 0,
    unavailable_provider_count: 0,
    auth_needed_provider_count: 0,
    average_run_duration_ms: 1834,
    active_runs: [],
    recent_failures: [
      {
        run_id: 'run_provider_probe_failed',
        provider_id: 'claude',
        status: 'failed',
        summary: 'worker claude failed: thread not found'
      }
    ],
    unavailable_providers: [],
    auth_problem_providers: [],
    metadata: {
      deprioritized_providers: ['claude']
    },
    provider_stats: [
      {
        provider_id: 'claude',
        total_runs: 4,
        active_runs: 0,
        completed_runs: 1,
        failed_runs: 3,
        cancelled_runs: 0,
        crashed_runs: 0,
        average_duration_ms: 2100,
        failure_rate: 0.75,
        last_failure_summary: 'worker claude failed: thread not found',
        metadata: {
          provider_deprioritized: true,
          deprioritization_reason: 'recent transient provider failures'
        }
      },
      {
        provider_id: 'codex',
        total_runs: 5,
        active_runs: 1,
        completed_runs: 4,
        failed_runs: 0,
        cancelled_runs: 0,
        crashed_runs: 0,
        average_duration_ms: 1450,
        failure_rate: 0.0,
        metadata: {}
      }
    ]
  };
}

function buildAgentFixture() {
  const checkedAt = new Date().toISOString();
  return {
    provider_id: 'codex',
    display_name: 'Codex',
    provider_type: 'local_cli',
    transport: 'process',
    capabilities: ['coding', 'reading', 'ops'],
    installed: true,
    version: '0.0.0-probe',
    auth_status: 'ok',
    ready: true,
    readiness_reason: 'probe fixture ready',
    checked_at: checkedAt,
    metadata: {
      provider_protocol: 'app_server_json_rpc',
      execution_backend: 'provider_app_server'
    }
  };
}

function buildPreflightAgentFixture() {
  return {
    ...buildAgentFixture(),
    checked_at: new Date().toISOString(),
    metadata: {
      provider_protocol: 'app_server_json_rpc',
      execution_backend: 'provider_app_server',
      dispatch_preflight_mode: 'active_probe',
      dispatch_preflight_probe_kind: 'cli_help',
      dispatch_preflight_probe_args: ['--version'],
      dispatch_preflight_command_shape: ['direct', '--version'],
      dispatch_preflight_exit_code: 0,
      dispatch_preflight_output_preview: 'codex 0.0.0-probe',
      provider_failure_class: 'none',
      provider_retryable: false
    }
  };
}

function buildWorkerReadinessFixture() {
  return {
    worker_id: 'codex',
    worker_type: 'codex',
    ready: true,
    reason: 'dispatch preflight ready',
    mode: 'dispatch',
    checks: {
      passive_readiness: true,
      dispatch_preflight: true
    },
    dispatch_preflight_ready: true,
    dispatch_preflight_reason: 'dispatch preflight ready',
    dispatch_preflight_cached: false,
    dispatch_preflight_mode: 'active_probe',
    dispatch_preflight_active_probe: true,
    dispatch_preflight_metadata: {
      launch_mode: 'direct',
      launch_target: 'codex',
      dispatch_preflight_probe_kind: 'cli_help',
      dispatch_preflight_probe_args: ['--version'],
      dispatch_preflight_command_shape: ['direct', '--version'],
      dispatch_preflight_exit_code: 0,
      dispatch_preflight_output_preview: 'codex 0.0.0-probe'
    },
    cli_profile: {
      cli_profile_evidence_available: true,
      supports_model: true,
      supports_resume: true,
      supports_json_output: true
    }
  };
}

function buildLiveFlowFixture(task) {
  return {
    task: {
      id: task.id,
      session_id: task.session_id || task.sessionId,
      title: task.title,
      status: 'active',
      control_node: 'scheduler',
      assigned_worker: 'codex',
      metadata: {
        task_type: 'coding'
      }
    },
    route_preview: {
      selected_worker: 'codex',
      route_source: 'task_pinned',
      task_type: 'coding',
      route_reason: 'task pinned worker still satisfies current route constraints',
      recovery_provider_deprioritized: true,
      recovery_deprioritized_provider: 'claude',
      recovery_deprioritization_reason: 'recent transient provider failures',
      recovery_unpinned_recommendation: {
        selected_worker: 'codex',
        route_source: 'ready_fallback',
        task_type: 'coding',
        provider_deprioritized: true,
        deprioritized_provider: 'claude',
        deprioritization_reason: 'recent transient provider failures'
      }
    },
    provider_selection: {
      selected_provider: 'codex',
      selected_worker_id: 'codex',
      metadata: {
        provider_deprioritized: true,
        deprioritized_provider: 'claude',
        deprioritization_reason: 'recent transient provider failures'
      }
    },
    runtime_context: {
      active_context: {
        continuity_summary: 'console provider window probe continuity summary'
      },
      recent_artifacts: [],
      recent_decisions: []
    },
    runtime_cognition_surface: {
      route: {
        selected_worker: 'codex',
        route_source: 'task_pinned',
        route_reason: 'task pinned worker still satisfies current route constraints'
      }
    },
    runtime_cognition_timeline: [],
    judgment_trace: {},
    tool_invocations: []
  };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  fs.mkdirSync(path.dirname(args.reportPath), { recursive: true });
  fs.mkdirSync(path.dirname(args.screenshotPath), { recursive: true });

  const browserPath = process.env.PUPPETEER_EXECUTABLE_PATH || findBrowserPath();
  if (!browserPath) {
    throw new Error('Edge browser not found. Set PUPPETEER_EXECUTABLE_PATH.');
  }

  const fixture = await createFixture(args.baseUrl);
  const runtimeHealthFixture = buildRuntimeHealthFixture();
  const liveFlowFixture = buildLiveFlowFixture(fixture.task);
  const agentFixture = buildAgentFixture();
  const preflightAgentFixture = buildPreflightAgentFixture();
  const workerReadinessFixture = buildWorkerReadinessFixture();

  const browser = await puppeteer.launch({
    executablePath: browserPath,
    headless: 'new',
    ignoreHTTPSErrors: true,
    args: ['--ignore-certificate-errors']
  });

  try {
    const page = await browser.newPage();
    page.setViewport({ width: 1600, height: 1000 });
    page.setDefaultTimeout(30000);

    const targetUrl = `${args.baseUrl.replace(/\/+$/, '')}/console/#session=${encodeURIComponent(fixture.session.id)}&task=${encodeURIComponent(fixture.task.id)}`;
    log(`open console probe: ${targetUrl}`);
    await page.evaluateOnNewDocument((payload) => {
      const originalFetch = window.fetch.bind(window);
      window.__consoleProviderPreflightProbe = {
        preflightCount: 0,
        lastPreflightMethod: null
      };
      const okJson = (body) => new Response(JSON.stringify({ data: body }), {
        status: 200,
        headers: {
          'Content-Type': 'application/json'
        }
      });
      window.fetch = async (input, init) => {
        const url = typeof input === 'string' ? input : String(input && input.url ? input.url : '');
        const method = String(init && init.method ? init.method : 'GET').toUpperCase();
        if (url.includes('/api/v1/runtime_health?limit=8')) {
          return okJson(payload.runtimeHealthFixture);
        }
        if (url.includes(`/api/v1/tasks/${payload.taskId}/live_flow?limit=8`)) {
          return okJson(payload.liveFlowFixture);
        }
        if (url.includes(`/api/v1/tasks/${payload.taskId}/provider_selection`)) {
          return okJson(payload.liveFlowFixture.provider_selection);
        }
        if (url.includes('/api/v1/workers/codex/readiness?mode=dispatch')) {
          return okJson(payload.workerReadinessFixture);
        }
        if (url.includes('/api/v1/agents/codex/preflight')) {
          window.__consoleProviderPreflightProbe.preflightCount += 1;
          window.__consoleProviderPreflightProbe.lastPreflightMethod = method;
          return okJson(payload.preflightAgentFixture);
        }
        if (url.includes('/api/v1/agents/codex/runs?')) {
          return okJson([]);
        }
        if (url.endsWith('/api/v1/agents/codex')) {
          return okJson(payload.agentFixture);
        }
        if (url.endsWith('/api/v1/agents')) {
          const preflightRan = window.__consoleProviderPreflightProbe.preflightCount > 0;
          return okJson([preflightRan ? payload.preflightAgentFixture : payload.agentFixture]);
        }
        if (url.includes('/api/v1/agent_runs?')) {
          return okJson([]);
        }
        return originalFetch(input, init);
      };
    }, {
      taskId: fixture.task.id,
      runtimeHealthFixture,
      liveFlowFixture,
      agentFixture,
      preflightAgentFixture,
      workerReadinessFixture
    });
    await page.goto(targetUrl, { waitUntil: 'domcontentloaded', timeout: 30000 });
    await page.waitForSelector('#runtimeHealth', { timeout: 30000 });
    await page.waitForSelector('#routeBox', { timeout: 30000 });
    let waitError = null;
    try {
      await page.waitForFunction(() => {
        const runtimeHealth = document.querySelector('#runtimeHealth');
        const routeBox = document.querySelector('#routeBox');
        return runtimeHealth && routeBox
          && runtimeHealth.textContent.includes('当前恢复降级窗口：claude')
          && routeBox.textContent.includes('恢复阶段会优先避开 claude');
      }, { timeout: 30000 });
    } catch (error) {
      waitError = error;
    }

    await page.waitForSelector('#agentInventory [data-provider-id="codex"]', { timeout: 30000 });
    await page.click('#agentInventory [data-provider-id="codex"]');
    await page.waitForFunction(() => {
      const detail = document.querySelector('#agentDetail');
      return detail && detail.textContent.includes('Codex') && detail.textContent.includes('运行 Preflight');
    }, { timeout: 30000 });
    await page.waitForSelector('#agentDetail [data-provider-action="preflight"][data-provider-id="codex"]', { timeout: 30000 });
    await page.click('#agentDetail [data-provider-action="preflight"][data-provider-id="codex"]');
    await page.waitForFunction(() => {
      const detail = document.querySelector('#agentDetail');
      const probe = window.__consoleProviderPreflightProbe || {};
      return probe.preflightCount === 1
        && probe.lastPreflightMethod === 'POST'
        && detail
        && detail.textContent.includes('provider preflight result')
        && detail.textContent.includes('active_probe')
        && detail.textContent.includes('codex 0.0.0-probe')
        && detail.textContent.includes('worker dispatch probe');
    }, { timeout: 30000 });

    const result = await page.evaluate(() => {
      const runtimeHealth = document.querySelector('#runtimeHealth');
      const routeBox = document.querySelector('#routeBox');
      const agentDetail = document.querySelector('#agentDetail');
      const providerComparison = Array.from(document.querySelectorAll('.provider-stats-row')).map((row) => row.textContent.replace(/\s+/g, ' ').trim());
      const activeTaskCard = document.querySelector('.task-card.is-active');
      const probe = window.__consoleProviderPreflightProbe || {};
      return {
        hash: window.location.hash || '',
        runtimeHealthText: runtimeHealth ? runtimeHealth.textContent.replace(/\s+/g, ' ').trim() : '',
        routeBoxText: routeBox ? routeBox.textContent.replace(/\s+/g, ' ').trim() : '',
        agentDetailText: agentDetail ? agentDetail.textContent.replace(/\s+/g, ' ').trim() : '',
        providerComparison,
        activeTaskText: activeTaskCard ? activeTaskCard.textContent.replace(/\s+/g, ' ').trim() : '',
        preflightCount: probe.preflightCount || 0,
        lastPreflightMethod: probe.lastPreflightMethod || null
      };
    });

    await page.screenshot({ path: args.screenshotPath, fullPage: true });

    const report = {
      base_url: args.baseUrl,
      console_url: targetUrl,
      session_id: fixture.session.id,
      task_id: fixture.task.id,
      screenshot: args.screenshotPath,
      wait_error: waitError ? String(waitError.message || waitError) : null,
      checks: {
        runtime_health_window: result.runtimeHealthText.includes('当前恢复降级窗口：claude'),
        provider_row_hint: result.providerComparison.some((text) => text.includes('恢复阶段会优先避开 claude')),
        route_box_hint: result.routeBoxText.includes('恢复阶段会优先避开 claude'),
        provider_preflight_post: result.preflightCount === 1 && result.lastPreflightMethod === 'POST',
        provider_preflight_rendered: result.agentDetailText.includes('provider preflight result')
          && result.agentDetailText.includes('active_probe')
          && result.agentDetailText.includes('codex 0.0.0-probe'),
        worker_dispatch_probe_rendered: result.agentDetailText.includes('worker dispatch probe')
          && result.agentDetailText.includes('dispatch ready')
      },
      observed: result
    };

    fs.writeFileSync(args.reportPath, JSON.stringify(report, null, 2));

    const failures = Object.entries(report.checks).filter(([, passed]) => !passed);
    if (failures.length > 0) {
      console.error(JSON.stringify(report, null, 2));
      throw new Error(`console provider window probe failed: ${failures.map(([name]) => name).join(', ')}`);
    }

    console.log(JSON.stringify(report, null, 2));
  } finally {
    await browser.close();
  }
}

main().catch((error) => {
  console.error(error && error.stack ? error.stack : error);
  process.exit(1);
});
