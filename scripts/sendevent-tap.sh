#!/system/bin/sh
# sendevent-tap.sh <device> <x> <y> — реалистичное касание прямо в тачскрин.
# Инъекция в реальный /dev/input/eventX: BankID видит физическое касание.
DEV="$1"; X="$2"; Y="$3"
: "${DEV:=/dev/input/event3}"
: "${X:?need x}" "${Y:?need y}"

# MT-протокол: касание с реалистичным давлением и площадью контакта.
sendevent "$DEV" 3 57 100      # ABS_MT_TRACKING_ID = 100
sendevent "$DEV" 3 53 "$X"     # ABS_MT_POSITION_X
sendevent "$DEV" 3 54 "$Y"     # ABS_MT_POSITION_Y
sendevent "$DEV" 3 48 60       # ABS_MT_TOUCH_MAJOR (площадь пальца)
sendevent "$DEV" 3 58 600      # ABS_MT_PRESSURE (давление)
sendevent "$DEV" 1 330 1       # BTN_TOUCH down
sendevent "$DEV" 0 2 0         # SYN_MT_REPORT
sendevent "$DEV" 0 0 0         # SYN_REPORT
sleep 0.08
sendevent "$DEV" 3 57 4294967295  # ABS_MT_TRACKING_ID = -1 (release)
sendevent "$DEV" 1 330 0       # BTN_TOUCH up
sendevent "$DEV" 0 2 0         # SYN_MT_REPORT
sendevent "$DEV" 0 0 0         # SYN_REPORT
