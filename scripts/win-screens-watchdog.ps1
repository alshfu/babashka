# win-screens-watchdog — СТОРОЖ ЖИВЫХ ЭКРАНОВ НА ALSH. НЕ УДАЛЯТЬ.
#
# Железное правило проекта (AGENTS.md): экраны Note 10 и Redmi на Windows-машине
# ALSH видны ВСЕГДА. У оператора нет возможности подойти к устройствам —
# живое окно scrcpy это единственный «глаз» на телефон.
#
# Скрипт крутится вечно и каждые POLL_SEC секунд:
#   1. проверяет, что adb видит оба устройства (иначе: reconnect / restart-server);
#   2. проверяет, что для каждого серийника живо окно scrcpy (иначе: поднимает).
# scrcpy сам себя не перезапускает — при любом USB/adb-сбое окно умирает навсегда,
# этот сторож закрывает дыру.
#
# Установка автозапуска (один раз, из PowerShell Администратора):
#   powershell -ExecutionPolicy Bypass -File scripts\install-win-screens-watchdog.ps1
# Ручной запуск (видимый, для отладки):
#   powershell -ExecutionPolicy Bypass -File scripts\win-screens-watchdog.ps1

$ErrorActionPreference = 'Continue'

$REPO   = 'C:\Users\Administrator\IdeaProjects\babashka'
$ADB    = Join-Path $REPO 'tools\platform-tools\adb.exe'
$SCRCPY = Join-Path $REPO 'tools\scrcpy\scrcpy.exe'
$LOG    = Join-Path $REPO 'test_logs\win-screens-watchdog.log'
$POLL_SEC = 15

# Кто обязан быть на экране: серийник → заголовок окна.
$DEVICES = @(
    @{ Serial = 'R58M9167C4H';      Title = 'Note 10 (B-app)' },
    @{ Serial = 'DQ6TC64DY9PRBE4T'; Title = 'Redmi (A-app)' }
)

function Log([string]$msg) {
    $line = '{0:yyyy-MM-dd HH:mm:ss} {1}' -f (Get-Date), $msg
    Add-Content -Path $LOG -Value $line -Encoding UTF8
}

function AdbArgsOk { (Test-Path $ADB) -and (Test-Path $SCRCPY) }

function Get-AdbSerials {
    # Серийники устройств в состоянии "device" (не offline/unauthorized).
    $out = & $ADB 'devices' 2>$null
    $serials = @()
    foreach ($l in $out) {
        if ($l -match '^(\S+)\s+device\s*$') { $serials += $Matches[1] }
    }
    return $serials
}

function Scrcpy-RunningFor([string]$serial) {
    # Ищем процесс scrcpy, в командной строке которого есть этот серийник.
    $procs = Get-CimInstance Win32_Process -Filter "Name='scrcpy.exe'" 2>$null
    foreach ($p in $procs) {
        if ($p.CommandLine -and $p.CommandLine.Contains($serial)) { return $true }
    }
    return $false
}

function Start-Screen([string]$serial, [string]$title) {
    # --stay-awake: телефон не засыпает, пока окно живо (глаз всегда живой).
    # Окно НЕ свёрнуто и НЕ скрыто — смысл сторожа в видимости.
    Start-Process -FilePath $SCRCPY -ArgumentList @(
        '--serial', $serial,
        '--window-title', $title,
        '--stay-awake'
    )
    Log "scrcpy поднят: $title ($serial)"
}

Log '=== сторож экранов запущен ==='

if (-not (AdbArgsOk)) {
    Log "НЕТ БИНАРЕЙ: adb=$ADB scrcpy=$SCRCPY — проверь tools\ в репозитории"
}

while ($true) {
    try {
        if (AdbArgsOk) {
            $serials = Get-AdbSerials
            foreach ($d in $DEVICES) {
                $s = $d.Serial
                if ($serials -notcontains $s) {
                    Log "$($d.Title) ($s): adb не видит — reconnect"
                    & $ADB '-s' $s 'reconnect' 2>$null | Out-Null
                    Start-Sleep -Seconds 2
                    $serials = Get-AdbSerials
                    if ($serials -notcontains $s) {
                        Log "$($d.Title) ($s): reconnect не помог — restart adb-server"
                        & $ADB 'kill-server' 2>$null | Out-Null
                        Start-Sleep -Seconds 2
                        & $ADB 'start-server' 2>$null | Out-Null
                        Start-Sleep -Seconds 3
                        $serials = Get-AdbSerials
                    }
                }
                if ($serials -contains $s) {
                    if (-not (Scrcpy-RunningFor $s)) {
                        Start-Screen $s $d.Title
                    }
                } else {
                    Log "$($d.Title) ($s): устройство не на связи, окно поднимать некуда"
                }
            }
        }
    } catch {
        Log "ошибка цикла: $($_.Exception.Message)"
    }
    Start-Sleep -Seconds $POLL_SEC
}
