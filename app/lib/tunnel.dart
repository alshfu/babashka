import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

/// Локальный HTTP CONNECT-прокси, гоняющий TCP через телефон в Швеции.
///
/// Система/приложение настраивается на прокси 127.0.0.1:8877; каждый CONNECT
/// становится потоком в одном WebSocket к серверу (/tunnel), тот релеит кадры
/// телефону, а он открывает TCP к цели — трафик выходит с его (шведского) IP.
///
/// Кадр: [streamId:uint32 BE][op:uint8][payload]
/// op: 0=open ("host:port"), 1=data, 2=close, 3=error.
class TunnelProxy {
  TunnelProxy({required this.onChanged});

  static const opOpen = 0;
  static const opData = 1;
  static const opClose = 2;
  static const opError = 3;

  final void Function() onChanged;

  bool online = false; // WS к серверу поднят
  String lastError = '';
  int activeStreams = 0;

  WebSocketChannel? _ws;
  ServerSocket? _server;
  Timer? _reconnect;
  int _backoffMs = 1000;
  bool _stopped = false;
  int _nextStreamId = 1;
  String _url = '';

  final Map<int, Socket> _clients = {};

  Future<void> start({
    required String server,
    required String pairId,
    required String token,
    int port = 8877,
  }) async {
    _stopped = false;
    _url = '$server/tunnel?pairId=${Uri.encodeComponent(pairId)}'
        '&token=${Uri.encodeComponent(token)}&side=app';
    _server ??= await ServerSocket.bind(InternetAddress.loopbackIPv4, port);
    _server!.listen(_onClient);
    _reconnect?.cancel();
    _open();
  }

  Future<void> stop() async {
    _stopped = true;
    _reconnect?.cancel();
    await _ws?.sink.close();
    for (final s in _clients.values.toList()) {
      s.destroy();
    }
    _clients.clear();
    online = false;
    onChanged();
  }

  int get port => _server?.port ?? 0;

  // ── WS к серверу ────────────────────────────────────────────────────────────

  void _open() {
    if (_stopped) return;
    final ws = WebSocketChannel.connect(Uri.parse(_url));
    _ws = ws;
    ws.ready.then((_) {
      _backoffMs = 1000;
      online = true;
      lastError = '';
      onChanged();
    }).catchError((Object e) {
      _fail('tunnel connect: $e');
    });
    ws.stream.listen(
      (data) => _onFrame(data as Uint8List),
      onDone: () => _fail('tunnel ws closed'),
      onError: (Object e) => _fail('tunnel ws: $e'),
    );
  }

  void _fail(String err) {
    debugPrint('[tunnel] fail: $err (streams=${_clients.length})');
    lastError = err;
    online = false;
    // Все потоки мертвы: сервер закрыл peer-сокет, кадры некуда слать.
    for (final s in _clients.values.toList()) {
      s.destroy();
    }
    _clients.clear();
    activeStreams = 0;
    onChanged();
    if (_stopped) return;
    _reconnect?.cancel();
    _reconnect = Timer(Duration(milliseconds: _backoffMs), _open);
    _backoffMs = (_backoffMs * 2).clamp(1000, 15000);
  }

  void _send(int streamId, int op, List<int> payload) {
    final ws = _ws;
    if (ws == null || !online) return;
    final frame = ByteData(5 + payload.length);
    frame.setUint32(0, streamId);
    frame.setUint8(4, op);
    frame.buffer.asUint8List().setRange(5, 5 + payload.length, payload);
    ws.sink.add(frame.buffer.asUint8List());
  }

  void _onFrame(Uint8List frame) {
    if (frame.length < 5) return;
    final data = ByteData.sublistView(frame);
    final streamId = data.getUint32(0);
    final op = data.getUint8(4);
    final payload = frame.sublist(5);
    final client = _clients[streamId];
    switch (op) {
      case opData:
        client?.add(payload);
      case opClose || opError:
        if (op == opError) {
          debugPrint('[tunnel] stream $streamId error: ${utf8.decode(payload, allowMalformed: true)}');
        } else {
          debugPrint('[tunnel] stream $streamId close');
        }
        if (client != null) {
          _clients.remove(streamId);
          activeStreams = _clients.length;
          // Гraceful close: сначала flush (отдать накопленный ответ клиенту),
          // потом FIN. destroy() тут же шлёт RST и стирает недочитанные данные.
          unawaited(client.flush().whenComplete(() => client.close()));
          onChanged();
        }
    }
  }

  // ── Локальный CONNECT-прокси ────────────────────────────────────────────────

  void _onClient(Socket client) {
    // ОДИН слушатель на сокет (Socket.listen — single-subscription, повторный
    // вызов бросает StateError): сначала копим заголовок CONNECT, потом
    // переключаемся в режим проксирования кадров.
    var headerDone = false;
    final buffer = <int>[];
    int? streamId;

    void closeStream() {
      final id = streamId;
      streamId = null;
      if (id != null && _clients.remove(id) != null) {
        activeStreams = _clients.length;
        _send(id, opClose, const []);
        onChanged();
      }
    }

    client.listen(
      (chunk) {
        if (!headerDone) {
          buffer.addAll(chunk);
          final end = _findHeaderEnd(buffer);
          if (end < 0) return; // ждём конец заголовков
          headerDone = true;
          streamId = _beginStream(client, buffer, end);
        } else {
          final id = streamId;
          if (id != null) _send(id, opData, chunk);
        }
      },
      onError: (_) { closeStream(); client.destroy(); },
      onDone: () { closeStream(); client.destroy(); },
      cancelOnError: true,
    );
  }

  int _findHeaderEnd(List<int> buf) {
    for (var i = 0; i + 3 < buf.length; i++) {
      if (buf[i] == 13 && buf[i + 1] == 10 && buf[i + 2] == 13 && buf[i + 3] == 10) {
        return i + 4;
      }
    }
    return -1;
  }

  /// Разбор CONNECT, регистрация потока и ответ 200. null — отказ (уже отвечено).
  int? _beginStream(Socket client, List<int> header, int headerEnd) {
    final request = utf8.decode(header.sublist(0, headerEnd), allowMalformed: true);
    final line = request.split('\r\n').first;
    final match = RegExp(r'^CONNECT ([^:\s]+):(\d+) HTTP').firstMatch(line);
    if (match == null || !online) {
      // Не CONNECT или канал не поднят — честно отказываем.
      client.write('HTTP/1.1 ${online ? "405" : "503"}\r\nContent-Length: 0\r\n\r\n');
      client.close();
      return null;
    }
    final target = '${match[1]}:${match[2]}';
    final streamId = _nextStreamId++;
    _clients[streamId] = client;
    activeStreams = _clients.length;
    onChanged();

    _send(streamId, opOpen, utf8.encode(target));
    // Оптимистичное 200: если телефон не достучится до цели, придёт opError
    // и мы закроем сокет — TLS-хендшейк клиента просто оборвётся.
    client.write('HTTP/1.1 200 Connection established\r\n\r\n');
    // Байты, приехавшие вместе с заголовком (пайплайнинг), не теряем.
    final leftover = header.sublist(headerEnd);
    if (leftover.isNotEmpty) _send(streamId, opData, leftover);
    return streamId;
  }
}
