"""把人工金标话术合并到指定账号的 RAG 语料库。

输入：
  gold_<account>.json — 金标 QA（每条多个 question 变体共享同一个 answer）

逻辑：
  1. 加载 gold JSON
  2. 展开成单 question 条目（每个 question 一条）
  3. 调 embedding API 取向量（带本地缓存）
  4. 追加到 output/<account>/qa_<account>_texts.json 和 .embeddings.bin
  5. 每条新增条目带 "is_gold": true，方便 prompt 区分

用法：
  source .venv/bin/activate
  python merge_gold.py --account xingshu
"""
import argparse
import json
import pickle
import struct
import sys
from pathlib import Path

from openai import OpenAI

from config import ACCOUNTS, DASHSCOPE_API_KEY, DASHSCOPE_BASE_URL, account_paths

EMBED_MODEL = "text-embedding-v3"
EMBED_BATCH = 10

_client = OpenAI(api_key=DASHSCOPE_API_KEY, base_url=DASHSCOPE_BASE_URL)


def load_gold(path: Path) -> list[dict]:
    """加载金标 JSON。结构：[{scene, questions: [...], answer, risk_note}, ...]"""
    return json.loads(path.read_text(encoding="utf-8"))


def expand_to_items(gold: list[dict]) -> list[dict]:
    """展开成 RAG 单条格式：每个 question 一条记录，共享同 answer。"""
    out = []
    for entry in gold:
        scene = entry.get("scene", "其他")
        answer = entry["answer"]
        risk = entry.get("risk_note", "")
        for q in entry.get("questions", []):
            q = q.strip()
            if not q:
                continue
            out.append({
                "scene": scene,
                "question": q,
                "answer": answer,
                "business_line": "书画",
                "risk_note": risk,
                "is_gold": True,
            })
    return out


def embed_texts(texts: list[str], cache: dict[str, list[float]]) -> list[list[float]]:
    """带缓存的批量 embedding。"""
    todo = [t for t in texts if t not in cache]
    if todo:
        print(f"  调 embedding API: {len(todo)} 条")
        for i in range(0, len(todo), EMBED_BATCH):
            batch = todo[i: i + EMBED_BATCH]
            resp = _client.embeddings.create(model=EMBED_MODEL, input=batch)
            for t, item in zip(batch, resp.data):
                cache[t] = item.embedding
            print(f"    {min(i + EMBED_BATCH, len(todo))}/{len(todo)}")
    return [cache[t] for t in texts]


def load_existing_corpus(texts_path: Path, embed_path: Path) -> tuple[list[dict], list[list[float]], int]:
    """加载现有的 RAG 语料库。返回 (texts, vectors, dim)。"""
    items = json.loads(texts_path.read_text(encoding="utf-8"))
    with embed_path.open("rb") as f:
        n, d = struct.unpack("<ii", f.read(8))
        vecs = []
        for _ in range(n):
            vecs.append(list(struct.unpack(f"<{d}f", f.read(d * 4))))
    if n != len(items):
        raise ValueError(f"texts 条数 ({len(items)}) 与 embeddings ({n}) 不一致")
    return items, vecs, d


def write_corpus(texts: list[dict], vectors: list[list[float]], texts_path: Path, embed_path: Path) -> None:
    texts_path.write_text(json.dumps(texts, ensure_ascii=False, indent=2), encoding="utf-8")
    n = len(vectors)
    d = len(vectors[0])
    with embed_path.open("wb") as f:
        f.write(struct.pack("<ii", n, d))
        for v in vectors:
            f.write(struct.pack(f"<{d}f", *v))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--account", required=True, choices=list(ACCOUNTS))
    args = parser.parse_args()

    paths = account_paths(args.account)
    gold_path = paths["out_dir"].parent.parent / f"gold_{args.account}.json"
    if not gold_path.exists():
        print(f"找不到金标文件: {gold_path}")
        return 1

    texts_path = paths["rag_texts"]
    embed_path = paths["rag_embeddings"]
    cache_path = paths["embed_cache"]

    if not texts_path.exists() or not embed_path.exists():
        print(f"现有 RAG 语料库不存在，请先跑 export_rag.py --account {args.account}")
        return 1

    print(f"[1/5] 加载现有 RAG 库（账号: {args.account}）…")
    existing_texts, existing_vecs, dim = load_existing_corpus(texts_path, embed_path)
    print(f"  现有: {len(existing_texts)} 条，向量维度 {dim}")

    print(f"[2/5] 加载金标话术…")
    gold = load_gold(gold_path)
    new_items = expand_to_items(gold)
    print(f"  金标 {len(gold)} 条 → 展开为 {len(new_items)} 个 Q-A 对")

    print(f"[3/5] 加载 embedding 缓存并补全新条目…")
    cache: dict[str, list[float]] = {}
    if cache_path.exists():
        with cache_path.open("rb") as f:
            cache = pickle.load(f)
    new_questions = [it["question"] for it in new_items]
    new_vecs = embed_texts(new_questions, cache)
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    with cache_path.open("wb") as f:
        pickle.dump(cache, f)

    if len(new_vecs[0]) != dim:
        print(f"维度不匹配: 新向量 {len(new_vecs[0])} vs 现有 {dim}")
        return 1

    print(f"[4/5] 给现有条目补 is_gold=false 标记（如缺失）…")
    for it in existing_texts:
        it.setdefault("is_gold", False)

    merged_texts = existing_texts + new_items
    merged_vecs = existing_vecs + new_vecs
    print(f"  合并后总数: {len(merged_texts)} 条（其中金标 {sum(1 for t in merged_texts if t.get('is_gold'))} 条）")

    print(f"[5/5] 写出文件…")
    write_corpus(merged_texts, merged_vecs, texts_path, embed_path)
    print(f"  ✓ {texts_path}")
    print(f"  ✓ {embed_path} ({embed_path.stat().st_size / 1024 / 1024:.1f} MB)")
    print(f"\n下一步：复制到 app/src/main/assets/ 并重新打包 APK。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
