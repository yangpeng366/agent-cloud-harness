-- Agent Cloud Harness - SQLite Schema v0.2
-- 核心: sessions + tasks + decisions + artifacts + events + resume_packets + relations + skills + checkpoints

CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  title TEXT,
  status TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  closed_at TEXT,
  root_task_id TEXT,
  current_task_id TEXT,
  summary TEXT,
  metadata_json TEXT
);

CREATE TABLE IF NOT EXISTS tasks (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  parent_task_id TEXT,
  title TEXT NOT NULL,
  status TEXT NOT NULL,
  priority TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  started_at TEXT,
  completed_at TEXT,
  owner_role TEXT,
  summary TEXT,
  goal TEXT,
  next_step TEXT,
  assigned_worker TEXT,
  control_node TEXT,          -- intake | scheduler | continue | packet | human_gate | handoff | end
  waiting_reason TEXT,        -- 进入 waiting 的原因
  metadata_json TEXT,
  FOREIGN KEY(session_id) REFERENCES sessions(id)
);

CREATE TABLE IF NOT EXISTS session_messages (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  task_id TEXT,
  role TEXT NOT NULL,
  message_type TEXT NOT NULL,
  content TEXT NOT NULL,
  created_at TEXT NOT NULL,
  metadata_json TEXT,
  FOREIGN KEY(session_id) REFERENCES sessions(id),
  FOREIGN KEY(task_id) REFERENCES tasks(id)
);

CREATE TABLE IF NOT EXISTS decisions (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  task_id TEXT,
  created_at TEXT NOT NULL,
  decision_type TEXT,
  summary TEXT NOT NULL,
  rationale TEXT,
  impact_level TEXT,
  supersedes_decision_id TEXT,
  metadata_json TEXT,
  FOREIGN KEY(session_id) REFERENCES sessions(id),
  FOREIGN KEY(task_id) REFERENCES tasks(id)
);

CREATE TABLE IF NOT EXISTS artifacts (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  task_id TEXT,
  created_at TEXT NOT NULL,
  artifact_type TEXT NOT NULL,
  title TEXT,
  uri TEXT,
  content_hash TEXT,
  summary TEXT,
  metadata_json TEXT,
  FOREIGN KEY(session_id) REFERENCES sessions(id),
  FOREIGN KEY(task_id) REFERENCES tasks(id)
);

CREATE TABLE IF NOT EXISTS events (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  task_id TEXT,
  created_at TEXT NOT NULL,
  event_type TEXT NOT NULL,
  actor_type TEXT,
  actor_id TEXT,
  summary TEXT,
  payload_json TEXT,
  FOREIGN KEY(session_id) REFERENCES sessions(id),
  FOREIGN KEY(task_id) REFERENCES tasks(id)
);

CREATE TABLE IF NOT EXISTS resume_packets (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  task_id TEXT,
  created_at TEXT NOT NULL,
  packet_version TEXT NOT NULL,
  active_task_summary TEXT,
  decision_summary TEXT,
  artifact_summary TEXT,
  open_questions_json TEXT,
  next_step TEXT,
  payload_json TEXT NOT NULL,
  FOREIGN KEY(session_id) REFERENCES sessions(id),
  FOREIGN KEY(task_id) REFERENCES tasks(id)
);

CREATE TABLE IF NOT EXISTS relations (
  id TEXT PRIMARY KEY,
  source_type TEXT NOT NULL,
  source_id TEXT NOT NULL,
  relation_type TEXT NOT NULL,
  target_type TEXT NOT NULL,
  target_id TEXT NOT NULL,
  created_at TEXT NOT NULL,
  metadata_json TEXT
);

-- 新增: skills 表 (支持 Skill Registry)
CREATE TABLE IF NOT EXISTS skills (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  description TEXT,
  capability_tags_json TEXT,   -- ["search", "browser", "parse"]
  input_schema_json TEXT,
  output_schema_json TEXT,
  dependencies_json TEXT,      -- {"api_key": true, "backend": true}
  risk_level TEXT,             -- low | medium | high | critical
  installed INTEGER NOT NULL DEFAULT 0,
  ready INTEGER NOT NULL DEFAULT 0,
  last_checked_at TEXT,
  version TEXT,
  metadata_json TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

-- 新增: checkpoints 表 (支持长任务 checkpoint 与 consolidation)
CREATE TABLE IF NOT EXISTS checkpoints (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  task_id TEXT NOT NULL,
  created_at TEXT NOT NULL,
  checkpoint_type TEXT NOT NULL,   -- periodic | pause_before | handoff_before | session_end
  consolidation_summary TEXT,
  refined_packet_json TEXT,
  world_model_delta_json TEXT,
  metadata_json TEXT,
  FOREIGN KEY(session_id) REFERENCES sessions(id),
  FOREIGN KEY(task_id) REFERENCES tasks(id)
);

CREATE TABLE IF NOT EXISTS learning_memories (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  task_id TEXT NOT NULL,
  created_at TEXT NOT NULL,
  memory_type TEXT NOT NULL,        -- routing_preference | context_retention_hint | completion_pattern | worker_heuristic
  state TEXT NOT NULL,              -- candidate | reinforced | stable_hint
  hint_key TEXT NOT NULL,
  summary TEXT NOT NULL,
  confidence_score REAL,
  reinforcement_count INTEGER NOT NULL DEFAULT 1,
  evidence_json TEXT,
  metadata_json TEXT,
  FOREIGN KEY(session_id) REFERENCES sessions(id),
  FOREIGN KEY(task_id) REFERENCES tasks(id)
);

CREATE TABLE IF NOT EXISTS tool_invocations (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  task_id TEXT NOT NULL,
  worker_id TEXT NOT NULL,
  tool_name TEXT NOT NULL,
  arguments_json TEXT,
  result_summary TEXT,
  success INTEGER NOT NULL,
  elapsed_ms INTEGER,
  created_at TEXT NOT NULL,
  metadata_json TEXT,
  FOREIGN KEY(session_id) REFERENCES sessions(id),
  FOREIGN KEY(task_id) REFERENCES tasks(id)
);

CREATE TABLE IF NOT EXISTS experiment_runs (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  task_id TEXT NOT NULL UNIQUE,
  experiment_name TEXT,
  task_case_key TEXT,
  task_title TEXT NOT NULL,
  task_type TEXT,
  task_length_bucket TEXT,
  model_mode TEXT NOT NULL,
  total_steps INTEGER NOT NULL DEFAULT 0,
  completion_status TEXT NOT NULL,
  acceptance_result TEXT,
  total_cost REAL NOT NULL DEFAULT 0,
  strong_model_cost_ratio REAL,
  handoff_count INTEGER NOT NULL DEFAULT 0,
  resume_count INTEGER NOT NULL DEFAULT 0,
  human_gate_count INTEGER NOT NULL DEFAULT 0,
  failure_reason TEXT,
  recovery_success INTEGER,
  final_artifact_quality_note TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  metadata_json TEXT,
  FOREIGN KEY(session_id) REFERENCES sessions(id),
  FOREIGN KEY(task_id) REFERENCES tasks(id)
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_tasks_session_status_updated
ON tasks(session_id, status, updated_at);

CREATE INDEX IF NOT EXISTS idx_session_messages_session_created
ON session_messages(session_id, created_at);

CREATE INDEX IF NOT EXISTS idx_session_messages_task_created
ON session_messages(task_id, created_at);

CREATE INDEX IF NOT EXISTS idx_decisions_session_task_created
ON decisions(session_id, task_id, created_at);

CREATE INDEX IF NOT EXISTS idx_artifacts_session_task_created
ON artifacts(session_id, task_id, created_at);

CREATE INDEX IF NOT EXISTS idx_events_session_task_created
ON events(session_id, task_id, created_at);

CREATE INDEX IF NOT EXISTS idx_resume_packets_session_task_created
ON resume_packets(session_id, task_id, created_at);

CREATE INDEX IF NOT EXISTS idx_relations_source
ON relations(source_type, source_id, relation_type);

CREATE INDEX IF NOT EXISTS idx_relations_target
ON relations(target_type, target_id, relation_type);

CREATE INDEX IF NOT EXISTS idx_skills_ready
ON skills(ready, updated_at);

CREATE INDEX IF NOT EXISTS idx_checkpoints_task_created
ON checkpoints(task_id, created_at);

CREATE INDEX IF NOT EXISTS idx_learning_memories_task_created
ON learning_memories(task_id, created_at);

CREATE INDEX IF NOT EXISTS idx_learning_memories_type_hint
ON learning_memories(memory_type, hint_key, created_at);

CREATE INDEX IF NOT EXISTS idx_tool_invocations_task_created
ON tool_invocations(task_id, created_at);

CREATE INDEX IF NOT EXISTS idx_tool_invocations_session_task_created
ON tool_invocations(session_id, task_id, created_at);

CREATE INDEX IF NOT EXISTS idx_experiment_runs_experiment_case
ON experiment_runs(experiment_name, task_case_key, model_mode, updated_at);

CREATE INDEX IF NOT EXISTS idx_experiment_runs_task_length
ON experiment_runs(task_length_bucket, model_mode, updated_at);
