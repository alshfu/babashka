#!/usr/bin/env python3
"""
Watch for the BankID confirm screen and fire the UHID login immediately.

Зачем: сессия BankID живёт недолго. Пользователь вручную жмёт «Logga in»
в Swedbank, а этот скрипт каждые ~0.6 с проверяет экран телефона и,
как только появляется кнопка «Identifiera med säkerhetskod», сразу
выполняет run_bankid_login() из uhid-bankid-login.py.

Запуск:
    .venv/bin/python scripts/bankid-watch-and-login.py [--pin <PIN>] [--timeout 120]
"""
import argparse
import importlib.util
import time
from pathlib import Path

CONFIRM_TEXT = "Identifiera med säkerhetskod"
POLL_INTERVAL = 0.6

# Импортируем uhid-bankid-login.py (в имени дефисы — только через importlib).
_spec = importlib.util.spec_from_file_location(
    "uhid_bankid_login",
    Path(__file__).with_name("uhid-bankid-login.py"),
)
_uhid = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_uhid)


def wait_for_confirm_screen(timeout_s: float) -> bool:
    """Ждать, пока на экране появится кнопка подтверждения BankID."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        texts = _uhid.current_texts()
        if any(CONFIRM_TEXT in t for t in texts):
            return True
        time.sleep(POLL_INTERVAL)
    return False


def main() -> None:
    parser = argparse.ArgumentParser(description="Watch BankID confirm screen and fire UHID login")
    parser.add_argument("--pin", default=_uhid.PIN, help="BankID PIN")
    parser.add_argument("--timeout", type=float, default=120, help="Сколько секунд ждать экран подтверждения")
    args = parser.parse_args()

    print(f"waiting for BankID confirm screen ({args.timeout:.0f} s)…")
    if not wait_for_confirm_screen(args.timeout):
        print("confirm screen did not appear — aborting")
        return

    print("confirm screen detected — firing UHID login NOW")
    _uhid.run_bankid_login(args.pin)


if __name__ == "__main__":
    main()
