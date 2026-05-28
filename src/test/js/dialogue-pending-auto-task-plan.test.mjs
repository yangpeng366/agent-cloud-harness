import test from 'node:test';
import assert from 'node:assert/strict';

import {
  buildPendingAutoTaskTracker,
  resolvePendingAutoTaskCandidate
} from '../../main/resources/web/dialogue/pending-auto-task-plan.js';
import { buildComposerSubmitContext } from '../../main/resources/web/dialogue/composer-submit-context-plan.js';

test('buildPendingAutoTaskTracker tracks new task materialization flow', () => {
  const tracker = buildPendingAutoTaskTracker({
    sessionId: 'session_1',
    resolvedMode: 'task',
    existingTaskId: '',
    currentTaskIds: ['task_a']
  });
  assert.equal(tracker.shouldTrack, true);
  assert.equal(tracker.sessionId, 'session_1');
  assert.equal(tracker.resolvedMode, 'task');
  assert.deepEqual(tracker.knownTaskIds, ['task_a']);

  const noTrack = buildPendingAutoTaskTracker({
    sessionId: 'session_1',
    resolvedMode: 'followup',
    existingTaskId: 'task_a',
    currentTaskIds: ['task_a']
  });
  assert.equal(noTrack.shouldTrack, false);
});

test('buildPendingAutoTaskTracker tracks manual-start task creation until task id is known', () => {
  const submitContext = buildComposerSubmitContext({
    planResolvedMode: 'task',
    selectedTaskId: 'task_auto_1',
    selectedTaskStatus: 'active',
    continueCurrentChecked: false
  });
  assert.equal(submitContext.referencedTaskId, '');
  assert.equal(submitContext.continueCurrentTaskId, '');

  const tracker = buildPendingAutoTaskTracker({
    sessionId: 'session_manual',
    resolvedMode: 'task',
    existingTaskId: submitContext.referencedTaskId || submitContext.continueCurrentTaskId,
    intent: 'manual intent',
    currentTaskIds: ['task_auto_1']
  });

  assert.equal(tracker.shouldTrack, true);
  assert.equal(resolvePendingAutoTaskCandidate({
    tracker,
    currentSessionId: 'session_manual',
    tasks: [
      { id: 'task_auto_1', metadata: { auto_start: true } },
      { id: 'task_late_auto_2', goal: 'late auto task', metadata: { auto_start: true, intent: 'late auto task' } },
      { id: 'task_manual_1', metadata: { auto_start: false, start_mode: 'manual', intent: 'manual intent' } }
    ]
  }), 'task_manual_1');
});

test('resolvePendingAutoTaskCandidate prefers unseen task matching submitted intent', () => {
  const tracker = buildPendingAutoTaskTracker({
    sessionId: 'session_manual',
    resolvedMode: 'task',
    existingTaskId: '',
    intent: 'responses browser probe manual start task',
    currentTaskIds: ['task_default']
  });

  assert.equal(resolvePendingAutoTaskCandidate({
    tracker,
    currentSessionId: 'session_manual',
    tasks: [
      { id: 'task_default', goal: 'responses browser probe default task auto' },
      { id: 'task_late_auto', goal: 'responses browser probe auto-start task' },
      {
        id: 'task_manual',
        goal: 'responses browser probe manual start task',
        metadata: { intent: 'responses browser probe manual start task', auto_start: false }
      }
    ]
  }), 'task_manual');
});

test('resolvePendingAutoTaskCandidate waits when unseen tasks do not match submitted intent', () => {
  const tracker = buildPendingAutoTaskTracker({
    sessionId: 'session_manual',
    resolvedMode: 'task',
    existingTaskId: '',
    intent: 'chat browser probe manual start task',
    currentTaskIds: ['task_default']
  });

  assert.equal(resolvePendingAutoTaskCandidate({
    tracker,
    currentSessionId: 'session_manual',
    tasks: [
      { id: 'task_default', goal: 'chat browser probe default task auto' },
      { id: 'task_late_auto', goal: 'chat browser probe auto-start task' }
    ]
  }), '');
});

test('buildPendingAutoTaskTracker also tracks default message path when it materializes a new task', () => {
  const tracker = buildPendingAutoTaskTracker({
    sessionId: 'session_2',
    resolvedMode: 'message',
    existingTaskId: '',
    currentTaskIds: ['task_seed']
  });

  assert.equal(tracker.shouldTrack, true);
  assert.equal(tracker.sessionId, 'session_2');
  assert.equal(tracker.resolvedMode, 'message');
  assert.deepEqual(tracker.knownTaskIds, ['task_seed']);
});

test('resolvePendingAutoTaskCandidate picks first unseen task in same session', () => {
  const tracker = buildPendingAutoTaskTracker({
    sessionId: 'session_1',
    resolvedMode: 'task',
    existingTaskId: '',
    currentTaskIds: ['task_a']
  });

  const candidate = resolvePendingAutoTaskCandidate({
    tracker,
    currentSessionId: 'session_1',
    tasks: [
      { id: 'task_a' },
      { id: 'task_b' }
    ]
  });
  assert.equal(candidate, 'task_b');
});

test('resolvePendingAutoTaskCandidate ignores other session or no new task', () => {
  const tracker = buildPendingAutoTaskTracker({
    sessionId: 'session_1',
    resolvedMode: 'task',
    existingTaskId: '',
    currentTaskIds: ['task_a']
  });

  assert.equal(resolvePendingAutoTaskCandidate({
    tracker,
    currentSessionId: 'session_2',
    tasks: [{ id: 'task_b' }]
  }), '');

  assert.equal(resolvePendingAutoTaskCandidate({
    tracker,
    currentSessionId: 'session_1',
    tasks: [{ id: 'task_a' }]
  }), '');
});
