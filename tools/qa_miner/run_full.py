"""全量抽取：1500+ 会话，并发 5，按文件名缓存支持断点续跑。

用法:
    source .venv/bin/activate
    python run_full.py           # 全量
    python run_full.py --limit 50  # 先跑前 N 个验证
"""
import argparse
import hashlib
import json
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from tqdm import tqdm

from config import OUTPUT_DIR, WECHAT_EXPORT_DIR
from extractor import extract_qas
from filters import filter_sessions, load_session, extract_dialog, session_has_inquiry

CONCURRENCY = 5
CACHE_DIR = OUTPUT_DIR / ".cache"
CACHE_DIR.mkdir(parents=True, exist_ok=True)

FULL_JSONL = OUTPUT_DIR / "full_qas.jsonl"
SKIPPED_LOG = OUTPUT_DIR / "full_skipped.txt"


def cache_key(path: Path) -> str:
    return hashlib.md5(path.name.encode()).hexdigest()


def cache_path(path: Path) -> Path:
    return CACHE_DIR / f"{cache_key(path)}.json"


def is_cached(path: Path) -> bool:
    return cache_path(path).exists()


def read_cache(path: Path) -> list[dict]:
    try:
        return json.loads(cache_path(path).read_text(encoding="utf-8"))
    except Exception:
        return []


def write_cache(path: Path, qas: list[dict]) -> None:
    cache_path(path).write_text(
        json.dumps(qas, ensure_ascii=False, indent=2), encoding="utf-8"
    )


def process_one(path: Path) -> tuple[Path, list[dict], str]:
    """返回 (path, qas, status)。status: 'cached'|'extracted'|'skipped'|'error'"""
    if is_cached(path):
        return path, read_cache(path), "cached"

    session = load_session(path)
    if not session:
        write_cache(path, [])
        return path, [], "error"

    dialog = extract_dialog(session)
    if not dialog or not session_has_inquiry(dialog):
        write_cache(path, [])
        return path, [], "skipped"

    try:
        qas = extract_qas(dialog)
    except Exception as e:
        print(f"\n  ! {path.name}: {e}")
        write_cache(path, [])
        return path, [], "error"

    for qa in qas:
        qa["source_file"] = path.name
    write_cache(path, qas)
    return path, qas, "extracted"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=0, help="只处理前 N 个文件（0=全量）")
    args = parser.parse_args()

    if not WECHAT_EXPORT_DIR.exists():
        print(f"找不到导出目录: {WECHAT_EXPORT_DIR}")
        return 1

    all_files = sorted(WECHAT_EXPORT_DIR.glob("*.json"))
    if args.limit:
        all_files = all_files[: args.limit]

    cached = sum(1 for p in all_files if is_cached(p))
    print(f"共 {len(all_files)} 个文件，已缓存 {cached} 个，待处理 {len(all_files)-cached} 个")

    all_qas: list[dict] = []
    stats = {"cached": 0, "extracted": 0, "skipped": 0, "error": 0}

    with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        futures = {pool.submit(process_one, p): p for p in all_files}
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

    # 写最终 JSONL（全量合并）
    with FULL_JSONL.open("w", encoding="utf-8") as f:
        for qa in all_qas:
            f.write(json.dumps(qa, ensure_ascii=False) + "\n")

    print()
    print(f"完成: cached={stats['cached']} new={stats['extracted']} skipped={stats['skipped']} error={stats['error']}")
    print(f"总 QA 数: {len(all_qas)}")
    print(f"输出: {FULL_JSONL}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
