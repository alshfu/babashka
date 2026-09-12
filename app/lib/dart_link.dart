// Dart-реализация канала «B-app ⇄ сервер» (/link + список устройств) для платформ,
// где нет нативного слоя (iOS). На Android сокет живёт в KeepAliveService (нативно,
// независимо от Flutter-движка) — эта реализация туда не подключается.
//
// Семантика зеркалит KeepAliveService.kt + MainActivity.kt: тот же эндпоинт
// $server/link?pairId=…&token=…, те же кадры (t:deeplink / t:screencast), те же
// статусы (deeplink-ack → stage=sent, deeplink-status → как есть), тот же бэкофон
// реконнекта до 15 с и очередь диплинков, посланных до коннекта. Различие:
// у iOS нет фонового сервиса — сокет держит процесс приложения и засыпает вместе
// с ним (ограничение платформы, не протокола).

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:web_socket_channel/web_socket_channel.dart';

/// Статус подписания, как его публикует нативный слой Android:
/// сырая JSON-строка {"stage","ok","err"} + событие «появился новый статус».
class LinkStatusEvent {
  LinkStatusEvent(this.rawJson);
  final String rawJson;
}

class DartLinkService {
  WebSocketChannel? _ws;
  StreamSubscription<dynamic>? _wsSub;
  Timer? _reconnectTimer;

  String _activeEndpoint = '';
  int _backoffMs = 1000;
  bool _disposed = false;

  /// Сырая строка последнего статуса — то, что на Android лежит в prefs lastStatus.
  String lastStatusRaw = '';

  /// Сырая строка последнего setup-status — аналог prefs lastSetupStatus на Android.
  String lastSetupStatusRaw = '';

  /// Очередь диплинков, посланных до коннекта (как pendingUrl у сервиса).
  String? _pendingUrl;
  String? _pendingDeviceId;

  /// События статуса для UI (аналог broadcast ACTION_STATUS → 'changed').
  final _events = StreamController<LinkStatusEvent>.broadcast();
  Stream<LinkStatusEvent> get events => _events.stream;

  /// События setup-status: свежая карта шагов активации от A-app.
  final _setupEvents = StreamController<Map<String, bool>>.broadcast();
  Stream<Map<String, bool>> get setupEvents => _setupEvents.stream;

  bool get online => _ws != null;

  /// Подключить (или перевести на новый эндпоинт) сокет /link. Идемпотентна:
  /// повторный вызов с тем же эндпоинтом ничего не делает.
  Future<void> reload(String server, String pairId, String token) async {
    final endpoint = '$server/link?pairId=$pairId&token=$token';
    if (_activeEndpoint == endpoint && _ws != null) {
      _flushPending();
      return;
    }
    await _closeSocket();
    _activeEndpoint = endpoint;
    await _connect();
  }

  /// Принудительно: закрыть живой (возможно, полумёртвый после сна) сокет
  /// и подключиться заново. Вызывается при возврате приложения в foreground.
  Future<void> forceReload(String server, String pairId, String token) async {
    await _closeSocket();
    _activeEndpoint = '';
    _backoffMs = 1000;
    await reload(server, pairId, token);
  }

  void sendDeeplink(String url, String? deviceId) {
    if (url.startsWith('bankid:///') == false) return;
    if (online) {
      _send(jsonEncode({'t': 'deeplink', 'url': url, if (deviceId != null) 'deviceId': deviceId}));
    } else {
      // Как на Android: держим в очереди и шлём сразу после коннекта.
      _pendingUrl = url;
      _pendingDeviceId = deviceId;
    }
  }

  void sendScreencast(bool on, String? deviceId) {
    if (online) {
      _send(jsonEncode({'t': 'screencast', 'on': on, if (deviceId != null) 'deviceId': deviceId}));
    }
  }

  /// Сброс BankID-PIN на выбранном A-app: телефон покажет экран ввода PIN
  /// (вводит человек у телефона) и включит трансляцию. Итог придёт событием
  /// deeplink-status со stage=pin-saved|pin-cancelled.
  void sendPinSetup(String? deviceId) {
    if (online) {
      _send(jsonEncode({'t': 'pin-setup', if (deviceId != null) 'deviceId': deviceId}));
    }
  }

  /// Открыть экран настройки (шаг активации) на выбранном A-app-устройстве.
  void sendSetupOpen(String step, String? deviceId) {
    if (online) {
      _send(jsonEncode({'t': 'setup-open', 'step': step, if (deviceId != null) 'deviceId': deviceId}));
    }
  }

  /// Запросить свежий статус активации у выбранного A-app-устройства.
  void sendSetupQuery(String? deviceId) {
    if (online) {
      _send(jsonEncode({'t': 'setup-query', if (deviceId != null) 'deviceId': deviceId}));
    }
  }

  void clearStatus() {
    lastStatusRaw = '';
  }

  /// Список A-app устройств — REST, как fetchDevices() у KeepAliveService.
  /// Возвращает JSON-массив (List<dynamic>) или пустой список при ошибке.
  Future<List<dynamic>> fetchDevices(String server, String pairId, String token) async {
    final base = server.startsWith('wss://')
        ? 'https://${server.substring(6)}'
        : server.startsWith('ws://')
            ? 'http://${server.substring(5)}'
            : server;
    final client = HttpClient();
    try {
      final request = await client
          .getUrl(Uri.parse('$base/api/devices?pairId=$pairId'))
          .timeout(const Duration(seconds: 10));
      request.headers.set('x-agent-token', token);
      final response = await request.close().timeout(const Duration(seconds: 10));
      if (response.statusCode != 200) return [];
      final body = await response.transform(utf8.decoder).join();
      return jsonDecode(body) as List<dynamic>;
    } catch (_) {
      return [];
    } finally {
      client.close();
    }
  }

  Future<void> _connect() async {
    if (_disposed || _ws != null) return;
    try {
      final ws = WebSocketChannel.connect(Uri.parse(_activeEndpoint));
      _ws = ws;
      _wsSub = ws.stream.listen(
        _onMessage,
        onError: (_) => _scheduleReconnect(),
        onDone: _scheduleReconnect,
      );
      // Готовность канала: connect() у WebSocketChannel возвращается до хендшейка,
      // поэтому online публикуем после первого открытия потока фактически.
      _backoffMs = 1000;
      _flushPending();
      _events.add(LinkStatusEvent(lastStatusRaw));
    } catch (_) {
      _scheduleReconnect();
    }
  }

  void _onMessage(dynamic data) {
    try {
      final msg = jsonDecode(data as String) as Map<String, dynamic>;
      switch (msg['t']) {
        case 'deeplink-ack':
          final delivered = msg['delivered'] == true;
          _publishStatus(jsonEncode({
            'stage': 'sent',
            'ok': delivered,
            'err': delivered ? '' : 'den valda enheten är offline',
          }));
        case 'deeplink-status':
          _publishStatus(jsonEncode({
            'stage': msg['stage'] ?? '',
            'ok': msg['ok'] == true,
            'err': msg['err'] ?? '',
          }));
        case 'setup-status':
          final steps = msg['steps'];
          if (steps is Map) {
            _publishSetupStatus(jsonEncode({
              'steps': steps.map((k, v) => MapEntry(k.toString(), v == true)),
            }));
          }
      }
    } catch (_) {}
  }

  void _publishStatus(String raw) {
    lastStatusRaw = raw;
    _events.add(LinkStatusEvent(raw));
  }

  void _publishSetupStatus(String raw) {
    lastSetupStatusRaw = raw;
    try {
      final steps = (jsonDecode(raw) as Map<String, dynamic>)['steps'];
      if (steps is Map) {
        _setupEvents.add(steps.map((k, v) => MapEntry(k.toString(), v == true)));
      }
    } catch (_) {}
  }

  void _send(String payload) {
    try {
      _ws?.sink.add(payload);
    } catch (_) {}
  }

  void _flushPending() {
    final url = _pendingUrl;
    if (url == null || !online) return;
    final deviceId = _pendingDeviceId;
    _send(jsonEncode({'t': 'deeplink', 'url': url, if (deviceId != null) 'deviceId': deviceId}));
    _pendingUrl = null;
    _pendingDeviceId = null;
  }

  void _scheduleReconnect() {
    if (_disposed) return;
    _ws = null;
    _events.add(LinkStatusEvent(lastStatusRaw)); // online=false → UI обновится
    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(Duration(milliseconds: _backoffMs), () {
      _reconnectTimer = null;
      if (!_disposed && _ws == null && _activeEndpoint.isNotEmpty) {
        _connect();
      }
    });
    _backoffMs = (_backoffMs * 2).clamp(1000, 15000);
  }

  Future<void> _closeSocket() async {
    _reconnectTimer?.cancel();
    _reconnectTimer = null;
    await _wsSub?.cancel();
    _wsSub = null;
    try {
      _ws?.sink.close();
    } catch (_) {}
    _ws = null;
  }

  Future<void> dispose() async {
    _disposed = true;
    await _closeSocket();
    await _events.close();
    await _setupEvents.close();
  }
}
