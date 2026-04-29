"""DashScope 调用配置 + 账号 / 路径常量。"""
import os
from pathlib import Path

DASHSCOPE_API_KEY = os.environ.get("DASHSCOPE_API_KEY", "")
if not DASHSCOPE_API_KEY:
    raise RuntimeError(
        "DASHSCOPE_API_KEY 未设置。请 `export DASHSCOPE_API_KEY=sk-...` 后再运行。"
    )
DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"

EXTRACT_MODEL = "qwen-turbo"

ROOT = Path(__file__).resolve().parent
OUTPUT_BASE = ROOT / "output"
OUTPUT_BASE.mkdir(exist_ok=True)

# 客服身份关键词：消息 senderName 命中即视为客服侧。两个账号共用同一组前缀。
AGENT_NAME_KEYWORDS = ("行恕", "麒麟斋", "行书书院")


# 两个微信账号的配置。
# 跑 pipeline 时通过 `--account kirin|xingshu` 切换。
ACCOUNTS: dict[str, dict] = {
    "kirin": {
        "label": "麒麟斋",
        "agent_signature": "行恕书院（麒麟斋）",
        "business_desc": (
            "麒麟斋的业务包括：文化课补习（数学/语文/英语等）、"
            "书法（硬笔/毛笔）、国画、棋类（围棋/象棋）等。"
        ),
        "wechat_export_dir": Path(os.path.expanduser("~/Desktop/wechat-export")),
        "output_subdir": "kirin",
    },
    "xingshu": {
        "label": "行恕书院",
        "agent_signature": "行恕书院（万科校区）",
        "business_desc": (
            "行恕书院的业务包括：书法（硬笔/毛笔）、国画、儿童画等书画类课程，"
            "以及棋类（围棋/象棋）课程。书画为主营业务。"
        ),
        "wechat_export_dir": Path(os.path.expanduser("~/Desktop/xingshu")),
        "output_subdir": "xingshu",
    },
}


def account_paths(account: str) -> dict[str, Path]:
    """返回某个账号的所有路径（目录、缓存、输出）。自动创建必要目录。"""
    if account not in ACCOUNTS:
        raise ValueError(f"未知账号: {account}，可选: {list(ACCOUNTS)}")
    cfg = ACCOUNTS[account]
    out_dir = OUTPUT_BASE / cfg["output_subdir"]
    out_dir.mkdir(parents=True, exist_ok=True)
    cache_dir = out_dir / ".cache"
    cache_dir.mkdir(parents=True, exist_ok=True)
    return {
        "wechat_export_dir": cfg["wechat_export_dir"],
        "out_dir": out_dir,
        "cache_dir": cache_dir,
        "full_jsonl": out_dir / "full_qas.jsonl",
        "skipped_log": out_dir / "full_skipped.txt",
        "embed_cache": cache_dir / "embeddings.pkl",
        "clustered_jsonl": out_dir / "clustered_qas.jsonl",
        "review_md": out_dir / "review.md",
        "rag_texts": out_dir / f"qa_{account}_texts.json",
        "rag_embeddings": out_dir / f"qa_{account}_embeddings.bin",
    }


# === 向后兼容 ===
# 原先的 OUTPUT_DIR 保留指向 OUTPUT_BASE，避免老脚本崩。
# 新代码请用 account_paths(account) 拿到精确路径。
OUTPUT_DIR = OUTPUT_BASE
WECHAT_EXPORT_DIR = ACCOUNTS["kirin"]["wechat_export_dir"]
