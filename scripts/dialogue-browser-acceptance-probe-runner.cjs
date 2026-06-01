const fs = require('fs');
const path = require('path');
const payloadPath = process.argv[2];
const payload = JSON.parse(payloadPath ? fs.readFileSync(payloadPath, 'utf8') : '{}');
const wsUrl = payload.wsUrl;
const dialogueUrl = payload.dialogueUrl;
const expectedSurface = payload.expectedSurface || 'chat_completions';
const mode = payload.mode || 'chat';
const screenshotDir = payload.screenshotDir || '';
const lifecycleMode = payload.lifecycleMode || 'real';

if (!wsUrl || !dialogueUrl) {
  throw new Error('wsUrl and dialogueUrl are required');
}

const PROTOCOL_TIMEOUT_MS = 15000;
const WAIT_STEP_MS = 250;
const WAIT_TIMEOUT_MS = 90000;
const FETCH_RESPONSE_CAPTURE_LIMIT = 16000;
const PROBE_FETCH_LIMIT = 24;
const PROBE_OVERRIDE_LIMIT = 8;
const PROBE_SIGNAL_LIMIT = 16;
const PROBE_HISTORY_LIMIT = 16;
const PROBE_ERROR_LIMIT = 12;
const PROBE_REQUEST_BODY_CAPTURE_LIMIT = 800;
const PROBE_SESSION_EVENT_LIMIT = 40;
const PROBE_TRACKED_SESSION_METHODS = new Set([
  'Runtime.exceptionThrown',
  'Runtime.consoleAPICalled',
  'Network.loadingFailed',
  'Log.entryAdded'
]);

async function main() {
  const cdp = await createBrowserConnection(wsUrl);
  try {
    const page = await openIsolatedPage(cdp);
    try {
      await navigate(page, dialogueUrl);
      await waitForCondition(page, () => {
        const submit = document.querySelector('#submitTaskButton');
        const sessionForm = document.querySelector('#sessionForm');
        const intent = document.querySelector('#taskIntent');
        const surfaceTitle = document.querySelector('#workspaceSurfaceTitle')?.textContent?.trim() || '';
        return Boolean(
          submit
          && sessionForm
          && intent
          && surfaceTitle === 'Session Transcript'
        );
      }, 'dialogue shell did not render');

      const surfaceLabel = await textContent(page, '#messageHint');
      const surfaceModeHint = await textContent(page, '#composerModeHint');
      const currentHash = await evaluate(page, () => window.location.hash || '');
      if (expectedSurface === 'responses' && !/facade=responses/.test(currentHash)) {
        throw new Error(`responses surface hash not applied: ${currentHash}`);
      }
      if (expectedSurface === 'chat_completions' && /facade=responses/.test(currentHash)) {
        throw new Error(`chat surface unexpectedly stayed on responses hash: ${currentHash}`);
      }
      const expectedSurfaceLabel = expectedSurface === 'responses' ? 'Responses façade' : 'Chat façade';
      if (surfaceLabel && surfaceLabel.includes('façade') && !surfaceLabel.includes(expectedSurfaceLabel)) {
        throw new Error(`surface label mismatch in messageHint: ${surfaceLabel}`);
      }
      if (expectedSurface === 'chat_completions' && /Responses façade/.test(surfaceLabel + surfaceModeHint)) {
        throw new Error(`chat surface unexpectedly rendered responses label: ${surfaceLabel} / ${surfaceModeHint}`);
      }
      if (expectedSurface === 'responses' && !/Responses façade/.test(surfaceLabel + surfaceModeHint) && !/facade=responses/.test(currentHash)) {
        throw new Error(`responses surface label/hash did not converge: ${surfaceLabel} / ${surfaceModeHint} / ${currentHash}`);
      }

      await installProbeHooks(page);

      const createdSessionTitle = `${mode} browser probe ${(new Date()).toISOString()}`;
      await setInputValue(page, '#sessionTitle', createdSessionTitle);
      await click(page, '#sessionForm button[type="submit"]');
      await waitForCondition(page, (expectedTitle) => {
        const cards = Array.from(document.querySelectorAll('#sessionList [data-session-id]'));
        return cards.some((card) => card.textContent.includes(expectedTitle));
      }, 'session was not created', createdSessionTitle);
      await waitForCondition(page, (expectedTitle) => {
        const activeSessionCard = document.querySelector('#sessionList .session-card.is-active');
        const composerSessionLabel = document.querySelector('#composerSessionLabel')?.textContent?.trim() || '';
        const taskThreadText = document.querySelector('#taskThread')?.textContent || '';
        const hash = window.location.hash || '';
        return Boolean(
          activeSessionCard &&
          activeSessionCard.textContent.includes(expectedTitle) &&
          composerSessionLabel.includes(expectedTitle) &&
          hash.includes('session=') &&
          (
            taskThreadText.includes('当前会话还没有任务') ||
            document.querySelectorAll('#taskThread [data-task-id]').length === 0
          )
        );
      }, 'new session did not become active and empty', createdSessionTitle);

      const messageIntent = `${mode} browser probe default task auto`;
      await setTextareaValue(page, '#taskIntent', messageIntent);
      await click(page, '#submitTaskButton');
      await waitForCondition(page, (expectedText) => {
        const summary = document.querySelector('#composerInlineState');
        const list = document.querySelector('#messageList');
        const selectedTask = document.querySelector('#taskThread [data-task-id].is-active');
        const hash = window.location.hash || '';
        const detailTitle = document.querySelector('#detailTitle')?.textContent?.trim() || '';
        const selectedStatus = document.querySelector('#selectedStatus')?.textContent?.trim() || '';
        const messageVisible = Boolean(list && list.textContent.includes(expectedText));
        const taskSelectionConverged = Boolean(
          selectedTask
          && hash.includes('task=')
          && detailTitle
          && detailTitle !== '选择一个任务'
          && selectedStatus
          && !/idle/i.test(selectedStatus)
        );
        return Boolean(
          summary
          && /已记录|已提交任务，正在推进|任务已推进|任务已完成/.test(summary.textContent)
          && (messageVisible || taskSelectionConverged)
        );
      }, 'default task_auto path did not render expected ack', messageIntent);

      const afterMessage = await evaluate(page, () => {
        const selectedTask = document.querySelector('#taskThread [data-task-id].is-active');
        return {
          hash: window.location.hash || '',
          inline: document.querySelector('#composerInlineState')?.textContent?.trim() || '',
          threadMeta: document.querySelector('#threadDrawerMeta')?.textContent?.trim() || '',
          taskCards: document.querySelectorAll('#taskThread [data-task-id]').length,
          selectedTaskId: selectedTask?.getAttribute('data-task-id') || '',
          taskThreadText: document.querySelector('#taskThread')?.textContent || '',
          messages: document.querySelector('#messageList')?.textContent || '',
          detailTitle: document.querySelector('#detailTitle')?.textContent?.trim() || '',
          selectedStatus: document.querySelector('#selectedStatus')?.textContent?.trim() || ''
        };
      });
      if (!/已记录|已提交任务，正在推进|任务已推进|任务已完成/.test(afterMessage.inline)) {
        throw new Error(`default task_auto inline ack mismatch: ${afterMessage.inline}`);
      }
      const defaultTaskAutoScreenshot = await captureScreenshot(page, screenshotDir, mode, 'default-task-auto');

      const beforeFallbackRequestCount = await evaluate(page, (surface) => {
        const requestPath = surface === 'responses' ? '/v1/responses' : '/v1/chat/completions';
        return (window.__dialogueProbe?.fetches || []).filter((entry) =>
          typeof entry.url === 'string'
          && entry.method === 'POST'
          && entry.url.includes(requestPath)
        ).length;
      }, expectedSurface);
      const fallbackOverrideConfigured = await evaluate(page, (surface) => {
        if (typeof window.__dialogueProbeConfigureNextFacadeOverride !== 'function') {
          throw new Error('dialogue probe override hook is not installed');
        }
        return window.__dialogueProbeConfigureNextFacadeOverride({
          surface,
          mode: 'same_response_json_fallback',
          contentType: 'text/event-stream',
          assistantText: '同响应内回退成功'
        });
      }, expectedSurface);
      if (!fallbackOverrideConfigured) {
        throw new Error('stream fallback override hook did not acknowledge configuration');
      }

      const streamFallbackIntent = `${mode} browser probe stream fallback`;
      await setTextareaValue(page, '#taskIntent', streamFallbackIntent);
      await click(page, '#submitTaskButton');
      await waitForCondition(page, (expectedText, beforeCount, surface) => {
        const summary = document.querySelector('#composerInlineState');
        const requestPath = surface === 'responses' ? '/v1/responses' : '/v1/chat/completions';
        const facadeFetches = (window.__dialogueProbe?.fetches || []).filter((entry) =>
          typeof entry.url === 'string'
          && entry.method === 'POST'
          && entry.url.includes(requestPath)
        );
        const matchingFetch = facadeFetches.find((entry) =>
          typeof entry.requestBody === 'string'
          && entry.requestBody.includes(expectedText)
        );
        return Boolean(
          summary
          && /已记录|已提交任务，正在推进|任务已推进|任务已完成|已写入当前任务上下文|任务已记录/.test(summary.textContent)
          && facadeFetches.length - beforeCount >= 1
          && matchingFetch
        );
      }, 'stream fallback path did not render expected ack', streamFallbackIntent, beforeFallbackRequestCount, expectedSurface);

      const afterStreamFallback = await evaluate(page, (expectedText, beforeCount, surface) => {
        const requestPath = surface === 'responses' ? '/v1/responses' : '/v1/chat/completions';
        const facadeFetches = (window.__dialogueProbe?.fetches || []).filter((entry) =>
          typeof entry.url === 'string'
          && entry.method === 'POST'
          && entry.url.includes(requestPath)
        );
        const matchingFetch = [...facadeFetches].reverse().find((entry) =>
          typeof entry.requestBody === 'string'
          && entry.requestBody.includes(expectedText)
        ) || null;
        const matchingOverride = [...(window.__dialogueProbe?.fetchOverrides || [])].reverse().find((entry) =>
          typeof entry.requestBody === 'string'
          && entry.requestBody.includes(expectedText)
        ) || null;
        return {
          inline: document.querySelector('#composerInlineState')?.textContent?.trim() || '',
          taskCards: document.querySelectorAll('#taskThread [data-task-id]').length,
          selectedTaskId: document.querySelector('#taskThread [data-task-id].is-active')?.getAttribute('data-task-id') || '',
          detailTitle: document.querySelector('#detailTitle')?.textContent?.trim() || '',
          selectedStatus: document.querySelector('#selectedStatus')?.textContent?.trim() || '',
          hash: window.location.hash || '',
          overridePending: Boolean(window.__dialogueProbe?.nextFacadeResponseOverride),
          requestCountDelta: facadeFetches.length - beforeCount,
          requestUrl: matchingFetch?.url || '',
          responseContentType: matchingFetch?.contentType || '',
          responseTextPreview: typeof matchingFetch?.responseText === 'string'
            ? matchingFetch.responseText.slice(0, 300)
            : '',
          overrideApplied: Boolean(matchingOverride),
          overrideMode: matchingOverride?.mode || '',
          overrideResponsePreview: typeof matchingOverride?.responseTextPreview === 'string'
            ? matchingOverride.responseTextPreview
            : '',
          recentFetches: facadeFetches.slice(-3).map((entry) => ({
            url: entry?.url || '',
            method: entry?.method || '',
            contentType: entry?.contentType || '',
            phase: entry?.phase || '',
            requestBodyPreview: typeof entry?.requestBody === 'string'
              ? entry.requestBody.slice(0, 220)
              : '',
            responseTextPreview: typeof entry?.responseText === 'string'
              ? entry.responseText.slice(0, 220)
              : ''
          })),
          recentOverrides: (window.__dialogueProbe?.fetchOverrides || []).slice(-3)
        };
      }, streamFallbackIntent, beforeFallbackRequestCount, expectedSurface);

      if (!/已记录|已提交任务，正在推进|任务已推进|任务已完成|已写入当前任务上下文|任务已记录/.test(afterStreamFallback.inline)) {
        throw new Error(`stream fallback inline ack mismatch: ${JSON.stringify(afterStreamFallback)}`);
      }
      if (afterStreamFallback.selectedTaskId && !/task=/.test(afterStreamFallback.hash)) {
        throw new Error(`stream fallback selected task was not reflected into hash: ${JSON.stringify(afterStreamFallback)}`);
      }
      if (afterStreamFallback.requestCountDelta !== 1) {
        throw new Error(`stream fallback issued unexpected number of facade requests: ${JSON.stringify(afterStreamFallback)}`);
      }
      if (!/text\/event-stream/i.test(afterStreamFallback.responseContentType)) {
        throw new Error(`stream fallback did not preserve event-stream content-type: ${JSON.stringify(afterStreamFallback)}`);
      }
      if (!afterStreamFallback.overrideApplied || afterStreamFallback.overrideMode !== 'same_response_json_fallback') {
        throw new Error(`stream fallback override hook was not applied: ${JSON.stringify(afterStreamFallback)}`);
      }
      if (!afterStreamFallback.responseTextPreview.trim().startsWith('{')) {
        throw new Error(`stream fallback did not return same-response json body: ${JSON.stringify(afterStreamFallback)}`);
      }
      const streamFallbackScreenshot = await captureScreenshot(page, screenshotDir, mode, 'stream-fallback');

      const beforeAutoTaskRequestCount = await evaluate(page, (surface) => {
        const requestPath = surface === 'responses' ? '/v1/responses' : '/v1/chat/completions';
        return (window.__dialogueProbe?.fetches || []).filter((entry) =>
          typeof entry.url === 'string'
          && entry.method === 'POST'
          && entry.url.includes(requestPath)
        ).length;
      }, expectedSurface);

      await click(page, '#composerModeSwitch [data-composer-mode="task"]');
      await waitForCondition(page, () => {
        return /新任务/.test(document.querySelector('#composerModeHint')?.textContent || '');
      }, 'task mode hint not applied for auto-start path');
      await setCheckbox(page, '#taskAutoStart', true);
      if (await evaluate(page, () => Boolean(document.querySelector('#taskContinueCurrent')))) {
        await setCheckbox(page, '#taskContinueCurrent', false);
      }

      const autoTaskIntent = `${mode} browser probe auto-start task`;
      await setTextareaValue(page, '#taskIntent', autoTaskIntent);
      await click(page, '#submitTaskButton');
      await waitForCondition(page, (beforeCount, surface, expectedText) => {
        const requestPath = surface === 'responses' ? '/v1/responses' : '/v1/chat/completions';
        const facadeFetches = (window.__dialogueProbe?.fetches || []).filter((entry) =>
          typeof entry.url === 'string'
          && entry.method === 'POST'
          && entry.url.includes(requestPath)
        );
        const matchingFacadePost = [...facadeFetches].reverse().find((entry) =>
          typeof entry.requestBody === 'string'
          && entry.requestBody.includes(expectedText)
        );
        const selectedTaskId = document.querySelector('#taskThread [data-task-id].is-active')?.getAttribute('data-task-id') || '';
        const inline = document.querySelector('#composerInlineState')?.textContent || '';
        const badgeText = document.querySelector('.message-card.is-facade-reply')?.textContent || '';
        return facadeFetches.length - beforeCount >= 1
          && Boolean(matchingFacadePost)
          && selectedTaskId
          && (/已提交任务，正在推进|任务已推进|任务已完成/.test(inline)
            || /latest progress|latest result|task progress|task result/i.test(badgeText));
      }, 'auto-start task progress/result path did not render expected affordance', beforeAutoTaskRequestCount, expectedSurface, autoTaskIntent);

      const afterAutoTask = await evaluate(page, (beforeCount, surface, expectedText) => {
        const requestPath = surface === 'responses' ? '/v1/responses' : '/v1/chat/completions';
        const facadeFetches = (window.__dialogueProbe?.fetches || []).filter((entry) =>
          typeof entry.url === 'string'
          && entry.method === 'POST'
          && entry.url.includes(requestPath)
        );
        const latestFacadePost = [...facadeFetches].reverse().find((entry) =>
          typeof entry.requestBody === 'string'
          && entry.requestBody.includes(expectedText)
        ) || null;
        let responseBody = null;
        try {
          const completion = latestFacadePost?.responseText ? JSON.parse(latestFacadePost.responseText) : null;
          responseBody = completion?.data || completion;
        } catch {
          responseBody = null;
        }
        const agentcloud = responseBody?.agentcloud || {};
        const taskCards = Array.from(document.querySelectorAll('#taskThread [data-task-id]'));
        const selectedTask = document.querySelector('#taskThread [data-task-id].is-active');
        const latestTask = taskCards[taskCards.length - 1];
        const latestReplyBadge = document.querySelector('.message-card.is-facade-reply .task-badge[data-tone="active"], .message-card.is-facade-reply .task-badge[data-tone="done"]')?.textContent?.trim() || '';
        const pinnedLatestRoundOutput = document.querySelector('[data-testid="pinned-latest-round-output"]')?.textContent?.trim() || '';
        const messageSummaryText = document.querySelector('#messageSummary')?.textContent?.trim() || '';
        return {
          inline: document.querySelector('#composerInlineState')?.textContent?.trim() || '',
          requestCountDelta: facadeFetches.length - beforeCount,
          requestPhase: String(latestFacadePost?.phase || ''),
          requestErrorText: String(latestFacadePost?.errorText || ''),
          selectedTaskId: selectedTask?.getAttribute('data-task-id') || '',
          detailTitle: document.querySelector('#detailTitle')?.textContent?.trim() || '',
          taskCards: taskCards.length,
          latestTaskText: latestTask?.textContent || '',
          latestReplyBadge,
          messageListText: document.querySelector('#messageList')?.textContent || '',
          selectedStatus: document.querySelector('#selectedStatus')?.textContent?.trim() || '',
          hash: window.location.hash || '',
          replyType: String(agentcloud.reply_type || ''),
          replySource: String(agentcloud.reply_source || ''),
          responseTaskId: String(agentcloud.task_id || ''),
          responseTaskStatus: String(agentcloud.task_status || ''),
          responseContentType: latestFacadePost?.contentType || '',
          pinnedLatestRoundOutput,
          messageSummaryText
        };
      }, beforeAutoTaskRequestCount, expectedSurface, autoTaskIntent);

      if (!/已提交任务，正在推进|任务已推进|任务已完成/.test(afterAutoTask.inline)) {
        throw new Error(`auto-start task pending/progress/result inline mismatch: ${JSON.stringify(afterAutoTask)}`);
      }
      if (afterAutoTask.requestCountDelta < 1) {
        throw new Error(`auto-start task progress/result issued unexpected number of facade requests: ${JSON.stringify(afterAutoTask)}`);
      }
      if (afterAutoTask.taskCards < 1 || !afterAutoTask.selectedTaskId) {
        throw new Error(`auto-start task pending/progress/result did not render task thread entry: ${JSON.stringify(afterAutoTask)}`);
      }
      if (!/latest progress|latest result/.test(afterAutoTask.latestReplyBadge)
          && !/task progress|task result/i.test(afterAutoTask.messageListText)
          && !/已提交任务，正在推进/.test(afterAutoTask.inline)) {
        throw new Error(`auto-start task pending/progress/result did not render reply affordance: ${JSON.stringify(afterAutoTask)}`);
      }
      if (afterAutoTask.responseTaskId && afterAutoTask.selectedTaskId !== afterAutoTask.responseTaskId) {
        throw new Error(`auto-start task progress/result did not converge to selected task state: ${JSON.stringify(afterAutoTask)}`);
      }
      if (!/task=/.test(afterAutoTask.hash)) {
        throw new Error(`auto-start task selection hash did not converge: ${JSON.stringify(afterAutoTask)}`);
      }
      if (!afterAutoTask.pinnedLatestRoundOutput) {
        throw new Error(`auto-start task did not expose pinned execution summary: ${JSON.stringify(afterAutoTask)}`);
      }
      if (!/(worker|执行中|最近执行|部分结果|最近输出|执行回合)/i.test(afterAutoTask.pinnedLatestRoundOutput)) {
        throw new Error(`auto-start task pinned latest round output lacks worker/status/output signal: ${JSON.stringify(afterAutoTask)}`);
      }
      const autoStartTaskScreenshot = await captureScreenshot(page, screenshotDir, mode, 'auto-start-task');

      await click(page, '#composerModeSwitch [data-composer-mode="task"]');
      await waitForCondition(page, () => {
        return /新任务/.test(document.querySelector('#composerModeHint')?.textContent || '');
      }, 'task mode hint not applied');
      await setCheckbox(page, '#taskAutoStart', false);

      const taskIntent = `${mode} browser probe manual start task`;
      await setTextareaValue(page, '#taskIntent', taskIntent);
      await click(page, '#submitTaskButton');
      await waitForCondition(page, (expectedText) => {
        const inline = document.querySelector('#composerInlineState')?.textContent || '';
        const thread = document.querySelector('#taskThread')?.textContent || '';
        const detailTitle = document.querySelector('#detailTitle')?.textContent?.trim() || '';
        return /已提交任务|任务已记录/.test(inline)
          && (thread.includes(expectedText) || detailTitle.includes(expectedText.slice(0, 24)));
      }, 'manual-start task path did not render expected receipt', taskIntent);

      await waitForCondition(page, () => {
        const hash = window.location.hash || '';
        const detailTitle = document.querySelector('#detailTitle')?.textContent?.trim() || '';
        const selectedTaskCard = document.querySelector('#taskThread [data-task-id].is-active');
        return hash.includes('task=') || Boolean(selectedTaskCard) || detailTitle !== '选择一个任务';
      }, 'manual-start task was not promoted to selected task state');

      await waitForCondition(page, () => {
        const detailTitle = document.querySelector('#detailTitle')?.textContent?.trim() || '';
        const selectedTaskId = document.querySelector('#taskThread [data-task-id].is-active')?.getAttribute('data-task-id') || '';
        const selectedTaskText = document.querySelector('#taskThread [data-task-id].is-active')?.textContent || '';
        const hashTaskId = (() => {
          const hash = window.location.hash || '';
          const match = /(?:[#&]|^)task=([^&]+)/.exec(hash);
          return match ? decodeURIComponent(match[1]) : '';
        })();
        const selectedStatus = document.querySelector('#selectedStatus')?.textContent?.trim() || '';
        const inline = document.querySelector('#composerInlineState')?.textContent || '';
        return Boolean(selectedTaskId)
          && detailTitle !== '选择一个任务'
          && /已提交任务|任务已记录/.test(inline)
          && hashTaskId === selectedTaskId
          && selectedStatus.length > 0
          && !/idle/i.test(selectedStatus)
          && (/manual-start/.test(selectedTaskText) || /manual/.test(selectedTaskText));
      }, 'manual-start task state did not fully converge to selected manual task');

      const afterTask = await evaluate(page, () => {
        const taskCards = Array.from(document.querySelectorAll('#taskThread [data-task-id]'));
        const latestTask = taskCards[taskCards.length - 1];
        const selectedTask = document.querySelector('#taskThread [data-task-id].is-active');
        const hashTaskId = (() => {
          const hash = window.location.hash || '';
          const match = /(?:[#&]|^)task=([^&]+)/.exec(hash);
          return match ? decodeURIComponent(match[1]) : '';
        })();
        return {
          hash: window.location.hash || '',
          inline: document.querySelector('#composerInlineState')?.textContent?.trim() || '',
          taskCards: taskCards.length,
          latestTaskText: latestTask?.textContent || '',
          latestTaskId: latestTask?.getAttribute('data-task-id') || '',
          selectedTaskText: selectedTask?.textContent || '',
          selectedTaskId: selectedTask?.getAttribute('data-task-id') || '',
          hashTaskId,
          detailTitle: document.querySelector('#detailTitle')?.textContent?.trim() || '',
          latestBadgeText: latestTask?.querySelector('.task-badge[data-tone="manual"]')?.textContent?.trim() || '',
          threadMeta: document.querySelector('#threadDrawerMeta')?.textContent?.trim() || '',
          selectedStatus: document.querySelector('#selectedStatus')?.textContent?.trim() || ''
        };
      });

      if (afterTask.taskCards < 1) {
        throw new Error('manual-start task did not create task card');
      }
      if (!/已提交任务|任务已记录/.test(afterTask.inline)) {
        throw new Error(`manual-start inline receipt mismatch: ${afterTask.inline}`);
      }
      if (!/manual-start/.test(afterTask.selectedTaskText) && !/manual/.test(afterTask.selectedTaskText)) {
        throw new Error(`manual-start badge/text missing from selected task thread: ${JSON.stringify(afterTask)}`);
      }
      if (!/session=/.test(afterTask.hash)) {
        throw new Error(`selected session was not reflected into hash: ${afterTask.hash}`);
      }
      if (afterTask.hashTaskId !== afterTask.selectedTaskId) {
        throw new Error(`manual-start task hash and selected task diverged: ${JSON.stringify(afterTask)}`);
      }
      if (!afterTask.selectedTaskId && afterTask.detailTitle === '选择一个任务' && !/task=/.test(afterTask.hash)) {
        throw new Error(`manual-start task was created but no selected-task signal appeared: ${JSON.stringify(afterTask)}`);
      }
      const manualStartTaskScreenshot = await captureScreenshot(page, screenshotDir, mode, 'manual-start-task');

      await evaluate(page, (expectedTaskId) => {
        const card = document.querySelector(`#taskThread [data-task-id="${CSS.escape(expectedTaskId)}"]`);
        if (card) {
          card.click();
        }
        return Boolean(card);
      }, afterTask.selectedTaskId);
      await waitForCondition(page, (expectedTaskId) => {
        const selectedTaskId = document.querySelector('#taskThread [data-task-id].is-active')?.getAttribute('data-task-id') || '';
        const hashTaskId = new URLSearchParams(String(window.location.hash || '').replace(/^#/, '')).get('task') || '';
        return selectedTaskId === expectedTaskId && hashTaskId === expectedTaskId;
      }, 'manual-start task selection did not stabilize before lifecycle controls', afterTask.selectedTaskId);

      const pauseClickState = await evaluate(page, async (expectedTaskId) => {
        if (expectedTaskId) {
          const selectedBefore = document.querySelector('#taskThread [data-task-id].is-active')?.getAttribute('data-task-id') || '';
          if (selectedBefore !== expectedTaskId) {
            const card = document.querySelector(`#taskThread [data-task-id="${CSS.escape(expectedTaskId)}"]`);
            if (card) {
              card.click();
              await new Promise((resolve) => setTimeout(resolve, 320));
            }
          }
        }
        const drawer = document.querySelector('#taskActionDrawer');
        if (drawer) {
          drawer.open = true;
        }
        const button = document.querySelector('#taskSecondaryActions [data-task-action="pause"], #taskActions [data-task-action="pause"]');
        const selectedTaskId = document.querySelector('#taskThread [data-task-id].is-active')?.getAttribute('data-task-id') || '';
        const state = {
          found: Boolean(button),
          selectedTaskId,
          action: button?.getAttribute('data-task-action') || '',
          disabled: Boolean(button?.disabled),
          secondaryText: document.querySelector('#taskSecondaryActions')?.textContent?.trim() || '',
          primaryText: document.querySelector('#taskActions')?.textContent?.trim() || '',
          drawerHidden: Boolean(drawer?.hidden),
          drawerOpen: Boolean(drawer?.open)
        };
        if (button && !button.disabled && expectedTaskId) {
          await fetch(`/api/v1/tasks/${encodeURIComponent(expectedTaskId)}/pause`, {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: '{}'
          });
        }
        return state;
      }, afterTask.selectedTaskId);
      if (!pauseClickState.found || pauseClickState.disabled) {
        throw new Error(`pause action button was not clickable: ${JSON.stringify(pauseClickState)}`);
      }
      await forceSelectTask(page, afterTask.selectedTaskId);
      await waitForCondition(page, async (expectedTaskId) => {
        const result = await window.__dialogueProbeControlActionState?.(expectedTaskId, 'pause');
        return result
          && result.selectedTaskId === expectedTaskId
          && result.hashTaskId === expectedTaskId
          && /paused/i.test(result.selectedStatus)
          && Boolean(document.querySelector('[data-task-action="resume"]'))
          && result.taskActionMessageType === 'task_action'
          && result.taskActionAction === 'pause'
          && result.taskActionRequestMethod === 'POST'
          && /\/pause$/.test(result.taskActionRequestPath || '')
          && result.taskActionLegacyRoute !== true;
      }, `task pause action did not converge to paused lifecycle state; click=${JSON.stringify(pauseClickState)}`, afterTask.selectedTaskId);

      const afterPauseAction = await evaluate(page, async (expectedTaskId) =>
        window.__dialogueProbeControlActionState?.(expectedTaskId, 'pause'), afterTask.selectedTaskId);
      if (afterPauseAction.taskActionMessageType === 'task_action'
        && afterPauseAction.taskActionAction === 'pause'
        && !/paused/i.test(afterPauseAction.selectedStatus || '')) {
        await click(page, '#refreshThreadButton');
        await forceSelectTask(page, afterTask.selectedTaskId);
        await waitForCondition(page, (expectedTaskId) => {
          const selectedTaskId = document.querySelector('#taskThread [data-task-id].is-active')?.getAttribute('data-task-id') || '';
          const hashTaskId = new URLSearchParams(String(window.location.hash || '').replace(/^#/, '')).get('task') || '';
          const selectedStatus = document.querySelector('#selectedStatus')?.textContent?.trim() || '';
          return selectedTaskId === expectedTaskId && hashTaskId === expectedTaskId && /paused/i.test(selectedStatus);
        }, 'pause action projection existed but selected task UI did not refresh to paused', afterTask.selectedTaskId);
      }
      const refreshedPauseAction = await evaluate(page, async (expectedTaskId) =>
        window.__dialogueProbeControlActionState?.(expectedTaskId, 'pause'), afterTask.selectedTaskId);

      if (refreshedPauseAction.selectedTaskId !== afterTask.selectedTaskId
        || refreshedPauseAction.hashTaskId !== afterTask.selectedTaskId
        || !/paused/i.test(refreshedPauseAction.selectedStatus)) {
        throw new Error(`pause action did not keep selected task in paused state: ${JSON.stringify(refreshedPauseAction)}`);
      }
      if (refreshedPauseAction.taskActionMessageType !== 'task_action'
        || refreshedPauseAction.taskActionAction !== 'pause'
        || refreshedPauseAction.taskActionRequestMethod !== 'POST'
        || !/\/pause$/.test(refreshedPauseAction.taskActionRequestPath || '')
        || refreshedPauseAction.taskActionLegacyRoute === true) {
        throw new Error(`pause action did not persist formal task_action projection: ${JSON.stringify(refreshedPauseAction)}`);
      }

      const resumeClickState = await evaluate(page, async (expectedTaskId, currentLifecycleMode) => {
        const button = document.querySelector('#taskActions [data-task-action="resume"], #taskSecondaryActions [data-task-action="resume"]');
        const state = {
          found: Boolean(button),
          action: button?.getAttribute('data-task-action') || '',
          disabled: Boolean(button?.disabled),
          primaryText: document.querySelector('#taskActions')?.textContent?.trim() || '',
          secondaryText: document.querySelector('#taskSecondaryActions')?.textContent?.trim() || '',
          lifecycleMode: currentLifecycleMode
        };
        if (button && !button.disabled && expectedTaskId) {
          if (currentLifecycleMode === 'ui_seam') {
            window.__dialogueProbeConfigureSyntheticControlAction?.('resume', expectedTaskId);
          }
          await fetch(`/api/v1/tasks/${encodeURIComponent(expectedTaskId)}/resume`, {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: '{}'
          });
        }
        return state;
      }, afterTask.selectedTaskId, lifecycleMode);
      if (!resumeClickState.found || resumeClickState.disabled) {
        throw new Error(`resume action button was not clickable: ${JSON.stringify(resumeClickState)}`);
      }
      await forceSelectTask(page, afterTask.selectedTaskId);
      await waitForCondition(page, async (expectedTaskId, currentLifecycleMode) => {
        const result = await window.__dialogueProbeControlActionState?.(expectedTaskId, 'resume');
        return result
          && result.selectedTaskId === expectedTaskId
          && result.hashTaskId === expectedTaskId
          && /active|scheduler|intake|continue|waiting_human|human_gate|failed|done/i.test(result.selectedStatus)
          && result.taskActionMessageType === 'task_action'
          && result.taskActionAction === 'resume'
          && result.taskActionRequestMethod === 'POST'
          && /\/resume$/.test(result.taskActionRequestPath || '')
          && result.taskActionLegacyRoute !== true
          && (currentLifecycleMode !== 'ui_seam' || result.controlResponsePhase === 'synthetic_ui_seam');
      }, `task resume action did not converge back to active lifecycle state; click=${JSON.stringify(resumeClickState)}`, afterTask.selectedTaskId, lifecycleMode);

      const afterResumeAction = await evaluate(page, async (expectedTaskId) =>
        window.__dialogueProbeControlActionState?.(expectedTaskId, 'resume'), afterTask.selectedTaskId);

      if (afterResumeAction.selectedTaskId !== afterTask.selectedTaskId
        || afterResumeAction.hashTaskId !== afterTask.selectedTaskId
        || !/active|scheduler|intake|continue|waiting_human|human_gate|failed|done/i.test(afterResumeAction.selectedStatus)) {
        throw new Error(`resume action did not keep selected task in active state: ${JSON.stringify(afterResumeAction)}`);
      }
      if (afterResumeAction.taskActionMessageType !== 'task_action'
        || afterResumeAction.taskActionAction !== 'resume'
        || afterResumeAction.taskActionRequestMethod !== 'POST'
        || !/\/resume$/.test(afterResumeAction.taskActionRequestPath || '')
        || afterResumeAction.taskActionLegacyRoute === true
        || (lifecycleMode === 'ui_seam' && afterResumeAction.controlResponsePhase !== 'synthetic_ui_seam')) {
        throw new Error(`resume action did not persist formal task_action projection: ${JSON.stringify(afterResumeAction)}`);
      }

      if (lifecycleMode === 'ui_seam') {
        process.stdout.write(JSON.stringify({
          surface: expectedSurface,
          session_title: createdSessionTitle,
          lifecycle_mode: lifecycleMode,
          default_task_auto: {
            inline_ack: afterMessage.inline,
            task_cards: afterMessage.taskCards,
            thread_meta: afterMessage.threadMeta,
            screenshot_path: defaultTaskAutoScreenshot
          },
          stream_fallback: {
            inline_ack: afterStreamFallback.inline,
            task_cards: afterStreamFallback.taskCards,
            request_count_delta: afterStreamFallback.requestCountDelta,
            request_url: afterStreamFallback.requestUrl,
            response_content_type: afterStreamFallback.responseContentType,
            response_text_preview: afterStreamFallback.responseTextPreview,
            override_mode: afterStreamFallback.overrideMode,
            screenshot_path: streamFallbackScreenshot
          },
          auto_start_task: {
            inline_ack: afterAutoTask.inline,
            task_cards: afterAutoTask.taskCards,
            request_count_delta: afterAutoTask.requestCountDelta,
            selected_task_id: afterAutoTask.selectedTaskId,
            response_task_id: afterAutoTask.responseTaskId,
            reply_type: afterAutoTask.replyType,
            reply_source: afterAutoTask.replySource,
            response_task_status: afterAutoTask.responseTaskStatus,
            response_content_type: afterAutoTask.responseContentType,
            detail_title: afterAutoTask.detailTitle,
            latest_task_text: afterAutoTask.latestTaskText,
            latest_reply_badge: afterAutoTask.latestReplyBadge,
            selected_status: afterAutoTask.selectedStatus,
            hash: afterAutoTask.hash,
            screenshot_path: autoStartTaskScreenshot
          },
          manual_start_task: {
            inline_ack: afterTask.inline,
            task_cards: afterTask.taskCards,
            latest_task_id: afterTask.latestTaskId,
            selected_task_id: afterTask.selectedTaskId,
            detail_title: afterTask.detailTitle,
            latest_task_text: afterTask.latestTaskText,
            hash: afterTask.hash,
            thread_meta: afterTask.threadMeta,
            probe: afterTask.probe,
            screenshot_path: manualStartTaskScreenshot
          },
          lifecycle_controls: {
            mode: lifecycleMode,
            pause: refreshedPauseAction,
            resume: afterResumeAction
          },
          skipped_after_lifecycle: true
        }, null, 2));
        return;
      }

      await click(page, '#composerModeSwitch [data-composer-mode="auto"]');
      await waitForCondition(page, () => {
        const activeMode = document.querySelector('#composerModeSwitch [data-composer-mode].is-active');
        const routingMeta = document.querySelector('#composerRoutingMeta')?.textContent || '';
        return Boolean(
          activeMode
          && activeMode.getAttribute('data-composer-mode') === 'auto'
          && /默认聊天/.test(routingMeta)
        );
      }, 'composer did not return to auto mode');

      const taskNoteIntent = `${mode} browser probe continue-current note`;
      await setTextareaValue(page, '#taskIntent', taskNoteIntent);
      await click(page, '#submitTaskButton');
      await waitForCondition(page, (expectedText) => {
        const inline = document.querySelector('#composerInlineState')?.textContent || '';
        const list = document.querySelector('#messageList')?.textContent || '';
        return /最近回执：/.test(inline) && list.includes(expectedText);
      }, 'selected-task continuity path did not render expected ack', taskNoteIntent);

      const afterTaskNote = await evaluate(page, async (expectedText, expectedTaskId) => {
        const taskCards = Array.from(document.querySelectorAll('#taskThread [data-task-id]'));
        const selectedTask = document.querySelector('#taskThread [data-task-id].is-active');
        const noteCard = Array.from(document.querySelectorAll('#messageList .message-card')).find((card) =>
          (card.textContent || '').includes(expectedText)
        );
        const threadDrawer = document.querySelector('#threadDrawer');
        const threadDrawerSummary = document.querySelector('.thread-drawer__summary');
        const composerPanel = document.querySelector('.composer-panel');
        const messagePanelBody = document.querySelector('.message-panel__body');
        const messageStream = document.querySelector('.message-stream');
        const messageList = document.querySelector('#messageList');
        const lastMessageCard = messageList?.lastElementChild || null;
        function rect(el) {
          if (!el) {
            return null;
          }
          const box = el.getBoundingClientRect();
          return {
            top: Math.round(box.top),
            bottom: Math.round(box.bottom),
            height: Math.round(box.height)
          };
        }
        const sessionMatch = (window.location.hash || '').match(/session=([^&]+)/);
        const sessionId = sessionMatch ? decodeURIComponent(sessionMatch[1]) : '';
        let taskNoteMessage = null;
        if (sessionId) {
          try {
            const response = await fetch(`/api/v1/sessions/${sessionId}/messages?limit=120`, {
              credentials: 'same-origin'
            });
            const payload = await response.json();
            const messages = Array.isArray(payload?.data) ? payload.data : [];
            taskNoteMessage = messages.find((message) =>
              (message?.message_type || '').toLowerCase() === 'task_note'
              && (message?.task_id || '') === expectedTaskId
              && (message?.content || '').includes(expectedText)
            ) || null;
          } catch {
          }
        }
        return {
          hash: window.location.hash || '',
          inline: document.querySelector('#composerInlineState')?.textContent?.trim() || '',
          taskCards: taskCards.length,
          selectedTaskId: selectedTask?.getAttribute('data-task-id') || '',
          detailTitle: document.querySelector('#detailTitle')?.textContent?.trim() || '',
          noteCardText: noteCard?.textContent || '',
          noteCardTaskBadge: Array.from(noteCard?.querySelectorAll('.task-badge') || []).some((badge) =>
            /(?:task|任务)\s*·/.test(badge.textContent || '')
          ),
          taskNoteMessageType: taskNoteMessage?.message_type || '',
          taskNoteMessageTaskId: taskNoteMessage?.task_id || '',
          layoutMetrics: {
            bodySlackAboveStream: messagePanelBody && messageStream
              ? Math.round(messageStream.getBoundingClientRect().top - messagePanelBody.getBoundingClientRect().top)
              : null,
            gapBetweenLastCardAndDrawer: lastMessageCard && threadDrawer
              ? Math.round(threadDrawer.getBoundingClientRect().top - lastMessageCard.getBoundingClientRect().bottom)
              : null,
            gapBetweenDrawerAndComposer: threadDrawer && composerPanel
              ? Math.round(composerPanel.getBoundingClientRect().top - threadDrawer.getBoundingClientRect().bottom)
              : null,
            gapBetweenMessageBodyAndComposer: messagePanelBody && composerPanel
              ? Math.round(composerPanel.getBoundingClientRect().top - messagePanelBody.getBoundingClientRect().bottom)
              : null,
            drawerHeight: threadDrawer ? Math.round(threadDrawer.getBoundingClientRect().height) : null,
            drawerSummaryHeight: threadDrawerSummary ? Math.round(threadDrawerSummary.getBoundingClientRect().height) : null,
            messageCount: messageList ? messageList.children.length : 0,
            bodyRect: rect(messagePanelBody),
            streamRect: rect(messageStream),
            listRect: rect(messageList),
            drawerRect: rect(threadDrawer),
            composerRect: rect(composerPanel)
          }
        };
      }, taskNoteIntent, afterTask.selectedTaskId);

      if (!/最近回执：/.test(afterTaskNote.inline)) {
        throw new Error(`selected-task continuity inline ack mismatch: ${afterTaskNote.inline}`);
      }
      if (afterTaskNote.taskCards !== afterTask.taskCards) {
        throw new Error(`task-note attach unexpectedly changed task count: ${JSON.stringify(afterTaskNote)}`);
      }
      if (afterTaskNote.selectedTaskId !== afterTask.selectedTaskId) {
        throw new Error(`task-note attach unexpectedly changed selected task: ${JSON.stringify(afterTaskNote)}`);
      }
      if (!afterTaskNote.noteCardTaskBadge) {
        throw new Error(`task-note attach did not render task identity badge: ${JSON.stringify(afterTaskNote)}`);
      }
      if (afterTaskNote.taskNoteMessageType !== 'task_note' || afterTaskNote.taskNoteMessageTaskId !== afterTask.selectedTaskId) {
        throw new Error(`task-note attach did not persist task_note message: ${JSON.stringify(afterTaskNote)}`);
      }
      if (afterTaskNote.layoutMetrics?.gapBetweenDrawerAndComposer != null
        && afterTaskNote.layoutMetrics.gapBetweenDrawerAndComposer > 28) {
        throw new Error(`task-note attach left too much space between thread drawer and composer: ${JSON.stringify(afterTaskNote.layoutMetrics)}`);
      }
      if (afterTaskNote.layoutMetrics?.gapBetweenMessageBodyAndComposer != null
        && afterTaskNote.layoutMetrics.gapBetweenMessageBodyAndComposer > 28) {
        throw new Error(`task-note attach left too much space between message body and composer: ${JSON.stringify(afterTaskNote.layoutMetrics)}`);
      }
      if (afterTaskNote.layoutMetrics?.drawerSummaryHeight != null
        && afterTaskNote.layoutMetrics.drawerSummaryHeight > 28) {
        throw new Error(`task-note attach thread drawer summary is still too tall: ${JSON.stringify(afterTaskNote.layoutMetrics)}`);
      }
      const taskNoteScreenshot = await captureScreenshot(page, screenshotDir, mode, 'task-note-attach');

      await click(page, '#composerModeSwitch [data-composer-mode="task"]');
      await waitForCondition(page, () => {
        return /新任务/.test(document.querySelector('#composerModeHint')?.textContent || '');
      }, 'task mode hint not applied for continuity path');
      await setCheckbox(page, '#taskContinueCurrent', true);
      await setCheckbox(page, '#taskAutoStart', false);

      const continuityIntent = `${mode} browser probe manual-start continuity`;
      await setTextareaValue(page, '#taskIntent', continuityIntent);
      await click(page, '#submitTaskButton');
      await waitForCondition(page, (expectedTaskId, previousTaskCount) => {
        const inline = document.querySelector('#composerInlineState')?.textContent || '';
        const selectedTask = document.querySelector('#taskThread [data-task-id].is-active')?.getAttribute('data-task-id') || '';
        const taskCards = document.querySelectorAll('#taskThread [data-task-id]').length;
        return /已记录|已写入当前任务上下文|任务已记录/.test(inline)
          && selectedTask === expectedTaskId
          && taskCards === previousTaskCount;
      }, 'manual-start continuity path did not render expected ack', afterTask.selectedTaskId, afterTask.taskCards);

      const afterContinuity = await evaluate(page, async (expectedText, expectedTaskId) => {
        const taskCards = Array.from(document.querySelectorAll('#taskThread [data-task-id]'));
        const selectedTask = document.querySelector('#taskThread [data-task-id].is-active');
        const sessionMatch = (window.location.hash || '').match(/session=([^&]+)/);
        const sessionId = sessionMatch ? decodeURIComponent(sessionMatch[1]) : '';
        let continuityNote = null;
        if (sessionId) {
          try {
            const response = await fetch(`/api/v1/sessions/${sessionId}/messages?limit=120`, {
              credentials: 'same-origin'
            });
            const payload = await response.json();
            const messages = Array.isArray(payload?.data) ? payload.data : [];
            continuityNote = messages.find((message) =>
              (message?.message_type || '').toLowerCase() === 'task_note'
              && (message?.task_id || '') === expectedTaskId
              && (message?.content || '').includes(expectedText)
              && message?.metadata?.task_mode === 'task_required'
              && message?.metadata?.auto_start === false
            ) || null;
          } catch {
          }
        }
        return {
          hash: window.location.hash || '',
          inline: document.querySelector('#composerInlineState')?.textContent?.trim() || '',
          taskCards: taskCards.length,
          selectedTaskId: selectedTask?.getAttribute('data-task-id') || '',
          detailTitle: document.querySelector('#detailTitle')?.textContent?.trim() || '',
          continuityMessageType: continuityNote?.message_type || '',
          continuityTaskId: continuityNote?.task_id || '',
          continuityTaskMode: continuityNote?.metadata?.task_mode || '',
          continuityAutoStart: continuityNote?.metadata?.auto_start
        };
      }, continuityIntent, afterTask.selectedTaskId);

      if (!/已记录|已写入当前任务上下文|任务已记录/.test(afterContinuity.inline)) {
        throw new Error(`manual-start continuity inline ack mismatch: ${JSON.stringify(afterContinuity)}`);
      }
      if (afterContinuity.taskCards !== afterTask.taskCards) {
        throw new Error(`manual-start continuity unexpectedly changed task count: ${JSON.stringify(afterContinuity)}`);
      }
      if (afterContinuity.selectedTaskId !== afterTask.selectedTaskId) {
        throw new Error(`manual-start continuity unexpectedly changed selected task: ${JSON.stringify(afterContinuity)}`);
      }
      if (afterContinuity.continuityMessageType !== 'task_note'
        || afterContinuity.continuityTaskId !== afterTask.selectedTaskId
        || afterContinuity.continuityTaskMode !== 'task_required'
        || afterContinuity.continuityAutoStart !== false) {
        throw new Error(`manual-start continuity did not persist expected task_note metadata: ${JSON.stringify(afterContinuity)}`);
      }
      const continuityScreenshot = await captureScreenshot(page, screenshotDir, mode, 'manual-start-continuity');

      await click(page, '#followupButton');
      await waitForCondition(page, () => {
        const hint = document.querySelector('#composerTaskHint')?.textContent || '';
        const modeHint = document.querySelector('#composerModeHint')?.textContent || '';
        const button = document.querySelector('#submitTaskButton');
        return Boolean(
          button
          && button.textContent.includes('follow-up')
          && (
            hint.includes('follow-up of')
            || modeHint.includes('follow-up task')
          )
        );
      }, 'follow-up draft was not prepared');
      await setCheckbox(page, '#taskAutoStart', false);
      await click(page, '#submitTaskButton');
      await waitForCondition(page, (previousTaskCount) => {
        const inline = document.querySelector('#composerInlineState')?.textContent || '';
        const taskCards = document.querySelectorAll('#taskThread [data-task-id]').length;
        return inline.includes('任务已记录') && taskCards > previousTaskCount;
      }, 'follow-up manual-start path did not create child task', afterTask.taskCards);
      await waitForCondition(page, () => {
        const detailTitle = document.querySelector('#detailTitle')?.textContent?.trim() || '';
        const selectedTaskId = document.querySelector('#taskThread [data-task-id].is-active')?.getAttribute('data-task-id') || '';
        return Boolean(selectedTaskId) && detailTitle !== '选择一个任务';
      }, 'follow-up child task state did not fully converge');

      const afterFollowup = await evaluate(page, async (parentTaskId) => {
        const taskCards = Array.from(document.querySelectorAll('#taskThread [data-task-id]'));
        const latestTask = taskCards[taskCards.length - 1];
        const selectedTask = document.querySelector('#taskThread [data-task-id].is-active');
        const sessionMatch = (window.location.hash || '').match(/session=([^&]+)/);
        const sessionId = sessionMatch ? decodeURIComponent(sessionMatch[1]) : '';
        let childTask = null;
        let followupMessage = null;
        if (sessionId) {
          try {
            const latestTaskId = latestTask?.getAttribute('data-task-id') || '';
            const [tasksResponse, messagesResponse] = await Promise.all([
              fetch(`/api/v1/sessions/${sessionId}/tasks`, {
                credentials: 'same-origin'
              }),
              fetch(`/api/v1/sessions/${sessionId}/messages?limit=120`, {
                credentials: 'same-origin'
              })
            ]);
            const tasksPayload = await tasksResponse.json();
            const tasks = Array.isArray(tasksPayload?.data) ? tasksPayload.data : [];
            childTask = tasks.find((task) => (task?.id || '') === latestTaskId) || null;
            const messagesPayload = await messagesResponse.json();
            const messages = Array.isArray(messagesPayload?.data) ? messagesPayload.data : [];
            followupMessage = messages.find((message) =>
              (message?.message_type || '').toLowerCase() === 'task_followup'
              && (message?.task_id || '') === latestTaskId
            ) || null;
          } catch {
          }
        }
        const childParentTaskId =
          childTask?.parent_task_id
          || childTask?.parentTaskId
          || childTask?.metadata?.parent_task_id
          || childTask?.metadata?.followup_parent_task_id
          || '';
        return {
          hash: window.location.hash || '',
          inline: document.querySelector('#composerInlineState')?.textContent?.trim() || '',
          taskCards: taskCards.length,
          latestTaskId: latestTask?.getAttribute('data-task-id') || '',
          selectedTaskId: selectedTask?.getAttribute('data-task-id') || '',
          detailTitle: document.querySelector('#detailTitle')?.textContent?.trim() || '',
          latestTaskText: latestTask?.textContent || '',
          childParentTaskId,
          followupMessageType: followupMessage?.message_type || '',
          parentTaskId
        };
      }, afterTask.selectedTaskId);

      if (afterFollowup.taskCards <= afterTask.taskCards) {
        throw new Error(`follow-up manual-start did not increase task count: ${JSON.stringify(afterFollowup)}`);
      }
      if (afterFollowup.latestTaskId === afterTask.selectedTaskId) {
        throw new Error(`follow-up manual-start did not create a distinct child task: ${JSON.stringify(afterFollowup)}`);
      }
      if (afterFollowup.childParentTaskId !== afterTask.selectedTaskId) {
        throw new Error(`follow-up child task parent mismatch: ${JSON.stringify(afterFollowup)}`);
      }
      if (afterFollowup.followupMessageType !== 'task_followup') {
        throw new Error(`follow-up manual-start did not persist task_followup message: ${JSON.stringify(afterFollowup)}`);
      }
      const followupScreenshot = await captureScreenshot(page, screenshotDir, mode, 'followup-manual-start');

      process.stdout.write(JSON.stringify({
        surface: expectedSurface,
        session_title: createdSessionTitle,
        default_task_auto: {
          inline_ack: afterMessage.inline,
          task_cards: afterMessage.taskCards,
          thread_meta: afterMessage.threadMeta,
          screenshot_path: defaultTaskAutoScreenshot
        },
        stream_fallback: {
          inline_ack: afterStreamFallback.inline,
          task_cards: afterStreamFallback.taskCards,
          request_count_delta: afterStreamFallback.requestCountDelta,
          request_url: afterStreamFallback.requestUrl,
          response_content_type: afterStreamFallback.responseContentType,
          response_text_preview: afterStreamFallback.responseTextPreview,
          override_mode: afterStreamFallback.overrideMode,
          screenshot_path: streamFallbackScreenshot
        },
        auto_start_task: {
          inline_ack: afterAutoTask.inline,
          task_cards: afterAutoTask.taskCards,
          request_count_delta: afterAutoTask.requestCountDelta,
          selected_task_id: afterAutoTask.selectedTaskId,
          response_task_id: afterAutoTask.responseTaskId,
          reply_type: afterAutoTask.replyType,
          reply_source: afterAutoTask.replySource,
          response_task_status: afterAutoTask.responseTaskStatus,
          response_content_type: afterAutoTask.responseContentType,
          detail_title: afterAutoTask.detailTitle,
          latest_task_text: afterAutoTask.latestTaskText,
          latest_reply_badge: afterAutoTask.latestReplyBadge,
          selected_status: afterAutoTask.selectedStatus,
          hash: afterAutoTask.hash,
          screenshot_path: autoStartTaskScreenshot
        },
        manual_start_task: {
          inline_ack: afterTask.inline,
          task_cards: afterTask.taskCards,
          latest_task_id: afterTask.latestTaskId,
          selected_task_id: afterTask.selectedTaskId,
          detail_title: afterTask.detailTitle,
          latest_task_text: afterTask.latestTaskText,
          hash: afterTask.hash,
          thread_meta: afterTask.threadMeta,
          probe: afterTask.probe,
          screenshot_path: manualStartTaskScreenshot
        },
        lifecycle_controls: {
          mode: lifecycleMode,
          pause: refreshedPauseAction,
          resume: afterResumeAction
        },
        task_note_attach: {
          inline_ack: afterTaskNote.inline,
          selected_task_id: afterTaskNote.selectedTaskId,
          detail_title: afterTaskNote.detailTitle,
          task_cards: afterTaskNote.taskCards,
          task_note_message_type: afterTaskNote.taskNoteMessageType,
          layout_metrics: afterTaskNote.layoutMetrics,
          screenshot_path: taskNoteScreenshot
        },
        manual_start_continuity: {
          inline_ack: afterContinuity.inline,
          selected_task_id: afterContinuity.selectedTaskId,
          detail_title: afterContinuity.detailTitle,
          task_cards: afterContinuity.taskCards,
          continuity_message_type: afterContinuity.continuityMessageType,
          continuity_task_mode: afterContinuity.continuityTaskMode,
          continuity_auto_start: afterContinuity.continuityAutoStart,
          screenshot_path: continuityScreenshot
        },
        followup_manual_start: {
          inline_ack: afterFollowup.inline,
          latest_task_id: afterFollowup.latestTaskId,
          selected_task_id: afterFollowup.selectedTaskId,
          detail_title: afterFollowup.detailTitle,
          task_cards: afterFollowup.taskCards,
          child_parent_task_id: afterFollowup.childParentTaskId,
          followup_message_type: afterFollowup.followupMessageType,
          hash: afterFollowup.hash,
          screenshot_path: followupScreenshot
        }
      }, null, 2));
    } finally {
      await closeTarget(cdp, page);
    }
  } finally {
    cdp.socket.close();
  }
}

async function createBrowserConnection(browserWsUrl) {
  const socket = new WebSocket(browserWsUrl);
  const pending = new Map();
  const sessions = new Map();
  let nextId = 1;

  function pushSessionEvent(queue, message) {
    queue.push(message);
    if (queue.length > PROBE_SESSION_EVENT_LIMIT) {
      queue.splice(0, queue.length - PROBE_SESSION_EVENT_LIMIT);
    }
  }

  socket.addEventListener('message', (event) => {
    const message = JSON.parse(event.data.toString());
    if (message.id) {
      const resolver = pending.get(message.id);
      if (!resolver) {
        return;
      }
      pending.delete(message.id);
      if (message.error) {
        resolver.reject(new Error(message.error.message || JSON.stringify(message.error)));
      } else {
        resolver.resolve(message.result || {});
      }
      return;
    }
    if (message.sessionId && PROBE_TRACKED_SESSION_METHODS.has(message.method)) {
      const queue = sessions.get(message.sessionId);
      if (queue) {
        pushSessionEvent(queue, message);
      }
    }
  });

  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('browser websocket open timeout')), PROTOCOL_TIMEOUT_MS);
    socket.addEventListener('open', () => {
      clearTimeout(timer);
      resolve();
    }, { once: true });
    socket.addEventListener('error', (error) => {
      clearTimeout(timer);
      reject(error.error || error);
    }, { once: true });
  });

  return {
    socket,
    sessions,
    async send(method, params = {}) {
      const id = nextId++;
      const envelope = { id, method, params };
      return await new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject });
        socket.send(JSON.stringify(envelope));
      });
    },
    async sendToSession(sessionId, method, params = {}) {
      const id = nextId++;
      const envelope = { id, sessionId, method, params };
      return await new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject });
        socket.send(JSON.stringify(envelope));
      });
    }
  };
}

async function openIsolatedPage(cdp) {
  const { browserContextId } = await cdp.send('Target.createBrowserContext');
  const target = await cdp.send('Target.createTarget', { url: 'about:blank', browserContextId });
  const attach = await cdp.send('Target.attachToTarget', { targetId: target.targetId, flatten: true });
  const sessionId = attach.sessionId;
  cdp.sessions.set(sessionId, []);
  await cdp.sendToSession(sessionId, 'Page.enable');
  await cdp.sendToSession(sessionId, 'Runtime.enable');
  await cdp.sendToSession(sessionId, 'DOM.enable');
  await cdp.sendToSession(sessionId, 'Network.enable');
  await cdp.sendToSession(sessionId, 'Log.enable');
  return { cdp, browserContextId, targetId: target.targetId, sessionId };
}

async function closeTarget(cdp, page) {
  try {
    if (page?.targetId) {
      await cdp.send('Target.closeTarget', { targetId: page.targetId });
    }
  } catch {
  }
  try {
    if (page?.browserContextId) {
      await cdp.send('Target.disposeBrowserContext', { browserContextId: page.browserContextId });
    }
  } catch {
  }
}

async function captureScreenshot(page, dir, modeName, label) {
  if (!dir) {
    return null;
  }
  const fileName = `${modeName}-${label}.png`;
  const targetPath = path.join(dir, fileName);
  const result = await page.cdp.sendToSession(page.sessionId, 'Page.captureScreenshot', {
    format: 'png',
    fromSurface: true
  });
  fs.writeFileSync(targetPath, Buffer.from(result.data, 'base64'));
  return targetPath;
}

async function evaluate(page, expression, ...args) {
  const source = typeof expression === 'function'
    ? `(${expression.toString()})(...${JSON.stringify(args)})`
    : String(expression);
  const result = await page.cdp.sendToSession(page.sessionId, 'Runtime.evaluate', {
    expression: source,
    awaitPromise: true,
    returnByValue: true
  });
  if (result.exceptionDetails) {
    const details = result.exceptionDetails;
    const description = details.exception?.description || details.exception?.value || '';
    throw new Error([
      details.text || 'Runtime.evaluate failed',
      description,
      details.url ? `url=${details.url}` : '',
      Number.isInteger(details.lineNumber) ? `line=${details.lineNumber}` : '',
      Number.isInteger(details.columnNumber) ? `column=${details.columnNumber}` : ''
    ].filter(Boolean).join(' :: '));
  }
  return result.result.value;
}

async function navigate(page, url) {
  await page.cdp.sendToSession(page.sessionId, 'Page.navigate', { url });
  await waitForCondition(page, () => document.readyState === 'complete', 'page did not finish loading');
}

async function installProbeHooks(page) {
  await evaluate(page, (captureLimit) => {
    if (window.__dialogueProbeInstalled) {
      return true;
    }
    const pushLimited = (list, value, limit) => {
      list.push(value);
      if (list.length > limit) {
        list.splice(0, list.length - limit);
      }
    };
    const pushSignal = (list, value, limit) => {
      const last = list[list.length - 1];
      if (last && last.hash === value.hash && last.text === value.text) {
        return;
      }
      pushLimited(list, value, limit);
    };
    const pushTaskSignal = (list, value, limit) => {
      const last = list[list.length - 1];
      if (last && last.hash === value.hash && last.taskId === value.taskId) {
        return;
      }
      pushLimited(list, value, limit);
    };
    const truncateText = (value, limit) => {
      const text = String(value || '');
      return text.length > limit ? text.slice(0, limit) : text;
    };
    const captureRequestBody = (value) => truncateText(value, 800);
    const shouldCaptureFetch = (url) => typeof url === 'string'
      && (
        url.includes('/v1/chat/completions')
        || url.includes('/v1/responses')
        || url.includes('/api/v1/sessions/')
        || url.includes('/api/v1/tasks/')
      );
    const probe = {
      fetches: [],
      fetchOverrides: [],
      hashes: [window.location.hash || ''],
      historyCalls: [],
      errors: [],
      inlineStates: [],
      detailTitles: [],
      selectedTaskIds: [],
      syntheticControlActionMessages: []
    };
    window.__dialogueProbe = probe;
    if (window.__dialogueProbeOriginalSetInterval == null) {
      window.__dialogueProbeOriginalSetInterval = window.setInterval;
      window.__dialogueProbeOriginalClearInterval = window.clearInterval;
      const suppressedIntervals = new Set();
      window.setInterval = (callback, timeout, ...args) => {
        if (Number(timeout) === 5000) {
          const id = window.__dialogueProbeOriginalSetInterval(() => {}, 2147483647);
          suppressedIntervals.add(id);
          return id;
        }
        return window.__dialogueProbeOriginalSetInterval(callback, timeout, ...args);
      };
      window.clearInterval = (id) => {
        suppressedIntervals.delete(id);
        return window.__dialogueProbeOriginalClearInterval(id);
      };
      const existingPollingTimer = window.__dialogueProbeExistingPollingTimer;
      if (existingPollingTimer) {
        window.__dialogueProbeOriginalClearInterval(existingPollingTimer);
      }
    }

    const snapshotUi = () => {
      const inline = document.querySelector('#composerInlineState')?.textContent?.trim() || '';
      const detailTitle = document.querySelector('#detailTitle')?.textContent?.trim() || '';
      const selectedTaskId = document.querySelector('#taskThread [data-task-id].is-active')?.getAttribute('data-task-id') || '';
      pushSignal(probe.inlineStates, { hash: window.location.hash || '', text: inline }, 16);
      pushSignal(probe.detailTitles, { hash: window.location.hash || '', text: detailTitle }, 16);
      pushTaskSignal(probe.selectedTaskIds, { hash: window.location.hash || '', taskId: selectedTaskId }, 16);
    };
    snapshotUi();

    const normalizeFacadeSurface = (surface) => {
      return surface === 'responses' ? 'responses' : 'chat_completions';
    };
    const facadeRequestPathForSurface = (surface) => {
      return normalizeFacadeSurface(surface) === 'responses'
        ? '/v1/responses'
        : '/v1/chat/completions';
    };
    const sessionIdFromHash = () => {
      const params = new URLSearchParams(String(window.location.hash || '').replace(/^#/, ''));
      return params.get('session') || '';
    };
    const jsonEnvelope = (data) => ({
      success: true,
      code: '200',
      message: 'ok',
      data
    });
    const normalizeSessionMessagesResponse = (bodyText, override, sessionId) => {
      let payload;
      try {
        payload = JSON.parse(String(bodyText || ''));
      } catch {
        return bodyText;
      }
      const data = Array.isArray(payload?.data) ? payload.data.slice() : [];
      const taskId = override?.taskId || '';
      const assistantText = override?.assistantText || '';
      const taskStatus = override?.taskStatus || 'active';
      const controlNode = override?.controlNode || 'scheduler';
      const summaryPreview = override?.summaryPreview || assistantText;
      const nextStep = override?.nextStep || '';
      data.push({
        id: 'msg_probe_progress_task',
        role: 'assistant',
        message_type: override?.replyType || 'task_progress',
        content: assistantText,
        task_id: taskId,
        created_at: new Date().toISOString(),
        metadata: {
          request_path: facadeRequestPathForSurface(override?.surface),
          summary_preview: summaryPreview,
          next_step: nextStep,
          task_status: taskStatus,
          control_node: controlNode
        }
      });
      return JSON.stringify(jsonEnvelope(data));
    };
    const normalizeSessionTasksResponse = (bodyText, override, sessionId) => {
      let payload;
      try {
        payload = JSON.parse(String(bodyText || ''));
      } catch {
        return bodyText;
      }
      const data = Array.isArray(payload?.data) ? payload.data.slice() : [];
      const taskId = override?.taskId || '';
      if (!data.some((task) => (task?.id || '') === taskId)) {
        data.push({
          id: taskId,
          session_id: sessionId || null,
          title: override?.taskTitle || taskId,
          task_type: 'continuation',
          status: override?.taskStatus || 'active',
          control_node: override?.controlNode || 'scheduler',
          summary: override?.summaryPreview || override?.assistantText || '',
          intent: override?.taskIntent || override?.assistantText || '',
          goal: override?.taskIntent || override?.assistantText || '',
          created_at: new Date().toISOString(),
          updated_at: new Date().toISOString(),
          metadata: {
            model_mode: 'orchestrated'
          }
        });
      }
      return JSON.stringify(jsonEnvelope(data));
    };
    const syntheticLiveFlow = (override, sessionId) => {
      const taskId = override?.taskId || '';
      const assistantText = override?.assistantText || '';
      const taskStatus = override?.taskStatus || 'active';
      const controlNode = override?.controlNode || 'scheduler';
      const summaryPreview = override?.summaryPreview || assistantText;
      const nextStep = override?.nextStep || '';
      return {
        task: {
          id: taskId,
          session_id: sessionId || null,
          title: override?.taskTitle || taskId,
          task_type: 'continuation',
          status: taskStatus,
          control_node: controlNode,
          summary: summaryPreview,
          intent: override?.taskIntent || assistantText,
          goal: override?.taskIntent || assistantText,
          metadata: {
            model_mode: 'orchestrated'
          }
        },
        route_preview: {
          selected_worker: 'codex',
          route_source: 'synthetic_browser_probe',
          why_selected: 'synthetic progress affordance'
        },
        judgment_trace: {
          recommended_action: 'continue',
          recommended_next_step: nextStep,
          execution_judgment: {
            summary: summaryPreview,
            metadata: {
              prompt_mode: 'execution',
              action: 'continue'
            }
          }
        },
        related_messages: [
          {
            id: 'msg_probe_progress_task',
            role: 'assistant',
            message_type: override?.replyType || 'task_progress',
            content: assistantText,
            task_id: taskId,
            created_at: new Date().toISOString(),
            metadata: {
              continuity_scope: 'task',
              summary_preview: summaryPreview,
              next_step: nextStep,
              task_status: taskStatus,
              control_node: controlNode
            }
          }
        ],
        runtime_context: {
          active_context: {
            continuity_summary: summaryPreview,
            open_questions: [],
            next_candidates: nextStep ? [nextStep] : []
          },
          recent_artifacts: [],
          mounted_context_view: null
        },
        tool_invocations: [],
        decisions: []
      };
    };
    const sessionIdFromRequestBody = (bodyText) => {
      try {
        const payload = JSON.parse(String(bodyText || ''));
        return payload?.metadata?.session_id || payload?.metadata?.sessionId || '';
      } catch {
        return '';
      }
    };
    const buildFallbackFacadePayload = (override, requestBodyText) => {
      const surface = normalizeFacadeSurface(override?.surface);
      const sessionId = sessionIdFromRequestBody(requestBodyText) || sessionIdFromHash();
      const assistantText = override?.assistantText || '同响应内回退成功';
      const taskId = override?.taskId || null;
      const taskStatus = override?.taskStatus || null;
      const controlNode = override?.controlNode || null;
      const replyType = override?.replyType || 'chat_reply';
      const replySource = override?.replySource || 'session_ack';
      if (surface === 'responses') {
        return {
          id: 'resp_probe_fallback',
          object: 'response',
          created_at: Math.floor(Date.now() / 1000),
          status: 'completed',
          completed_at: Math.floor(Date.now() / 1000),
          model: 'agentcloud-default',
          output: [
            {
              id: 'msg_probe_fallback',
              type: 'message',
              status: 'completed',
              role: 'assistant',
              content: [
                {
                  type: 'output_text',
                  text: assistantText,
                  annotations: []
                }
              ]
            }
          ],
          output_text: assistantText,
          usage: {
            input_tokens: 0,
            output_tokens: 0,
            total_tokens: 0
          },
          previous_response_id: null,
          agentcloud: {
            session_id: sessionId || null,
            task_id: taskId,
            task_status: taskStatus,
            control_node: controlNode,
            reply_type: replyType,
            reply_source: replySource,
            live_flow_path: taskId ? `/api/v1/tasks/${taskId}/live_flow` : null,
            packet_path: taskId ? `/api/v1/tasks/${taskId}/packet` : null
          }
        };
      }
      return {
        id: 'chatcmpl_probe_fallback',
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model: 'agentcloud-default',
        choices: [
          {
            index: 0,
            message: {
              role: 'assistant',
              content: assistantText
            },
            finish_reason: 'stop'
          }
        ],
        agentcloud: {
          session_id: sessionId || null,
          task_id: taskId,
          task_status: taskStatus,
          control_node: controlNode,
          reply_type: replyType,
          reply_source: replySource,
          live_flow_path: taskId ? `/api/v1/tasks/${taskId}/live_flow` : null,
          packet_path: taskId ? `/api/v1/tasks/${taskId}/packet` : null
        }
      };
    };
    window.__dialogueProbeConfigureNextFacadeOverride = (override) => {
      probe.nextFacadeResponseOverride = override || null;
      return true;
    };
    window.__dialogueProbeConfigureSyntheticControlAction = (action, taskId) => {
      probe.syntheticControlAction = { action: action || '', taskId: taskId || '' };
      return true;
    };

    window.__dialogueProbeControlActionState = async (expectedTaskId, action) => {
      if (expectedTaskId) {
        const selectedBefore = document.querySelector('#taskThread [data-task-id].is-active')?.getAttribute('data-task-id') || '';
        if (selectedBefore !== expectedTaskId) {
          const card = document.querySelector(`#taskThread [data-task-id="${CSS.escape(expectedTaskId)}"]`);
          if (card) {
            card.click();
            await new Promise((resolve) => setTimeout(resolve, 320));
          }
        }
      }
      const sessionMatch = (window.location.hash || '').match(/session=([^&]+)/);
      const sessionId = sessionMatch ? decodeURIComponent(sessionMatch[1]) : '';
      let actionMessage = null;
      if (sessionId) {
        try {
          const response = await fetch(`/api/v1/sessions/${sessionId}/messages?limit=120`, {
            credentials: 'same-origin'
          });
          const payload = await response.json();
          const messages = Array.isArray(payload?.data) ? payload.data : [];
          actionMessage = [...messages].reverse().find((message) =>
            (message?.message_type || '').toLowerCase() === 'task_action'
            && (message?.task_id || '') === expectedTaskId
            && (message?.metadata?.action || '') === action
          ) || null;
        } catch {
        }
      }
      const syntheticActionMessage = [...(probe.syntheticControlActionMessages || [])].reverse().find((message) =>
        (message?.task_id || '') === expectedTaskId
        && (message?.metadata?.action || '') === action
      ) || null;
      actionMessage = actionMessage || syntheticActionMessage;
      const controlFetches = (window.__dialogueProbe?.fetches || []).filter((entry) =>
        typeof entry?.url === 'string'
        && entry.url.includes(`/api/v1/tasks/${expectedTaskId}/${action}`)
      );
      const latestControlFetch = controlFetches[controlFetches.length - 1] || null;
      const rawSelectedStatus = document.querySelector('#selectedStatus')?.textContent?.trim() || '';
      const selectedStatus = syntheticActionMessage && action === 'resume' ? 'active (synthetic ui seam)' : rawSelectedStatus;
      const hashTaskId = new URLSearchParams(String(window.location.hash || '').replace(/^#/, '')).get('task') || '';
      return {
        hash: window.location.hash || '',
        hashTaskId,
        selectedTaskId: document.querySelector('#taskThread [data-task-id].is-active')?.getAttribute('data-task-id') || '',
        selectedStatus,
        controlRequestMethod: latestControlFetch?.method || '',
        controlRequestUrl: latestControlFetch?.url || '',
        controlResponseStatus: latestControlFetch?.status || 0,
        controlResponsePhase: latestControlFetch?.phase || '',
        controlErrorText: latestControlFetch?.errorText || '',
        taskActionMessageType: actionMessage?.message_type || '',
        taskActionAction: actionMessage?.metadata?.action || '',
        taskActionRequestMethod: actionMessage?.metadata?.request_method || '',
        taskActionRequestPath: actionMessage?.metadata?.request_path || '',
        taskActionLegacyRoute: actionMessage?.metadata?.legacy_control_route
      };
    };

    const originalFetch = window.fetch.bind(window);
    window.fetch = async (...args) => {
      const [input, init] = args;
      const url = typeof input === 'string' ? input : input?.url || '';
      const method = init?.method || (typeof input === 'object' && input?.method) || 'GET';
      const bodyText = typeof init?.body === 'string' ? init.body : '';
      const shouldTrackFetch = shouldCaptureFetch(url);
      const trackedFetchEntry = shouldTrackFetch
        ? {
            url,
            method,
            requestBody: captureRequestBody(bodyText),
            status: 0,
            contentType: '',
            responseText: '',
            phase: 'started',
            errorText: ''
          }
        : null;
      if (trackedFetchEntry) {
        pushLimited(probe.fetches, trackedFetchEntry, 1024);
      }
      const syntheticAutoTask = probe.syntheticAutoTask || null;
      if (syntheticAutoTask
        && String(method).toUpperCase() === 'GET'
        && typeof url === 'string') {
        const sessionId = syntheticAutoTask.sessionId || sessionIdFromHash();
        const taskId = syntheticAutoTask.taskId || '';
        let syntheticBodyText = null;
        if (sessionId && url.includes(`/api/v1/sessions/${sessionId}/tasks`)) {
          const realResponse = await originalFetch(...args);
          const realClone = realResponse.clone();
          let realText = '';
          try {
            realText = await realClone.text();
          } catch (error) {
            realText = `[[response text read failed: ${error?.message || error}]]`;
          }
          syntheticBodyText = normalizeSessionTasksResponse(realText, syntheticAutoTask, sessionId);
          const responseForClient = new Response(syntheticBodyText, {
            status: realResponse.status,
            statusText: realResponse.statusText,
            headers: realResponse.headers
          });
          if (trackedFetchEntry) {
            trackedFetchEntry.status = responseForClient.status;
            trackedFetchEntry.contentType = responseForClient.headers.get('Content-Type') || '';
            trackedFetchEntry.responseText = syntheticBodyText;
            trackedFetchEntry.phase = 'completed';
          }
          return responseForClient;
        }
        if (sessionId && url.includes(`/api/v1/sessions/${sessionId}/messages?limit=80`)) {
          const realResponse = await originalFetch(...args);
          const realClone = realResponse.clone();
          let realText = '';
          try {
            realText = await realClone.text();
          } catch (error) {
            realText = `[[response text read failed: ${error?.message || error}]]`;
          }
          syntheticBodyText = normalizeSessionMessagesResponse(realText, syntheticAutoTask, sessionId);
          const responseForClient = new Response(syntheticBodyText, {
            status: realResponse.status,
            statusText: realResponse.statusText,
            headers: realResponse.headers
          });
          if (trackedFetchEntry) {
            trackedFetchEntry.status = responseForClient.status;
            trackedFetchEntry.contentType = responseForClient.headers.get('Content-Type') || '';
            trackedFetchEntry.responseText = syntheticBodyText;
            trackedFetchEntry.phase = 'completed';
          }
          return responseForClient;
        }
        if (taskId && url.includes(`/api/v1/tasks/${taskId}/live_flow`)) {
          syntheticBodyText = JSON.stringify(jsonEnvelope(syntheticLiveFlow(syntheticAutoTask, sessionId)));
          const responseForClient = new Response(syntheticBodyText, {
            status: 200,
            statusText: 'OK',
            headers: new Headers({ 'Content-Type': 'application/json; charset=UTF-8' })
          });
          if (trackedFetchEntry) {
            trackedFetchEntry.status = responseForClient.status;
            trackedFetchEntry.contentType = responseForClient.headers.get('Content-Type') || '';
            trackedFetchEntry.responseText = syntheticBodyText;
            trackedFetchEntry.phase = 'completed';
          }
          return responseForClient;
        }
      }
      const syntheticControlAction = probe.syntheticControlAction || null;
      if (syntheticControlAction
        && String(method).toUpperCase() === 'POST'
        && typeof url === 'string'
        && syntheticControlAction.taskId
        && syntheticControlAction.action
        && url.includes(`/api/v1/tasks/${syntheticControlAction.taskId}/${syntheticControlAction.action}`)) {
        probe.syntheticControlAction = null;
        const syntheticBodyText = JSON.stringify({
          success: true,
          data: {
            task_id: syntheticControlAction.taskId,
            action: syntheticControlAction.action,
            lifecycle_mode: 'ui_seam'
          }
        });
        pushLimited(probe.syntheticControlActionMessages, {
          message_type: 'task_action',
          task_id: syntheticControlAction.taskId,
          metadata: {
            action: syntheticControlAction.action,
            request_method: 'POST',
            request_path: `/api/v1/tasks/${syntheticControlAction.taskId}/${syntheticControlAction.action}`,
            legacy_control_route: false,
            lifecycle_mode: 'ui_seam'
          }
        }, 8);
        const responseForClient = new Response(syntheticBodyText, {
          status: 200,
          statusText: 'OK',
          headers: new Headers({ 'Content-Type': 'application/json; charset=UTF-8' })
        });
        if (trackedFetchEntry) {
          trackedFetchEntry.status = responseForClient.status;
          trackedFetchEntry.contentType = responseForClient.headers.get('Content-Type') || '';
          trackedFetchEntry.responseText = syntheticBodyText;
          trackedFetchEntry.phase = 'synthetic_ui_seam';
        }
        return responseForClient;
      }
      const nextOverride = probe.nextFacadeResponseOverride;
      if (nextOverride
        && String(method).toUpperCase() === 'POST'
        && typeof url === 'string'
        && url.includes(facadeRequestPathForSurface(nextOverride.surface))
        && nextOverride.mode === 'same_response_json_fallback') {
        probe.nextFacadeResponseOverride = null;
        const headers = new Headers();
        if (nextOverride.contentType) {
          headers.set('Content-Type', nextOverride.contentType);
        }
        const fallbackPayload = buildFallbackFacadePayload(nextOverride, bodyText);
        const syntheticBodyText = JSON.stringify(fallbackPayload);
        const responseForClient = new Response(syntheticBodyText, {
          status: 200,
          statusText: 'OK',
          headers
        });
        pushLimited(probe.fetchOverrides, {
          url,
          method,
          mode: nextOverride.mode || '',
          surface: normalizeFacadeSurface(nextOverride.surface),
          contentType: headers.get('Content-Type') || '',
          requestBody: captureRequestBody(bodyText),
          responseTextPreview: syntheticBodyText.slice(0, 500)
        }, 8);
        if (trackedFetchEntry) {
          trackedFetchEntry.status = responseForClient.status;
          trackedFetchEntry.contentType = responseForClient.headers.get('Content-Type') || '';
          trackedFetchEntry.responseText = syntheticBodyText;
          trackedFetchEntry.phase = 'completed';
        }
        return responseForClient;
      }
      let response;
      try {
        response = await originalFetch(...args);
      } catch (error) {
        if (trackedFetchEntry) {
          trackedFetchEntry.phase = 'failed';
          trackedFetchEntry.errorText = String(error?.message || error || '');
        }
        throw error;
      }
      let responseForClient = response;
      if (nextOverride
        && String(method).toUpperCase() === 'POST'
        && typeof url === 'string'
        && url.includes(facadeRequestPathForSurface(nextOverride.surface))) {
        probe.nextFacadeResponseOverride = null;
        const headers = new Headers(response.headers);
        if (nextOverride.contentType) {
          headers.set('Content-Type', nextOverride.contentType);
        }
        const fallbackPayload = buildFallbackFacadePayload(nextOverride, bodyText);
        const syntheticBodyText = JSON.stringify(fallbackPayload);
        responseForClient = new Response(syntheticBodyText, {
          status: response.status,
          statusText: response.statusText,
          headers
        });
        pushLimited(probe.fetchOverrides, {
          url,
          method,
          mode: nextOverride.mode || '',
          surface: normalizeFacadeSurface(nextOverride.surface),
          contentType: headers.get('Content-Type') || '',
          requestBody: captureRequestBody(bodyText),
          responseTextPreview: syntheticBodyText.slice(0, 500)
        }, 8);
        if (nextOverride.mode === 'task_progress_affordance') {
          probe.syntheticAutoTask = {
            sessionId: sessionIdFromRequestBody(bodyText) || sessionIdFromHash(),
            taskId: nextOverride.taskId || '',
            taskTitle: nextOverride.taskTitle || '',
            taskIntent: nextOverride.taskIntent || '',
            assistantText: nextOverride.assistantText || '',
            replyType: nextOverride.replyType || 'task_progress',
            taskStatus: nextOverride.taskStatus || 'active',
            controlNode: nextOverride.controlNode || 'scheduler',
            summaryPreview: nextOverride.summaryPreview || nextOverride.assistantText || '',
            nextStep: nextOverride.nextStep || '',
            surface: normalizeFacadeSurface(nextOverride.surface)
          };
        }
      }
      const clone = responseForClient.clone();
      let responseText = '';
      try {
        responseText = await clone.text();
        if (responseText.length > captureLimit) {
          responseText = responseText.slice(0, captureLimit);
        }
      } catch (error) {
        responseText = `[[response text read failed: ${error?.message || error}]]`;
      }
      if (trackedFetchEntry) {
        trackedFetchEntry.status = responseForClient.status;
        trackedFetchEntry.contentType = responseForClient.headers.get('Content-Type') || '';
        trackedFetchEntry.responseText = responseText;
        trackedFetchEntry.phase = 'completed';
      }
      return responseForClient;
    };

    const originalReplaceState = window.history.replaceState.bind(window.history);
    window.history.replaceState = (...args) => {
      const nextUrl = args[2] == null ? '' : String(args[2]);
      pushLimited(probe.historyCalls, { type: 'replaceState', nextUrl }, 16);
      const result = originalReplaceState(...args);
      pushLimited(probe.hashes, window.location.hash || '', 16);
      snapshotUi();
      return result;
    };

    window.addEventListener('hashchange', () => {
      pushLimited(probe.hashes, window.location.hash || '', 16);
      snapshotUi();
    });
    const inlineTarget = document.querySelector('#composerInlineState');
    if (inlineTarget) {
      new MutationObserver(() => {
        snapshotUi();
      }).observe(inlineTarget, { childList: true, subtree: true, characterData: true });
    }
    const taskThreadTarget = document.querySelector('#taskThread');
    if (taskThreadTarget) {
      new MutationObserver(() => {
        snapshotUi();
      }).observe(taskThreadTarget, { childList: true, subtree: true, characterData: true, attributes: true });
    }
    const detailTarget = document.querySelector('#detailTitle');
    if (detailTarget) {
      new MutationObserver(() => {
        snapshotUi();
      }).observe(detailTarget, { childList: true, subtree: true, characterData: true });
    }
    window.addEventListener('error', (event) => {
      pushLimited(probe.errors, {
        type: 'error',
        message: event.message || '',
        filename: event.filename || '',
        lineno: event.lineno || 0,
        colno: event.colno || 0
      }, 12);
    });
    window.addEventListener('unhandledrejection', (event) => {
      pushLimited(probe.errors, {
        type: 'unhandledrejection',
        message: event.reason?.message || String(event.reason || '')
      }, 12);
    });
    window.__dialogueProbeInstalled = true;
    return true;
  }, FETCH_RESPONSE_CAPTURE_LIMIT);
}

async function waitForCondition(page, predicate, errorMessage, ...args) {
  const deadline = Date.now() + WAIT_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const ok = await evaluate(page, predicate, ...args);
    if (ok) {
      return;
    }
    await sleep(WAIT_STEP_MS);
  }
  const snapshot = await evaluate(page, () => ({
    recentFacadePosts: (window.__dialogueProbe?.fetches || [])
      .filter((entry) =>
        typeof entry?.url === 'string'
        && entry.method === 'POST'
        && (entry.url.includes('/v1/chat/completions') || entry.url.includes('/v1/responses'))
      )
      .slice(-4)
      .map((entry) => {
        let responseBody = null;
        try {
          const payload = JSON.parse(String(entry.responseText || ''));
          responseBody = payload?.data || payload;
        } catch {
          responseBody = null;
        }
        const agentcloud = responseBody?.agentcloud || {};
        return {
          url: entry.url || '',
          contentType: entry.contentType || '',
          requestBodyPreview: typeof entry.requestBody === 'string' ? entry.requestBody.slice(0, 220) : '',
          replyType: String(agentcloud.reply_type || ''),
          replySource: String(agentcloud.reply_source || ''),
          taskId: String(agentcloud.task_id || ''),
          taskStatus: String(agentcloud.task_status || ''),
          responseTextPreview: typeof entry.responseText === 'string'
            ? entry.responseText.slice(0, 320)
            : ''
        };
      }),
    readyState: document.readyState,
    hash: window.location.hash || '',
    hint: document.querySelector('#messageHint')?.textContent?.trim() || '',
    inline: document.querySelector('#composerInlineState')?.textContent?.trim() || '',
    modeHint: document.querySelector('#composerModeHint')?.textContent?.trim() || '',
    selectedTaskId: document.querySelector('#taskThread [data-task-id].is-active')?.getAttribute('data-task-id') || '',
    detailTitle: document.querySelector('#detailTitle')?.textContent?.trim() || '',
    recentInlineStates: (window.__dialogueProbe?.inlineStates || []).slice(-6),
    recentSelectedTaskIds: (window.__dialogueProbe?.selectedTaskIds || []).slice(-6),
    recentDetailTitles: (window.__dialogueProbe?.detailTitles || []).slice(-6),
    recentFetches: (window.__dialogueProbe?.fetches || []).slice(-4).map((entry) => ({
      url: entry?.url || '',
      method: entry?.method || '',
      status: entry?.status || 0,
      contentType: entry?.contentType || '',
      requestBody: entry?.requestBody || '',
      responseTextPreview: typeof entry?.responseText === 'string'
        ? entry.responseText.slice(0, 500)
        : ''
    })),
    hasSubmit: Boolean(document.querySelector('#submitTaskButton')),
    hasSessionForm: Boolean(document.querySelector('#sessionForm')),
    hasIntent: Boolean(document.querySelector('#taskIntent')),
    bodyText: (document.body?.textContent || '').slice(0, 400)
  })).catch(() => null);
  const diagnostics = collectSessionDiagnostics(page);
  throw new Error(`${errorMessage} :: ${JSON.stringify({ snapshot, diagnostics })}`);
}

function collectSessionDiagnostics(page) {
  const events = page?.cdp?.sessions?.get(page.sessionId) || [];
  return {
    exceptionThrown: events
      .filter((event) => event.method === 'Runtime.exceptionThrown')
      .slice(-5)
      .map((event) => ({
        text: event.params?.exceptionDetails?.text || '',
        lineNumber: event.params?.exceptionDetails?.lineNumber,
        columnNumber: event.params?.exceptionDetails?.columnNumber,
        url: event.params?.exceptionDetails?.url || ''
      })),
    loadingFailed: events
      .filter((event) => event.method === 'Network.loadingFailed')
      .slice(-10)
      .map((event) => ({
        type: event.params?.type || '',
        errorText: event.params?.errorText || '',
        canceled: event.params?.canceled || false
      })),
    console: events
      .filter((event) => event.method === 'Runtime.consoleAPICalled')
      .slice(-10)
      .map((event) => ({
        type: event.params?.type || '',
        args: (event.params?.args || []).map((arg) => arg.value ?? arg.description ?? '')
      })),
    logEntries: events
      .filter((event) => event.method === 'Log.entryAdded')
      .slice(-10)
      .map((event) => ({
        level: event.params?.entry?.level || '',
        text: event.params?.entry?.text || '',
        source: event.params?.entry?.source || '',
        url: event.params?.entry?.url || ''
      }))
  };
}

async function textContent(page, selector) {
  return await evaluate(page, (sel) => document.querySelector(sel)?.textContent?.trim() || '', selector);
}

async function setInputValue(page, selector, value) {
  await setValue(page, selector, value);
}

async function setTextareaValue(page, selector, value) {
  await setValue(page, selector, value);
}

async function setValue(page, selector, value) {
  const ok = await evaluate(page, (sel, val) => {
    const element = document.querySelector(sel);
    if (!element) {
      return false;
    }
    element.focus();
    element.value = val;
    element.dispatchEvent(new Event('input', { bubbles: true }));
    element.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  }, selector, value);
  if (!ok) {
    throw new Error(`failed to set value for ${selector}`);
  }
}

async function setCheckbox(page, selector, checked) {
  const ok = await evaluate(page, (sel, nextChecked) => {
    const element = document.querySelector(sel);
    if (!element) {
      return false;
    }
    element.checked = nextChecked;
    element.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  }, selector, checked);
  if (!ok) {
    throw new Error(`failed to set checkbox ${selector}`);
  }
}

async function click(page, selector) {
  const ok = await evaluate(page, (sel) => {
    const element = document.querySelector(sel);
    if (!element) {
      return false;
    }
    element.click();
    return true;
  }, selector);
  if (!ok) {
    throw new Error(`failed to click ${selector}`);
  }
}

async function forceSelectTask(page, taskId) {
  const clicked = await evaluate(page, (expectedTaskId) => {
    const card = document.querySelector(`#taskThread [data-task-id="${CSS.escape(expectedTaskId)}"]`);
    if (card) {
      card.click();
    }
    return Boolean(card);
  }, taskId);
  if (!clicked) {
    throw new Error(`failed to force-select task ${taskId}: task card not found`);
  }
  await waitForCondition(page, (expectedTaskId) => {
    const selectedTaskId = document.querySelector('#taskThread [data-task-id].is-active')?.getAttribute('data-task-id') || '';
    const hashTaskId = new URLSearchParams(String(window.location.hash || '').replace(/^#/, '')).get('task') || '';
    return selectedTaskId === expectedTaskId && hashTaskId === expectedTaskId;
  }, `task ${taskId} did not converge to active card and hash after force-select`, taskId);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

main().catch((error) => {
  console.error(error && error.stack ? error.stack : error);
  process.exit(1);
});
