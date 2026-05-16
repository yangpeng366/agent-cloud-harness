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

const DEFAULT_BASE_URL = 'http://localhost:8080';
const DEFAULT_REPORT_PATH = path.resolve(__dirname, '../.tmp/dialogue-business-smoke-report.json');
const RECEIPT_RE = /已记录|已推进|已完成|已写入当前任务上下文/;

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
    baseUrl: DEFAULT_BASE_URL,
    reportPath: DEFAULT_REPORT_PATH,
    headless: true,
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
    if (arg === '--headful') {
      parsed.headless = false;
      continue;
    }
  }

  return parsed;
}

function ensureDir(filePath) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
}

function pushStep(report, name, passed, details) {
  report.steps.push({ name, passed, details });
  console.log(`${passed ? 'OK' : 'FAIL'} ${name}`);
  if (details) {
    console.log(`   ${details}`);
  }
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

async function fetchTask(baseUrl, taskId) {
  const payload = await fetchJson(
    `${baseUrl.replace(/\/+$/, '')}/api/v1/tasks/${encodeURIComponent(taskId)}`
  );
  return payload?.data ?? payload;
}

async function waitForHealth(baseUrl, timeoutMs = 30000) {
  const healthUrl = `${baseUrl.replace(/\/+$/, '')}/api/v1/health`;
  const deadline = Date.now() + timeoutMs;
  let lastError = null;

  while (Date.now() < deadline) {
    try {
      const response = await fetch(healthUrl);
      if (response.ok) {
        return;
      }
      lastError = new Error(`health status=${response.status}`);
    } catch (error) {
      lastError = error;
    }
    await sleep(800);
  }

  throw lastError || new Error(`health check timed out for ${healthUrl}`);
}

async function waitForShell(page) {
  await page.waitForSelector('.dialogue-shell', { timeout: 30000 });
  await page.waitForSelector('#sessionForm', { timeout: 30000 });
  await page.waitForSelector('#taskForm', { timeout: 30000 });
  await page.waitForSelector('#taskIntent', { timeout: 30000 });
  await sleep(1200);
}

async function openDialogueShell(page, baseUrl) {
  const targetUrl = `${baseUrl.replace(/\/+$/, '')}/dialogue/`;
  for (let attempt = 1; attempt <= 2; attempt += 1) {
    try {
      await page.goto(targetUrl, {
        waitUntil: 'domcontentloaded',
        timeout: 30000,
      });
      await waitForShell(page);
      return targetUrl;
    } catch (error) {
      const message = error.message || '';
      if (!/Navigation timeout/i.test(message)
        && !/ERR_ABORTED/i.test(message)
        && !/waiting for selector/i.test(message)) {
        throw error;
      }
      if (attempt === 2) {
        throw error;
      }
      log(`dialogue shell open is unstable, retrying once: ${targetUrl}`);
      try {
        await page.goto('about:blank', { waitUntil: 'load', timeout: 10000 });
      } catch (_) {
        // ignore reset errors before retry
      }
    }
  }
  throw new Error(`failed to open dialogue shell: ${targetUrl}`);
}

async function requestSubmit(page, formSelector) {
  await page.$eval(formSelector, (form) => {
    if (typeof form.requestSubmit === 'function') {
      form.requestSubmit();
      return;
    }
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  });
}

async function checkboxState(page, selector) {
  return page.$eval(selector, (input) => Boolean(input.checked));
}

async function setCheckbox(page, selector, checked) {
  const current = await checkboxState(page, selector);
  if (current === checked) {
    return;
  }
  try {
    await page.click(selector);
  } catch (_) {
    // fall through to DOM-level fallback below
  }
  try {
    await page.waitForFunction(
      (targetSelector, expectedChecked) => {
        const input = document.querySelector(targetSelector);
        return !!input && Boolean(input.checked) === expectedChecked;
      },
      { timeout: 3000 },
      selector,
      checked
    );
    return;
  } catch (_) {
    // fall back to explicit DOM mutation for flaky checkbox convergence
  }
  await page.$eval(
    selector,
    (input, expectedChecked) => {
      input.checked = expectedChecked;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    },
    checked
  );
  await page.waitForFunction(
    (targetSelector, expectedChecked) => {
      const input = document.querySelector(targetSelector);
      return !!input && Boolean(input.checked) === expectedChecked;
    },
    { timeout: 10000 },
    selector,
    checked
  );
}

async function createSession(page, title) {
  const before = await page.evaluate(() => ({
    sessionCount: document.querySelectorAll('.session-card').length,
    hash: window.location.hash,
  }));

  const createSessionResponse = page.waitForResponse(
    (response) => response.request().method() === 'POST' && /\/api\/v1\/sessions$/.test(response.url()),
    { timeout: 30000 }
  );

  await page.locator('#sessionTitle').fill(title);
  await requestSubmit(page, '#sessionForm');

  const sessionResponse = await createSessionResponse;
  const sessionPayload = await sessionResponse.json().catch(() => null);

  await page.waitForFunction(
    (expectedCount, previousHash) => {
      const sessionCards = document.querySelectorAll('.session-card').length;
      const activeSession = document.querySelector('.session-card.is-active');
      const activeTitle = activeSession?.querySelector('.session-card__title')?.textContent?.trim() || '';
      const params = new URLSearchParams(window.location.hash.replace(/^#/, ''));
      const selectedSession = params.get('session') || '';
      return (
        sessionCards >= expectedCount + 1 &&
        !!activeSession &&
        !!selectedSession &&
        activeTitle.length > 0 &&
        window.location.hash !== previousHash
      );
    },
    { timeout: 30000 },
    before.sessionCount,
    before.hash
  );

  await sleep(1000);
  const state = await page.evaluate(() => ({
    selectedSessionLabel: document.querySelector('#composerSessionLabel')?.textContent.trim() || '',
    sessionCount: document.querySelectorAll('.session-card').length,
    hasActiveSession: !!document.querySelector('.session-card.is-active'),
    activeSessionTitle: document.querySelector('.session-card.is-active .session-card__title')?.textContent.trim() || '',
    hash: window.location.hash,
  }));

  return {
    ...state,
    responseStatus: sessionResponse.status(),
    responseSessionId: sessionPayload?.data?.id || sessionPayload?.id || '',
  };
}

async function submitDefaultTaskAuto(page, intent) {
  const before = await page.evaluate(() => ({
    messageCount: document.querySelectorAll('.message-list > *').length,
    hash: window.location.hash,
  }));

  await page.locator('#taskIntent').fill(intent);
  await requestSubmit(page, '#taskForm');

  await page.waitForFunction(
    () => {
      const inline = document.querySelector('#composerInlineState');
      const messageCount = document.querySelectorAll('.message-list > *').length;
      return (inline && /已记录|已推进|已完成/.test(inline.textContent || '')) || messageCount > 0;
    },
    { timeout: 30000 }
  );

  await page.waitForFunction(
    (previousMessageCount, previousHash) => {
      const messageCount = document.querySelectorAll('.message-list > *').length;
      const detailTitle = document.querySelector('#detailTitle')?.textContent?.trim() || '';
      return messageCount > previousMessageCount
        && window.location.hash.includes('task=')
        && window.location.hash !== previousHash
        && detailTitle.length > 0
        && detailTitle !== '选择一个任务';
    },
    { timeout: 30000 },
    before.messageCount,
    before.hash
  );

  return page.evaluate(() => ({
    inlineState: document.querySelector('#composerInlineState')?.textContent.trim() || '',
    hash: window.location.hash,
    messageCount: document.querySelectorAll('.message-list > *').length,
    detailTitle: document.querySelector('#detailTitle')?.textContent.trim() || '',
  }));
}

async function assertPinnedLatestRoundOutput(page) {
  await page.waitForFunction(
    () => {
      const pinned = document.querySelector('[data-testid="pinned-latest-round-output"]')?.textContent || '';
      const summary = document.querySelector('#messageSummary')?.textContent || '';
      return /latest round output/i.test(pinned) || /latest round output/i.test(summary);
    },
    { timeout: 30000 }
  );

  return page.evaluate(() => ({
    pinnedText: document.querySelector('[data-testid="pinned-latest-round-output"]')?.textContent.trim() || '',
    summaryText: document.querySelector('#messageSummary')?.textContent.trim() || '',
    selectedStatus: document.querySelector('#selectedStatus')?.textContent.trim() || '',
  }));
}

async function waitForTaskScopedMessages(baseUrl, sessionId, taskId, predicate, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs;
  let lastPayload = null;
  const requestUrl = `${baseUrl.replace(/\/+$/, '')}/api/v1/sessions/${encodeURIComponent(sessionId)}/messages?limit=20&task_id=${encodeURIComponent(taskId)}`;

  while (Date.now() < deadline) {
    lastPayload = await fetchJson(requestUrl);
    const messages = Array.isArray(lastPayload?.data) ? lastPayload.data : Array.isArray(lastPayload) ? lastPayload : [];
    const match = predicate(messages);
    if (match) {
      return { messages, match };
    }
    await sleep(800);
  }

  return {
    messages: Array.isArray(lastPayload?.data) ? lastPayload.data : Array.isArray(lastPayload) ? lastPayload : [],
    match: null,
  };
}

async function waitForSelectedTaskDetails(page, baseUrl, taskId, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs;
  let lastSnapshot = null;

  while (Date.now() < deadline) {
    const task = await fetchTask(baseUrl, taskId);
    lastSnapshot = await page.evaluate(() => ({
      hashTaskId: new URLSearchParams(window.location.hash.replace(/^#/, '')).get('task') || '',
      activeTaskId: document.querySelector('.dialogue-task.is-active')?.getAttribute('data-task-id') || '',
      detailTitle: document.querySelector('#detailTitle')?.textContent.trim() || '',
      selectedStatus: document.querySelector('#selectedStatus')?.textContent.trim() || '',
      submitDisabled: Boolean(document.querySelector('#submitTaskButton')?.disabled)
    }));
    const expectedTitle = String(task?.title || '').trim();
    const expectedNode = String(task?.control_node || task?.controlNode || '').trim();
    const expectedStatus = String(task?.status || '').trim();
    const statusOk = !expectedNode || lastSnapshot.selectedStatus.includes(expectedNode);
    const taskStateOk = !expectedStatus || lastSnapshot.selectedStatus.includes(expectedStatus);
    if (
      lastSnapshot.hashTaskId === taskId
      && lastSnapshot.activeTaskId === taskId
      && expectedTitle
      && lastSnapshot.detailTitle === expectedTitle
      && statusOk
      && taskStateOk
      && lastSnapshot.submitDisabled === false
    ) {
      return {
        ...lastSnapshot,
        expectedTitle,
        expectedNode,
        expectedStatus
      };
    }
    await sleep(500);
  }

  throw new Error(`selected task details did not settle for ${taskId}: ${JSON.stringify(lastSnapshot || {})}`);
}

async function submitManualStartTask(page, baseUrl, title, intent) {
  await page.locator('[data-composer-mode="task"]').click();
  await page.locator('#composerAdvanced summary').click();
  await sleep(300);
  await page.locator('#taskTitle').fill(title);
  await page.locator('#taskIntent').fill(intent);

  await setCheckbox(page, '#taskAutoStart', false);

  const before = await page.evaluate(() => ({
    hash: window.location.hash,
    taskCount: document.querySelectorAll('.dialogue-task').length,
  }));

  await requestSubmit(page, '#taskForm');

  await page.waitForFunction(
    (previousHash, previousTaskCount) => {
      const taskCount = document.querySelectorAll('.dialogue-task').length;
      return (
        window.location.hash.includes('task=') &&
        window.location.hash !== previousHash &&
        taskCount >= previousTaskCount
      );
    },
    { timeout: 30000 },
    before.hash,
    before.taskCount
  );
  const hashTaskId = await page.evaluate(() =>
    new URLSearchParams(window.location.hash.replace(/^#/, '')).get('task') || ''
  );
  const settled = await waitForSelectedTaskDetails(page, baseUrl, hashTaskId, 30000);

  return page.evaluate((settledTaskId) => ({
    hash: window.location.hash,
    detailTitle: document.querySelector('#detailTitle')?.textContent.trim() || '',
    inlineState: document.querySelector('#composerInlineState')?.textContent.trim() || '',
    selectedStatus: document.querySelector('#selectedStatus')?.textContent.trim() || '',
    activeTaskId: document.querySelector('.dialogue-task.is-active')?.getAttribute('data-task-id') || '',
    settledTaskId
  }), settled.hashTaskId);
}

async function submitContinueCurrent(page, baseUrl, intent) {
  await setCheckbox(page, '#taskContinueCurrent', true);
  await setCheckbox(page, '#taskAutoStart', false);

  const before = await page.evaluate(() => ({
    sessionId: new URLSearchParams(window.location.hash.replace(/^#/, '')).get('session') || '',
    taskId: new URLSearchParams(window.location.hash.replace(/^#/, '')).get('task') || '',
    hash: window.location.hash,
    detailTitle: document.querySelector('#detailTitle')?.textContent.trim() || '',
  }));

  await page.locator('#taskIntent').fill(intent);
  const createReplyRequest = page.waitForRequest(
    (request) => request.method() === 'POST' && /\/v1\/(chat\/completions|responses)$/.test(request.url()),
    { timeout: 30000 }
  );
  await requestSubmit(page, '#taskForm');
  await createReplyRequest;
  await page.waitForFunction(
    (previousHash) => window.location.hash.includes('task=') && window.location.hash === previousHash,
    { timeout: 30000 },
    before.hash
  );

  const taskMessages = await waitForTaskScopedMessages(
    baseUrl,
    before.sessionId,
    before.taskId,
    (messages) => {
      const note = messages.find((message) => {
        const type = String(message?.message_type || message?.messageType || message?.type || '').trim();
        return type === 'task_note' && (message?.content || '').includes(intent);
      });
      const reply = messages.find((message) => {
        const type = String(message?.message_type || message?.messageType || message?.type || '').trim();
        const content = message?.content || '';
        return message?.role === 'assistant'
          && type === 'chat_reply'
          && /已记录到当前任务上下文|等待手动继续/.test(content);
      });
      if (!note || !reply) {
        return null;
      }
      return {
        noteType: String(note?.message_type || note?.messageType || note?.type || '').trim(),
        replyPreview: String(reply.content || '').trim(),
      };
    }
  );

  const state = await page.evaluate(() => ({
    hash: window.location.hash,
    inlineState: document.querySelector('#composerInlineState')?.textContent.trim() || '',
    detailTitle: document.querySelector('#detailTitle')?.textContent.trim() || '',
  }));

  return {
    ...state,
    taskMessageCount: taskMessages.messages.length,
    taskNoteVerified: Boolean(taskMessages.match?.noteType),
    noteType: taskMessages.match?.noteType || '',
    replyPreview: taskMessages.match?.replyPreview || '',
  };
}

async function main() {
  const config = parseArgs(process.argv.slice(2));
  ensureDir(config.reportPath);

  const browserPath = process.env.PUPPETEER_EXECUTABLE_PATH || findBrowserPath();
  if (!browserPath) {
    console.error('Edge browser not found. Set PUPPETEER_EXECUTABLE_PATH.');
    process.exit(1);
  }

  const report = {
    generatedAt: new Date().toISOString(),
    baseUrl: config.baseUrl,
    steps: [],
  };

  const browser = await puppeteer.launch({
    executablePath: browserPath,
    headless: config.headless ? 'new' : false,
    ignoreHTTPSErrors: true,
    args: [
      '--ignore-certificate-errors',
      '--disable-background-networking',
      '--disable-background-timer-throttling',
      '--disable-breakpad',
      '--disable-component-update',
      '--disable-dev-shm-usage',
      '--disable-features=Translate,BackForwardCache,AcceptCHFrame,MediaRouter',
      '--disable-renderer-backgrounding',
      '--disable-sync',
      '--metrics-recording-only',
      '--no-first-run',
      '--no-default-browser-check',
      '--no-service-autorun',
    ],
  });

  const page = await browser.newPage();
  page.setDefaultTimeout(30000);
  await page.setViewport({ width: 1600, height: 1000 });
  page.on('console', (msg) => {
    console.log(`PAGE ${msg.type()}: ${msg.text()}`);
  });
  page.on('pageerror', (error) => {
    console.error(`PAGEERROR: ${error.stack || error.message}`);
  });
  page.on('requestfailed', (request) => {
    console.error(`REQUESTFAILED: ${request.method()} ${request.url()} ${request.failure()?.errorText || ''}`);
  });

  try {
    const sessionTitle = `dialogue smoke ${new Date().toISOString()}`;
    const messageIntent = `dialogue smoke default task auto ${new Date().toISOString()}`;
    const manualTaskTitle = 'dialogue smoke manual-start task';
    const manualTaskIntent = `dialogue smoke manual-start ${new Date().toISOString()}`;
    const continuationIntent = `dialogue smoke continue-current ${new Date().toISOString()}`;

    await waitForHealth(config.baseUrl);
    const dialogueUrl = await openDialogueShell(page, config.baseUrl);
    log(`访问 ${dialogueUrl}`);
    pushStep(report, 'open dialogue shell', true, 'dialogue shell loaded');

    const sessionResult = await createSession(page, sessionTitle);
    pushStep(
      report,
      'create session',
      sessionResult.hasActiveSession && sessionResult.responseStatus === 200,
      `selected=${sessionResult.selectedSessionLabel} activeTitle=${sessionResult.activeSessionTitle} sessions=${sessionResult.sessionCount} active=${sessionResult.hasActiveSession} hash=${sessionResult.hash} responseStatus=${sessionResult.responseStatus} responseSessionId=${sessionResult.responseSessionId}`
    );

    const messageResult = await submitDefaultTaskAuto(page, messageIntent);
    pushStep(
      report,
      'submit default task_auto',
      messageResult.hash.includes('task=') && messageResult.detailTitle.length > 0,
      `detail=${messageResult.detailTitle} inline=${messageResult.inlineState} hash=${messageResult.hash} messages=${messageResult.messageCount}`
    );

    const pinnedResult = await assertPinnedLatestRoundOutput(page);
    pushStep(
      report,
      'default task_auto pinned latest round output',
      /latest round output/i.test(pinnedResult.pinnedText + pinnedResult.summaryText),
      `status=${pinnedResult.selectedStatus} pinned=${pinnedResult.pinnedText || '<empty>'} summary=${pinnedResult.summaryText || '<empty>'}`
    );

    const manualTaskResult = await submitManualStartTask(page, config.baseUrl, manualTaskTitle, manualTaskIntent);
    pushStep(
      report,
      'submit manual-start task',
      manualTaskResult.hash.includes('task=') && manualTaskResult.detailTitle.length > 0 && manualTaskResult.activeTaskId === manualTaskResult.settledTaskId,
      `detail=${manualTaskResult.detailTitle} inline=${manualTaskResult.inlineState} hash=${manualTaskResult.hash} status=${manualTaskResult.selectedStatus} activeTask=${manualTaskResult.activeTaskId}`
    );

    const continuationResult = await submitContinueCurrent(page, config.baseUrl, continuationIntent);
    pushStep(
      report,
      'submit continue-current note',
      continuationResult.hash.includes('task=')
        && continuationResult.taskNoteVerified
        && (RECEIPT_RE.test(continuationResult.inlineState) || continuationResult.replyPreview.length > 0),
      `detail=${continuationResult.detailTitle} inline=${continuationResult.inlineState} hash=${continuationResult.hash} noteType=${continuationResult.noteType} taskMessages=${continuationResult.taskMessageCount} reply=${continuationResult.replyPreview || '<empty>'}`
    );
  } catch (error) {
    pushStep(report, 'business smoke aborted', false, error.message);
    fs.writeFileSync(config.reportPath, JSON.stringify(report, null, 2), 'utf8');
    await browser.close();
    throw error;
  }

  fs.writeFileSync(config.reportPath, JSON.stringify(report, null, 2), 'utf8');
  await browser.close();

  const failedSteps = report.steps.filter((step) => !step.passed);
  log(`业务 smoke 报告已写入: ${config.reportPath}`);
  if (failedSteps.length > 0) {
    console.error(`有 ${failedSteps.length} 个步骤未通过`);
    process.exit(1);
  }

  log('业务 smoke 通过');
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
