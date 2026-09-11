// Встроенный CONNECT-прокси B-app: LAN-порт устройства → шведский IP телефона A
// через /tunnel (тот же протокол, что у scripts/tunnel-gateway.mjs).
//
// Зачем: «туннель между iPhone и A» — на iOS нет системного прокси и Network
// Extension заблокирован до починки Apple ID. Рабочий путь Apple-совместимый:
// прокси слушает на ВСЕХ интерфейсах (0.0.0.0:8877), а в настройках Wi-Fi iPhone
// вручную задаётся HTTP-прокси = собственный IP этого iPhone:8877. Тогда Safari/
// Swedbank/другие приложения выходят с IP телефона A. Android-путь с нативным
// глобальным прокси (TunnelProxyNative) остаётся без изменений.
//
// Протокол /tunnel: кадр = [streamId:u32 BE][op:u8][payload]; op 0=open "host:port",
// 1=data, 2=close, 3=error. Open-ack НЕТ — ответ CONNECT клиенту шлём сразу после
// op=open; если phone не подключила поток, op=0 повторяем каждые 3 с (сервер молча
// теряет кадры, если app пришёл раньше phone), после 6 попыток — обрыв.

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:web_socket_channel/web_socket_channel.dart';

class TunnelProxyService {
  ServerSocket? _server;
  final Set<Socket> _clients = {};
  bool get active => _server != null;

  String _serverUrl = '';
  String _pairId = '';
  String _token = '';

  Future<void> start(String server, String pairId, String token) async {
    if (_server != null) return;
    _serverUrl = server;
    _pairId = pairId;
    _token = token;
    final serverSocket = await ServerSocket.bind(InternetAddress.anyIPv4, 8877, shared: true);
    _server = serverSocket;
    serverSocket.listen(_handleClient);
  }

  Future<void> stop() async {
    final s = _server;
    _server = null;
    await s?.close();
    for (final c in _clients.toList()) {
      c.destroy();
    }
    _clients.clear();
  }

  /// IP устройства в LAN — для подсказки «какой прокси вписать в настройки Wi-Fi».
  static Future<String?> lanIp() async {
    try {
      for (final iface in await NetworkInterface.list(type: InternetAddressType.IPv4)) {
        for (final addr in iface.addresses) {
          if (!addr.isLoopback && addr.rawAddress[0] != 169 /* 169.254.x link-local */) {
            return addr.address;
          }
        }
      }
    } catch (_) {}
    return null;
  }

  void _handleClient(Socket client) {
    _clients.add(client);
    var closed = false;
    WebSocketChannel? ws;
    StreamSubscription? wsSub;
    var streamId = 0;
    Timer? openRetry;
    Timer? stallTimer;
    var gotPhoneFrame = false;

    void close() {
      if (closed) return;
      closed = true;
      openRetry?.cancel();
      stallTimer?.cancel();
      wsSub?.cancel();
      try { ws?.sink.close(); } catch (_) {}
      client.destroy();
      _clients.remove(client);
    }

    Uint8List frame(int op, List<int> payload) {
      final f = Uint8List(5 + payload.length);
      final b = ByteData.sublistView(f);
      b.setUint32(0, streamId, Endian.big);
      f[4] = op;
      f.setRange(5, f.length, payload);
      return f;
    }

    void sendData(Uint8List chunk) {
      try {
        ws?.sink.add(frame(1, chunk));
      } catch (_) {
        close();
      }
    }

    void openTunnel(String target) {
      final url = '$_serverUrl/tunnel?pairId=${Uri.encodeQueryComponent(_pairId)}'
          '&token=${Uri.encodeQueryComponent(_token)}&side=app';
      streamId = DateTime.now().millisecondsSinceEpoch & 0x7fffffff;
      ws = WebSocketChannel.connect(Uri.parse(url));
      wsSub = ws!.stream.listen(
        (data) {
          if (closed || data is! List<int> || data.length < 5) return;
          gotPhoneFrame = true;
          openRetry?.cancel();
          stallTimer?.cancel();
          final op = data[4];
          if (op == 1) {
            try {
              client.add(data.sublist(5));
            } catch (_) {
              close();
            }
          } else if (op == 2 || op == 3) {
            close();
          }
        },
        onError: (_) => close(),
        onDone: close,
      );
      // Готовность канала: connect() возвращается до хендшейка — первый кадр
      // попадает во внутреннюю очередь сокета, сервер его отшлёт по открытии.
      ws!.sink.add(frame(0, utf8.encode(target)));
      // Ответ CONNECT клиенту — сразу (open-ack нет; отложенный ответ = дедлок).
      try {
        client.write('HTTP/1.1 200 Connection Established\r\n\r\n');
      } catch (_) {
        close();
        return;
      }
      // Гонка «app раньше phone»: сервер теряет op=0 молча — повторяем до первого
      // кадра от phone.
      var retries = 0;
      openRetry = Timer.periodic(const Duration(seconds: 3), (t) {
        if (closed || gotPhoneFrame) return;
        if (++retries > 6) {
          close();
          return;
        }
        try {
          ws?.sink.add(frame(0, utf8.encode(target)));
        } catch (_) {
          close();
        }
      });
      // Подвисание без единого кадра (phone мертв/сеть мертва) — обрыв по таймауту.
      stallTimer = Timer(const Duration(seconds: 75), () {
        if (!closed && !gotPhoneFrame) close();
      });
    }

    var handshake = Uint8List(0);
    var phase = 'connect-line'; // connect-line → headers → pipe
    var target = '';

    client.listen(
      (chunk) {
        if (closed) return;
        handshake = Uint8List.fromList([...handshake, ...chunk]);
        if (phase == 'connect-line') {
          final eol = _indexOfEol(handshake);
          if (eol < 0) return;
          final line = utf8.decode(handshake.sublist(0, eol));
          handshake = handshake.sublist(eol + 2);
          final parts = line.split(' ');
          if (parts.length < 2 || parts[0].toUpperCase() != 'CONNECT') {
            client.write('HTTP/1.1 405 Method Not Allowed\r\n\r\n');
            close();
            return;
          }
          target = parts[1];
          phase = 'headers';
        }
        if (phase == 'headers') {
          final hdrEnd = _indexOfHeadersEnd(handshake);
          if (hdrEnd < 0) return;
          handshake = handshake.sublist(hdrEnd + 4);
          phase = 'pipe';
          openTunnel(target);
        }
        if (phase == 'pipe' && handshake.isNotEmpty) {
          final data = handshake;
          handshake = Uint8List(0);
          sendData(data);
        }
      },
      onError: (_) => close(),
      onDone: close,
      cancelOnError: true,
    );
  }

  static int _indexOfEol(Uint8List buf) {
    for (var i = 0; i + 1 < buf.length; i++) {
      if (buf[i] == 13 && buf[i + 1] == 10) return i;
    }
    return -1;
  }

  static int _indexOfHeadersEnd(Uint8List buf) {
    for (var i = 0; i + 3 < buf.length; i++) {
      if (buf[i] == 13 && buf[i + 1] == 10 && buf[i + 2] == 13 && buf[i + 3] == 10) return i;
    }
    return -1;
  }
}
