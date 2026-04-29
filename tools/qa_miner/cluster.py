"""聚类去重：用 embedding 把语义相近的 QA 归类，每类选代表，压缩到 ~80 条。

用法:
    source .venv/bin/activate
    python cluster.py                            # 默认读 output/full_qas.jsonl
    python cluster.py --input /path/to/qas.jsonl
    python cluster.py --target 80               # 目标条数（默认 80）
"""
import argparse
import json
import pickle
import re
import sys
from pathlib import Path
from typing import Optional

import numpy as np
from openai import OpenAI
from sklearn.cluster import KMeans
from sklearn.metrics import pairwise_distances_argmin_min
from tqdm import tqdm

from config import ACCOUNTS, DASHSCOPE_API_KEY, DASHSCOPE_BASE_URL, account_paths

EMBED_MODEL = "text-embedding-v3"
EMBED_BATCH = 10  # DashScope text-embedding-v3 单次最多 10 条

_client = OpenAI(api_key=DASHSCOPE_API_KEY, base_url=DASHSCOPE_BASE_URL)

# 场景归一化：把抽取时出现的自由命名归并到标准 SCENE_TAGS
SCENE_NORMALIZE = {
    "课程时间": "上课时间",
    "调整时段": "上课时间",
    "周末课程": "上课时间",
    "课程进度": "课程介绍",
    "课程结束时间": "上课时间",
    "师资": "老师介绍",
    "开班情况": "报名咨询",
    "转课": "请假补课",
    "老生服务": "孩子状态",
    "接送通知": "其他",
}

# 各场景的 K（聚类数）上限；超大场景按比例，小场景至少 1
# 比例因子：每 X 条产出 1 个代表
SCENE_RATIO = 35   # 每 35 条 QA 留 1 条代表
SCENE_MIN_K = 2    # 每个场景至少 2 条代表
SCENE_MAX_K = {   # 允许的最大 K（防止头部场景占比过高）
    "课程介绍": 15,
    "上课时间": 12,
    "价格咨询": 12,
    "请假补课": 6,
    "试听课":   5,
    "地址交通": 4,
    "报名咨询": 4,
    "班型咨询": 4,
    "其他":     4,
    "老师介绍": 4,
    "年龄适配": 3,
    "退费":     3,
    "零基础咨询": 3,
    "效果承诺": 2,
    "续费":     2,
    "考级比赛": 2,
    "孩子状态": 2,
}

PHONE_RE = re.compile(r'1[3-9]\d{9}')
WECHAT_RE = re.compile(r'微信[号：:]\s*\S+')


# ── 数据加载 ───────────────────────────────────────────────────────────────


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
            # 修正 confidence 字段类型
            conf = q.get("confidence", 0)
            if isinstance(conf, str):
                try:
                    conf = float(conf)
                except ValueError:
                    conf = 0.0
            q["confidence"] = conf
            qas.append(q)
    return qas


def clean_qas(qas: list[dict], min_confidence: float = 0.8) -> list[dict]:
    cleaned = []
    for q in qas:
        if q["confidence"] < min_confidence:
            continue
        # 脱敏
        reply = q["agent_reply"]
        reply = PHONE_RE.sub("[联系方式]", reply)
        reply = WECHAT_RE.sub("[微信联系方式]", reply)
        # 去掉微信表情占位符（如 [愉快]）
        reply = re.sub(r'\[[一-鿿A-Za-z0-9_]+\]', '', reply).strip()
        q = dict(q)
        q["agent_reply"] = reply
        # 场景归一化
        q["scene"] = SCENE_NORMALIZE.get(q["scene"], q["scene"])
        cleaned.append(q)
    return cleaned


# ── Embedding ──────────────────────────────────────────────────────────────


def _embed_batch(texts: list[str]) -> list[list[float]]:
    resp = _client.embeddings.create(model=EMBED_MODEL, input=texts)
    return [item.embedding for item in resp.data]


def get_embeddings(texts: list[str], cache_file: Optional[Path] = None) -> np.ndarray:
    """获取 embedding，支持增量缓存。"""
    cache: dict[str, list[float]] = {}
    if cache_file and cache_file.exists():
        with cache_file.open("rb") as f:
            cache = pickle.load(f)

    to_embed = [t for t in texts if t not in cache]
    if to_embed:
        print(f"  调用 embedding API: {len(to_embed)} 条（共 {len(texts)} 条）")
        for i in tqdm(range(0, len(to_embed), EMBED_BATCH), desc="  embedding"):
            batch = to_embed[i: i + EMBED_BATCH]
            vecs = _embed_batch(batch)
            for t, v in zip(batch, vecs):
                cache[t] = v

        if cache_file:
            cache_file.parent.mkdir(parents=True, exist_ok=True)
            with cache_file.open("wb") as f:
                pickle.dump(cache, f)

    return np.array([cache[t] for t in texts], dtype=np.float32)


# ── 聚类 ───────────────────────────────────────────────────────────────────


def scene_k(scene: str, n: int) -> int:
    k = max(SCENE_MIN_K, round(n / SCENE_RATIO))
    max_k = SCENE_MAX_K.get(scene, 3)
    return min(k, max_k, n)  # 不超过样本数


def cluster_scene(qas: list[dict], k: int) -> list[dict]:
    """对同一场景的 QA 做 K-means，每类选置信度最高（tie 时选最短 reply）的代表。"""
    if k >= len(qas):
        return qas
    texts = [q["customer_question"] for q in qas]
    vecs = get_embeddings(texts, cache_file=None)  # 全量 embedding 已经在外面做了

    # 从全局 embedding 矩阵里取对应行（通过文本匹配）
    # 注意：这里直接用已经算好的向量，cluster_scene 不再独立调 API
    return _kmeans_pick(qas, vecs, k)


def _kmeans_pick(qas: list[dict], vecs: np.ndarray, k: int) -> list[dict]:
    km = KMeans(n_clusters=k, n_init=10, random_state=42)
    labels = km.fit_predict(vecs)
    picked = []
    for cluster_id in range(k):
        members = [qas[i] for i, l in enumerate(labels) if l == cluster_id]
        if not members:
            continue
        # 优先置信度高；同置信度选 reply 最短（更简洁）
        best = max(members, key=lambda q: (q["confidence"], -len(q["agent_reply"])))
        picked.append(best)
    return picked


# ── 输出 ───────────────────────────────────────────────────────────────────


def write_jsonl(qas: list[dict], path: Path) -> None:
    with path.open("w", encoding="utf-8") as f:
        for q in qas:
            f.write(json.dumps(q, ensure_ascii=False) + "\n")


def write_review_md(qas: list[dict], path: Path) -> None:
    by_scene: dict[str, list[dict]] = {}
    for q in qas:
        by_scene.setdefault(q["scene"], []).append(q)

    lines = [
        "# 话术库 Review 表",
        "",
        f"共 **{len(qas)}** 条，请在「操作」列填写：✅ 保留 / ✏️ 修改 / ❌ 删除",
        "",
    ]
    for scene, items in sorted(by_scene.items(), key=lambda x: -len(x[1])):
        lines.append(f"## {scene}（{len(items)} 条）")
        lines.append("")
        lines.append("| # | 业务线 | 客户问题 | 客服回复 | 风险提示 | 置信度 | 操作 |")
        lines.append("|---|--------|----------|----------|----------|--------|------|")
        for i, q in enumerate(items, 1):
            q_text = q["customer_question"].replace("|", "｜").replace("\n", " ")
            a_text = q["agent_reply"].replace("|", "｜").replace("\n", " / ")
            risk = q.get("risk_note", "").replace("|", "｜") or "-"
            conf = f"{q['confidence']:.1f}"
            biz = q["business_line"]
            lines.append(f"| {i} | {biz} | {q_text} | {a_text} | {risk} | {conf} | ✅ |")
        lines.append("")

    path.write_text("\n".join(lines), encoding="utf-8")


# ── 主流程 ─────────────────────────────────────────────────────────────────


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--account", required=True, choices=list(ACCOUNTS), help="账号: kirin 或 xingshu")
    parser.add_argument("--target", type=int, default=80, help="目标 QA 条数（仅影响提示）")
    args = parser.parse_args()

    paths = account_paths(args.account)
    input_path = paths["full_jsonl"]
    embed_cache = paths["embed_cache"]

    if not input_path.exists():
        print(f"找不到输入文件: {input_path}（请先跑 run_full.py --account {args.account}）")
        return 1

    print(f"[1/4] 加载 & 清洗 QA（账号: {args.account}）…")
    raw_qas = load_qas(input_path)
    qas = clean_qas(raw_qas)
    print(f"  原始 {len(raw_qas)} 条 → 清洗后 {len(qas)} 条（confidence ≥ 0.8）")

    print(f"\n[2/4] 获取 embedding（文本共 {len(qas)} 条）…")
    texts = [q["customer_question"] for q in qas]
    all_vecs = get_embeddings(texts, cache_file=embed_cache)

    print(f"\n[3/4] 按场景聚类…")
    # 按场景分组，并把对应向量带进去
    scene_groups: dict[str, list[tuple[dict, np.ndarray]]] = {}
    for qa, vec in zip(qas, all_vecs):
        scene_groups.setdefault(qa["scene"], []).append((qa, vec))

    final_qas: list[dict] = []
    for scene, items in sorted(scene_groups.items(), key=lambda x: -len(x[1])):
        scene_qas = [it[0] for it in items]
        scene_vecs = np.array([it[1] for it in items], dtype=np.float32)
        k = scene_k(scene, len(scene_qas))
        print(f"  {scene}: {len(scene_qas)} 条 → K={k}")
        picked = _kmeans_pick(scene_qas, scene_vecs, k)
        final_qas.extend(picked)

    print(f"\n  聚类结果: {len(final_qas)} 条（目标 {args.target} 条）")

    print(f"\n[4/4] 写出结果…")
    out_jsonl = paths["clustered_jsonl"]
    out_md = paths["review_md"]
    write_jsonl(final_qas, out_jsonl)
    write_review_md(final_qas, out_md)
    print(f"  ✓ {out_jsonl}")
    print(f"  ✓ {out_md}")

    # 打印统计
    from collections import Counter
    scene_cnt = Counter(q["scene"] for q in final_qas)
    print("\n场景分布:")
    for scene, cnt in sorted(scene_cnt.items(), key=lambda x: -x[1]):
        print(f"  {scene}: {cnt}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
