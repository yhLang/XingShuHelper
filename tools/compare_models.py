#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
模型对比脚本：在切换 LLM 之前先验证 deepseek-v4-flash 输出质量是否够用。

用法：
    cd /Users/kdlyh/Projects/XingShuHelper
    python3 tools/compare_models.py

不需要装任何第三方库，纯 stdlib（urllib + json）。

输出：每个 query 把两个模型的回复并排打出来，肉眼对比。
"""
import json
import os
import re
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def read_api_key():
    path = os.path.join(ROOT, "local.properties")
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line.startswith("DASHSCOPE_API_KEY="):
                return line.split("=", 1)[1].strip()
    raise RuntimeError("DASHSCOPE_API_KEY 未在 local.properties 里找到")


def read_structured_kb():
    path = os.path.join(ROOT, "app/src/main/assets/structured_xingshu.txt")
    with open(path, encoding="utf-8") as f:
        return f.read().strip()


def load_qa_samples(n=3):
    """挑几条最常用的话术作为模拟 RAG 召回的上下文。"""
    path = os.path.join(ROOT, "app/src/main/assets/qa_xingshu_texts.json")
    with open(path, encoding="utf-8") as f:
        items = json.load(f)
    # 按 scene 多样性挑：试听 / 价格 / 时段 / 课程介绍 等
    seen = set()
    out = []
    for it in items:
        if it.get("scene") in seen:
            continue
        seen.add(it.get("scene"))
        out.append(it)
        if len(out) >= n:
            break
    return out


SYSTEM_PROMPT_TPL = """你是行恕书画艺术培训中心的 AI 客服助手，专门帮助前台客服人员快速生成微信回复草稿。

## 回复原则
- 语气自然亲切，像微信真人客服，不要像官方模板
- 不编造具体价格、课表、老师排班（数据已提供时直接引用）
- 不承诺效果、不保证考级通过、不保证获奖
- 优先引导预约试听课
- 遇到不确定信息，提醒人工确认

## 当期课程结构化数据（价格、时段以此为准）

{structured}

## 标准话术（参考其措辞、长度和语气）

{qa_block}

## 输出格式（必须严格遵循 JSON）

{{
  "is_sensitive": false,
  "sensitive_note": "",
  "short": "简短版回复，2-3 句",
  "natural": "自然版回复，3-5 句",
  "invite": "邀约版回复，3-5 句，引导试听预约",
  "intent": "高/中/低",
  "next_step": "建议下一步操作",
  "human_confirm": ""
}}
"""


def build_system_prompt():
    structured = read_structured_kb()
    qa_items = load_qa_samples()
    qa_block = "\n\n".join(
        f"【{q['scene']}】\n客户问法：{q['question']}\n推荐回复：{q['answer']}"
        for q in qa_items
    )
    return SYSTEM_PROMPT_TPL.format(structured=structured, qa_block=qa_block)


# 8 个典型客户问题，覆盖各个场景
TEST_QUERIES = [
    # 价格类（结构化 KB 直接命中）
    "你好，硬笔16节多少钱？",
    # 时段类
    "周三下午有书法课吗？",
    # 优惠政策（之前 v1.1.1 才修的路由）
    "老生续报有什么优惠？我有两个孩子",
    # 综合（价格+时段一起问）
    "我家孩子5岁能学吗？周末有课吗？大概多少钱？",
    # 试听邀约
    "可以先试听看看吗",
    # 敏感（应该标记）
    "我上次报的课不想上了，能退费吗",
    # 闲聊/无明确问题
    "你们老师都有教师资格证吧",
    # 模糊/隐藏意图
    "嗯…再考虑一下",
]


def call_model(api_key: str, model: str, system_prompt: str, user_query: str, timeout=60):
    """调用阿里百炼 OpenAI 兼容 API。返回 (耗时秒, 回复文本, prompt_tokens, completion_tokens)。"""
    body = {
        "model": model,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": f"客户消息：{user_query}"},
        ],
        "temperature": 0.3,
        "max_tokens": 1024,
    }
    req = urllib.request.Request(
        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        dt = time.time() - t0
        content = data["choices"][0]["message"]["content"]
        usage = data.get("usage", {})
        return dt, content, usage.get("prompt_tokens", 0), usage.get("completion_tokens", 0)
    except Exception as e:
        return time.time() - t0, f"[ERROR] {type(e).__name__}: {e}", 0, 0


def extract_json(text):
    """从模型输出里抠 JSON，多种 fallback。"""
    text = text.strip()
    # 直接试
    try:
        return json.loads(text)
    except Exception:
        pass
    # 去 markdown fence
    cleaned = re.sub(r"^```(?:json)?\s*", "", text)
    cleaned = re.sub(r"```\s*$", "", cleaned).strip()
    try:
        return json.loads(cleaned)
    except Exception:
        pass
    # 抠 {...}
    start = text.find("{")
    end = text.rfind("}")
    if start != -1 and end > start:
        try:
            return json.loads(text[start : end + 1])
        except Exception:
            pass
    return None


def format_output(label, dt, content, ptok, ctok):
    parsed = extract_json(content)
    out = [f"━━ {label}（{dt:.2f}s, prompt={ptok}, completion={ctok}）━━"]
    if parsed is None:
        out.append(f"[JSON 解析失败] 原文前 400 字：")
        out.append(content[:400])
    else:
        if parsed.get("is_sensitive"):
            out.append(f"⚠ 敏感: {parsed.get('sensitive_note', '')}")
        out.append(f"简短: {parsed.get('short', '')}")
        out.append(f"自然: {parsed.get('natural', '')}")
        out.append(f"邀约: {parsed.get('invite', '')}")
        meta = []
        if parsed.get("intent"):
            meta.append(f"意向={parsed['intent']}")
        if parsed.get("next_step"):
            meta.append(f"下一步={parsed['next_step']}")
        if parsed.get("human_confirm"):
            meta.append(f"人工={parsed['human_confirm']}")
        if meta:
            out.append("  · " + " / ".join(meta))
    return "\n".join(out)


def main():
    api_key = read_api_key()
    system_prompt = build_system_prompt()

    print("=" * 80)
    print(f"system prompt 长度: {len(system_prompt)} 字符")
    print(f"测试模型: qwen-plus (当前) vs deepseek-v4-flash (候选)")
    print(f"测试 query 数: {len(TEST_QUERIES)}")
    print("=" * 80)

    totals = {"qwen-plus": [0.0, 0, 0], "deepseek-v4-flash": [0.0, 0, 0]}

    for i, query in enumerate(TEST_QUERIES, 1):
        print(f"\n{'='*80}")
        print(f"【Query {i}/{len(TEST_QUERIES)}】{query}")
        print("=" * 80)

        # 并行调两个模型
        with ThreadPoolExecutor(max_workers=2) as ex:
            f_qwen = ex.submit(call_model, api_key, "qwen-plus", system_prompt, query)
            f_ds = ex.submit(call_model, api_key, "deepseek-v4-flash", system_prompt, query)
            qwen_result = f_qwen.result()
            ds_result = f_ds.result()

        for model, result in [("qwen-plus", qwen_result), ("deepseek-v4-flash", ds_result)]:
            dt, content, ptok, ctok = result
            totals[model][0] += dt
            totals[model][1] += ptok
            totals[model][2] += ctok

        print(format_output("qwen-plus", *qwen_result))
        print()
        print(format_output("deepseek-v4-flash", *ds_result))

    print(f"\n{'='*80}")
    print("汇总")
    print("=" * 80)
    for model, (total_dt, total_p, total_c) in totals.items():
        n = len(TEST_QUERIES)
        print(
            f"{model}: 总耗时 {total_dt:.1f}s ({total_dt/n:.2f}s/次), "
            f"input {total_p} tok, output {total_c} tok"
        )
    print()
    print("肉眼对比要点：")
    print("  1. 价格/时段引用是否准确")
    print("  2. 邀约话术诱导性是否到位")
    print("  3. 敏感场景识别（query 6 退费）")
    print("  4. 模糊问题处理（query 8 再考虑）")
    print("  5. JSON 输出格式稳定性（解析失败计数）")


if __name__ == "__main__":
    main()
