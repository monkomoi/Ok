#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AI API Checker - kiểm tra API key, endpoint và model AI.

Python 3.9+, chỉ dùng thư viện chuẩn (không cần pip install).
Chạy: python ai_api_checker.py

Hỗ trợ:
- OpenAI-compatible: OpenAI, OpenRouter, Groq, Together, Fireworks, DeepSeek,
  Mistral, xAI, Cerebras, Perplexity, DeepInfra, DashScope/Qwen, SiliconFlow,
  Z.ai, MiniMax, Cohere-compatible, Hugging Face Router, Ollama, LM Studio,
  vLLM và endpoint tương thích khác.
- Anthropic Messages API.
- Google Gemini REST API.
- Endpoint tùy chỉnh.

Lưu ý: tool gửi một prompt ngắn để xác nhận model thực sự tạo được phản hồi.
Việc gọi API có thể tiêu tốn quota/chi phí. Không kiểm thử hàng loạt model nếu
chưa hiểu giới hạn giá và rate limit của nhà cung cấp.
"""

from __future__ import annotations

import argparse
import getpass
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

VERSION = "1.1.0"
DEFAULT_PROMPT = "Reply with exactly: API_OK"


@dataclass(frozen=True)
class Provider:
    name: str
    base_url: str
    env_key: str = ""
    protocol: str = "openai"
    note: str = ""


# Các preset chỉ là điểm bắt đầu. Provider có thể đổi endpoint/model theo thời gian.
PROVIDERS: Dict[str, Provider] = {
    "openai": Provider("OpenAI", "https://api.openai.com/v1", "OPENAI_API_KEY"),
    "openrouter": Provider("OpenRouter", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY"),
    "groq": Provider("Groq", "https://api.groq.com/openai/v1", "GROQ_API_KEY"),
    "together": Provider("Together AI", "https://api.together.xyz/v1", "TOGETHER_API_KEY"),
    "fireworks": Provider("Fireworks AI", "https://api.fireworks.ai/inference/v1", "FIREWORKS_API_KEY"),
    "deepseek": Provider("DeepSeek", "https://api.deepseek.com/v1", "DEEPSEEK_API_KEY"),
    "mistral": Provider("Mistral AI", "https://api.mistral.ai/v1", "MISTRAL_API_KEY"),
    "xai": Provider("xAI / Grok", "https://api.x.ai/v1", "XAI_API_KEY"),
    "cerebras": Provider("Cerebras", "https://api.cerebras.ai/v1", "CEREBRAS_API_KEY"),
    "perplexity": Provider("Perplexity", "https://api.perplexity.ai", "PERPLEXITY_API_KEY"),
    "deepinfra": Provider("DeepInfra", "https://api.deepinfra.com/v1/openai", "DEEPINFRA_API_KEY"),
    "dashscope": Provider("Alibaba DashScope / Qwen", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", "DASHSCOPE_API_KEY"),
    "siliconflow": Provider("SiliconFlow", "https://api.siliconflow.com/v1", "SILICONFLOW_API_KEY"),
    "zai": Provider("Z.ai / GLM", "https://api.z.ai/api/paas/v4", "ZAI_API_KEY"),
    "minimax": Provider("MiniMax", "https://api.minimax.io/v1", "MINIMAX_API_KEY"),
    "cohere": Provider("Cohere (Compatibility API)", "https://api.cohere.ai/compatibility/v1", "COHERE_API_KEY"),
    "huggingface": Provider("Hugging Face Router", "https://router.huggingface.co/v1", "HF_TOKEN"),
    "anthropic": Provider("Anthropic / Claude", "https://api.anthropic.com", "ANTHROPIC_API_KEY", "anthropic"),
    "gemini": Provider("Google Gemini", "https://generativelanguage.googleapis.com", "GEMINI_API_KEY", "gemini"),
    "ollama": Provider("Ollama (local)", "http://localhost:11434/v1", "", "openai", "Thường không cần API key"),
    "lmstudio": Provider("LM Studio (local)", "http://localhost:1234/v1", "", "openai", "Bật Local Server trong LM Studio trước"),
    "vllm": Provider("vLLM (local/custom)", "http://localhost:8000/v1", "", "openai", "Cần server vLLM đang chạy"),
}


def print_banner() -> None:
    print("\n" + "=" * 68)
    print("  AI API CHECKER  |  v" + VERSION)
    print("  Kiểm tra endpoint • API key • model • tốc độ phản hồi")
    print("=" * 68)


def clean_base_url(url: str) -> str:
    url = url.strip().rstrip("/")
    if not re.match(r"^https?://", url, re.IGNORECASE):
        raise ValueError("Base URL phải bắt đầu bằng http:// hoặc https://")
    return url


def request_json(
    method: str,
    url: str,
    headers: Optional[Dict[str, str]] = None,
    payload: Optional[Dict[str, Any]] = None,
    timeout: int = 30,
) -> Tuple[int, Dict[str, Any], float]:
    data = None
    req_headers = {"Accept": "application/json", "User-Agent": f"AI-API-Checker/{VERSION}"}
    if headers:
        req_headers.update(headers)
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req_headers.setdefault("Content-Type", "application/json")

    req = urllib.request.Request(url, data=data, headers=req_headers, method=method.upper())
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode("utf-8", errors="replace")
            elapsed = time.perf_counter() - started
            try:
                parsed = json.loads(raw) if raw else {}
            except json.JSONDecodeError:
                parsed = {"_raw": raw[:4000]}
            return response.status, parsed if isinstance(parsed, dict) else {"_data": parsed}, elapsed
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        elapsed = time.perf_counter() - started
        try:
            parsed = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            parsed = {"_raw": raw[:4000]}
        return exc.code, parsed if isinstance(parsed, dict) else {"_data": parsed}, elapsed


def error_message(data: Dict[str, Any]) -> str:
    err = data.get("error", data)
    if isinstance(err, dict):
        for key in ("message", "detail", "type", "code"):
            value = err.get(key)
            if value:
                return str(value)[:700]
    if isinstance(err, str):
        return err[:700]
    return str(data.get("_raw") or data)[:700]


def explain_http_error(status: int, message: str) -> str:
    low = message.lower()
    if status == 401:
        return "API key sai, hết hạn hoặc chưa được gửi đúng cách."
    if status == 403:
        return "Bị từ chối quyền: kiểm tra quyền API, billing, khu vực hoặc giới hạn tài khoản."
    if status == 404:
        return "Không tìm thấy endpoint/model. Kiểm tra Base URL, đường dẫn API và model ID."
    if status == 429:
        if any(x in low for x in ("quota", "billing", "insufficient_quota", "credit")):
            return "Hết quota/tín dụng hoặc chưa bật thanh toán."
        return "Đang bị rate limit. Giảm tần suất hoặc kiểm tra giới hạn RPM/TPM."
    if status == 400:
        return "Request không hợp lệ: thường do model ID hoặc tham số không được hỗ trợ."
    if status in (408, 504):
        return "Request timeout. Kiểm tra mạng hoặc tăng timeout."
    if status >= 500:
        return "Lỗi phía nhà cung cấp/server. Thử lại sau hoặc kiểm tra trạng thái dịch vụ."
    return "Request thất bại. Xem thông báo lỗi chi tiết bên dưới."


def auth_headers(protocol: str, api_key: str) -> Dict[str, str]:
    if not api_key:
        return {}
    if protocol == "anthropic":
        return {"x-api-key": api_key, "anthropic-version": "2023-06-01"}
    if protocol == "gemini":
        return {"x-goog-api-key": api_key}
    return {"Authorization": "Bearer " + api_key}


def list_models(protocol: str, base_url: str, api_key: str, timeout: int) -> Tuple[List[str], str]:
    headers = auth_headers(protocol, api_key)
    try:
        if protocol == "gemini":
            url = base_url.rstrip("/") + "/v1beta/models"
        else:
            url = base_url.rstrip("/") + "/v1/models" if protocol == "anthropic" else base_url.rstrip("/") + "/models"
        status, data, _ = request_json("GET", url, headers=headers, timeout=timeout)
        if status < 200 or status >= 300:
            return [], f"HTTP {status}: {error_message(data)}"

        raw_items = data.get("data") or data.get("models") or []
        model_ids: List[str] = []
        for item in raw_items:
            if isinstance(item, str):
                model_id = item
            elif isinstance(item, dict):
                model_id = item.get("id") or item.get("name") or item.get("model")
                if protocol == "gemini":
                    methods = item.get("supportedGenerationMethods", [])
                    if methods and "generateContent" not in methods:
                        continue
            else:
                model_id = None
            if model_id:
                model_ids.append(str(model_id))
        model_ids = sorted(set(model_ids), key=str.lower)
        if not model_ids:
            return [], "API list-models trả về thành công nhưng không có model khả dụng."
        return model_ids, ""
    except Exception as exc:
        return [], f"Không lấy được danh sách model: {type(exc).__name__}: {exc}"


def extract_text(protocol: str, data: Dict[str, Any]) -> str:
    if protocol == "anthropic":
        content = data.get("content", [])
        if isinstance(content, list):
            return "".join(str(part.get("text", "")) for part in content if isinstance(part, dict))
    elif protocol == "gemini":
        candidates = data.get("candidates", [])
        if candidates:
            parts = candidates[0].get("content", {}).get("parts", [])
            return "".join(str(part.get("text", "")) for part in parts if isinstance(part, dict))
    else:
        choices = data.get("choices", [])
        if choices:
            choice = choices[0]
            message = choice.get("message", {})
            content = message.get("content", "")
            if isinstance(content, list):
                return "".join(str(x.get("text", "")) for x in content if isinstance(x, dict))
            return str(content or choice.get("text", ""))
    return ""


def usage_info(protocol: str, data: Dict[str, Any]) -> str:
    usage = data.get("usage", {})
    if protocol == "gemini":
        usage = data.get("usageMetadata", usage)
        inp = usage.get("promptTokenCount") if isinstance(usage, dict) else None
        out = usage.get("candidatesTokenCount") if isinstance(usage, dict) else None
    elif isinstance(usage, dict):
        inp = usage.get("input_tokens") or usage.get("prompt_tokens")
        out = usage.get("output_tokens") or usage.get("completion_tokens")
    else:
        return ""
    if inp is None and out is None:
        return ""
    return f" | token vào: {inp if inp is not None else '?'} | token ra: {out if out is not None else '?'}"


def chat_request(
    protocol: str,
    base_url: str,
    api_key: str,
    model: str,
    messages: List[Dict[str, str]],
    max_tokens: int,
    timeout: int,
) -> Dict[str, Any]:
    """Gửi một lượt chat với lịch sử hội thoại theo định dạng chuẩn nội bộ."""
    headers = auth_headers(protocol, api_key)
    model = model.strip()

    if protocol == "gemini":
        model_path = model.removeprefix("models/")
        url = base_url.rstrip("/") + "/v1beta/models/" + urllib.parse.quote(model_path, safe=".-_:") + ":generateContent"
        system_parts = [m["content"] for m in messages if m.get("role") == "system" and m.get("content")]
        contents = []
        for item in messages:
            role = item.get("role")
            if role == "system":
                continue
            if role not in ("user", "assistant"):
                continue
            contents.append({
                "role": "model" if role == "assistant" else "user",
                "parts": [{"text": item.get("content", "")}],
            })
        payload: Dict[str, Any] = {
            "contents": contents,
            "generationConfig": {"maxOutputTokens": max_tokens},
        }
        if system_parts:
            payload["systemInstruction"] = {"parts": [{"text": "\n\n".join(system_parts)}]}
    elif protocol == "anthropic":
        url = base_url.rstrip("/") + "/v1/messages"
        system_parts = [m["content"] for m in messages if m.get("role") == "system" and m.get("content")]
        chat_messages = [
            {"role": m["role"], "content": m.get("content", "")}
            for m in messages if m.get("role") in ("user", "assistant")
        ]
        payload = {"model": model, "max_tokens": max_tokens, "messages": chat_messages}
        if system_parts:
            payload["system"] = "\n\n".join(system_parts)
    else:
        url = base_url.rstrip("/") + "/chat/completions"
        payload = {
            "model": model,
            "messages": [
                {"role": m.get("role", "user"), "content": m.get("content", "")}
                for m in messages if m.get("role") in ("system", "user", "assistant")
            ],
            "max_tokens": max_tokens,
        }

    try:
        status, data, elapsed = request_json("POST", url, headers=headers, payload=payload, timeout=timeout)
        # Một số model OpenAI-compatible mới chỉ chấp nhận max_completion_tokens.
        if protocol == "openai" and status == 400 and "max_tokens" in error_message(data).lower():
            payload.pop("max_tokens", None)
            payload["max_completion_tokens"] = max_tokens
            status, data, elapsed = request_json("POST", url, headers=headers, payload=payload, timeout=timeout)
        text = extract_text(protocol, data)
        return {
            "ok": 200 <= status < 300 and bool(text.strip()),
            "status": status,
            "elapsed": elapsed,
            "text": text.strip(),
            "usage": usage_info(protocol, data),
            "error": error_message(data) if status < 200 or status >= 300 else ("API trả về rỗng/không có text" if not text.strip() else ""),
            "raw": data,
        }
    except Exception as exc:
        return {
            "ok": False,
            "status": 0,
            "elapsed": 0.0,
            "text": "",
            "usage": "",
            "error": f"{type(exc).__name__}: {exc}",
            "raw": {},
        }


def chat_test(
    protocol: str,
    base_url: str,
    api_key: str,
    model: str,
    prompt: str,
    max_tokens: int,
    timeout: int,
) -> Dict[str, Any]:
    """Kiểm tra nhanh một model bằng một lượt prompt."""
    return chat_request(
        protocol, base_url, api_key, model,
        [{"role": "user", "content": prompt}], max_tokens, timeout,
    )


def chat_loop(
    provider_key: str,
    protocol: str,
    base_url: str,
    api_key: str,
    initial_model: str,
    max_tokens: int,
    timeout: int,
    initial_system: str = "",
) -> None:
    """Chat nhiều lượt trong terminal; API key không được ghi vào lịch sử."""
    model = initial_model
    history: List[Dict[str, str]] = []
    system_prompt = initial_system.strip()
    total_turns = 0

    print("\n" + "=" * 68)
    print("  CHAT SESSION")
    print(f"  Provider: {provider_key} | Model: {model} | Protocol: {protocol}")
    print("  Chat không streaming: phản hồi sẽ hiện khi model tạo xong.")
    print("  Gõ /help để xem lệnh, /exit để kết thúc.")
    print("=" * 68)

    while True:
        try:
            user_text = input("\nBạn: ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nĐã kết thúc phiên chat.")
            break

        if not user_text:
            continue
        if user_text.lower() in ("/exit", "/quit", "/q"):
            print("Đã kết thúc phiên chat.")
            break
        if user_text.lower() == "/help":
            print("""Lệnh chat:
  /help                 Hiện hướng dẫn
  /exit                 Thoát phiên chat
  /reset                Xóa lịch sử hội thoại
  /model MODEL_ID       Đổi model trong cùng provider
  /system TEXT          Đặt system prompt
  /system off           Xóa system prompt
  /save [ten_file.json] Lưu hội thoại (không lưu API key)

Mọi nội dung khác được gửi tới model như tin nhắn bình thường.""")
            continue
        if user_text.lower() == "/reset":
            history.clear()
            total_turns = 0
            print("Đã xóa lịch sử hội thoại.")
            continue
        if user_text.lower().startswith("/model "):
            new_model = user_text[7:].strip()
            if new_model:
                model = new_model
                history.clear()
                print(f"Đã chuyển sang model: {model}. Lịch sử cũ được xóa để tránh trộn ngữ cảnh giữa model.")
            else:
                print("Cú pháp: /model MODEL_ID")
            continue
        if user_text.lower() == "/system off":
            system_prompt = ""
            print("Đã tắt system prompt.")
            continue
        if user_text.lower().startswith("/system"):
            new_system = user_text[len("/system"):].strip()
            if not new_system:
                try:
                    new_system = input("System prompt mới: ").strip()
                except (EOFError, KeyboardInterrupt):
                    print("\nĐã hủy.")
                    continue
            system_prompt = new_system
            print("Đã cập nhật system prompt. Lịch sử chat được giữ nguyên.")
            continue
        if user_text.lower().startswith("/save"):
            parts = user_text.split(maxsplit=1)
            filename = parts[1].strip() if len(parts) > 1 else "ai_chat_history.json"
            if not filename.lower().endswith(".json"):
                filename += ".json"
            save_messages = ([{"role": "system", "content": system_prompt}] if system_prompt else []) + history
            transcript = {
                "provider": provider_key,
                "protocol": protocol,
                "base_url": base_url,
                "model": model,
                "saved_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                "messages": save_messages,
            }
            try:
                with open(filename, "w", encoding="utf-8") as file:
                    json.dump(transcript, file, ensure_ascii=False, indent=2)
                print(f"Đã lưu hội thoại vào: {os.path.abspath(filename)}")
            except OSError as exc:
                print(f"Không thể lưu file: {exc}")
            continue

        request_messages: List[Dict[str, str]] = []
        if system_prompt:
            request_messages.append({"role": "system", "content": system_prompt})
        request_messages.extend(history)
        request_messages.append({"role": "user", "content": user_text})

        print("AI: đang suy nghĩ...", end="", flush=True)
        result = chat_request(protocol, base_url, api_key, model, request_messages, max_tokens, timeout)
        print("\r" + " " * 32 + "\r", end="")

        if result["ok"]:
            print("AI: " + result["text"])
            print(f"\n[{result['elapsed']:.2f}s{result['usage']}]" )
            history.append({"role": "user", "content": user_text})
            history.append({"role": "assistant", "content": result["text"]})
            total_turns += 1
            # Giới hạn lịch sử gửi lên để tránh request phình vô hạn.
            if len(history) > 40:
                history = history[-40:]
                print("[Đã rút gọn ngữ cảnh: chỉ giữ 20 lượt gần nhất.]" )
        else:
            print(f"[API ERROR: HTTP {result['status'] or 'NETWORK'}]")
            print(explain_http_error(result["status"], result["error"]))
            print(result["error"][:1000])
            print("Tin nhắn lỗi không được thêm vào lịch sử, bạn có thể thử lại.")

    print(f"\nKết thúc: {total_turns} lượt chat thành công | Model cuối: {model}")


def choose_provider_interactive() -> Tuple[str, Provider]:
    keys = list(PROVIDERS.keys())
    print("\nNhà cung cấp có preset:")
    for i, key in enumerate(keys, 1):
        p = PROVIDERS[key]
        print(f"  {i:2}. {p.name:<30} [{key}]")
    print(f"  {len(keys) + 1:2}. Tự nhập endpoint / provider khác")
    while True:
        choice = input("\nChọn số: ").strip()
        if choice.isdigit() and 1 <= int(choice) <= len(keys):
            key = keys[int(choice) - 1]
            return key, PROVIDERS[key]
        if choice == str(len(keys) + 1):
            return "custom", Provider("Custom", "", "", "openai")
        print("Lựa chọn không hợp lệ.")


def interactive_config(args: argparse.Namespace) -> Tuple[str, str, str, str, str]:
    if args.provider and args.provider in PROVIDERS:
        key = args.provider
        provider = PROVIDERS[key]
    elif args.provider == "custom":
        key, provider = "custom", Provider("Custom", "", "", args.protocol or "openai")
    else:
        key, provider = choose_provider_interactive()

    protocol = args.protocol or provider.protocol
    base_url = args.base_url or provider.base_url
    if key == "custom" or not base_url:
        if not args.non_interactive:
            print("\nGiao thức API:")
            print("  1. OpenAI-compatible (phổ biến nhất)")
            print("  2. Anthropic Messages API")
            print("  3. Google Gemini API")
            if not args.protocol:
                pick = input("Chọn [1]: ").strip() or "1"
                protocol = {"1": "openai", "2": "anthropic", "3": "gemini"}.get(pick, "openai")
            if not base_url:
                base_url = input("Base URL (ví dụ https://api.example.com/v1): ").strip()
        elif not base_url or not args.protocol:
            raise ValueError("Chế độ non-interactive với custom cần --base-url và --protocol.")
    else:
        print(f"\nProvider: {provider.name}")
        print(f"Base URL: {base_url}")
        if args.base_url:
            print("(Đang dùng Base URL tùy chỉnh)")
        if not args.non_interactive:
            change = input("Đổi Base URL? [y/N]: ").strip().lower()
            if change == "y":
                base_url = input("Base URL mới: ").strip()

    base_url = clean_base_url(base_url)
    env_name = provider.env_key
    api_key = args.api_key or (os.getenv(env_name, "") if env_name else "")
    if not api_key and not args.no_auth and env_name:
        if not args.non_interactive:
            print(f"Chưa tìm thấy biến môi trường {env_name}.")
            api_key = getpass.getpass("Nhập API key (nội dung sẽ được ẩn): ").strip()
        else:
            raise ValueError(f"Thiếu API key. Hãy đặt biến môi trường {env_name}.")
    if args.no_auth:
        api_key = ""

    model = args.model.strip() if args.model else ""
    if not model and args.interactive_model:
        model = input("Model ID (để trống nếu muốn chọn từ danh sách): ").strip()
    return key, protocol, base_url, api_key, model


def print_model_list(models: List[str], limit: int = 120) -> None:
    print(f"\nTìm thấy {len(models)} model:")
    for i, model in enumerate(models[:limit], 1):
        print(f"  {i:3}. {model}")
    if len(models) > limit:
        print(f"  ... còn {len(models) - limit} model chưa hiển thị.")


def choose_model(models: List[str]) -> str:
    if not models:
        return input("Nhập Model ID thủ công: ").strip()
    print_model_list(models)
    while True:
        choice = input("\nNhập số model hoặc dán Model ID: ").strip()
        if choice.isdigit() and 1 <= int(choice) <= len(models):
            return models[int(choice) - 1]
        if choice:
            return choice
        print("Chưa chọn model.")


def run_checks(args: argparse.Namespace) -> int:
    try:
        provider_key, protocol, base_url, api_key, selected_model = interactive_config(args)
    except (ValueError, KeyboardInterrupt) as exc:
        print(f"\nDừng: {exc}")
        return 2

    if protocol not in ("openai", "anthropic", "gemini"):
        print("Protocol không hợp lệ. Chọn openai, anthropic hoặc gemini.")
        return 2
    print(f"\nProtocol: {protocol} | Endpoint: {base_url}")
    print("API key: " + ("đã nhập (được ẩn)" if api_key else "không dùng key"))

    print("\n[1/3] Kiểm tra kết nối và lấy danh sách model...")
    models, model_error = list_models(protocol, base_url, api_key, args.timeout)
    if models:
        print(f"[OK] Lấy được {len(models)} model.")
    else:
        print(f"[!] Không lấy được model list: {model_error}")
        print("    Một số API không cung cấp endpoint /models; vẫn có thể test bằng Model ID thủ công.")

    if args.list_only:
        if models:
            print_model_list(models, args.display_limit)
        return 0 if models else 1

    if args.test_all:
        if not models:
            print("Không có model list, không thể test-all. Hãy chỉ định --model.")
            return 1
        cap = max(1, args.max_models)
        test_models = models[:cap]
        print(f"\nSắp gửi request tới {len(test_models)} model. Mỗi request có thể tiêu tốn quota/chi phí.")
        if len(models) > cap:
            print(f"Đang giới hạn {cap}/{len(models)} model đầu tiên. Dùng --max-models để đổi giới hạn.")
        if not args.yes:
            confirm = input("Tiếp tục? Gõ YES để xác nhận: ").strip()
            if confirm != "YES":
                print("Đã hủy test hàng loạt.")
                return 0
    else:
        if selected_model:
            test_models = [selected_model]
        elif args.model:
            test_models = [args.model]
        elif models:
            if args.non_interactive:
                print("Thiếu --model. Dùng --list-only hoặc chỉ định --model.")
                return 2
            test_models = [choose_model(models)]
        else:
            if args.non_interactive:
                print("Không có model list. Cần chỉ định --model MODEL_ID.")
                return 2
            manual = input("Nhập Model ID cần test: ").strip()
            if not manual:
                print("Chưa có Model ID.")
                return 2
            test_models = [manual]

    print("\n[2/3] Gửi prompt kiểm tra model...")
    results = []
    for index, model in enumerate(test_models, 1):
        print(f"\n--- [{index}/{len(test_models)}] {model} ---")
        result = chat_test(protocol, base_url, api_key, model, args.prompt, args.max_tokens, args.timeout)
        results.append((model, result))
        if result["ok"]:
            print(f"[PASS] HTTP {result['status']} | {result['elapsed']:.2f}s{result['usage']}")
            print("Phản hồi: " + result["text"].replace("\n", " ")[:500])
        else:
            print(f"[FAIL] HTTP {result['status'] or 'NETWORK'} | {result['elapsed']:.2f}s")
            print("Nguyên nhân: " + explain_http_error(result["status"], result["error"]))
            print("Chi tiết: " + result["error"][:700])

    passed = sum(1 for _, result in results if result["ok"])
    print("\n[3/3] TỔNG KẾT")
    print(f"Provider: {provider_key} | {passed}/{len(results)} model phản hồi thành công")
    for model, result in results:
        state = "PASS" if result["ok"] else "FAIL"
        print(f"  {state:<4} | {result['elapsed']:>6.2f}s | {model}")

    if args.json_out:
        report = {
            "tool_version": VERSION,
            "provider": provider_key,
            "protocol": protocol,
            "base_url": base_url,
            "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
            "results": [
                {
                    "model": model,
                    "ok": result["ok"],
                    "http_status": result["status"],
                    "latency_seconds": round(result["elapsed"], 3),
                    "response": result["text"],
                    "usage": result["usage"],
                    "error": result["error"],
                }
                for model, result in results
            ],
        }
        with open(args.json_out, "w", encoding="utf-8") as file:
            json.dump(report, file, ensure_ascii=False, indent=2)
        print(f"\nĐã lưu báo cáo: {args.json_out}")

    all_passed = passed == len(results) and passed > 0
    if all_passed and len(results) == 1 and not args.non_interactive:
        should_chat = args.chat
        if not should_chat:
            try:
                answer = input("\nModel đã hoạt động. Chat thử ngay? [Y/n]: ").strip().lower()
                should_chat = answer not in ("n", "no", "khong", "không")
            except (EOFError, KeyboardInterrupt):
                should_chat = False
        if should_chat:
            chat_loop(
                provider_key, protocol, base_url, api_key, results[0][0],
                args.chat_max_tokens, args.timeout, args.system,
            )

    return 0 if all_passed else 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Kiểm tra API AI đa nhà cung cấp và model (không cần cài thư viện ngoài).",
        formatter_class=argparse.RawTextHelpFormatter,
        epilog=(
            "Ví dụ:\n"
            "  python ai_api_checker.py\n"
            "  python ai_api_checker.py --provider groq --model MODEL_ID\n"
            "  python ai_api_checker.py --provider openrouter --list-only\n"
            "  python ai_api_checker.py --provider custom --protocol openai --base-url https://host/v1 --model MODEL_ID\n"
            "  python ai_api_checker.py --provider anthropic --model claude-model-id\n"
            "  python ai_api_checker.py --provider gemini --model gemini-model-id\n"
            "  python ai_api_checker.py --provider openrouter --model MODEL_ID --chat\n"
            "  python ai_api_checker.py --provider openrouter --model MODEL_ID --chat --system \"You are a helpful assistant\"\n"
            "  python ai_api_checker.py --provider openrouter --test-all --max-models 10\n\n"
            "API key: ưu tiên đặt trong biến môi trường tương ứng; nếu không, tool sẽ hỏi ẩn key."
        ),
    )
    parser.add_argument("--provider", help="Tên preset: " + ", ".join(PROVIDERS) + ", hoặc custom")
    parser.add_argument("--protocol", choices=("openai", "anthropic", "gemini"), help="Giao thức API")
    parser.add_argument("--base-url", help="Base URL tùy chỉnh")
    parser.add_argument("--api-key", help="API key (không khuyến nghị vì có thể lộ trong lịch sử lệnh)")
    parser.add_argument("--no-auth", action="store_true", help="Không gửi API key, phù hợp server local")
    parser.add_argument("--model", help="Model ID cần test")
    parser.add_argument("--list-only", action="store_true", help="Chỉ lấy và in danh sách model")
    parser.add_argument("--test-all", action="store_true", help="Test lần lượt các model lấy được")
    parser.add_argument("--max-models", type=int, default=10, help="Giới hạn model khi --test-all (mặc định: 10)")
    parser.add_argument("--yes", action="store_true", help="Bỏ xác nhận trước khi test hàng loạt")
    parser.add_argument("--prompt", default=DEFAULT_PROMPT, help="Prompt test")
    parser.add_argument("--max-tokens", type=int, default=32, help="Giới hạn token cho lượt test nhanh (mặc định: 32)")
    parser.add_argument("--chat-max-tokens", type=int, default=1024, help="Giới hạn token cho mỗi lượt chat (mặc định: 1024)")
    parser.add_argument("--timeout", type=int, default=60, help="Timeout mỗi request, giây (mặc định: 60)")
    parser.add_argument("--display-limit", type=int, default=120, help="Số model hiển thị tối đa")
    parser.add_argument("--json-out", help="Lưu báo cáo kết quả ra file JSON")
    parser.add_argument("--chat", action="store_true", help="Vào chế độ chat ngay sau khi test model thành công")
    parser.add_argument("--system", default="", help="System prompt ban đầu cho phiên chat")
    parser.add_argument("--non-interactive", action="store_true", help="Không hỏi chọn model, dùng trong script")
    parser.add_argument("--interactive-model", action="store_true", help=argparse.SUPPRESS)
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    print_banner()
    if args.timeout < 1 or args.max_tokens < 1 or args.chat_max_tokens < 1:
        print("timeout, max-tokens và chat-max-tokens phải lớn hơn 0.")
        return 2
    if args.provider and args.provider not in PROVIDERS and args.provider != "custom":
        print("Provider không có preset. Dùng --provider custom và nhập --base-url/--protocol.")
        print("Preset hiện có: " + ", ".join(PROVIDERS))
        return 2
    if args.non_interactive and not args.provider:
        print("Chế độ --non-interactive yêu cầu --provider.")
        return 2
    if args.non_interactive and args.chat:
        print("--chat cần chế độ tương tác với terminal; không dùng cùng --non-interactive.")
        return 2
    try:
        return run_checks(args)
    except KeyboardInterrupt:
        print("\nĐã dừng theo yêu cầu.")
        return 130


if __name__ == "__main__":
    sys.exit(main())
