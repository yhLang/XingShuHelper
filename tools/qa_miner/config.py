"""DashScope 调用配置 + 路径常量。"""
import os
from pathlib import Path

DASHSCOPE_API_KEY = os.environ.get("DASHSCOPE_API_KEY", "")
if not DASHSCOPE_API_KEY:
    raise RuntimeError(
        "DASHSCOPE_API_KEY 未设置。请 `export DASHSCOPE_API_KEY=sk-...` 后再运行。"
    )
DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"

EXTRACT_MODEL = "qwen-turbo"

WECHAT_EXPORT_DIR = Path(os.path.expanduser("~/Desktop/wechat-export"))

ROOT = Path(__file__).resolve().parent
OUTPUT_DIR = ROOT / "output"
OUTPUT_DIR.mkdir(exist_ok=True)

AGENT_NAME_KEYWORDS = ("行恕", "麒麟斋", "行书书院")
