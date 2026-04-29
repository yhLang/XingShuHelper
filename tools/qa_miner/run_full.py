"""全量抽取：会话 → LLM 抽 QA。并发 5，按文件名缓存支持断点续跑。

用法:
    source .venv/bin/activate
    python run_full.py --account kirin               # 跑麒麟斋全量
    python run_full.py --account xingshu             # 跑行恕书院全量
    python run_full.py --account xingshu --limit 50  # 验证用
"""
import argparse
import hashlib
import json
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from tqdm import tqdm

from config import ACCOUNTS, account_paths
from extractor import build_system_prompt, extract_qas
from filters import extract_dialog, load_session, session_has_inquiry

CONCURRENCY = 5


def cache_key(path: Path) -> str:
    return hashlib.md5(path.name.encode()).hexdigest()


def cache_file(cache_dir: Path, path: Path) -> Path:
    return cache_dir / f"{cache_key(path)}.json"


def is_cached(cache_dir: Path, path: Path) -> bool:
    return cache_file(cache_dir, path).exists()


def read_cache(cache_dir: Path, path: Path) -> list[dict]:
    try:
        return json.loads(cache_file(cache_dir, path).read_text(encoding="utf-8"))
    except Exception:
        return []


def write_cache(cache_dir: Path, path: Path, qas: list[dict]) -> None:
    cache_file(cache_dir, path).write_text(
        json.dumps(qas, ensure_ascii=False, indent=2), encoding="utf-8"
    )


def process_one(path: Path, cache_dir: Path, system_prompt: str) -> tuple[Path, list[dict], str]:
    if is_cached(cache_dir, path):
        return path, read_cache(cache_dir, path), "cached"

    session = load_session(path)
    if not session:
        write_cache(cache_dir, path, [])
        return path, [], "error"

    dialog = extract_dialog(session)
    if not dialog or not session_has_inquiry(dialog):
        write_cache(cache_dir, path, [])
        return path, [], "skipped"

    try:
        qas = extract_qas(dialog, system_prompt)
    except Exception as e:
        print(f"\n  ! {path.name}: {e}")
        write_cache(cache_dir, path, [])
        return path, [], "error"

    for qa in qas:
        qa["source_file"] = path.name
    write_cache(cache_dir, path, qas)
    return path, qas, "extracted"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--account", required=True, choices=list(ACCOUNTS), help="账号: kirin 或 xingshu")
    parser.add_argument("--limit", type=int, default=0, help="只处理前 N 个文件（0=全量）")
    args = parser.parse_args()

    paths = account_paths(args.account)
    cfg = ACCOUNTS[args.account]
    wechat_dir: Path = paths["wechat_export_dir"]

    if not wechat_dir.exists():
        print(f"找不到导出目录: {wechat_dir}")
        return 1

    system_prompt = build_system_prompt(cfg["agent_signature"], cfg["business_desc"])

    all_files = sorted(wechat_dir.glob("*.json"))
    if args.limit:
        all_files = all_files[: args.limit]

    cache_dir = paths["cache_dir"]
    cached = sum(1 for p in all_files if is_cached(cache_dir, p))
    print(f"账号: {cfg['label']}（{args.account}）")
    print(f"导出目录: {wechat_dir}")
    print(f"共 {len(all_files)} 个文件，已缓存 {cached} 个，待处理 {len(all_files) - cached} 个")

    all_qas: list[dict] = []
    stats = {"cached": 0, "extracted": 0, "skipped": 0, "error": 0}

    with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        futures = {pool.submit(process_one, p, cache_dir, system_prompt): p for p in all_files}
        with tqdm(total=len(futures), desc="处理中") as bar:
            for fut in as_completed(futures):
                path, qas, status = fut.result()
                all_qas.extend(qas)
                stats[status] = stats.get(status, 0) + 1
                bar.set_postfix(
                    cached=stats["cached"],
                    new=stats["extracted"],
                    skip=stats["skipped"],
                    err=stats["error"],
                    qa=len(all_qas),
                )
                bar.update(1)

    full_jsonl = paths["full_jsonl"]
    with full_jsonl.open("w", encoding="utf-8") as f:
        for qa in all_qas:
            f.write(json.dumps(qa, ensure_ascii=False) + "\n")

    print()
    print(f"完成: cached={stats['cached']} new={stats['extracted']} skipped={stats['skipped']} error={stats['error']}")
    print(f"总 QA 数: {len(all_qas)}")
    print(f"输出: {full_jsonl}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
