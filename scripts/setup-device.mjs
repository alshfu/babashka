#!/usr/bin/env node
/**
 * setup-device — полная настройка устройства одной командой, без ручных шагов.
 *
 * Роли:
 *   gateway — устройство оператора (Samsung/при руке): приложение-шлюз (com.bankid.bus),
 *             перехват bankid://, настройки канала, разрешения, домен app.bankid.com.
 *   phone   — устройство в Швеции: приложение se.pult.app, демо-пара, PIN'ы, appops.
 *
 * Запуск:
 *   node scripts/setup-device.mjs gateway <serial>            # поставить/обновить и настроить
 *   node scripts/setup-device.mjs gateway <serial> --reinstall  # сначала удалить
 *   node scripts/setup-device.mjs phone <serial> [--bankid-pin <PIN>] [--lock-pin 1234]
 *
 * Примеры APK по умолчанию: app/build/.../app-debug.apk и android/.../grandma-v2-debug.apk.
 */
import { execFileSync } from 'node:child_process';

const ROLE = process.argv[2];
const SERIAL = process.argv[3];
const REINSTALL = process.argv.includes('--reinstall');
const arg = (name, dflt) => {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : dflt;
};

const TOKEN = arg('--token', process.env.AGENT_TOKEN || '');
if (!TOKEN) {
  console.error('нужен токен канала: --token <AGENT_TOKEN> или env AGENT_TOKEN');
  process.exit(1);
}
const SERVER = arg('--server', 'wss://85.190.98.57.sslip.io:8445');
const PAIR_ID = arg('--pair-id', 'demo-pair-000000000000');
const BANKID_PIN = arg('--bankid-pin', '');
const LOCK_PIN = arg('--lock-pin', '');

if (!ROLE || !SERIAL || !['gateway', 'phone'].includes(ROLE)) {
  console.error('usage: node scripts/setup-device.mjs gateway|phone <serial> [--reinstall] [options]');
  process.exit(1);
}

const adb = (args, allowFail = false) => {
  try {
    return execFileSync('adb', ['-s', SERIAL, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  } catch (e) {
    if (allowFail) return '';
    throw new Error(`adb ${args.join(' ')}: ${e.stderr || e.message}`);
  }
};
const shell = (cmd, allowFail = false) => adb(['shell', cmd], allowFail);

const writePrefs = (pkg, file, xml) => {
  execFileSync('adb', [
    '-s', SERIAL, 'shell',
    `run-as ${pkg} sh -c 'mkdir -p shared_prefs && cat > shared_prefs/${file}'`,
  ], { input: xml, stdio: ['pipe', 'pipe', 'pipe'] });
};

function setupGateway() {
  const apk = arg('--apk', 'app/build/app/outputs/flutter-apk/app-debug.apk');
  console.log('1/5 удаление живого BankID (мешает перехвату)…');
  const bankid = shell('pm list packages com.bankid.bus', true);
  if (bankid.includes('com.bankid.bus')) {
    // Это может быть и наш шим — отличаем по подписи: у шима есть наша MainActivity.
    const ours = shell('cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER com.bankid.bus', true);
    if (!ours.includes('pult_gateway')) {
      console.log('   настоящий BankID → в корзину (данные сохраняются)');
      shell('pm uninstall -k com.bankid.bus', true) || shell('pm uninstall com.bankid.bus');
    }
  }
  console.log('2/5 установка шлюза…');
  if (REINSTALL) shell('pm uninstall com.bankid.bus', true);
  adb(['install', '-r', apk]);

  console.log('3/5 настройки канала (сервер/pairId/токен)…');
  writePrefs('com.bankid.bus', 'FlutterSharedPreferences.xml',
    `<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>` +
    `<string name="flutter.server">${SERVER}</string>` +
    `<string name="flutter.pairId">${PAIR_ID}</string>` +
    `<string name="flutter.token">${TOKEN}</string>` +
    `</map>`);

  console.log('4/5 разрешения и домен по умолчанию…');
  shell('pm grant com.bankid.bus android.permission.WRITE_SECURE_SETTINGS');
  shell('pm set-app-links-user-selection --user 0 --package com.bankid.bus true app.bankid.com');

  console.log('5/5 запуск…');
  shell('am force-stop com.bankid.bus');
  shell('monkey -p com.bankid.bus -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1');
  console.log('готово: шлюз настроен, канал поднимется сам за пару секунд');
}

function setupPhone() {
  const apk = arg('--apk', 'android/grandma/build/outputs/apk/v2/debug/grandma-v2-debug.apk');
  console.log('1/5 установка приложения устройства…');
  if (REINSTALL) shell('pm uninstall se.pult.app', true);
  adb(['install', '-r', apk]);

  console.log('2/5 роль «device» + PIN’ы…');
  const pinXml = BANKID_PIN ? `<string name="bankid_pin">${BANKID_PIN}</string>` : '';
  const lockXml = LOCK_PIN ? `<string name="lock_pin">${LOCK_PIN}</string>` : '';
  writePrefs('se.pult.app', 'pult_settings.xml',
    `<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>` +
    `<string name="app_role">device</string>${pinXml}${lockXml}</map>`);

  console.log('3/5 автозахват экрана, wireless debugging и a11y-служба…');
  shell('appops set se.pult.app PROJECT_MEDIA allow', true);
  shell('pm grant se.pult.app android.permission.WRITE_SECURE_SETTINGS', true);
  shell('settings put secure enabled_accessibility_services se.pult.app/ru.pult.grandma.control.RemoteControlService', true);
  shell('settings put secure accessibility_enabled 1', true);

  console.log('4/5 исключение из экономии батареи + MIUI-автозапуск…');
  shell('dumpsys deviceidle whitelist +se.pult.app', true);
  shell('appops set se.pult.app 10008 allow', true);

  console.log('5/5 запуск + авто-паринг wireless ADB (без ручного ввода)…');
  shell('monkey -p se.pult.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1');
  sleep(6000);
  shell('am broadcast -a se.pult.app.PAIR -p se.pult.app', true);
  console.log('готово: устройство выйдет на связь само (проверь hello на сервере)');
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

ROLE === 'gateway' ? setupGateway() : setupPhone();
