"""导出 RAG 语料库：生成 Android assets 所需的两个文件。

输出（在 output/<account>/ 下）：
  qa_<account>_texts.json       — QA 文本列表（小，~600KB）
  qa_<account>_embeddings.bin  — float32 二进制向量矩阵（大，~10MB）

二进制格式（小端序）：
  [0..3]   int32  N  — 条目数
  [4..7]   int32  D  — 向量维度（1024）
  [8..]    float32 × N × D — 行主序

用法：
  source .venv/bin/activate
  python export_rag.py --account kirin
  python export_rag.py --account xingshu
"""
import argparse
import json
import pickle
import re
import struct
import sys
from pathlib import Path

from config import ACCOUNTS, account_paths

PHONE_RE = re.compile(r'1[3-9]\d{9}')
WECHAT_RE = re.compile(r'微信[号：:]\s*\S+')


def load_qas(path: Path) -> list[dict]:
    qas = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                q = json.loads(line)
            except json.JSONDecodeError:
                continue
            conf = q.get("confidence", 0)
            if isinstance(conf, str):
                try:
                    conf = float(conf)
                except ValueError:
                    conf = 0.0
            q["confidence"] = conf
            qas.append(q)
    return qas


def clean_reply(text: str) -> str:
    text = PHONE_RE.sub("[联系方式]", text)
    text = WECHAT_RE.sub("[微信联系方式]", text)
    text = re.sub(r'\[[一-鿿A-Za-z0-9_]+\]', '', text).strip()
    return text


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--account", required=True, choices=list(ACCOUNTS))
    parser.add_argument("--min-confidence", type=float, default=0.8)
    args = parser.parse_args()

    paths = account_paths(args.account)
    input_path = paths["full_jsonl"]
    cache_path = paths["embed_cache"]

    if not input_path.exists():
        print(f"找不到输入文件: {input_path}")
        return 1
    if not cache_path.exists():
        print(f"找不到 embedding 缓存: {cache_path}（请先跑 cluster.py --account {args.account}）")
        return 1

    print(f"[1/3] 加载 QA 和 embedding 缓存（账号: {args.account}）…")
    raw_qas = load_qas(input_path)
    with cache_path.open("rb") as f:
        embed_cache: dict[str, list[float]] = pickle.load(f)
    print(f"  QA 总数: {len(raw_qas)}，embedding 缓存: {len(embed_cache)} 条")

    print("[2/3] 过滤 & 匹配向量…")
    texts = []
    vectors = []
    skipped_no_embed = 0
    skipped_low_conf = 0

    for q in raw_qas:
        if q["confidence"] < args.min_confidence:
            skipped_low_conf += 1
            continue
        question = q["customer_question"]
        vec = embed_cache.get(question)
        if vec is None:
            skipped_no_embed += 1
            continue
        texts.append({
            "scene": q.get("scene", "其他"),
            "question": question,
            "answer": clean_reply(q.get("agent_reply", "")),
            "business_line": q.get("business_line", "其他"),
            "risk_note": q.get("risk_note", ""),
        })
        vectors.append(vec)

    print(f"  保留: {len(texts)} 条，跳过低置信度: {skipped_low_conf}，跳过无向量: {skipped_no_embed}")

    if not texts:
        print("没有可用的 QA，退出")
        return 1

    D = len(vectors[0])
    N = len(vectors)
    print(f"  向量维度: D={D}，条目数: N={N}")

    print("[3/3] 写出文件…")
    texts_path = paths["rag_texts"]
    embed_path = paths["rag_embeddings"]

    texts_path.write_text(json.dumps(texts, ensure_ascii=False, indent=2), encoding="utf-8")

    with embed_path.open("wb") as f:
        f.write(struct.pack("<ii", N, D))
        for vec in vectors:
            f.write(struct.pack(f"<{D}f", *vec))

    texts_size = texts_path.stat().st_size / 1024
    embed_size = embed_path.stat().st_size / 1024 / 1024
    print(f"  ✓ {texts_path}（{texts_size:.0f} KB）")
    print(f"  ✓ {embed_path}（{embed_size:.1f} MB）")
    print(f"\n把这两个文件复制到 app/src/main/assets/ 目录即可。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
