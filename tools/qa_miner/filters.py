"""规则过滤：丢弃事务性消息与无价值会话，把候选会话压缩到值得喂给 LLM 的子集。"""
import json
import re
from pathlib import Path
from typing import Iterable

from config import AGENT_NAME_KEYWORDS

# 客户消息的"事务性"模式（家长接送/请假/打卡）。命中即视为非业务咨询。
TRANSACTIONAL_PATTERNS = [
    r"(楼下|门口|外面).{0,5}(等|到了)",
    r"(接|送).{0,3}(走|下来|出来|回家|过来)",
    r"(请假|有事|来不了|不能来|去不了|生病|发烧|感冒|肚子痛)",
    r"(作业|签字|打卡|卷子|默写)",
    r"(上来了|下来了|出来了|到了|收到了?|收到\.)",
    r"^(好|好的|嗯+|哦+|谢谢|多谢|辛苦了?|麻烦了?|ok|OK)[，。！\.~。!]*$",
    r"^(收到|了解|知道了?|明白)[，。！\.~。!]*$",
]
TRANSACTIONAL_RE = re.compile("|".join(TRANSACTIONAL_PATTERNS))

# 业务咨询的强信号关键词。命中其一即视为高价值候选。
INQUIRY_KEYWORDS = [
    # 价格 / 报名
    "多少钱", "价格", "学费", "收费", "报名", "缴费", "续费", "优惠", "便宜",
    # 课程 / 师资 / 形式
    "课程", "什么课", "教什么", "老师", "师资", "教练", "班型", "几个人", "一对一", "小班",
    # 试听 / 体验
    "试听", "体验", "感受", "可以先",
    # 年龄 / 适配
    "几岁", "多大", "年龄", "零基础", "没基础", "没学过",
    # 时间 / 地址
    "上课时间", "几点上", "周末", "节假日", "什么时候上", "在哪", "地址", "怎么过来",
    # 异议 / 退费 / 投诉
    "退费", "退款", "不想学", "不学了", "投诉", "不满意", "意见",
    # 效果 / 考级
    "考级", "比赛", "效果", "能学到", "学得到",
    # 请假补课规则（区别于单次请假）
    "请假能补", "能补课吗", "补课规则", "补课政策",
]
INQUIRY_RE = re.compile("|".join(re.escape(k) for k in INQUIRY_KEYWORDS))

MIN_CUSTOMER_MSG_LEN = 8  # 客户消息字数下限
MIN_INQUIRY_HITS = 1       # 整个会话至少要有 N 条客户消息命中咨询关键词


def is_agent_name(name: str) -> bool:
    return any(k in name for k in AGENT_NAME_KEYWORDS)


def load_session(path: Path) -> dict | None:
    try:
        with path.open("r", encoding="utf-8") as f:
            return json.load(f)
    except (json.JSONDecodeError, OSError):
        return None


def extract_dialog(session: dict) -> list[dict]:
    """返回标准化的消息列表：[{role: 'agent'|'customer', text, time}]，仅文本类型。"""
    messages = session.get("messages") or []
    out: list[dict] = []
    for m in messages:
        if m.get("typeName") != "文本":
            continue
        raw = m.get("content") or ""
        text = raw.strip() if isinstance(raw, str) else ""
        if not text:
            continue
        sender_name = m.get("senderName") or ""
        role = "agent" if is_agent_name(sender_name) else "customer"
        out.append({
            "role": role,
            "text": text,
            "time": m.get("timeFormatted") or "",
        })
    return out


def session_has_inquiry(dialog: list[dict]) -> bool:
    """会话中是否存在足够多的客户咨询信号。"""
    inquiry_hits = 0
    qualified_customer_msgs = 0
    for m in dialog:
        if m["role"] != "customer":
            continue
        if len(m["text"]) < MIN_CUSTOMER_MSG_LEN:
            continue
        # 跳过命中事务模式的消息
        if TRANSACTIONAL_RE.search(m["text"]) and not INQUIRY_RE.search(m["text"]):
            continue
        qualified_customer_msgs += 1
        if INQUIRY_RE.search(m["text"]):
            inquiry_hits += 1
    return inquiry_hits >= MIN_INQUIRY_HITS and qualified_customer_msgs >= 1


def filter_sessions(paths: Iterable[Path]) -> list[tuple[Path, list[dict]]]:
    """对一批会话文件做规则过滤，返回 (路径, 标准化对话) 列表。仅保留疑似含业务咨询的。"""
    keep: list[tuple[Path, list[dict]]] = []
    for p in paths:
        session = load_session(p)
        if not session:
            continue
        dialog = extract_dialog(session)
        if not dialog:
            continue
        if not session_has_inquiry(dialog):
            continue
        keep.append((p, dialog))
    return keep
