import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:app_links/app_links.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:share_plus/share_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'dart_link.dart';
import 'screencast.dart';
import 'tunnel_proxy.dart';

/// B-app (Controller) på Note 10.
///
/// Första skärmen är listan över tillgängliga A-app-enheter. Ett tryck på en
/// enhet öppnar dess styrsida: delad skärm (lowlat) och tunnel för all trafik.
/// BankID-signeringsstatus syns på styrsidan. Appen sparar inga PIN-koder.
void main() => runApp(const ControllerApp());

class ControllerApp extends StatelessWidget {
  const ControllerApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Pult – Kontroll',
        theme: ThemeData.dark(useMaterial3: true),
        home: const DeviceListPage(),
      );
}

enum SignStage { idle, sent, opened, signing, signed, failed }

class Device {
  Device({
    required this.deviceId,
    required this.pairId,
    this.label,
    this.model,
    this.os,
    this.ip,
    this.online = false,
  });

  final String deviceId;
  final String pairId;
  final String? label;
  final String? model;
  final String? os;
  final String? ip;
  final bool online;

  String get displayName => label?.isNotEmpty == true
      ? label!
      : (model?.isNotEmpty == true ? model! : 'A-app');

  String get subtitle {
    final parts = <String>[
      if (model?.isNotEmpty == true) 'Modell: $model',
      if (os?.isNotEmpty == true) 'OS: $os',
      if (ip?.isNotEmpty == true) 'IP: $ip',
      online ? 'Ansluten' : 'Inte ansluten',
    ];
    return parts.join(' · ');
  }
}

class LinkBridge extends ChangeNotifier {
  static const defaultServer = 'wss://85.190.98.57.sslip.io:8445';
  static const defaultPairId = 'Dl8YrhLu00VwOz62sQr4gw';
  static const defaultToken = 'Pxj6sCxMzQEsthyDRyLaV6IfM9zFu5M5';
  // Inkakade inställningar: höj när default-värdena ändras — då skrivs de
  // över i befintliga installationer vid nästa start (init → _loadSettings).
  static const defaultsVersion = 2;
  static const _ch = MethodChannel('pult.gateway/link');
  static const _control = MethodChannel('pult.gateway/control');
  static const tunnelPort = 8877;

  // iOS: нативного слоя канала нет (KeepAliveService — Android-only), поэтому
  // /link держим прямо в Dart (dart_link.dart). Семантика та же, что у нативной.
  DartLinkService? _dart;
  final _appLinks = AppLinks();
  StreamSubscription<Uri>? _appLinkSub;
  // iOS/общий fallback: встроенный CONNECT-прокси на 0.0.0.0:8877 → /tunnel
  // (туннель до шведского IP без системного прокси, см. tunnel_proxy.dart).
  TunnelProxyService? _tunnelProxy;
  String? proxyLanIp;

  String server = defaultServer;
  String pairId = defaultPairId;
  String token = defaultToken;
  String? selectedDeviceId;

  bool online = false;
  SignStage stage = SignStage.idle;
  String lastError = '';
  bool tunnelOnline = false;
  int tunnelStreams = 0;

  bool proxyOn = false;
  bool proxyWanted = true;
  // Туннель активен; SSID для iOS-профиля — только ввод вручную (iOS не отдаёт
  // SSID без entitlement).
  String proxySsid = '';
  // Android: null = не проверяли. false = bankid:// уходит НЕ в B-app — вход
  // не инициируется; онбординг показывает карточку со ссылкой в настройки.
  bool? defaultLinkHandler;
  // Senaste felet från setProxy (t.ex. saknad WRITE_SECURE_SETTINGS) —
  // visas på enhetssidan så man ser varför tunneln inte går.
  String proxyError = '';

  List<Device> devices = [];

  Future<void> init() async {
    _ch.setMethodCallHandler((call) async {
      if (call.method == 'changed') await refresh();
    });
    await _loadSettings();
    if (Platform.isIOS) {
      final dart = DartLinkService();
      _dart = dart;
      dart.events.listen((event) {
        _applyStatusRaw(event.rawJson);
        notifyListeners();
      });
      // Перехват bankid:// от Swedbank: iOS доставляет схему, заявленную в
      // Info.plist (CFBundleURLTypes), плагином app_links.
      _appLinkSub = _appLinks.uriLinkStream.listen(_onAppLink);
      final initial = await _appLinks.getInitialLink();
      if (initial != null) _onAppLink(initial);
    }
    await refresh();
    await loadDevices();
    unawaited(connect());
    await refreshProxyState();
    // Онбординг: ловим ли мы bankid:// (только нативный путь — на iOS схема
    // заявлена в Info.plist и система сама спрашивает разрешение при первом переходе).
    if (_dart == null) unawaited(checkDefaultLinkHandler());
    // Забытый включённый прокси без живого туннеля — снять, иначе сеть мертва.
    if (proxyOn && !tunnelOnline) await _setProxy(false);
  }

  /// Android: проверяем, что VIEW bankid:/// разрешается в наше приложение.
  Future<bool> checkDefaultLinkHandler() async {
    if (Platform.isIOS) {
      defaultLinkHandler = true;
      return true;
    }
    try {
      defaultLinkHandler = await _control.invokeMethod<bool>('isDefaultLinkHandler') ?? false;
    } catch (_) {
      defaultLinkHandler = null;
    }
    notifyListeners();
    return defaultLinkHandler ?? false;
  }

  /// Экран «Открывать по умолчанию» нашего приложения (Android).
  Future<void> openLinkSettings() async {
    try {
      await _control.invokeMethod<void>('openLinkSettings');
    } catch (_) {}
  }

  Future<void> connect() async {
    try {
      final dart = _dart;
      if (dart != null) {
        await dart.reload(server, pairId, token);
      } else {
        await _ch.invokeMethod<void>('reloadLink');
      }
      await refresh();
    } catch (_) {}
  }

  /// Принудительный переподключение. iOS: после возврата из фона сокет часто
  /// мёртв, а onDone/onError не приходили во время сна — reload() с тем же
  /// эндпоинтом сочтёт его живым и не пересоединится. Здесь — без раннего выхода.
  Future<void> reconnect() async {
    final dart = _dart;
    if (dart == null) {
      await connect();
      return;
    }
    try {
      await dart.forceReload(server, pairId, token);
      await refresh();
    } catch (_) {}
  }

  Future<void> refresh() async {
    final dart = _dart;
    if (dart != null) {
      online = dart.online;
      // Нативного туннеля на iOS нет — только прямой канал /link.
      tunnelOnline = false;
      tunnelStreams = 0;
      _applyStatusRaw(dart.lastStatusRaw);
    } else {
      try {
        final state = await _ch.invokeMethod<Map<dynamic, dynamic>>('getState');
        online = state?['online'] == true;
        tunnelOnline = state?['tunnelOnline'] == true;
        tunnelStreams = (state?['tunnelStreams'] as int?) ?? 0;
        _applyStatusRaw((state?['lastStatus'] as String?) ?? '');
      } catch (_) {}
    }
    // Туннель умер — прокси недействителен; туннель поднялся — вернуть прокси,
    // если он был желан (Swedbank-трафик Note 10 должен выходить со шведским IP).
    if (!tunnelOnline && proxyOn) {
      await _setProxy(false);
    } else if (tunnelOnline && proxyWanted && !proxyOn) {
      await toggleProxy(true);
      return; // toggleProxy сам зовёт notifyListeners
    }
    notifyListeners();
  }

  Future<void> clearStatus() async {
    final dart = _dart;
    if (dart != null) {
      dart.clearStatus();
    } else {
      try {
        await _ch.invokeMethod<void>('clearStatus');
      } catch (_) {}
    }
    stage = SignStage.idle;
    lastError = '';
    notifyListeners();
  }

  Future<void> loadDevices() async {
    final dart = _dart;
    try {
      final list = dart != null
          ? await dart.fetchDevices(server, pairId, token)
          : jsonDecode(await _control.invokeMethod<String>('getDevices') ?? '[]') as List<dynamic>;
      devices = list.map((it) {
        final m = it as Map<String, dynamic>;
        return Device(
          deviceId: m['deviceId'] as String? ?? '',
          pairId: m['pairId'] as String? ?? '',
          label: m['label'] as String?,
          model: m['model'] as String?,
          os: m['os'] as String?,
          ip: m['ip'] as String?,
          online: m['online'] == true,
        );
      }).toList();
    } catch (_) {
      devices = [];
    }
    notifyListeners();
  }

  Future<bool> sendScreencast(bool on) async {
    final dart = _dart;
    if (dart != null) {
      if (!dart.online) return false;
      dart.sendScreencast(on);
      return true;
    }
    try {
      await _ch.invokeMethod<void>('sendScreencast', on);
      return true;
    } catch (_) {
      return false;
    }
  }

  /// Кнопка «сброс PIN BankID»: на далёком телефоне поднимется экран ввода PIN
  /// (+ трансляция экрана, чтобы оператор видел процесс). Вводит человек у телефона;
  /// итог — событие статуса stage=pin-saved|pin-cancelled.
  Future<bool> sendPinSetup() async {
    final dart = _dart;
    if (dart != null) {
      if (!dart.online) return false;
      dart.sendPinSetup();
      return true;
    }
    try {
      await _ch.invokeMethod<void>('sendPinSetup');
      return true;
    } catch (_) {
      return false;
    }
  }

  /// Удалённая запись PIN, введённого владельцем в этом приложении: телефон
  /// сохранит его локально (PinStorage) — для входов по диплинку.
  Future<bool> sendPinSet(String pin) async {
    final dart = _dart;
    if (dart != null) {
      if (!dart.online) return false;
      dart.sendPinSet(pin);
      return true;
    }
    try {
      await _ch.invokeMethod<void>('sendPinSet', pin);
      return true;
    } catch (_) {
      return false;
    }
  }

  Future<void> selectDevice(Device device) async {
    selectedDeviceId = device.deviceId;
    await _saveSettings();
    if (tunnelOnline && proxyWanted && !proxyOn) await toggleProxy(true);
    notifyListeners();
  }

  Future<void> toggleProxy(bool on) async {
    // Misslyckat setProxy får aldrig stoppa navigeringen: enheter utan
    // WRITE_SECURE_SETTINGS (vanlig app) kastar här — tunneln via proxy är
    // helt enkelt otillgänglig, resten av appen ska leva vidare.
    final dart = _dart;
    if (dart != null) {
      // Dart-путь: собственный CONNECT-прокси на 0.0.0.0:8877 (iOS и прочие без
      // нативного слоя). Системный прокси не трогаем — на iOS его нет; трафик
      // направляется вручную (настройки Wi-Fi → прокси <lan-ip>:8877).
      try {
        final proxy = _tunnelProxy ??= TunnelProxyService();
        if (on) {
          await proxy.start(server, pairId, token);
          proxyLanIp = await TunnelProxyService.lanIp();
          proxyOn = true;
          proxyError = '';
        } else {
          await proxy.stop();
          proxyOn = false;
        }
      } catch (e) {
        proxyOn = false;
        proxyError = 'Kunde inte starta proxy: $e';
      }
      proxyWanted = on;
      await _saveSettings();
      notifyListeners();
      return;
    }
    try {
      await _setProxy(on);
      proxyOn = on;
      proxyError = '';
    } catch (e) {
      proxyOn = false;
      proxyError = 'Proxy kräver WRITE_SECURE_SETTINGS (adb: pm grant com.bankid.bus android.permission.WRITE_SECURE_SETTINGS): $e';
    }
    proxyWanted = on;
    await _saveSettings();
    notifyListeners();
  }

  Future<void> _setProxy(bool on) async {
    await _control.invokeMethod<bool>('setProxy', {'on': on, 'port': tunnelPort});
    proxyOn = on;
  }

  Future<void> refreshProxyState() async {
    final dart = _dart;
    if (dart != null) {
      proxyOn = _tunnelProxy?.active ?? false;
      return;
    }
    try {
      final value = await _control.invokeMethod<String>('getProxy');
      proxyOn = value != null && value.contains('$tunnelPort');
    } catch (_) {}
  }

  /// Отправка диплинка на выбранное A-app-устройство — «свой канал» B→A:
  /// только по этому запросу A-app поднимает BankID. Нормализация URL — как
  /// у перехвата bankid:// от Swedbank.
  Future<bool> sendDeeplinkToDevice(String rawUrl) async {
    final uri = Uri.tryParse(rawUrl.trim());
    if (uri == null) return false;
    final url = _normalizeBankIdUrl(uri);
    if (url.isEmpty) return false;
    final dart = _dart;
    if (dart != null) {
      if (!dart.online) return false;
      dart.sendDeeplink(url, selectedDeviceId);
      return true;
    }
    try {
      await _ch.invokeMethod<void>('sendDeeplink', url);
      return true;
    } catch (_) {
      return false;
    }
  }

  /// Генерация iOS-профиля (.mobileconfig): Wi-Fi-пayload с ручным прокси на
  /// наш туннель для заданного SSID. Установка: поделиться файлом → Настройки
  /// → «Профиль загружен» → установить; снятие — VPN и управление устройством.
  /// Возвращает файл или null, если не хватает данных (SSID/IP).
  Future<File?> generateProxyProfile() async {
    final ip = proxyLanIp ?? await TunnelProxyService.lanIp();
    final ssid = proxySsid.trim();
    if (ip == null || ssid.isEmpty) return null;
    final base = DateTime.now().millisecondsSinceEpoch;
    String esc(String s) =>
        s.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
    final xml = '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>PayloadContent</key>
  <array>
    <dict>
      <key>PayloadType</key><string>com.apple.wifi.managed</string>
      <key>PayloadVersion</key><integer>1</integer>
      <key>PayloadIdentifier</key><string>io.pult.wifi.tunnel.$base</string>
      <key>PayloadUUID</key><string>50454C54-$base-0000-0000-000000000001</string>
      <key>SSID_STR</key><string>${esc(ssid)}</string>
      <key>ProxyType</key><string>Manual</string>
      <key>ProxyServer</key><string>${esc(ip)}</string>
      <key>ProxyServerPort</key><integer>8877</integer>
    </dict>
  </array>
  <key>PayloadDisplayName</key><string>Pult Tunnel</string>
  <key>PayloadDescription</key><string>Manual proxy to the Pult device tunnel on Wi-Fi «${esc(ssid)}»</string>
  <key>PayloadIdentifier</key><string>io.pult.tunnel.profile.$base</string>
  <key>PayloadUUID</key><string>50454C54-$base-0000-0000-000000000000</string>
  <key>PayloadType</key><string>Configuration</string>
  <key>PayloadVersion</key><integer>1</integer>
</dict>
</plist>
''';
    final file = File('${Directory.systemTemp.path}/pult-tunnel.mobileconfig');
    await file.writeAsString(xml);
    return file;
  }

  /// Publik IP — через активный прокси (свой Dart-прокси или системный на
  /// Android), dvs. med tunnel ON är det den valda A-app-enhetens adress.
  Future<String> testPublicIp() async {
    final dart = _dart;
    final throughProxy = proxyOn && (dart != null ? (_tunnelProxy?.active ?? false) : true);
    try {
      final client = HttpClient();
      if (dart != null && throughProxy) {
        client.findProxy = (uri) => 'PROXY 127.0.0.1:$tunnelPort';
      }
      final request = await client
          .getUrl(Uri.parse('https://api.ipify.org?format=json'))
          .timeout(const Duration(seconds: 25));
      final response = await request.close();
      final body = await response.transform(utf8.decoder).join();
      client.close();
      final ip = (jsonDecode(body) as Map<String, dynamic>)['ip'] as String? ?? '?';
      return 'Publik IP: $ip';
    } catch (e) {
      return 'Det gick inte att kontrollera IP: $e';
    }
  }

  /// Команда реанимации на A-app (server → FCM → телефон действует сам):
  /// action = restart (мягкий) | reboot (перезагрузка устройства).
  /// Возвращает пустую строку при успехе, иначе текст ошибки.
  Future<String> reanimate(String action) async {
    final base = server.startsWith('wss://')
        ? 'https://${server.substring(6)}'
        : server.startsWith('ws://')
            ? 'http://${server.substring(5)}'
            : server;
    final client = HttpClient();
    try {
      final request = await client
          .postUrl(Uri.parse('$base/api/reanimate'))
          .timeout(const Duration(seconds: 10));
      request.headers.contentType = ContentType.json;
      request.headers.set('x-agent-token', token);
      request.write(jsonEncode({'pairId': pairId, 'action': action}));
      final response = await request.close().timeout(const Duration(seconds: 15));
      final body = await response.transform(utf8.decoder).join();
      if (response.statusCode != 200) return 'Fel: HTTP ${response.statusCode}';
      final m = jsonDecode(body) as Map<String, dynamic>;
      return m['sent'] == true ? '' : 'Push levererades inte — enheten saknar FCM-token?';
    } catch (e) {
      return 'Fel: $e';
    } finally {
      client.close();
    }
  }

  /// Страница просмотра lowlat-трансляции (room=demo — так же жёстко задано в A-app).
  /// Токен пары передаём странице: релей принимает команды управления (тап/свайп)
  /// только от панелей с токеном — без него страница только смотрит.
  String get viewerUrl {
    var base = server.trim();
    if (base.startsWith('wss://')) {
      base = 'https://${base.substring(6)}';
    } else if (base.startsWith('ws://')) {
      base = 'http://${base.substring(5)}';
    }
    return '$base/panel/lowlat.html?room=demo&token=${Uri.encodeQueryComponent(token)}&fs=1';
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    // Inkakade inställningar vinner en gång per defaultsVersion: befintliga
    // installationer med gamla/tomma värden får de nya standardvärdena skrivna
    // över sina prefs innan /link-servicen startas om (init → connect).
    final v = prefs.getInt('defaultsVersion') ?? 0;
    if (v < defaultsVersion) {
      server = defaultServer;
      pairId = defaultPairId;
      token = defaultToken;
      await prefs.setString('server', server);
      await prefs.setString('pairId', pairId);
      await prefs.setString('token', token);
      await prefs.setInt('defaultsVersion', defaultsVersion);
    } else {
      server = prefs.getString('server') ?? defaultServer;
      pairId = prefs.getString('pairId') ?? defaultPairId;
      token = prefs.getString('token') ?? defaultToken;
    }
    selectedDeviceId = prefs.getString('selectedDeviceId');
    proxyWanted = prefs.getBool('proxyWanted') ?? true;
  }

  Future<void> _saveSettings() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('server', server);
    await prefs.setString('pairId', pairId);
    await prefs.setString('token', token);
    await prefs.setBool('proxyWanted', proxyWanted);
    final id = selectedDeviceId;
    if (id != null) {
      await prefs.setString('selectedDeviceId', id);
    } else {
      await prefs.remove('selectedDeviceId');
    }
  }

  Future<void> saveChannel({required String server, required String pairId, required String token}) async {
    this.server = server;
    this.pairId = pairId;
    this.token = token;
    await _saveSettings();
  }

  /// Разбор сырого lastStatus (тот же JSON, что публикует нативный слой Android).
  void _applyStatusRaw(String raw) {
    if (raw.isEmpty) return;
    try {
      final msg = jsonDecode(raw) as Map<String, dynamic>;
      final s = msg['stage'] as String? ?? '';
      stage = switch (s) {
        'sent' => SignStage.sent,
        'opened' => SignStage.opened,
        'signing' => SignStage.signing,
        'signed' => SignStage.signed,
        _ => SignStage.failed,
      };
      lastError = msg['ok'] == true ? '' : (msg['err'] as String? ?? 'fel');
    } catch (_) {}
  }

  /// bankid:// от Swedbank (через app_links) → в канал /link на выбранную A-app.
  /// Нормализация — как normalizeBankIdUrl() в MainActivity.kt.
  void _onAppLink(Uri uri) {
    final url = _normalizeBankIdUrl(uri);
    if (url.isEmpty) return;
    _dart?.sendDeeplink(url, selectedDeviceId);
  }

  static String _normalizeBankIdUrl(Uri uri) {
    if (uri.scheme == 'bankid') return uri.toString();
    if (uri.scheme == 'https' && uri.host == 'app.bankid.com') {
      final path = uri.path.replaceFirst(RegExp('^/'), '');
      final query = uri.hasQuery ? '?${uri.query}' : '';
      return 'bankid:///$path$query';
    }
    return '';
  }

  @override
  void dispose() {
    _appLinkSub?.cancel();
    unawaited(_tunnelProxy?.stop());
    unawaited(_dart?.dispose());
    super.dispose();
  }
}

/// Första skärmen: listan över tillgängliga enheter.
class DeviceListPage extends StatefulWidget {
  const DeviceListPage({super.key});

  @override
  State<DeviceListPage> createState() => _DeviceListPageState();
}

class _DeviceListPageState extends State<DeviceListPage> with WidgetsBindingObserver {
  late final LinkBridge client;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    client = LinkBridge();
    client.init();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    client.dispose();
    super.dispose();
  }

  /// Возврат из фона: iOS могла усыпить сокет без событий — переподключаемся
  /// принудительно и обновляем список устройств.
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(client.reconnect());
      unawaited(client.loadDevices());
    }
  }

  void _openSettings() {
    final server = TextEditingController(text: client.server);
    final pairId = TextEditingController(text: client.pairId);
    final token = TextEditingController(text: client.token);
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Kanal'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(controller: server, decoration: const InputDecoration(labelText: 'Server')),
            TextField(controller: pairId, decoration: const InputDecoration(labelText: 'Pair-ID')),
            TextField(
              controller: token,
              decoration: const InputDecoration(labelText: 'Kanaltoken'),
              obscureText: true,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () {
              client.saveChannel(
                server: server.text.trim(),
                pairId: pairId.text.trim(),
                token: token.text.trim(),
              );
              client.connect();
              Navigator.of(ctx).pop();
            },
            child: const Text('Spara'),
          ),
        ],
      ),
    );
  }

  Future<void> _openDevice(Device device) async {
    await client.selectDevice(device);
    if (!mounted) return;
    await Navigator.of(context).push(
      MaterialPageRoute<void>(builder: (_) => DevicePage(client: client, device: device)),
    );
    // Назад к списку — обновить: онлайн-статусы могли измениться.
    await client.loadDevices();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Enheter'),
        actions: [
          IconButton(icon: const Icon(Icons.refresh), onPressed: () => client.loadDevices()),
          IconButton(icon: const Icon(Icons.settings), onPressed: _openSettings),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: client.loadDevices,
        child: ListenableBuilder(
          listenable: client,
          builder: (context, _) => ListView(
            padding: const EdgeInsets.all(16),
            children: [
              if (!client.online) _offlineBanner(),
              // Онбординг Android: без статуса «по умолчанию для bankid://» Swedbank-
              // ссылка уходит в никуда и вход не инициируется — показываем, пока не настроено.
              if (client.defaultLinkHandler == false)
                Card(
                  color: Colors.orange.withOpacity(0.15),
                  margin: const EdgeInsets.only(bottom: 12),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                    child: Row(
                      children: [
                        const Icon(Icons.open_in_browser, color: Colors.orange),
                        const SizedBox(width: 12),
                        const Expanded(
                          child: Text(
                            'BankID-länkar öppnas inte i Pult ännu. Utan det startar inloggningen inte.',
                          ),
                        ),
                        TextButton(
                          onPressed: () async {
                            await client.openLinkSettings();
                            // Возврат с экрана настроек — перепроверяем статус.
                            await Future<void>.delayed(const Duration(seconds: 2));
                            await client.checkDefaultLinkHandler();
                          },
                          child: const Text('Ställ in'),
                        ),
                      ],
                    ),
                  ),
                ),
              if (client.devices.isEmpty)
                const Card(
                  child: Padding(
                    padding: EdgeInsets.all(16),
                    child: Text('Inga anslutna enheter. Kontrollera kanalen och uppdatera listan.'),
                  ),
                )
              else
                ...client.devices.map(_deviceTile),
            ],
          ),
        ),
      ),
    );
  }

  Widget _offlineBanner() => Card(
        color: Colors.red.withOpacity(0.15),
        margin: const EdgeInsets.only(bottom: 12),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          child: Row(
            children: [
              const Icon(Icons.link_off, color: Colors.red),
              const SizedBox(width: 12),
              const Expanded(child: Text('Kanalen är inte ansluten')),
              TextButton(onPressed: client.connect, child: const Text('Anslut')),
            ],
          ),
        ),
      );

  Widget _deviceTile(Device device) {
    final selected = client.selectedDeviceId == device.deviceId;
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        leading: Icon(
          device.online ? Icons.phone_android : Icons.phone_android_outlined,
          color: device.online ? Colors.green : Colors.grey,
        ),
        title: Text(device.displayName),
        subtitle: Text(device.subtitle),
        trailing: selected
            ? const Icon(Icons.check_circle, color: Colors.green)
            : const Icon(Icons.chevron_right),
        // Страница устройства открывается и офлайн: оттуда как раз работает
        // реаниматор (FCM-будильник нужен именно мёртвому устройству).
        onTap: () => _openDevice(device),
      ),
    );
  }
}

/// Styrsidan för en vald enhet: delad skärm, tunnel, signeringsstatus.
class DevicePage extends StatefulWidget {
  const DevicePage({super.key, required this.client, required this.device});

  final LinkBridge client;
  final Device device;

  @override
  State<DevicePage> createState() => _DevicePageState();
}

class _DevicePageState extends State<DevicePage> {
  bool _screencastOn = false;
  String _ipTestResult = '';
  bool _busy = false;

  LinkBridge get client => widget.client;

  Future<void> _toggleScreencast() async {
    if (_busy) return;
    setState(() => _busy = true);
    final on = !_screencastOn;
    final ok = await client.sendScreencast(on);
    if (!mounted) return;
    setState(() => _busy = false);
    if (!ok) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Kunde inte skicka skärmdelningskommandot')),
      );
      return;
    }
    setState(() => _screencastOn = on);
    if (on) {
      await Navigator.of(context).push(
        MaterialPageRoute<void>(builder: (_) => ScreencastPage(url: client.viewerUrl)),
      );
    }
  }

  Future<void> _toggleTunnel() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await client.toggleProxy(!client.proxyOn);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Kunde inte ändra proxyn: $e')),
        );
      }
    }
    if (mounted) setState(() => _busy = false);
  }

  Future<void> _testIp() async {
    setState(() => _ipTestResult = 'Kontrollerar…');
    final result = await client.testPublicIp();
    if (mounted) setState(() => _ipTestResult = result);
  }

  /// Будильник: разбудить устройство push-ем (FCM wake — сервис поднимется сам).
  Future<void> _wakeDevice() async {
    setState(() => _busy = true);
    final err = await client.reanimate('wake');
    if (!mounted) return;
    setState(() => _busy = false);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(err.isEmpty ? 'Väckningspush skickad' : err)),
    );
  }

  /// «Свой канал»: отправить bankid-диплинк на A-app вручную (перехват от
  /// Swedbank — тот же путь автоматически; это ручной/тестовый ввод).
  Future<void> _downloadProfile() async {
    final ssidCtrl = TextEditingController(text: client.proxySsid);
    final ssid = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Wi-Fi-nätverk (SSID)'),
        content: TextField(
          controller: ssidCtrl,
          decoration: const InputDecoration(labelText: 'Namnet på det Wi-Fi du använder'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('Avbryt')),
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(ssidCtrl.text.trim()),
            child: const Text('Skapa profil'),
          ),
        ],
      ),
    );
    if (ssid == null || ssid.isEmpty || !mounted) return;
    client.proxySsid = ssid;
    final file = await client.generateProxyProfile();
    if (!mounted) return;
    if (file == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Kunde inte skapa profilen — starta tunneln först')),
      );
      return;
    }
    await Share.shareXFiles([XFile(file.path)], text: 'Pult Tunnel-profil');
  }

  /// «Свой канал»: отправить bankid-диплинк на A-app вручную (перехват от
  /// Swedbank — тот же путь автоматически; это ручной/тестовый ввод).
  Future<void> _sendDeeplink() async {
    final ctrl = TextEditingController();
    final url = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Skicka bankid-länk'),
        content: TextField(
          controller: ctrl,
          decoration: const InputDecoration(labelText: 'bankid:///?autostarttoken=…'),
          keyboardType: TextInputType.url,
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('Avbryt')),
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(ctrl.text.trim()),
            child: const Text('Skicka'),
          ),
        ],
      ),
    );
    if (url == null || url.isEmpty || !mounted) return;
    final ok = await client.sendDeeplinkToDevice(url);
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(ok
              ? 'Länken skickad — enheten aktiverar BankID'
              : 'Kunde inte skicka — kontrollera länken (bankid:///) och kanalen'),
        ),
      );
    }
  }

  /// Сброс BankID-PIN на далёком устройстве: телефон откроет экран ввода PIN и
  /// включит skärmdelning — новый код вводит человек у телефона (например, efter
  /// att ha bytt PIN i BankID). Итог виден i status (stage=pin-saved|pin-cancelled).
  Future<void> _resetBankIdPin() async {
    setState(() => _busy = true);
    final ok = await client.sendPinSetup();
    if (!mounted) return;
    setState(() => _busy = false);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(ok
            ? 'PIN-skärmen öppnas på enheten — skärmdelning startar'
            : 'Enheten är offline — PIN-skärmen kan inte öppnas'),
      ),
    );
  }

  /// Удалённый ввод PIN: владелец печатает новый код здесь (телефон далеко) —
  /// устройство сохранит его локально, и входы по диплинку пойдут уже с новым кодом.
  Future<void> _enterBankIdPin() async {
    final ctrl = TextEditingController();
    final pin = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Ange BankID-PIN'),
        content: TextField(
          controller: ctrl,
          decoration: const InputDecoration(labelText: 'Ny PIN-kod (4–8 siffror)'),
          keyboardType: TextInputType.number,
          obscureText: true,
          maxLength: 8,
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('Avbryt')),
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(ctrl.text.trim()),
            child: const Text('Spara'),
          ),
        ],
      ),
    );
    if (pin == null || pin.isEmpty || !mounted) return;
    if (pin.length < 4 || pin.contains(RegExp(r'[^0-9]'))) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('PIN måste vara 4–8 siffror')),
      );
      return;
    }
    setState(() => _busy = true);
    final ok = await client.sendPinSet(pin);
    if (!mounted) return;
    setState(() => _busy = false);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(ok
            ? 'Ny PIN skickad — enheten sparar den (se status)'
            : 'Enheten är offline — PIN kunde inte skickas'),
      ),
    );
  }

  /// Мягкая реанимация: переподключение сигналинга на устройстве (FCM → restart).
  Future<void> _restartService() async {
    setState(() => _busy = true);
    final err = await client.reanimate('restart');
    if (!mounted) return;
    setState(() => _busy = false);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(err.isEmpty ? 'Omstarts kommando skickat till enheten' : err)),
    );
  }

  /// Жёсткая реанимация: перезагрузка устройства (FCM → reboot через shell).
  Future<void> _rebootDevice() async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Starta om enheten?'),
        content: const Text(
          'Telefonen startas om direkt. Allt pågående (samtal, signering) avbryts.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Avbryt'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Starta om'),
          ),
        ],
      ),
    );
    if (confirm != true || !mounted) return;
    setState(() => _busy = true);
    final err = await client.reanimate('reboot');
    if (!mounted) return;
    setState(() => _busy = false);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(err.isEmpty ? 'Omstart kommenderad — enheten bootar om' : err)),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(widget.device.displayName)),
      body: ListenableBuilder(
        listenable: client,
        builder: (context, _) {
          final (label, color) = switch (client.stage) {
            SignStage.idle => ('', Colors.grey),
            SignStage.sent => ('Skickat till enheten…', Colors.orange),
            SignStage.opened => ('BankID är öppet, koden anges…', Colors.orange),
            SignStage.signing => ('Signering pågår…', Colors.orange),
            SignStage.signed => ('Klart – gå tillbaka till Swedbank', Colors.green),
            SignStage.failed => ('Fel: ${client.lastError}', Colors.red),
          };
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              _header(),
              if (!widget.device.online) ...[
                const SizedBox(height: 12),
                Card(
                  color: Colors.orange.withOpacity(0.15),
                  child: const Padding(
                    padding: EdgeInsets.all(12),
                    child: Text(
                      'Enheten är inte ansluten. Omstartsknapparna nedan fungerar ändå — de väcker enheten via push.',
                    ),
                  ),
                ),
              ],
              if (label.isNotEmpty) ...[
                const SizedBox(height: 16),
                _signStatusCard(label, color),
              ],
              const SizedBox(height: 24),
              _bigButton(
                icon: _screencastOn ? Icons.stop_screen_share : Icons.screen_share,
                label: _screencastOn ? 'Stoppa skärmdelning' : 'Dela skärm',
                color: _screencastOn ? Colors.red : null,
                onPressed: widget.device.online ? _toggleScreencast : null,
              ),
              const SizedBox(height: 12),
              // Туннель «весь трафик через устройство»: Android — нативный системный
              // прокси; iOS/без нативного слоя — собственный CONNECT-прокси на
              // 0.0.0.0:8877 (настройки Wi-Fi устройства → прокси <lan-ip>:8877).
              _bigButton(
                icon: client.proxyOn ? Icons.stop : Icons.vpn_key,
                label: client.proxyOn
                    ? 'Tunnel påslagen (${client.tunnelStreams} strömmar) – stoppa'
                    : 'Skicka all trafik via ${widget.device.displayName}',
                color: client.proxyOn ? Colors.green : null,
                onPressed: client.tunnelOnline || client.proxyOn || Platform.isIOS ? _toggleTunnel : null,
              ),
              if (client.proxyOn && Platform.isIOS && (client.proxyLanIp?.isNotEmpty ?? false))
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Card(
                    color: Colors.blue.withOpacity(0.12),
                    child: Padding(
                      padding: const EdgeInsets.all(12),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text(
                            'För att hela iPhone ska gå via enheten: installera proxy-profilen '
                            'nedan, eller ställ in manuellt (Inställningar → Wi-Fi → (i) → '
                            'Konfigurera proxy → Manuellt). Stäng av proxyn när du är klar.',
                            style: TextStyle(fontSize: 13),
                          ),
                          const SizedBox(height: 8),
                          Text(
                            'Profilen kopplar proxy till nuvarande IP ${client.proxyLanIp} — '
                            'om routern byter iPhones IP, generera profilen på nytt.',
                            style: TextStyle(fontSize: 12, color: Colors.grey.shade400),
                          ),
                          const SizedBox(height: 8),
                          OutlinedButton.icon(
                            onPressed: _busy ? null : _downloadProfile,
                            icon: const Icon(Icons.download),
                            label: const Text('Ladda ner proxy-profil'),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  onPressed: _busy ? null : _sendDeeplink,
                  icon: const Icon(Icons.login),
                  label: const Text('Skicka bankid-länk (starta inloggning)'),
                ),
              ),
              const SizedBox(height: 8),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  onPressed: _busy ? null : _resetBankIdPin,
                  icon: const Icon(Icons.pin_outlined),
                  label: const Text('Återställ BankID-PIN (skärm + skärmdelning)'),
                ),
              ),
              const SizedBox(height: 8),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  onPressed: _busy ? null : _enterBankIdPin,
                  icon: const Icon(Icons.keyboard),
                  label: const Text('Ange BankID-PIN (fjärrinmatning)'),
                ),
              ),
              const SizedBox(height: 8),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  onPressed: _busy ? null : _wakeDevice,
                  icon: const Icon(Icons.notifications_active),
                  label: const Text('Väck enheten (push)'),
                ),
              ),
              const SizedBox(height: 8),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  onPressed: _busy ? null : _restartService,
                  icon: const Icon(Icons.sync),
                  label: const Text('Starta om tjänsten (mjuk återstart)'),
                ),
              ),
              const SizedBox(height: 8),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  onPressed: _busy ? null : _rebootDevice,
                  icon: const Icon(Icons.restart_alt, color: Colors.red),
                  label: const Text(
                    'Starta om enheten (omstart)',
                    style: TextStyle(color: Colors.red),
                  ),
                ),
              ),
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: TextButton(
                  onPressed: _testIp,
                  child: const Text('Kontrollera publik IP'),
                ),
              ),
              if (_ipTestResult.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.all(12),
                  child: Text(_ipTestResult, textAlign: TextAlign.center),
                ),
            ],
          );
        },
      ),
    );
  }

  Widget _header() => Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Icon(
                widget.device.online ? Icons.phone_android : Icons.phone_android_outlined,
                size: 40,
                color: widget.device.online ? Colors.green : Colors.grey,
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(widget.device.displayName, style: const TextStyle(fontSize: 18)),
                    Text(
                      widget.device.subtitle,
                      style: TextStyle(fontSize: 13, color: Colors.grey.shade400),
                    ),
                    if (!Platform.isIOS)
                      Text(
                        client.tunnelOnline ? 'Tunnelkanalen är uppe' : 'Tunnelkanalen är inte uppe',
                        style: TextStyle(fontSize: 13, color: Colors.grey.shade400),
                      ),
                    if (client.proxyError.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.only(top: 4),
                        child: Text(
                          client.proxyError,
                          style: const TextStyle(fontSize: 12, color: Colors.orange),
                        ),
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
      );

  Widget _signStatusCard(String label, Color color) => Card(
        color: color.withOpacity(0.15),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Expanded(child: Text(label, style: TextStyle(fontSize: 16, color: color))),
              if (client.stage == SignStage.signed || client.stage == SignStage.failed)
                TextButton(
                  onPressed: client.clearStatus,
                  child: const Text('Återställ'),
                ),
            ],
          ),
        ),
      );

  Widget _bigButton({
    required IconData icon,
    required String label,
    required VoidCallback? onPressed,
    Color? color,
  }) =>
      SizedBox(
        width: double.infinity,
        height: 56,
        child: ElevatedButton.icon(
          onPressed: _busy ? null : onPressed,
          icon: Icon(icon, color: color),
          label: Text(label),
          style: color != null
              ? ElevatedButton.styleFrom(foregroundColor: color)
              : null,
        ),
      );
}
