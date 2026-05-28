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

function parseArgs(argv) {
  const parsed = {
    baseUrl: 'http://localhost:8080',
    surface: 'dialogue',
    reportPath: path.resolve('.tmp/recovery-job-ui-probe.json'),
    screenshotPath: path.resolve('.tmp/recovery-job-ui-probe.png'),
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--base-url' && argv[i + 1]) {
      parsed.baseUrl = argv[++i];
      continue;
    }
    if (arg === '--surface' && argv[i + 1]) {
      parsed.surface = argv[++i];
      continue;
    }
    if (arg === '--report' && argv[i + 1]) {
      parsed.reportPath = path.resolve(argv[++i]);
      continue;
    }
    if (arg === '--screenshot' && argv[i + 1]) {
      parsed.screenshotPath = path.resolve(argv[++i]);
    }
  }
  if (!['dialogue', 'console'].includes(parsed.surface)) {
    throw new Error('--surface must be dialogue or console');
  }
  return parsed;
}

function log(message) {
  const stamp = new Date().toLocaleString('zh-CN', { hour12: false });
  console.log(`[${stamp}] ${message}`);
}

async function httpJson(url, options = {}) {
  const response = await fetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {})
    }
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText} for ${url}: ${text}`);
  }
  const json = text ? JSON.parse(text) : null;
  return json?.data ?? json;
}

async function createFixture(baseUrl) {
  const session = await httpJson(`${baseUrl}/api/v1/sessions`, {
    method: 'POST',
    body: JSON.stringify({ title: 'recovery job ui probe session' })
  });
  const task = await httpJson(`${baseUrl}/api/v1/tasks`, {
    method: 'POST',
    body: JSON.stringify({
      title: 'recovery job ui probe task',
      task_type: 'coding',
      source: 'user',
      priority: 'medium',
      intent: 'verify recovery job ui async recover path',
      session_id: session.id,
      auto_start: false,
      metadata: {
        failure_class: 'worker_runtime_transient',
        provider_failure_class: 'provider_runtime_transient'
      }
    })
  });
  await httpJson(`${baseUrl}/api/v1/tasks/${encodeURIComponent(task.id)}/state`, {
    method: 'POST',
    body: JSON.stringify({
      state: 'failed',
      reason: 'probe: thread not found'
    })
  });
  return { session, task };
}

function buildRecoveryJob(task, requestId) {
  return {
    id: requestId,
    task_id: task.id,
    session_id: task.session_id || task.sessionId,
    status: 'running',
    mode: 'auto',
    recommended_action: 'fresh_session_resume',
    target_worker: 'codex',
    recovery_execution_mode: 'fresh_session',
    failure_class: 'worker_runtime_transient',
    provider_failure_class: 'provider_runtime_transient',
    status_url: `/api/v1/tasks/${task.id}/live_flow`,
    accepted_at: new Date().toISOString(),
    started_at: new Date().toISOString(),
    metadata: {
      recovery_action: 'fresh_session_resume'
    }
  };
}

function surfaceUrl(baseUrl, surface, fixture) {
  const root = baseUrl.replace(/\/+$/, '');
  const hash = `#session=${encodeURIComponent(fixture.session.id)}&task=${encodeURIComponent(fixture.task.id)}`;
  return `${root}/${surface}/${hash}`;
}

async function installFetchProbe(page, fixture, requestId) {
  await page.evaluateOnNewDocument((payload) => {
    const originalFetch = window.fetch.bind(window);
    const recoveries = [];
    const jobs = [payload.recoveryJob];
    const okJson = (data, status = 200) => new Response(JSON.stringify({ success: true, data }), {
      status,
      headers: { 'Content-Type': 'application/json' }
    });
    window.__recoveryJobUiProbe = { recoveries, jobs };
    window.fetch = async (input, init = {}) => {
      const url = typeof input === 'string' ? input : String(input && input.url ? input.url : '');
      if (url.includes(`/api/v1/tasks/${payload.taskId}/recovery_jobs`)) {
        return okJson(jobs);
      }
      if (url.includes(`/api/v1/tasks/${payload.taskId}/recover`)) {
        recoveries.push({
          url,
          method: init.method || 'GET',
          body: init.body || ''
        });
        return okJson({
          accepted: true,
          async: true,
          request_id: payload.requestId,
          status_url: `/api/v1/tasks/${payload.taskId}/live_flow`,
          plan: {
            recoverable: true,
            recommended_action: 'resume',
            recovery_execution_mode: 'fresh_session',
            provider_failure_class: 'provider_runtime_transient'
          }
        }, 202);
      }
      return originalFetch(input, init);
    };
  }, {
    taskId: fixture.task.id,
    requestId,
    recoveryJob: buildRecoveryJob(fixture.task, requestId)
  });
}

async function clickRecover(page) {
  const waits = {
    recoverRequest: false,
    recoveryJobVisible: false,
    error: null
  };
  await page.waitForSelector('[data-task-action="recover"]', { timeout: 30000 });
  await page.click('[data-task-action="recover"]');
  try {
    await page.waitForFunction(() => {
      return (window.__recoveryJobUiProbe?.recoveries || []).some((entry) => entry.url.includes('recover?async=true'));
    }, { timeout: 30000 });
    waits.recoverRequest = true;
    await page.waitForFunction(() => {
      const overview = document.querySelector('#taskOverview');
      return overview
        && overview.textContent.includes('Recovery Job')
        && overview.textContent.includes('recovery_probe_request');
    }, { timeout: 30000 });
    waits.recoveryJobVisible = true;
  } catch (error) {
    waits.error = String(error && error.message ? error.message : error);
  }
  return waits;
}

async function collectResult(page) {
  return page.evaluate(() => {
    const overview = document.querySelector('#taskOverview');
    const actions = document.querySelector('#taskActions');
    return {
      hash: window.location.hash || '',
      overviewText: overview ? overview.textContent.replace(/\s+/g, ' ').trim() : '',
      actionsText: actions ? actions.textContent.replace(/\s+/g, ' ').trim() : '',
      recoveries: window.__recoveryJobUiProbe?.recoveries || []
    };
  });
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const browserPath = process.env.PUPPETEER_EXECUTABLE_PATH || findBrowserPath();
  if (!browserPath) {
    throw new Error('Edge browser not found. Set PUPPETEER_EXECUTABLE_PATH.');
  }

  fs.mkdirSync(path.dirname(args.reportPath), { recursive: true });
  fs.mkdirSync(path.dirname(args.screenshotPath), { recursive: true });

  await httpJson(`${args.baseUrl.replace(/\/+$/, '')}/api/v1/health`);
  const fixture = await createFixture(args.baseUrl.replace(/\/+$/, ''));
  const requestId = 'recovery_probe_request';

  const browser = await puppeteer.launch({
    executablePath: browserPath,
    headless: 'new',
    ignoreHTTPSErrors: true,
    args: ['--ignore-certificate-errors']
  });

  try {
    const page = await browser.newPage();
    await page.setViewport({ width: args.surface === 'console' ? 1600 : 1440, height: 980 });
    page.setDefaultTimeout(30000);
    await installFetchProbe(page, fixture, requestId);

    const targetUrl = surfaceUrl(args.baseUrl, args.surface, fixture);
    log(`open recovery job UI probe: ${targetUrl}`);
    await page.goto(targetUrl, { waitUntil: 'domcontentloaded', timeout: 30000 });
    await page.waitForSelector('#taskOverview', { timeout: 30000 });
    const waits = await clickRecover(page);
    const observed = await collectResult(page);
    await page.screenshot({ path: args.screenshotPath, fullPage: true });

    const checks = {
      async_recover_request: observed.recoveries.some((entry) => entry.url.includes('recover?async=true')),
      request_body_mode_auto: observed.recoveries.some((entry) => String(entry.body || '').includes('"mode":"auto"')),
      recovery_job_visible: observed.overviewText.includes('Recovery Job'),
      request_id_visible: observed.overviewText.includes(requestId),
      running_status_visible: observed.overviewText.includes('running')
    };
    const report = {
      base_url: args.baseUrl,
      surface: args.surface,
      url: targetUrl,
      session_id: fixture.session.id,
      task_id: fixture.task.id,
      request_id: requestId,
      screenshot: args.screenshotPath,
      waits,
      checks,
      observed
    };
    fs.writeFileSync(args.reportPath, JSON.stringify(report, null, 2));

    const failures = Object.entries(checks).filter(([, passed]) => !passed);
    if (failures.length > 0) {
      console.error(JSON.stringify(report, null, 2));
      throw new Error(`recovery job UI probe failed: ${failures.map(([name]) => name).join(', ')}`);
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
