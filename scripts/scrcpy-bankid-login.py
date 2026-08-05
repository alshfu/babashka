#!/usr/bin/env python3
"""
Swedbank → BankID auto-login через клики по окну scrcpy (SDK-инъекция).

Почему так:
- `adb shell input` BankID молча игнорирует (события идут через виртуальное
  устройство uinput_nav — BankID их фильтрует, см. test_logs/bankid_test.log).
- Сырой UHID (--mouse=uhid) работает только пока курсор Mac над окном —
  длинные перемещения уводят курсор, дельты теряются.
- Клик по окну scrcpy превращается в инъекцию через injectInputEvent в
  системный ввод — BankID принимает её как настоящее касание.

Требования:
- Запущенный scrcpy с видео-окном, например:
    scrcpy -s DQ6TC64DY9PRBE4T --window-x=100 --window-y=100 \
        --window-width=360 --window-height=832 --window-title=pult-video \
        --always-on-top
- pyautogui (в .venv проекта).
- Телефон на adb (USB). Разрешение 720x1600.

Режимы:
  full         — Swedbank на экране входа: жмём «Logga in», ждём BankID, вводим PIN.
  bankid-only  — BankID уже открыт на экране подтверждения: жмём «Identifiera
                 med säkerhetskod», ждём PIN-pad, вводим PIN.

Примеры:
  .venv/bin/python scripts/scrcpy-bankid-login.py --mode full
  .venv/bin/python scripts/scrcpy-bankid-login.py --mode bankid-only --pin <PIN>
"""
import argparse
import importlib.util
import random
import subprocess
import time
from pathlib import Path

import pyautogui

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0.05

# Координаты на телефоне 720x1600.
KEYS = {
    "1": (120, 1008), "2": (361, 1008), "3": (601, 1008),
    "4": (120, 1152), "5": (361, 1152), "6": (601, 1152),
    "7": (120, 1296), "8": (361, 1296), "9": (601, 1296),
    "0": (361, 1437),
    "identifiera": (601, 1437),
    "confirm": (360, 1426),      # «Identifiera med säkerhetskod» (центр кнопки)
    "logga_in": (360, 1202),     # «Logga in» на стартовом экране Swedbank
}
CONFIRM_TEXT = "Identifiera med säkerhetskod"
PINPAD_TEXT = "Säkerhetskod"
VIDEO_W, VIDEO_H = 360, 800     # размер видео-области окна scrcpy

# Переиспользуем adb/uiautomator из uhid-bankid-login.py.
_spec = importlib.util.spec_from_file_location(
    "uhid_bankid_login",
    Path(__file__).with_name("uhid-bankid-login.py"),
)
_uhid = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_uhid)
SERIAL = _uhid.SERIAL


def scrcpy_window():
    """(x, y, title_h) окна scrcpy через AppleScript. title_h = высота заголовка."""
    out = subprocess.run(
        ["osascript", "-e",
         'tell application "System Events"\n'
         'set p to first process whose name contains "scrcpy"\n'
         'set w to first window of p\n'
         'set {px, py} to position of w\n'
         'set {sx, sy} to size of w\n'
         'return (px as string) & "," & py & "," & sx & "," & sy\n'
         'end tell'],
        capture_output=True, text=True, timeout=10,
    )
    px, py, sx, sy = [int(v) for v in out.stdout.strip().split(",")]
    title_h = max(0, sy - VIDEO_H)
    return px, py, title_h


def tap_phone(px, py, win):
    """Клик по точке телефона (px,py) через окно scrcpy."""
    wx, wy, title_h = win
    mx = wx + px * (VIDEO_W / 720)
    my = wy + title_h + py * (VIDEO_H / 1600)
    pyautogui.moveTo(mx + random.uniform(-2, 2), my + random.uniform(-2, 2))
    time.sleep(random.uniform(0.1, 0.3))
    pyautogui.click()
    time.sleep(random.uniform(0.2, 0.4))


def wait_for_text(needle, timeout_s=30, interval=0.6):
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if any(needle in t for t in _uhid.current_texts()):
            return True
        time.sleep(interval)
    return False


def run_bankid_login(pin, win):
    """Подтверждение → пауза → PIN → Identifiera (всё через окно scrcpy)."""
    if not wait_for_text(CONFIRM_TEXT, timeout_s=30):
        print("no confirm screen — aborting")
        return False
    print("confirm screen — tapping «Identifiera med säkerhetskod»")
    tap_phone(*KEYS["confirm"], win)

    if not wait_for_text(PINPAD_TEXT, timeout_s=10):
        print("PIN-pad did not open — aborting")
        return False
    print("PIN-pad open — entering PIN")
    time.sleep(random.uniform(0.8, 1.6))

    for i, digit in enumerate(pin):
        tap_phone(*KEYS[digit], win)
        print("tap", digit)
        if i < len(pin) - 1:
            time.sleep(random.uniform(0.8, 2.0))

    time.sleep(random.uniform(0.8, 1.8))
    tap_phone(*KEYS["identifiera"], win)
    print("tap identifiera")
    time.sleep(3)
    print("final texts:", " | ".join(_uhid.current_texts()[:8]))
    return True


def main():
    parser = argparse.ArgumentParser(description="Swedbank/BankID login via scrcpy window clicks")
    parser.add_argument("--mode", choices=["full", "bankid-only"], default="full")
    parser.add_argument("--pin", default=_uhid.PIN)
    args = parser.parse_args()
    pin = "".join(c for c in args.pin if c.isdigit())
    if not pin:
        raise SystemExit("empty PIN")

    win = scrcpy_window()
    print("scrcpy window:", win)

    if args.mode == "full":
        print("tapping «Logga in»")
        tap_phone(*KEYS["logga_in"], win)

    ok = run_bankid_login(pin, win)
    raise SystemExit(0 if ok else 1)


if __name__ == "__main__":
    main()
