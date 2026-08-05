#!/usr/bin/env python3
"""
BankID auto-login via scrcpy UHID mouse (physical HID, не детектируется BankID).

Требования:
- scrcpy запущен с --keyboard=uhid --mouse=uhid --no-video --no-audio
  и окном на (100,100) размером 360x800.
- pyautogui (в .venv проекта).
- Телефон на adb (USB), беспроводная отладка ВЫКЛЮЧЕНА (BankID её блокирует).

Схема координат: телефон 720x1600. Курсор UHID двигается относительно.
Сбрасываем его в (0,0) большим отрицательным сдвигом, затем двигаем к цели.
"""
import argparse
import os
import random
import subprocess, time, sys
import pyautogui

pyautogui.FAILSAFE = False
pyautogui.PAUSE = 0.05

SERIAL = "DQ6TC64DY9PRBE4T"
# PIN только из окружения — в коде и истории репозитория его быть не должно.
PIN = os.environ.get("BANKID_PIN", "")
CONFIRM_KEY = "0"          # кнопка подтверждения на месте «0» PIN-pad
IDENTIFIERA_KEY = "identifiera"

# Координаты кнопок BankID PIN-pad (по замерам на устройстве 720x1600).
KEYS = {
    "1": (120, 1008), "2": (361, 1008), "3": (601, 1008),
    "4": (120, 1152), "5": (361, 1152), "6": (601, 1152),
    "7": (120, 1296), "8": (361, 1296), "9": (601, 1296),
    "0": (361, 1437),
    "identifiera": (601, 1437),
    "confirm": (361, 1437),  # кнопка подтверждения на месте «0»
}


def adb(*args):
    return subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True, timeout=30)


def uhid_reset():
    """Захватить мышь над окном scrcpy и сбросить курсор телефона в (0,0)."""
    pyautogui.moveTo(280, 500)  # hover над окном scrcpy → захват
    time.sleep(0.2)
    pyautogui.moveRel(-2500, -2500)
    time.sleep(0.2)


def uhid_tap(x, y):
    """Переместить курсор в (x,y) и кликнуть."""
    pyautogui.moveRel(x, y)
    time.sleep(0.15)
    pyautogui.click()
    time.sleep(0.3)


def current_texts():
    adb("shell", "uiautomator", "dump", "/sdcard/_s.xml")
    adb("pull", "/sdcard/_s.xml", "/tmp/_s.xml")
    try:
        import xml.etree.ElementTree as ET
        root = ET.parse("/tmp/_s.xml").getroot()
        return [el.attrib.get("text", "") for el in root.iter() if el.attrib.get("text")]
    except Exception:
        return []


def run_bankid_login(pin=PIN, confirm_wait=(1.5, 2.8), digit_delay=(0.5, 2.0), identifiera_wait=(2.0, 4.0)):
    """Tap BankID confirm (at '0'), wait, enter PIN, tap Identifiera.

    Все задержки — случайные диапазоны, чтобы выглядеть как человек, а не как таймер.
    """
    uhid_reset()
    uhid_tap(*KEYS[CONFIRM_KEY])
    time.sleep(random.uniform(*confirm_wait))

    texts = current_texts()
    print("after confirm:", " | ".join(texts[:6]))

    for i, digit in enumerate(pin):
        uhid_reset()
        uhid_tap(*KEYS[digit])
        # Последнюю цифру не задерживаем — перед Identifiera будет своя пауза.
        if i < len(pin) - 1:
            time.sleep(random.uniform(*digit_delay))
        texts = current_texts()
        dots = [t for t in texts if set(t) <= {"•", "*", "●"} or "Säkerhetskod" in t or "tomt" in t]
        print(f"tap {digit}: {' | '.join(dots[:3])}")

    uhid_reset()
    uhid_tap(*KEYS[IDENTIFIERA_KEY])
    time.sleep(random.uniform(*identifiera_wait))

    texts = current_texts()
    print("final:", " | ".join(texts[:10]))
    out = adb("shell", "dumpsys", "activity", "activities")
    focused = [l for l in out.stdout.splitlines() if "mFocusedApp" in l][:2]
    print("focused:", focused)


def run_full_swedbank_flow(pin=PIN):
    # 1. Открыть Swedbank и нажать Logga in.
    adb("shell", "am", "force-stop", "se.swedbank.mobil")
    adb("shell", "am", "force-stop", "com.bankid.bus")
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(2)
    adb("shell", "input", "tap", "275", "330")
    time.sleep(8)
    adb("shell", "input", "tap", "360", "1202")
    time.sleep(5)
    # 2-4. BankID login.
    run_bankid_login(pin)


def main():
    parser = argparse.ArgumentParser(description="BankID auto-login via scrcpy UHID mouse")
    parser.add_argument("--mode", choices=["full", "bankid-only"], default="full",
                        help="full = open Swedbank and log in; bankid-only = BankID is already open")
    parser.add_argument("--pin", default=PIN, help="BankID PIN")
    args = parser.parse_args()

    if args.mode == "full":
        run_full_swedbank_flow(args.pin)
    else:
        run_bankid_login(args.pin)


if __name__ == "__main__":
    main()
