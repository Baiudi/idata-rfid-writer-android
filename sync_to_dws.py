#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
sync_to_dws.py —— 把 iData 写标 App 导出的结果回写到钉钉在线电子表格资产台账。

闭环：
  App 导出 rfid_write_results.json
      -> 本脚本读取该 JSON
      -> 通过 dws 读取钉钉在线电子表格(资产台账)
      -> 按「资产编号」匹配行，回填 标签TID / EPC / 写标状态 / 写标时间 / 写入内容
      -> 通过 dws sheet csv-put 写回（整表覆盖，保留其它单元格）

依赖：已安装并登录 dws CLI（v1.0.61+），且对目标表格有编辑权限。
所有 dws 命令自动附加 --format json。

用法示例：
  # 方式A：脚本直接读在线表格
  python sync_to_dws.py --results rfid_write_results.json --node <表格nodeId>

  # 方式B：先导出本地 CSV，再同步（不依赖 csv-get 的 JSON 结构，最稳）
  dws sheet export-csv --node <表格nodeId> --output ledger.csv
  python sync_to_dws.py --results rfid_write_results.json --ledger-csv ledger.csv --node <表格nodeId>

  # 写操作若触发确认门禁，加 --yes 重试：
  python sync_to_dws.py --results rfid_write_results.json --node <node> --yes

参数见 --help。
"""

import argparse
import csv
import io
import json
import subprocess
import sys

DEFAULT_KEY_COL = "资产编号"
DEFAULT_TARGETS = {
    "tid": "标签TID",
    "epc": "EPC",
    "status": "写标状态",
    "written_at": "写标时间",
    "written_data": "写入内容",
}


def run_dws(args, stdin=None, retry_yes=False):
    """执行 dws 命令，返回 (returncode, parsed_json_or_None, raw_text)。"""
    cmd = ["dws"] + args
    if retry_yes and "--yes" not in cmd:
        cmd.append("--yes")
    if "--format" not in cmd:
        cmd += ["--format", "json"]
    proc = subprocess.run(
        cmd,
        input=stdin.encode("utf-8") if stdin is not None else None,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    text = proc.stdout.decode("utf-8", "replace")
    parsed = None
    try:
        parsed = json.loads(text)
    except Exception:
        pass
    return proc.returncode, parsed, text


def load_results(path):
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, list):
        raise ValueError("results 文件应为 JSON 数组")
    return data


def load_ledger(node, sheet_id, rng, ledger_csv, profile):
    """返回 (header: list[str], rows: list[list[str]])。"""
    if ledger_csv:
        with open(ledger_csv, "r", encoding="utf-8-sig", newline="") as f:
            reader = csv.reader(f)
            matrix = [row for row in reader]
        if not matrix:
            raise ValueError("本地台账 CSV 为空")
        return matrix[0], matrix[1:]

    # 在线读取
    args = ["sheet", "csv-get", "--node", node, "--range", rng]
    if sheet_id:
        args += ["--sheet-id", sheet_id]
    if profile:
        args += ["--profile", profile]
    rc, parsed, text = run_dws(args)
    if rc != 0:
        print("读取在线台账失败：", text, file=sys.stderr)
        sys.exit(1)
    matrix = extract_matrix(parsed, text)
    if not matrix:
        print("无法从 csv-get 输出解析出表格数据，建议改用 --ledger-csv 方式。", file=sys.stderr)
        sys.exit(1)
    return matrix[0], matrix[1:]


def extract_matrix(parsed, raw_text):
    """从 csv-get 的 JSON/文本中抽取二维数组。"""
    if isinstance(parsed, dict):
        for k in ("values", "data", "rows", "csv"):
            v = parsed.get(k)
            if isinstance(v, list) and v and isinstance(v[0], list):
                return v
        # 有时 values 是字符串形式的 CSV
        if isinstance(parsed.get("values"), str):
            return csv_to_matrix(parsed["values"])
    if isinstance(parsed, list) and parsed and isinstance(parsed[0], list):
        return parsed
    # 退化：把原始文本当 CSV 解析
    return csv_to_matrix(raw_text)


def csv_to_matrix(text):
    reader = csv.reader(io.StringIO(text))
    return [row for row in reader]


def matrix_to_csv(matrix):
    buf = io.StringIO()
    writer = csv.writer(buf, quoting=csv.QUOTE_MINIMAL, lineterminator="\n")
    for row in matrix:
        writer.writerow(row)
    return buf.getvalue()


def ensure_columns(header, rows, targets):
    """确保目标列存在，不存在则在末尾追加；返回 {role: col_index}。"""
    idx = {}
    for role, name in targets.items():
        if name in header:
            idx[role] = header.index(name)
        else:
            header.append(name)
            for r in rows:
                r.append("")
            idx[role] = len(header) - 1
    return idx


def build_key_index(header, key_col):
    if key_col not in header:
        raise ValueError(f"台账表头缺少关键列「{key_col}」，无法按资产编号匹配")
    return header.index(key_col)


def sync(results, header, rows, targets, key_idx, include_failed, dry_run):
    role_idx = ensure_columns(header, rows, targets)
    # 关键列 -> 行号
    key_to_row = {}
    for i, r in enumerate(rows):
        if len(r) > key_idx:
            key_to_row[r[key_idx].strip()] = i

    changed = 0
    skipped = 0
    for res in results:
        status = (res.get("status") or "").strip()
        if status != "success" and not (include_failed and status == "failed"):
            skipped += 1
            continue
        asset = (res.get("asset_no") or "").strip()
        if not asset:
            skipped += 1
            continue
        if asset not in key_to_row:
            print(f"  [跳过] 台账中找不到资产编号: {asset}", file=sys.stderr)
            skipped += 1
            continue
        row = rows[key_to_row[asset]]
        # 补齐列宽（避免短行越界）
        while len(row) <= max(role_idx.values()):
            row.append("")
        row[role_idx["tid"]] = res.get("tid") or ""
        row[role_idx["epc"]] = res.get("epc") or ""
        row[role_idx["status"]] = status
        row[role_idx["written_at"]] = res.get("written_at") or ""
        row[role_idx["written_data"]] = res.get("written_data") or ""
        changed += 1

    if dry_run:
        print(f"[dry-run] 将更新 {changed} 行，跳过 {skipped} 行。未执行写回。")
        return changed, skipped

    return changed, skipped


def write_back(node, sheet_id, matrix, auto_convert, yes, profile):
    csv_text = matrix_to_csv(matrix)
    args = ["sheet", "csv-put", "--node", node, "--start-cell", "A1",
            "--csv", "-", "--allow-overwrite"]
    if not auto_convert:
        args += ["--auto-convert", "false"]
    if sheet_id:
        args += ["--sheet-id", sheet_id]
    if profile:
        args += ["--profile", profile]

    rc, parsed, text = run_dws(args, stdin=csv_text)
    # 确认门禁
    if rc != 0 and parsed and isinstance(parsed, dict):
        reason = (parsed.get("error") or {}).get("reason") or parsed.get("reason")
        if reason == "confirmation_required":
            if yes:
                rc, parsed, text = run_dws(args, stdin=csv_text, retry_yes=True)
            else:
                print("该写操作需要确认。请检查下列信息后加 --yes 重试：", file=sys.stderr)
                print(text, file=sys.stderr)
                sys.exit(2)
    if rc != 0:
        print("写回台账失败：", text, file=sys.stderr)
        sys.exit(1)
    print("写回成功。")


def main():
    p = argparse.ArgumentParser(description="把 iData 写标结果同步到钉钉在线电子表格资产台账")
    p.add_argument("--results", required=True, help="App 导出的写标结果 JSON 路径")
    p.add_argument("--node", help="钉钉在线电子表格 nodeId（写回时使用；读用 --ledger-csv 也可不传）")
    p.add_argument("--sheet-id", help="工作表 ID（多工作表时指定）")
    p.add_argument("--ledger-csv", help="本地台账 CSV（先 dws sheet export-csv 导出），离线读取更稳")
    p.add_argument("--range", default="A1:Z2000", help="在线读取范围，默认 A1:Z2000")
    p.add_argument("--key-col", default=DEFAULT_KEY_COL, help="台账中资产编号列名")
    p.add_argument("--tid-col", default=DEFAULT_TARGETS["tid"])
    p.add_argument("--epc-col", default=DEFAULT_TARGETS["epc"])
    p.add_argument("--status-col", default=DEFAULT_TARGETS["status"])
    p.add_argument("--time-col", default=DEFAULT_TARGETS["written_at"])
    p.add_argument("--data-col", default=DEFAULT_TARGETS["written_data"])
    p.add_argument("--include-failed", action="store_true", help="也把失败状态写回（默认只写成功）")
    p.add_argument("--auto-convert", dest="auto_convert", action="store_true",
                   help="允许 dws 自动类型转换（默认关闭以保留资产编号/十六进制文本）")
    p.add_argument("--dry-run", action="store_true", help="只打印将要更新的行数，不写回")
    p.add_argument("--yes", action="store_true", help="写操作触发确认门禁时自动确认")
    p.add_argument("--profile", help="dws 组织 profile（多账号时指定）")
    args = p.parse_args()

    if not args.node and not args.ledger_csv:
        p.error("必须提供 --node（在线读）或 --ledger-csv（本地读）之一")

    targets = {
        "tid": args.tid_col,
        "epc": args.epc_col,
        "status": args.status_col,
        "written_at": args.time_col,
        "written_data": args.data_col,
    }

    results = load_results(args.results)
    header, rows = load_ledger(args.node, args.sheet_id, args.range, args.ledger_csv, args.profile)
    key_idx = build_key_index(header, args.key_col)

    print(f"读取台账：{len(rows)} 行；写标结果：{len(results)} 条")
    changed, skipped = sync(results, header, rows, targets, key_idx, args.include_failed, args.dry_run)

    if not args.dry_run:
        if not args.node:
            p.error("写回需要 --node（目标表格 nodeId）")
        # 表头可能被追加了新列，整体作为 matrix 写回
        matrix = [header] + rows
        write_back(args.node, args.sheet_id, matrix, args.auto_convert, args.yes, args.profile)

    print(f"完成：更新 {changed} 行，跳过 {skipped} 行。")


if __name__ == "__main__":
    main()
