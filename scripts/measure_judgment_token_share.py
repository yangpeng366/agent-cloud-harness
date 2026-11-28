#!/usr/bin/env python3
"""M5 判定开销聚合（read-only）。

目的：把 record_only 旁路账里「每个任务的判定 token」和「执行侧 provider 自报 token」
按任务配对，输出 judgment_token_share 的逐任务明细与分位数（中位数 / P25 / P75）。

数据来源（只读，不写库）：
  - decisions 表：decision_type 以 `_judgment` 结尾的行，metadata_json 里的
    judgment_input_tokens / judgment_output_tokens / judgment_cached_tokens / judgment_stage
  - agent_runs 表：metadata_json.worker_metadata.provider_token_usage
    （codex app-server `thread/tokenUsage/updated` 自报累计快照；不是本地估算）

两个口径必须分别统计、禁止混算（见 docs/tiered-degradation-config.md「M5 首次实测」）：
  - total      : judgment / (judgment + worker.total_tokens)          —— 缓存按实际计费价进入分母
  - non_cached : judgment / (judgment + worker.input + worker.output) —— 剔除 cached_input，最严格口径

两侧 cached 语义不同，聚合时不可对称相加：
  - 判定侧（OpenAI 兼容 provider）：usage.input_tokens 已内含 cached_tokens，故判定合计取 input + output，
    与 ExperimentRunService#judgmentTokenShare 的口径一致；cached 仅作信息项回报，不再叠加（叠加即重复计数）。
  - 执行侧（codex app-server）：input_tokens 不含 cached_input_tokens，total_tokens 已含，故直接取 total_tokens。

用法：
  python scripts/measure_judgment_token_share.py --db <a.db> [<b.db> ...]
  python scripts/measure_judgment_token_share.py --db a.db --db b.db   # --db 可重复，累加而非覆盖
  python scripts/measure_judgment_token_share.py --db-dir .tmp/m5-record-only-run --pretty
  python scripts/measure_judgment_token_share.py --db a.db --markdown

退出码：0 = 成功（无样本时也输出 0 样本摘要）；2 = 参数错误；1 = 读库异常。
"""

import argparse
import glob
import json
import os
import sqlite3
import sys


def percentile(values, q):
    """线性插值分位数（与 numpy.percentile 默认口径一致），空列表返回 None。"""
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    pos = (len(ordered) - 1) * q
    low = int(pos)
    high = min(low + 1, len(ordered) - 1)
    frac = pos - low
    return ordered[low] + (ordered[high] - ordered[low]) * frac


def _int(value, default=0):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def collect_judgment_by_stage(conn, task_id):
    """同一 (task, judgment_stage) 只取最新一条，避免重试导致重复计费。"""
    rows = conn.execute(
        "SELECT id, created_at, decision_type, metadata_json FROM decisions "
        "WHERE task_id = ? AND decision_type LIKE '%\\_judgment' ESCAPE '\\' "
        "ORDER BY created_at ASC",
        (task_id,),
    ).fetchall()
    latest = {}
    for _id, created_at, decision_type, raw in rows:
        meta = json.loads(raw or "{}")
        stage = meta.get("judgment_stage") or decision_type.replace("_judgment", "")
        latest[stage] = {
            "decision_id": _id,
            "stage": stage,
            "input_tokens": _int(meta.get("judgment_input_tokens")),
            "output_tokens": _int(meta.get("judgment_output_tokens")),
            "cached_tokens": _int(meta.get("judgment_cached_tokens")),
            "usage_source": meta.get("judgment_usage_source"),
        }
    return latest


def collect_worker_usage(conn, task_id):
    """取该任务最新一条带 provider_token_usage 的 agent_run。"""
    rows = conn.execute(
        "SELECT id, ended_at, started_at, status, metadata_json FROM agent_runs "
        "WHERE task_id = ? ORDER BY COALESCE(ended_at, started_at, '') DESC",
        (task_id,),
    ).fetchall()
    for run_id, _ended, _started, status, raw in rows:
        meta = json.loads(raw or "{}")
        usage = (meta.get("worker_metadata") or {}).get("provider_token_usage") or {}
        if usage:
            return {
                "run_id": run_id,
                "status": status,
                "input_tokens": _int(usage.get("input_tokens")),
                "cached_input_tokens": _int(usage.get("cached_input_tokens")),
                "output_tokens": _int(usage.get("output_tokens")),
                "total_tokens": _int(
                    usage.get("total_tokens"),
                    _int(usage.get("input_tokens"))
                    + _int(usage.get("cached_input_tokens"))
                    + _int(usage.get("output_tokens")),
                ),
            }
    return None


def collect_task_samples(db_path):
    samples = []
    conn = sqlite3.connect("file:%s?mode=ro" % db_path.replace("\\", "/"), uri=True)
    try:
        tasks = conn.execute("SELECT id, title, status FROM tasks ORDER BY created_at ASC").fetchall()
        for task_id, title, status in tasks:
            stages = collect_judgment_by_stage(conn, task_id)
            worker = collect_worker_usage(conn, task_id)
            judgment_in = sum(s["input_tokens"] for s in stages.values())
            judgment_out = sum(s["output_tokens"] for s in stages.values())
            judgment_cached = sum(s["cached_tokens"] for s in stages.values())
            # 判定侧 input_tokens 已内含 cached_tokens（OpenAI 兼容语义），叠加即重复计数；
            # 故合计只取 input + output，cached 仅作信息项回报。口径同 ExperimentRunService。
            judgment_total = judgment_in + judgment_out
            sample = {
                "db": os.path.basename(db_path),
                "task_id": task_id,
                "task_title": title,
                "task_status": status,
                "judgment_stages": sorted(stages.keys()),
                "judgment_input_tokens": judgment_in,
                "judgment_output_tokens": judgment_out,
                "judgment_cached_tokens": judgment_cached,
                "judgment_total_tokens": judgment_total,
                "worker": worker,
            }
            if worker and judgment_total > 0:
                worker_total = worker["total_tokens"]
                worker_non_cached = worker["input_tokens"] + worker["output_tokens"]
                denom_total = judgment_total + worker_total
                denom_non_cached = judgment_total + worker_non_cached
                sample["judgment_token_share_total"] = round(judgment_total / denom_total, 5) if denom_total else 0.0
                sample["judgment_token_share_non_cached"] = (
                    round(judgment_total / denom_non_cached, 5) if denom_non_cached else 0.0
                )
            else:
                sample["judgment_token_share_total"] = None
                sample["judgment_token_share_non_cached"] = None
                sample["excluded_reason"] = "judgment_tokens_missing" if judgment_total == 0 else "worker_usage_missing"
            samples.append(sample)
    finally:
        conn.close()
    return samples


def summarize(samples):
    total = [s["judgment_token_share_total"] for s in samples if s["judgment_token_share_total"] is not None]
    non_cached = [s["judgment_token_share_non_cached"] for s in samples if s["judgment_token_share_non_cached"] is not None]
    excluded_by_reason = {}
    for s in samples:
        reason = s.get("excluded_reason")
        if reason:
            excluded_by_reason[reason] = excluded_by_reason.get(reason, 0) + 1

    def block(values):
        return {
            "samples": len(values),
            "median": round(percentile(values, 0.5), 5) if values else None,
            "p25": round(percentile(values, 0.25), 5) if values else None,
            "p75": round(percentile(values, 0.75), 5) if values else None,
            "min": round(min(values), 5) if values else None,
            "max": round(max(values), 5) if values else None,
        }

    return {
        "task_count": len(samples),
        "paired_samples": len(total),
        "excluded": len(samples) - len(total),
        "excluded_by_reason": excluded_by_reason,
        "basis_total": block(total),
        "basis_non_cached": block(non_cached),
        "m5_threshold": 0.10,
        "notes": [
            "两口径禁止混算：total 口径含 cached_input（按缓存价计费），non_cached 为最严格口径。",
            "判定侧合计 = input + output（provider 的 input_tokens 已含 cached，不再叠加，避免重复计数）。",
            "样本来自 record_only 旁路账；同一 (task, judgment_stage) 仅取最新一条。",
        ],
    }


def render_markdown(samples, summary):
    lines = []
    lines.append("| db | task | judgment(in/out) | worker(in/cached/out) | share(total) | share(non-cached) |")
    lines.append("|---|---|---|---|---|---|")
    for s in samples:
        w = s["worker"] or {}
        share_total = "n/a" if s["judgment_token_share_total"] is None else "%.2f%%" % (s["judgment_token_share_total"] * 100)
        share_nc = (
            "n/a" if s["judgment_token_share_non_cached"] is None else "%.2f%%" % (s["judgment_token_share_non_cached"] * 100)
        )
        lines.append(
            "| %s | %s | %d/%d | %d/%d/%d | %s | %s |"
            % (
                s["db"],
                (s["task_title"] or s["task_id"])[:36],
                s["judgment_input_tokens"],
                s["judgment_output_tokens"],
                _int(w.get("input_tokens")),
                _int(w.get("cached_input_tokens")),
                _int(w.get("output_tokens")),
                share_total,
                share_nc,
            )
        )
    b1 = summary["basis_total"]
    b2 = summary["basis_non_cached"]
    lines.append("")
    lines.append(
        "total 口径: n=%d median=%s p25=%s p75=%s | non-cached 口径: n=%d median=%s p25=%s p75=%s"
        % (
            b1["samples"],
            b1["median"],
            b1["p25"],
            b1["p75"],
            b2["samples"],
            b2["median"],
            b2["p25"],
            b2["p75"],
        )
    )
    return "\n".join(lines)


def main(argv):
    # 本机 Python 的 stdout 默认 gbk，而控制台代码页为 65001(UTF-8)，中文 notes 会输出成乱码；
    # 统一按 UTF-8 输出（管道/重定向场景同样成立）。
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
    parser = argparse.ArgumentParser(description="M5 judgment token share aggregation (read-only)")
    # action="extend"：--db 可重复且累加。默认的 store 语义下重复传 --db 只保留最后一个，
    # 会静默丢样本，进而把中位数/四分位算错（样本量本就是 M5 结论的前提）。
    parser.add_argument("--db", nargs="*", action="extend", default=[],
                        help="harness sqlite db 路径（可多个；--db 可重复，累加）")
    parser.add_argument("--db-dir", help="目录，按 harness*.db 展开")
    parser.add_argument("--pretty", action="store_true", help="缩进 JSON")
    parser.add_argument("--markdown", action="store_true", help="额外输出 markdown 表")
    args = parser.parse_args(argv)

    db_paths = list(args.db)
    if args.db_dir:
        db_paths.extend(sorted(glob.glob(os.path.join(args.db_dir, "harness*.db"))))
    db_paths = [p for p in db_paths if os.path.isfile(p)]
    if not db_paths:
        sys.stderr.write("no db found; pass --db <path> or --db-dir <dir>\n")
        return 2

    samples = []
    try:
        for path in db_paths:
            samples.extend(collect_task_samples(path))
    except sqlite3.Error as exc:
        sys.stderr.write("failed to read db: %s\n" % exc)
        return 1

    summary = summarize(samples)
    envelope = {"dbs": [os.path.basename(p) for p in db_paths], "summary": summary, "samples": samples}
    indent = 2 if args.pretty else None
    print(json.dumps(envelope, ensure_ascii=False, indent=indent))
    if args.markdown:
        print()
        print(render_markdown(samples, summary))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
