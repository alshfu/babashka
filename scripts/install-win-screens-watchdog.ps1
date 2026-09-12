# install-win-screens-watchdog — разовая установка автозапуска сторожа экранов на ALSH.
# Запуск: PowerShell от Администратора:
#   powershell -ExecutionPolicy Bypass -File scripts\install-win-screens-watchdog.ps1
#
# Создаёт задачу Планировщика "PultScreensWatchdog": старт при входе в систему,
# скрытое окно, перезапуск при падении каждую минуту. Сразу же запускает её.

$ErrorActionPreference = 'Stop'

$REPO   = 'C:\Users\Administrator\IdeaProjects\babashka'
$SCRIPT = Join-Path $REPO 'scripts\win-screens-watchdog.ps1'
$TASK   = 'PultScreensWatchdog'

if (-not (Test-Path $SCRIPT)) { throw "не найден $SCRIPT — сначала git pull на ALSH" }

$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument (
    '-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "{0}"' -f $SCRIPT)
$trigger = New-ScheduledTaskTrigger -AtLogOn
$settings = New-ScheduledTaskSettingsSet -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries

Register-ScheduledTask -TaskName $TASK -Action $action -Trigger $trigger -Settings $settings `
    -Description 'Pult: экраны Note 10 и Redmi на ALSH видны всегда (scrcpy-watchdog)' -Force | Out-Null

Start-ScheduledTask -TaskName $TASK
Write-Output "задача $TASK установлена и запущена; лог: test_logs\win-screens-watchdog.log"
