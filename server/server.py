"""Local development proxy for Alibaba Cloud Model Studio.

Run from the repository root: python server/server.py
Only binds to 127.0.0.1 by default. The Android emulator reaches it at 10.0.2.2.
"""

from __future__ import annotations

import base64
import binascii
import json
import os
import re
from datetime import date
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener, urlopen

ROOT = Path(__file__).resolve().parents[1]
MAX_BODY = 22 * 1024 * 1024
REPORT_TYPES = {"血常规", "生化", "凝血", "其他"}


def load_env() -> None:
    env_file = ROOT / ".env"
    if not env_file.exists():
        return
    for line in env_file.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        os.environ.setdefault(name.strip(), value.strip().strip('"').strip("'"))


def endpoint(path: str) -> str:
    workspace = os.getenv("BAILIAN_WORKSPACE_ID", "").strip()
    if workspace:
        return f"https://{workspace}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/{path}"
    # Alibaba Cloud documents that the existing Beijing domain remains available.
    return f"https://dashscope.aliyuncs.com/compatible-mode/v1/{path}"


def post_model(path: str, payload: dict) -> dict:
    key = os.getenv("APIKEY") or os.getenv("DASHSCOPE_API_KEY")
    if not key:
        raise ValueError("请在 .env 中配置 APIKEY")
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = Request(
        endpoint(path),
        body,
        {"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        opener = build_opener(ProxyHandler({})) if os.getenv("BAILIAN_NO_PROXY") == "1" else None
        with (opener.open(request, timeout=120) if opener else urlopen(request, timeout=120)) as response:
            return json.load(response)
    except HTTPError as error:
        detail = error.read(2048).decode("utf-8", errors="replace")
        raise RuntimeError(f"百炼 HTTP {error.code}: {detail}") from error
    except URLError as error:
        raise RuntimeError(f"无法连接百炼: {error.reason}") from error


PROMPT = """你是检验报告逐字转录助手。读取这一张报告图片，仅返回 JSON 对象。
不要诊断、推断、解释或补充常见正常值。不要提取姓名、病历号等身份信息。
格式：{"report_type":"血常规|生化|凝血|其他", "sample_date":"YYYY-MM-DD 或空字符串", "report_date":"YYYY-MM-DD 或空字符串", "institution":"报告上可见机构或空字符串", "observations":[{"name":"报告项目原文", "value":"结果原文", "unit":"单位原文或空字符串", "reference":"参考范围原文或空字符串", "flag":"报告提示↑、↓或空字符串"}]}。
必须逐行检查表格两栏/多栏，保持项目与结果、范围、单位同行对应。看不清的字段写空字符串，不猜测。报告类型不确定写其他。日期仅使用报告上实际印刷的采样或报告日期。只输出 JSON。"""


def clean_text(value: object, limit: int = 200) -> str:
    if value is None:
        return ""
    return str(value).strip()[:limit]


def normalize_date(value: object) -> str:
    text = clean_text(value, 60)
    match = re.search(r"(\d{4})[-年/.](\d{1,2})[-月/.](\d{1,2})", text)
    if not match:
        return ""
    try:
        return date(int(match[1]), int(match[2]), int(match[3])).isoformat()
    except ValueError:
        return ""


def normalize_report(raw: dict) -> dict:
    if not isinstance(raw, dict):
        raise ValueError("模型结果不是 JSON 对象")
    report_type = clean_text(raw.get("report_type"), 20)
    if report_type not in REPORT_TYPES:
        report_type = "其他"
    rows = raw.get("observations")
    if not isinstance(rows, list):
        raise ValueError("模型未返回项目列表")
    observations = []
    for row in rows[:150]:
        if not isinstance(row, dict):
            continue
        item = {key: clean_text(row.get(key)) for key in ("name", "value", "unit", "reference", "flag")}
        if item["name"]:
            observations.append(item)
    return {
        "report_type": report_type,
        "sample_date": normalize_date(raw.get("sample_date")),
        "report_date": normalize_date(raw.get("report_date")),
        "institution": clean_text(raw.get("institution")),
        "observations": observations,
    }


def suggest_report_type(report: dict) -> dict | None:
    """Use the text-only decision model when visual extraction cannot classify a report."""
    if report["report_type"] != "其他" or not os.getenv("BAILIAN_WORKSPACE_ID"):
        return None
    state = {
        "item_names": [row["name"] for row in report["observations"][:30]],
        "reported_type": report["report_type"],
    }
    questions = {
        "report_type": {
            "type": "choice",
            "instructions": "仅依据项目名称判断这份检验报告属于哪一类；不确定时选择 other。",
            "criteria": {
                "blood_count": "血常规：白细胞、红细胞、血小板等血细胞指标",
                "biochemistry": "生化：肝肾功能、血脂、血糖等生化指标",
                "coagulation": "凝血：凝血酶原时间、纤维蛋白原、D-二聚体等",
                "other": "其他类别或证据不足",
            },
        }
    }
    response = post_model("systemone", {"model": "decision-model-preview", "state": state, "questions": questions})
    answer = response.get("answers", {}).get("report_type", {})
    return {"choice": answer.get("choice", "other"), "confidence": answer.get("confidence", 0)}


def analyze(payload: dict) -> dict:
    image = payload.get("image_base64")
    if not isinstance(image, str) or not image:
        raise ValueError("缺少 image_base64")
    try:
        image_bytes = base64.b64decode(image, validate=True)
    except (ValueError, binascii.Error) as error:
        raise ValueError("图片 Base64 无效") from error
    if not image_bytes or len(image_bytes) > 15 * 1024 * 1024:
        raise ValueError("图片为空或大于 15 MB")
    mime = payload.get("mime_type", "image/jpeg")
    if mime not in ("image/jpeg", "image/png", "image/webp"):
        raise ValueError("暂不支持该图片格式")
    result = post_model(
        "chat/completions",
        {
            "model": "qwen3.7-flash",
            "messages": [
                {"role": "system", "content": PROMPT},
                {"role": "user", "content": [{"type": "image_url", "image_url": {"url": f"data:{mime};base64,{image}"}}, {"type": "text", "text": "请逐项转录这张报告。"}]},
            ],
            "response_format": {"type": "json_object"},
            "enable_thinking": False,
        },
    )
    choices = result.get("choices", [])
    if not choices:
        raise ValueError("模型没有返回识别内容")
    content = choices[0].get("message", {}).get("content", "")
    parsed = json.loads(content)
    report = normalize_report(parsed)
    if not report["observations"]:
        raise ValueError("未识别到任何检验项目，请裁剪或重拍")
    if report["report_type"] == "其他":
        try:
            suggestion = suggest_report_type(report)
            if suggestion and suggestion["confidence"] >= 0.8:
                report["report_type"] = {
                    "blood_count": "血常规", "biochemistry": "生化", "coagulation": "凝血"
                }.get(suggestion["choice"], "其他")
            report["decision_suggestion"] = suggestion
        except Exception:
            # A decision-model outage must not discard the extracted report.
            report["decision_suggestion"] = None
    report["model"] = result.get("model", "qwen3.7-flash")
    return report


def decide(payload: dict) -> dict:
    if not os.getenv("BAILIAN_WORKSPACE_ID"):
        raise ValueError("决策模型需要 BAILIAN_WORKSPACE_ID")
    state = payload.get("state")
    questions = payload.get("questions")
    if state is None or not isinstance(questions, dict) or not questions:
        raise ValueError("缺少 state 或 questions")
    result = post_model("systemone", {"model": "decision-model-preview", "state": state, "questions": questions})
    return {"answers": result.get("answers", {}), "model": result.get("model", "decision-model-preview")}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args: object) -> None:
        # Avoid logging report images or extracted health data.
        print(f"{self.client_address[0]} {self.command} {self.path} {args[1] if len(args) > 1 else ''}")

    def respond(self, status: int, data: dict) -> None:
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        if self.path == "/health":
            self.respond(200, {"ok": True, "qwen_configured": bool(os.getenv("APIKEY") or os.getenv("DASHSCOPE_API_KEY")), "decision_configured": bool(os.getenv("BAILIAN_WORKSPACE_ID"))})
        else:
            self.respond(404, {"error": "未找到接口"})

    def do_POST(self) -> None:
        if self.path not in ("/analyze", "/decide"):
            self.respond(404, {"error": "未找到接口"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= MAX_BODY:
                raise ValueError("请求体过大或为空")
            payload = json.loads(self.rfile.read(length))
            result = analyze(payload) if self.path == "/analyze" else decide(payload)
            self.respond(200, result)
        except (ValueError, json.JSONDecodeError) as error:
            self.respond(400, {"error": str(error)})
        except Exception as error:
            self.respond(502, {"error": str(error)})


if __name__ == "__main__":
    load_env()
    host = os.getenv("SERVER_HOST", "127.0.0.1")
    port = int(os.getenv("SERVER_PORT", "8765"))
    server = ThreadingHTTPServer((host, port), Handler)
    print(f"识别服务运行在 http://{host}:{port}")
    server.serve_forever()
