"""PoC 入口：随机抽 25 个会话，跑过滤+LLM 抽取，输出供人工 review 的 JSONL + Markdown。

用法:
    cd tools/qa_miner
    python -m venv .venv && source .venv/bin/activate
    pip install -r requirements.txt
    python poc.py
"""
import json
import random
import sys
from pathlib import Path

from tqdm import tqdm

from config import OUTPUT_DIR, WECHAT_EXPORT_DIR
from extractor import extract_qas, format_dialog
from filters import filter_sessions

SAMPLE_SIZE = 25
RANDOM_SEED = 42


def main() -> int:
    if not WECHAT_EXPORT_DIR.exists():
        print(f"找不到导出目录: {WECHAT_EXPORT_DIR}")
        return 1

    all_files = sorted(WECHAT_EXPORT_DIR.glob("*.json"))
    print(f"发现 {len(all_files)} 个会话文件")

    random.seed(RANDOM_SEED)
    sample_paths = random.sample(all_files, min(SAMPLE_SIZE, len(all_files)))
    print(f"随机抽取 {len(sample_paths)} 个进行 PoC")

    print("规则过滤中...")
    candidates = filter_sessions(sample_paths)
    print(f"过滤后剩 {len(candidates)} 个会话进入 LLM 抽取")

    jsonl_path = OUTPUT_DIR / "poc_qas.jsonl"
    md_path = OUTPUT_DIR / "poc_review.md"
    sample_dump_path = OUTPUT_DIR / "poc_filtered_dialog.md"

    extracted_total: list[dict] = []
    with sample_dump_path.open("w", encoding="utf-8") as dump_f:
        for path, dialog in tqdm(candidates, desc="LLM 抽取"):
            qas = extract_qas(dialog)
            for qa in qas:
                qa["source_file"] = path.name
                extracted_total.append(qa)

            dump_f.write(f"## {path.name}\n\n")
            dump_f.write("```\n")
            dump_f.write(format_dialog(dialog)[:3000])
            dump_f.write("\n```\n\n")
            dump_f.write(f"抽取结果：{len(qas)} 条\n\n")

    with jsonl_path.open("w", encoding="utf-8") as f:
        for qa in extracted_total:
            f.write(json.dumps(qa, ensure_ascii=False) + "\n")

    with md_path.open("w", encoding="utf-8") as f:
        f.write(f"# PoC 抽取结果（{len(extracted_total)} 条）\n\n")
        f.write(f"- 样本会话数: {len(sample_paths)}\n")
        f.write(f"- 过滤后会话数: {len(candidates)}\n")
        f.write(f"- 抽取 QA 数: {len(extracted_total)}\n\n")
        f.write("---\n\n")
        for i, qa in enumerate(extracted_total, 1):
            f.write(f"## #{i} {qa.get('scene', '?')} (置信度 {qa.get('confidence', 0):.2f})\n\n")
            f.write(f"- 业务线: {qa.get('business_line', '?')}\n")
            f.write(f"- 来源: {qa.get('source_file', '?')}\n")
            if qa.get("risk_note"):
                f.write(f"- 风险提示: {qa['risk_note']}\n")
            f.write(f"\n**客户问题**\n\n> {qa.get('customer_question', '')}\n\n")
            f.write(f"**客服回复**\n\n> {qa.get('agent_reply', '')}\n\n---\n\n")

    print()
    print(f"抽取 QA 数: {len(extracted_total)}")
    print(f"JSONL: {jsonl_path}")
    print(f"Markdown 评审: {md_path}")
    print(f"过滤后对话样本: {sample_dump_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
