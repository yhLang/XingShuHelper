"""LLM 抽取：把过滤后的对话送给 qwen-turbo，用工具调用强制结构化输出。"""
import json
from typing import Any

from openai import OpenAI

from config import DASHSCOPE_API_KEY, DASHSCOPE_BASE_URL, EXTRACT_MODEL

_client = OpenAI(api_key=DASHSCOPE_API_KEY, base_url=DASHSCOPE_BASE_URL)


SCENE_TAGS = [
    "价格咨询", "课程介绍", "年龄适配", "零基础咨询",
    "上课时间", "周末课程", "试听课", "地址交通",
    "老师介绍", "请假补课", "退费", "投诉",
    "效果承诺", "考级比赛", "续费", "报名咨询",
    "班型咨询", "孩子状态", "作品效果", "其他",
]

SYSTEM_PROMPT = """你是话术挖掘助手。下方是家长与"行恕书院（麒麟斋）"客服的微信聊天记录。
麒麟斋的业务包括：文化课补习（数学/语文/英语等）、书法（硬笔/毛笔）、国画、棋类（围棋/象棋）等。

任务：从聊天中提取真正的"业务咨询问答对"，作为 AI 客服的话术参考库。

【算"业务咨询"的情况】
- 新客户咨询：价格、课程内容、试听、年龄要求、零基础、报名流程、地址、师资、班型人数、上课时间
- 异议处理：退费、投诉、不满意、效果担忧、价格异议
- 政策类：请假补课规则（注意：不是单次请假，是规则性问题）、续费方式、考级/比赛参与、转课
- 老生服务：升班建议、学习进度、调整时段

【绝对不算（必须忽略）】
- 接送通知（"我在楼下"、"让孩子下来"）
- 单次请假/缺课通知
- 纯事务回复（"好"、"嗯"、"收到"、"知道了"）
- 作业/打卡/临时通知
- 闲聊、寒暄、节日祝福

【关于 agent_reply 的要求】
- 保留客服原话的措辞风格
- 把多条客服消息合并成一段连贯回复
- 去掉客户姓名、孩子名字等隐私信息，改为"孩子"/"家长"

如果整段聊天没有符合条件的业务咨询，调用 extract_qas 传空数组。
"""

EXTRACT_TOOL = {
    "type": "function",
    "function": {
        "name": "extract_qas",
        "description": "提交从一段聊天里抽取的业务咨询问答对",
        "parameters": {
            "type": "object",
            "required": ["qas"],
            "properties": {
                "qas": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "required": [
                            "scene", "business_line",
                            "customer_question", "agent_reply",
                            "risk_note", "confidence",
                        ],
                        "properties": {
                            "scene": {
                                "type": "string",
                                "enum": SCENE_TAGS,
                                "description": "从固定列表中选最匹配的场景标签",
                            },
                            "business_line": {
                                "type": "string",
                                "enum": ["文化课", "书画", "棋类", "其他"],
                            },
                            "customer_question": {"type": "string"},
                            "agent_reply": {"type": "string"},
                            "risk_note": {
                                "type": "string",
                                "description": "敏感场景（退费/投诉/价格/效果保证）的注意事项，否则空字符串",
                            },
                            "confidence": {"type": "number"},
                        },
                    },
                }
            },
        },
    },
}


def format_dialog(dialog: list[dict]) -> str:
    lines = []
    for m in dialog:
        role = "客服" if m["role"] == "agent" else "客户"
        lines.append(f"[{m['time']}] {role}: {m['text']}")
    return "\n".join(lines)


def extract_qas(dialog: list[dict]) -> list[dict[str, Any]]:
    """调用 qwen-turbo 抽取问答对。失败返回空列表。"""
    if not dialog:
        return []
    user_content = format_dialog(dialog)
    try:
        resp = _client.chat.completions.create(
            model=EXTRACT_MODEL,
            temperature=0.2,
            max_tokens=2048,
            tools=[EXTRACT_TOOL],
            tool_choice={"type": "function", "function": {"name": "extract_qas"}},
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": user_content},
            ],
        )
    except Exception as e:
        print(f"  ! API 错误: {e}")
        return []

    msg = resp.choices[0].message
    if not msg.tool_calls:
        return []
    args = msg.tool_calls[0].function.arguments
    try:
        parsed = json.loads(args)
    except json.JSONDecodeError:
        return []
    return parsed.get("qas") or []
