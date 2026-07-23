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
const DEFAULT_OUT_DIR = path.resolve(__dirname, '../.tmp/dialogue-shell-screens');

const PROFILE_DEFS = {
  desktop: {
    name: 'desktop',
    viewport: { width: 1600, height: 1000 },
    path: '/dialogue/',
  },
  narrow: {
    name: 'narrow',
    viewport: { width: 430, height: 932 },
    path: '/dialogue/',
  },
  responses: {
    name: 'responses',
    viewport: { width: 1600, height: 1000 },
    path: '/dialogue/#facade=responses',
  },
};

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
    outDir: DEFAULT_OUT_DIR,
    profiles: ['desktop', 'narrow', 'responses'],
    reportPath: '',
  };

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--base-url' && argv[i + 1]) {
      parsed.baseUrl = argv[++i];
      continue;
    }
    if (arg === '--out-dir' && argv[i + 1]) {
      parsed.outDir = path.resolve(argv[++i]);
      continue;
    }
    if (arg === '--profile' && argv[i + 1]) {
      const raw = argv[++i].trim();
      parsed.profiles = raw === 'all'
        ? ['desktop', 'narrow', 'responses']
        : raw.split(',').map((value) => value.trim()).filter(Boolean);
      continue;
    }
    if (arg === '--report' && argv[i + 1]) {
      parsed.reportPath = path.resolve(argv[++i]);
      continue;
    }
  }

  const unknownProfiles = parsed.profiles.filter((name) => !PROFILE_DEFS[name]);
  if (unknownProfiles.length > 0) {
    throw new Error(`unknown profile(s): ${unknownProfiles.join(', ')}`);
  }

  if (!parsed.reportPath) {
    parsed.reportPath = path.join(parsed.outDir, 'dialogue-shell-report.json');
  }

  return parsed;
}

function ensureDir(dirPath) {
  fs.mkdirSync(dirPath, { recursive: true });
}

function buildUrl(baseUrl, profilePath) {
  return `${baseUrl.replace(/\/+$/, '')}${profilePath}`;
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitForHealth(baseUrl, timeoutMs = 30000) {
  const healthUrl = buildUrl(baseUrl, '/api/v1/health');
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
    await delay(800);
  }

  throw lastError || new Error(`health check timed out for ${healthUrl}`);
}

async function waitForDialogueShell(page) {
  await page.waitForSelector('.dialogue-shell', { timeout: 30000 });
  await page.waitForSelector('.workspace', { timeout: 30000 });
  await page.waitForSelector('#taskIntent', { timeout: 30000 });
  await page.waitForSelector('.message-list', { timeout: 30000 });
  await new Promise((resolve) => setTimeout(resolve, 1500));
}

async function openDialogueShell(page, url) {
  for (let attempt = 1; attempt <= 2; attempt += 1) {
    try {
      await page.goto(url, {
        waitUntil: 'domcontentloaded',
        timeout: 30000,
      });
      await waitForDialogueShell(page);
      return;
    } catch (error) {
      const message = error.message || '';
      const retryable = /Navigation timeout/i.test(message)
        || /ERR_ABORTED/i.test(message)
        || /waiting for selector/i.test(message);
      if (!retryable || attempt === 2) {
        throw error;
      }
      log(`dialogue shell open is unstable, retrying once: ${url}`);
      try {
        await page.goto('about:blank', { waitUntil: 'load', timeout: 10000 });
      } catch (_) {
        // ignore reset errors before retry
      }
    }
  }
}

async function collectLayoutInfo(page) {
  return page.evaluate(() => {
    function toText(selector) {
      const node = document.querySelector(selector);
      return node ? node.textContent.trim() : '';
    }

    function visible(el) {
      if (!el) {
        return false;
      }
      if (el.hidden) {
        return false;
      }
      const style = window.getComputedStyle(el);
      if (style.display === 'none'
        && style.visibility !== 'hidden'
        && Number(style.opacity || '1') !== 0) {
        return false;
      }
      return el.getClientRects().length > 0;
    }

    function rect(el) {
      if (!el) {
        return { width: 0, height: 0 };
      }
      const box = el.getBoundingClientRect();
      return {
        width: Math.round(box.width),
        height: Math.round(box.height),
      };
    }

    const shell = document.querySelector('.dialogue-shell');
    const sidebar = document.querySelector('.sidebar');
    const workspace = document.querySelector('.workspace');
    const details = document.querySelector('.details');
    const detailsEmpty = document.querySelector('#taskDetailsEmpty');
    const taskOverview = document.querySelector('#taskOverview');
    const taskActions = document.querySelector('#taskActions');
    const taskDetailsScroll = document.querySelector('#taskDetailsScroll');
    const messagePanel = document.querySelector('.message-panel');
    const messageSummary = document.querySelector('#messageSummary');
    const messageList = document.querySelector('.message-list');
    const composer = document.querySelector('.composer');
    const composerTextarea = document.querySelector('#taskIntent');
    const composerAdvanced = document.querySelector('#composerAdvanced');
    const composerContextBlock = document.querySelector('#composerContextBlock');
    const messageAttachTaskWrap = document.querySelector('#messageAttachTaskWrap');
    const followupButton = document.querySelector('#followupButton');
    const clearFollowupButton = document.querySelector('#clearFollowupButton');
    const header = document.querySelector('.workspace__header');
    const sessionBar = document.querySelector('.workspace__sessionbar');
    const subBar = document.querySelector('.workspace__subbar');
    const threadDrawer = document.querySelector('#threadDrawer');
    const threadDrawerSummary = document.querySelector('.thread-drawer__summary');
    const sidebarToggle = document.querySelector('#sidebarToggle');
    const detailsToggle = document.querySelector('#detailsToggleButton');
    const surfaceLinks = Array.from(document.querySelectorAll('.surface-switch__link'));
    const modeButtons = Array.from(document.querySelectorAll('.composer-mode-switch button'));
    const collapsibleCards = Array.from(document.querySelectorAll('.details-card--collapsible'));
    const openCollapsibleCards = collapsibleCards.filter((card) => card.open);

    return {
      url: window.location.href,
      hash: window.location.hash,
      taskHashPresent: window.location.hash.includes('task='),
      title: document.title,
      viewport: {
        width: window.innerWidth,
        height: window.innerHeight,
      },
      healthStatus: toText('.health-pill'),
      shellPresent: !!shell,
      sidebarPresent: !!sidebar,
      workspacePresent: !!workspace,
      detailsPresent: !!details,
      messagePanelPresent: !!messagePanel,
      composerPresent: !!composer,
      composerTextareaPresent: !!composerTextarea,
      sidebarTogglePresent: !!sidebarToggle,
      detailsTogglePresent: !!detailsToggle,
      sessionCardCount: document.querySelectorAll('.session-card').length,
      hasActiveSession: !!document.querySelector('.session-card.is-active'),
      workspaceSurfaceTitle: toText('#workspaceSurfaceTitle'),
      composerSessionLabel: toText('#composerSessionLabel'),
      composerModeLabels: modeButtons.map((button) => button.textContent.trim()),
      composerModeCount: modeButtons.length,
      composerAdvancedOpen: !!composerAdvanced && composerAdvanced.open,
      composerContextVisible: visible(composerContextBlock),
      composerContextHiddenAttr: !!composerContextBlock && composerContextBlock.hidden,
      detailsEmptyVisible: visible(detailsEmpty),
      taskOverviewVisible: visible(taskOverview),
      taskActionsVisible: visible(taskActions),
      taskDetailsScrollVisible: visible(taskDetailsScroll),
      messageAttachVisible: visible(messageAttachTaskWrap),
      messageAttachHiddenAttr: !!messageAttachTaskWrap && messageAttachTaskWrap.hidden,
      followupButtonVisible: visible(followupButton),
      followupButtonHiddenAttr: !!followupButton && followupButton.hidden,
      clearFollowupButtonVisible: visible(clearFollowupButton),
      clearFollowupButtonHiddenAttr: !!clearFollowupButton && clearFollowupButton.hidden,
      messageCount: messageList ? messageList.children.length : 0,
      messageSummaryCount: messageSummary ? messageSummary.children.length : 0,
      threadDrawerOpen: !!threadDrawer && threadDrawer.open,
      threadDrawerVisible: visible(threadDrawer),
      routeToResponsesSurface: window.location.hash.includes('facade=responses'),
      activeSurfaceLabel: surfaceLinks.find((link) => link.classList.contains('surface-switch__link--active'))?.textContent.trim() || '',
      shellRect: rect(shell),
      sidebarRect: rect(sidebar),
      workspaceRect: rect(workspace),
      detailsRect: rect(details),
      headerRect: rect(header),
      sessionBarRect: rect(sessionBar),
      subBarRect: rect(subBar),
      messagePanelRect: rect(messagePanel),
      messageSummaryRect: rect(messageSummary),
      composerRect: rect(composer),
      threadDrawerSummaryRect: rect(threadDrawerSummary),
      sidebarVisible: visible(sidebar),
      detailsVisible: visible(details),
      composerVisible: visible(composer),
      sidebarBackdropVisible: visible(document.querySelector('#sidebarBackdrop')),
      openInspectorCardCount: openCollapsibleCards.length,
      collapsibleInspectorCardCount: collapsibleCards.length,
    };
  });
}

function evaluateProfile(profileName, info) {
  const checks = [];
  const narrowProfile = profileName === 'narrow';
  const expectVisibleDetailsRail = !narrowProfile;

  function addCheck(name, passed, detail) {
    checks.push({ name, passed, detail });
  }

  addCheck('shell present', info.shellPresent, 'dialogue shell should render');
  addCheck('workspace present', info.workspacePresent, 'workspace should render');
  addCheck('transcript present', info.messagePanelPresent, 'message panel should render');
  addCheck('composer present', info.composerPresent && info.composerTextareaPresent, 'composer and textarea should exist');
  addCheck(
    'transcript-first title',
    typeof info.workspaceSurfaceTitle === 'string' && info.workspaceSurfaceTitle.includes('Transcript'),
    `workspace title: ${info.workspaceSurfaceTitle || '(empty)'}`
  );
  addCheck(
    'single visible composer mode bar',
    info.composerModeCount <= 2,
    `visible mode buttons: ${info.composerModeLabels.join(' / ') || '(none)'}`
  );
  addCheck(
    'advanced controls collapsed by default',
    info.composerAdvancedOpen === false,
    `composerAdvanced.open=${info.composerAdvancedOpen}`
  );
  addCheck(
    'default dialogue shell does not auto-select task',
    info.taskHashPresent === false,
    `hash=${info.hash || '(empty)'}`
  );
  addCheck(
    'session-scoped shell keeps task-only composer actions hidden',
    info.taskHashPresent
      || (
        info.messageAttachHiddenAttr === true
        && info.followupButtonHiddenAttr === true
        && info.clearFollowupButtonHiddenAttr === true
        && info.messageAttachVisible === false
        && info.followupButtonVisible === false
        && info.clearFollowupButtonVisible === false
      ),
    `taskHash=${info.taskHashPresent} attach hidden=${info.messageAttachHiddenAttr}/${info.messageAttachVisible} followup hidden=${info.followupButtonHiddenAttr}/${info.followupButtonVisible} clearFollowup hidden=${info.clearFollowupButtonHiddenAttr}/${info.clearFollowupButtonVisible}`
  );
  addCheck(
    'session-scoped shell keeps composer context hidden',
    info.taskHashPresent
      || (info.composerContextHiddenAttr === true && info.composerContextVisible === false),
    `taskHash=${info.taskHashPresent} context hidden=${info.composerContextHiddenAttr}/${info.composerContextVisible}`
  );
  addCheck(
    'default shell keeps details folded or lightweight',
    info.taskHashPresent
      || (
        info.detailsVisible === false
          || (
            expectVisibleDetailsRail
              && info.detailsRect.width > 0
              && info.detailsEmptyVisible === true
              && info.taskOverviewVisible === false
              && info.taskActionsVisible === false
              && info.taskDetailsScrollVisible === false
          )
      )
      || (
        info.detailsEmptyVisible === true
        && info.taskOverviewVisible === false
        && info.taskActionsVisible === false
        && info.taskDetailsScrollVisible === false
      ),
    `taskHash=${info.taskHashPresent} detailsVisible=${info.detailsVisible} empty=${info.detailsEmptyVisible} overview=${info.taskOverviewVisible} actions=${info.taskActionsVisible} scroll=${info.taskDetailsScrollVisible}`
  );
  addCheck(
    'workspace dominates inspector',
    info.workspaceRect.width > 0 && info.workspaceRect.width >= info.detailsRect.width,
    `workspace=${info.workspaceRect.width}px details=${info.detailsRect.width}px`
  );
  addCheck(
    'rail stays secondary',
    info.sidebarRect.width > 0 && (
      narrowProfile
        ? (info.sidebarRect.height > 0 && info.workspaceRect.height >= info.sidebarRect.height * 8)
        : info.workspaceRect.width > info.sidebarRect.width * 4
    ),
    narrowProfile
      ? `rail=${info.sidebarRect.width}x${info.sidebarRect.height}px workspace=${info.workspaceRect.width}x${info.workspaceRect.height}px`
      : `rail=${info.sidebarRect.width}px workspace=${info.workspaceRect.width}px`
  );
  addCheck(
    'header stays lighter than transcript',
    info.headerRect.height > 0 && info.messagePanelRect.height > info.headerRect.height * 3,
    `header=${info.headerRect.height}px transcript=${info.messagePanelRect.height}px`
  );
  addCheck(
    'transcript dominates composer vertically',
    info.messagePanelRect.height > 0 && info.messagePanelRect.height >= info.composerRect.height,
    `transcript=${info.messagePanelRect.height}px composer=${info.composerRect.height}px`
  );
  addCheck(
    'summary stays subordinate to transcript',
    info.messageSummaryCount === 0
      || (info.messageSummaryRect.height > 0 && info.messagePanelRect.height > info.messageSummaryRect.height * 3),
    `summary=${info.messageSummaryRect.height}px transcript=${info.messagePanelRect.height}px count=${info.messageSummaryCount}`
  );
  addCheck(
    'thread drawer collapsed by default',
    info.threadDrawerVisible === false || info.threadDrawerOpen === false,
    `visible=${info.threadDrawerVisible} open=${info.threadDrawerOpen}`
  );
  addCheck(
    'inspector mostly folded',
    info.collapsibleInspectorCardCount === 0 || info.openInspectorCardCount <= 1,
    `open=${info.openInspectorCardCount} total=${info.collapsibleInspectorCardCount}`
  );

  if (profileName === 'narrow') {
    addCheck(
      'narrow viewport applied',
      info.viewport.width <= 480,
      `viewport width=${info.viewport.width}`
    );
    addCheck(
      'sidebar toggle exists on narrow profile',
      info.sidebarTogglePresent,
      'sidebar toggle should be available'
    );
  }

  if (profileName === 'responses') {
    addCheck(
      'responses surface hash applied',
      info.routeToResponsesSurface,
      `hash=${info.hash || '(empty)'}`
    );
  }

  const failures = checks.filter((check) => !check.passed);
  return {
    passed: failures.length === 0,
    checks,
    failures,
  };
}

async function captureProfile(browser, config, profile) {
  const page = await browser.newPage();
  page.setDefaultTimeout(30000);
  await page.setViewport(profile.viewport);

  const url = buildUrl(config.baseUrl, profile.path);
  const screenshotPath = path.join(config.outDir, `dialogue-shell-${profile.name}.png`);

  try {
    log(`访问 ${profile.name}: ${url}`);
    await openDialogueShell(page, url);

    await page.screenshot({
      path: screenshotPath,
      fullPage: true,
      type: 'png',
    });

    const info = await collectLayoutInfo(page);
    const evaluation = evaluateProfile(profile.name, info);
    return {
      profile: profile.name,
      url,
      screenshotPath,
      info,
      evaluation,
    };
  } finally {
    await page.close();
  }
}

function printProfileSummary(result) {
  console.log('');
  console.log(`=== ${result.profile} ===`);
  console.log(`url: ${result.url}`);
  console.log(`screenshot: ${result.screenshotPath}`);
  console.log(`viewport: ${result.info.viewport.width}x${result.info.viewport.height}`);
  console.log(`surface title: ${result.info.workspaceSurfaceTitle}`);
  console.log(`composer modes: ${result.info.composerModeLabels.join(' / ') || '(none)'}`);
  console.log(`advanced open: ${result.info.composerAdvancedOpen}`);
  console.log(`workspace/details: ${result.info.workspaceRect.width}/${result.info.detailsRect.width}`);
  console.log(`collapsible inspector cards open/total: ${result.info.openInspectorCardCount}/${result.info.collapsibleInspectorCardCount}`);
  for (const check of result.evaluation.checks) {
    console.log(`${check.passed ? '  ✅' : '  ❌'} ${check.name} — ${check.detail}`);
  }
}

async function main() {
  let config;
  try {
    config = parseArgs(process.argv.slice(2));
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }

  ensureDir(config.outDir);
  ensureDir(path.dirname(config.reportPath));

  const browserPath = process.env.PUPPETEER_EXECUTABLE_PATH || findBrowserPath();
  if (!browserPath) {
    console.error('找不到 Edge 浏览器，请设置 PUPPETEER_EXECUTABLE_PATH 环境变量');
    process.exit(1);
  }

  log(`找到浏览器: ${browserPath}`);
  log(`输出目录: ${config.outDir}`);
  await waitForHealth(config.baseUrl);

  const browser = await puppeteer.launch({
    executablePath: browserPath,
    headless: 'new',
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

  const report = {
    generatedAt: new Date().toISOString(),
    baseUrl: config.baseUrl,
    outDir: config.outDir,
    reportPath: config.reportPath,
    profiles: [],
  };

  let failedProfiles = 0;
  try {
    for (const profileName of config.profiles) {
      const result = await captureProfile(browser, config, PROFILE_DEFS[profileName]);
      report.profiles.push(result);
      printProfileSummary(result);
      if (!result.evaluation.passed) {
        failedProfiles += 1;
      }
    }
  } catch (error) {
    console.error('截图过程出错:', error);
    await browser.close();
    process.exit(1);
  }

  await browser.close();

  fs.writeFileSync(config.reportPath, JSON.stringify(report, null, 2), 'utf8');
  console.log('');
  log(`结构化报告已写入: ${config.reportPath}`);

  if (failedProfiles > 0) {
    console.error(`有 ${failedProfiles} 个 profile 未通过壳层断言`);
    process.exit(1);
  }

  log('全部 profile 通过壳层断言');
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
