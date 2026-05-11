import test from 'node:test';
import assert from 'node:assert/strict';

import {
  buildPendingAutoTaskTracker,
  resolvePendingAutoTaskCandidate
} from '../../main/resources/web/dialogue/pending-auto-task-plan.js';

test('buildPendingAutoTaskTracker only tracks new task auto-start flow', () => {
  const tracker = buildPendingAutoTaskTracker({
    sessionId: 'session_1',
    resolvedMode: 'task',
    existingTaskId: '',
    currentTaskIds: ['task_a']
  });
  assert.equal(tracker.shouldTrack, true);
  assert.equal(tracker.sessionId, 'session_1');
  assert.deepEqual(tracker.knownTaskIds, ['task_a']);

  const noTrack = buildPendingAutoTaskTracker({
    sessionId: 'session_1',
    resolvedMode: 'followup',
    existingTaskId: 'task_a',
    currentTaskIds: ['task_a']
  });
  assert.equal(noTrack.shouldTrack, false);
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
