import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:app_links/app_links.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

import 'screencast.dart';
import 'tunnel.dart';

/// Шлюз BankID-диплинков.
///
/// Swedbank на этом устройстве открывает bankid://…autostarttoken=… — BankID-приложения
/// здесь нет, схему перехватываем мы. Диплинк уходит по WebSocket на сервер (/link),
/// тот ретранслирует его телефону в Швеции: там BankID открывается и вход завершается
/// автоматически (PIN вводится на той стороне, сюда он не приходит). Статусы
/// («opened»/«signed»/«failed») возвращаются тем же сокетом.
void main() => runApp(const GatewayApp());

class GatewayApp extends StatelessWidget {
  const GatewayApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Pult Gateway',
        theme: ThemeData.dark(useMaterial3: true),
        home: const HomePage(),
      );
}

/// Стадии одного проброса диплинка.
enum SignStage { idle, sent, opened, signing, signed, failed }

class LinkClient {
  LinkClient({required this.onChanged});

  static const defaultServer = 'wss://89-127-235-17.sslip.io';
  static const defaultPairId = 'demo-pair-000000000000';

  String server = defaultServer;
  String pairId = defaultPairId;
  String token = '';

  bool online = false;
  SignStage stage = SignStage.idle;
  String lastError = '';
  String lastUrl = '';

  final VoidCallback onChanged;
  WebSocketChannel? _ws;
  Timer? _reconnect;
  int _backoffMs = 1000;
  bool _stopped = false;

  void connect() {
    _stopped = false;
    _reconnect?.cancel();
    _open();
  }

  void dispose() {
    _stopped = true;
    _reconnect?.cancel();
    _ws?.sink.close();
  }

  void _open() {
    if (_stopped) return;
    if (token.isEmpty) {
      _setError('нет токена канала');
      _scheduleReconnect();
      return;
    }
    final uri = Uri.parse('$server/link?pairId=${Uri.encodeComponent(pairId)}'
        '&token=${Uri.encodeComponent(token)}');
    final ws = WebSocketChannel.connect(uri);
    _ws = ws;
    ws.ready.then((_) {
      _backoffMs = 1000;
      online = true;
      lastError = '';
      onChanged();
    }).catchError((Object e) {
      _setError('connect: $e');
      _scheduleReconnect();
    });
    ws.stream.listen(
      (data) => _onMessage(data as String),
      onDone: () {
        online = false;
        onChanged();
        if (!_stopped) _scheduleReconnect();
      },
      onError: (Object e) => _setError('ws: $e'),
    );
  }

  void _scheduleReconnect() {
    if (_stopped) return;
    online = false;
    onChanged();
    _reconnect?.cancel();
    _reconnect = Timer(Duration(milliseconds: _backoffMs), _open);
    _backoffMs = (_backoffMs * 2).clamp(1000, 15000);
  }

  void _setError(String err) {
    lastError = err;
    onChanged();
  }

  /// Перехваченный диплинк → на сервер. true, если удалось отправить в сокет.
  bool sendDeeplink(String url) {
    if (!online || _ws == null) {
      _setError('канал офлайн — диплинк не отправлен');
      return false;
    }
    lastUrl = url;
    lastError = '';
    stage = SignStage.sent;
    onChanged();
    _ws!.sink.add(jsonEncode({'t': 'deeplink', 'url': url}));
    return true;
  }

  /// Пуск/стоп показа экрана телефона (lowlat). Ответ — screencast-ack.
  bool sendScreencast(bool on) {
    if (!online || _ws == null) return false;
    _ws!.sink.add(jsonEncode({'t': 'screencast', 'on': on}));
    return true;
  }

  void _onMessage(String raw) {
    Map<String, dynamic> msg;
    try {
      msg = jsonDecode(raw) as Map<String, dynamic>;
    } catch (_) {
      return;
    }
    switch (msg['t']) {
      case 'deeplink-ack':
        if (msg['delivered'] != true) {
          stage = SignStage.failed;
          lastError = 'телефон в Швеции офлайн';
        }
      case 'deeplink-status':
        final s = msg['stage'] as String? ?? '';
        stage = switch (s) {
          'opened' => SignStage.opened,
          'signing' => SignStage.signing,
          'signed' => SignStage.signed,
          _ => SignStage.failed,
        };
        // Ошибку показываем только для текущей попытки: на успехе и на новом
        // диплинке старый текст стираем.
        if (msg['ok'] == true) {
          lastError = '';
        } else {
          lastError = msg['err'] as String? ?? 'ошибка';
        }
      default:
        break;
    }
    onChanged();
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  late final LinkClient client;
  late final TunnelProxy tunnel;
  late final AppLinks _appLinks;
  StreamSubscription<Uri>? _linkSub;
  bool _busy = false;
  String? _pendingUrl; // диплинк, пришедший до поднятия канала (холодный старт)
  String _ipTestResult = '';
  bool _ipTestRunning = false;
  bool _proxyOn = false;
  static const _control = MethodChannel('pult.gateway/control');

  @override
  void initState() {
    super.initState();
    client = LinkClient(onChanged: () {
      // Терминальный статус освобождает дорогу следующему диплинку сразу,
      // не дожидаясь страховочного таймера.
      if (client.stage == SignStage.signed || client.stage == SignStage.failed) _busy = false;
      // Канал поднялся — смываем отложенный диплинк.
      if (client.online && _pendingUrl != null) {
        final url = _pendingUrl!;
        _pendingUrl = null;
        _send(url);
      }
      if (mounted) setState(() {});
    });
    tunnel = TunnelProxy(onChanged: () {
      if (mounted) setState(() {});
    });
    _loadSettings().then((_) {
      client.connect();
      tunnel.start(server: client.server, pairId: client.pairId, token: client.token);
      _refreshProxyState();
    });

    // Перехват bankid:// — и холодный старт по ссылке, и тёплый.
    _appLinks = AppLinks();
    _appLinks.getInitialLink().then((uri) {
      if (uri != null) _onDeeplink(uri);
    });
    _linkSub = _appLinks.uriLinkStream.listen(_onDeeplink);
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    client.server = prefs.getString('server') ?? LinkClient.defaultServer;
    client.pairId = prefs.getString('pairId') ?? LinkClient.defaultPairId;
    client.token = prefs.getString('token') ?? '';
  }

  Future<void> _saveSettings() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('server', client.server);
    await prefs.setString('pairId', client.pairId);
    await prefs.setString('token', client.token);
  }

  /// Swedbank открывает BankID ссылкой https://app.bankid.com/?autostarttoken=…
  /// (а не bankid://). Нормализуем её в bankid:/// — телефон в Швеции принимает
  /// только эту схему (валидация на стороне PultService).
  String? normalizeDeeplink(Uri uri) {
    if (uri.scheme == 'bankid') return uri.toString();
    if (uri.scheme == 'https' && uri.host == 'app.bankid.com') {
      // path: '', '/c', '/a' — варианты запуска BankID; у bankid:/// три слэша.
      final path = uri.path.startsWith('/') ? uri.path.substring(1) : uri.path;
      final query = uri.query.isEmpty ? '' : '?${uri.query}';
      return 'bankid:///$path$query';
    }
    return null;
  }

  void _send(String url) {
    _busy = true;
    final sent = client.sendDeeplink(url);
    debugPrint('[gateway] sent=$sent url=$url');
    // Страховка: если статус не придёт вовсе — разблокировать через 90 с.
    Timer(const Duration(seconds: 90), () => _busy = false);
  }

  void _onDeeplink(Uri uri) {
    debugPrint('[gateway] deeplink in: $uri');
    final url = normalizeDeeplink(uri);
    if (url == null) {
      debugPrint('[gateway] not a bankid link, ignored');
      return;
    }
    if (_busy) {
      debugPrint('[gateway] busy, skipped');
      return;
    }
    // Канал ещё не поднялся (холодный старт по диплинку) — ждём подключения.
    if (!client.online) {
      debugPrint('[gateway] offline, queued');
      _pendingUrl = url;
      return;
    }
    _send(url);
  }

  @override
  void dispose() {
    _linkSub?.cancel();
    client.dispose();
    tunnel.stop();
    super.dispose();
  }

  /// Текущее состояние глобального прокси (могли поменять снаружи).
  Future<void> _refreshProxyState() async {
    try {
      final value = await _control.invokeMethod<String>('getProxy');
      if (mounted) setState(() => _proxyOn = value != null && value.contains('8877'));
    } catch (_) {
      // WRITE_SECURE_SETTINGS ещё не выдан — тумблер покажет ошибку при попытке.
    }
  }

  Future<void> _toggleProxy(bool on) async {
    try {
      await _control.invokeMethod<bool>('setProxy', {'on': on, 'port': tunnel.port});
      setState(() => _proxyOn = on);
    } catch (e) {
      setState(() => _ipTestResult = 'прокси не переключён: $e');
    }
  }

  /// Показ экрана телефона: просим устройство начать lowlat-трансляцию и
  /// открываем страницу просмотра. При выходе трансляцию гасим.
  Future<void> _openScreencast() async {
    client.sendScreencast(true);
    final url = client.server
        .replaceFirst('wss://', 'https://')
        .replaceFirst('ws://', 'http://');
    await Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => ScreencastPage(url: '$url/panel/lowlat.html'),
    ));
    client.sendScreencast(false);
  }

  /// Самотест: запрос через туннель — вернуть должен IP телефона в Швеции, не этого.
  Future<void> _testTunnelIp() async {
    if (_ipTestRunning) return;
    setState(() {
      _ipTestRunning = true;
      _ipTestResult = 'Проверяю…';
    });
    Socket? socket;
    try {
      socket = await Socket.connect(InternetAddress.loopbackIPv4, tunnel.port,
          timeout: const Duration(seconds: 8));
      final buf = StringBuffer();
      final done = Completer<void>();
      var getSent = false;
      socket.listen(
        (chunk) {
          buf.write(utf8.decode(chunk, allowMalformed: true));
          // Как только прокси ответил 200 — шлём HTTP-запрос к цели.
          if (!getSent && buf.toString().contains('200 Connection established')) {
            getSent = true;
            socket!.write('GET / HTTP/1.1\r\nHost: api.ipify.org\r\nConnection: close\r\n\r\n');
          }
        },
        onDone: done.complete,
        onError: done.completeError,
      );
      socket.write('CONNECT api.ipify.org:80 HTTP/1.1\r\n\r\n');
      await done.future.timeout(const Duration(seconds: 20), onTimeout: () {});
      final text = buf.toString();
      // IP из тела ответа цели (строки прокси-200 адресов не содержат).
      final ip = RegExp(r'(\d{1,3}\.){3}\d{1,3}').allMatches(text).lastOrNull?.group(0);
      setState(() => _ipTestResult = ip != null
          ? 'IP выхода: $ip'
          : 'нет ответа за 20 с: ${text.substring(0, text.length.clamp(0, 120))}');
    } catch (e) {
      setState(() => _ipTestResult = 'ошибка: $e');
    } finally {
      socket?.destroy();
      setState(() => _ipTestRunning = false);
    }
  }

  void _openSettings() {
    final server = TextEditingController(text: client.server);
    final pairId = TextEditingController(text: client.pairId);
    final token = TextEditingController(text: client.token);
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Канал'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(controller: server, decoration: const InputDecoration(labelText: 'Сервер')),
            TextField(controller: pairId, decoration: const InputDecoration(labelText: 'pairId')),
            TextField(
              controller: token,
              decoration: const InputDecoration(labelText: 'Токен канала'),
              obscureText: true,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () {
              client.server = server.text.trim();
              client.pairId = pairId.text.trim();
              client.token = token.text.trim();
              _saveSettings();
              client.connect();
              tunnel.start(server: client.server, pairId: client.pairId, token: client.token);
              Navigator.of(ctx).pop();
            },
            child: const Text('Сохранить'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final (label, color) = switch (client.stage) {
      SignStage.idle => ('Жду диплинк от Swedbank', Colors.grey),
      SignStage.sent => ('Отправлено на телефон…', Colors.orange),
      SignStage.opened => ('BankID открыт, ввод кода…', Colors.orange),
      SignStage.signing => ('Подписание…', Colors.orange),
      SignStage.signed => ('Готово — вернитесь в Swedbank', Colors.green),
      SignStage.failed => ('Ошибка: ${client.lastError}', Colors.red),
    };
    return Scaffold(
      appBar: AppBar(
        title: const Text('Pult Gateway'),
        actions: [IconButton(icon: const Icon(Icons.settings), onPressed: _openSettings)],
      ),
      body: Center(
        child: SingleChildScrollView(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
            Icon(
              client.online ? Icons.link : Icons.link_off,
              size: 64,
              color: client.online ? Colors.green : Colors.red,
            ),
            const SizedBox(height: 12),
            Text(
              client.online ? 'Канал к телефону: онлайн' : 'Канал офлайн',
              style: const TextStyle(fontSize: 18),
            ),
            if (client.lastError.isNotEmpty && client.stage != SignStage.failed)
              Padding(
                padding: const EdgeInsets.all(8),
                child: Text(client.lastError, style: const TextStyle(color: Colors.redAccent)),
              ),
            const SizedBox(height: 36),
            Text(label, style: TextStyle(fontSize: 22, color: color), textAlign: TextAlign.center),
            if (client.stage == SignStage.signed || client.stage == SignStage.failed)
              TextButton(
                onPressed: () => setState(() {
                  client.stage = SignStage.idle;
                  _busy = false;
                }),
                child: const Text('Сбросить'),
              ),
            const SizedBox(height: 28),
            const Divider(),
            // Туннель: весь трафик банковских приложений идёт с IP телефона в Швеции.
            Text(
              tunnel.online
                  ? 'Туннель онлайн · 127.0.0.1:${tunnel.port} · потоков: ${tunnel.activeStreams}'
                  : 'Туннель офлайн',
              style: TextStyle(fontSize: 15, color: tunnel.online ? Colors.green : Colors.red),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24),
              child: SwitchListTile(
                title: const Text('Весь трафик через телефон', style: TextStyle(fontSize: 15)),
                subtitle: const Text('глобальный прокси 127.0.0.1:8877', style: TextStyle(fontSize: 12)),
                value: _proxyOn,
                onChanged: tunnel.online ? _toggleProxy : null,
              ),
            ),
            TextButton(
              onPressed: tunnel.online && !_ipTestRunning ? _testTunnelIp : null,
              child: const Text('Проверить IP через туннель'),
            ),
            if (_ipTestResult.isNotEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 24),
                child: Text(_ipTestResult, textAlign: TextAlign.center),
              ),
            const SizedBox(height: 12),
            ElevatedButton.icon(
              onPressed: client.online ? _openScreencast : null,
              icon: const Icon(Icons.smartphone),
              label: const Text('Показать экран телефона'),
            ),
          ],
        ),
      ),
    ),
    );
  }
}
