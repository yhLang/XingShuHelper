#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
清理 APK assets 里的非金标语料：
- qa_<account>_texts.json：只保留 is_gold=true 的条目
- qa_<account>_embeddings.bin：按对应 index 抽出向量

历史背景：早期 APK 内置了全量 5395 条（含 5254 条未审非金标），
后来云端 corpus 只发金标 141 条，但 APK assets 没同步清理 →
首装机时用的是全量脏数据，下次拉云端才覆盖。直接清掉 assets 里
的非金标，让首装机也用干净数据。

embeddings.bin 格式（little endian）：
  [int32 n][int32 d][float32 × n × d]
"""
import json
import os
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app/src/main/assets")
ACCOUNTS = ["xingshu", "kirin"]


def strip(account: str):
    json_path = os.path.join(ASSETS, f"qa_{account}_texts.json")
    bin_path = os.path.join(ASSETS, f"qa_{account}_embeddings.bin")

    if not os.path.exists(json_path):
        print(f"[skip] {account}: {json_path} 不存在")
        return
    if not os.path.exists(bin_path):
        print(f"[skip] {account}: {bin_path} 不存在")
        return

    # 读 json
    with open(json_path, encoding="utf-8") as f:
        items = json.load(f)
    n_total = len(items)

    # 找金标 index
    gold_idx = [i for i, it in enumerate(items) if it.get("is_gold")]
    n_gold = len(gold_idx)

    if n_gold == 0:
        print(f"[warn] {account}: 没有金标条目，跳过")
        return

    print(f"{account}: {n_total} → {n_gold} 条金标（精简 {(1 - n_gold/n_total)*100:.1f}%）")

    # 读 bin
    with open(bin_path, "rb") as f:
        raw = f.read()
    n, d = struct.unpack("<ii", raw[:8])
    if n != n_total:
        print(f"[error] {account}: json 条数 {n_total} 与 bin 条数 {n} 不一致，跳过")
        return

    vec_size = d * 4  # float32
    header_size = 8

    # 抽出金标对应向量
    new_vecs = bytearray()
    new_vecs.extend(struct.pack("<ii", n_gold, d))
    for idx in gold_idx:
        offset = header_size + idx * vec_size
        new_vecs.extend(raw[offset : offset + vec_size])

    # 写新 json
    new_items = [items[i] for i in gold_idx]
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(new_items, f, ensure_ascii=False, indent=2)
    json_old_size = os.path.getsize(json_path)

    # 写新 bin
    with open(bin_path, "wb") as f:
        f.write(bytes(new_vecs))

    print(f"  json: {json_path} ({os.path.getsize(json_path)} bytes)")
    print(f"  bin:  {bin_path} ({os.path.getsize(bin_path)} bytes)")


def main():
    for account in ACCOUNTS:
        strip(account)


if __name__ == "__main__":
    main()
