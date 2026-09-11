import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

/// Показ экрана телефона (lowlat: H.264 по WebSocket → WebCodecs) внутри приложения.
/// Страница /panel/lowlat.html сама шлёт тапы по тому же сокету — это полный пульт,
/// а не только просмотр.
///
/// Полноэкранный режим: ни статус-бар, ни системная навигация не съедают площадь,
/// экран не гаснет (wakelock). Тачи НЕ перехватываются поверх WebView — они идут
/// в lowlat-страницу как команды управления; выход — только кнопкой «закрыть».
/// Альбомная ориентация не форсируется: экран телефона вертикальный, вертикал
/// рядом с клавиатурой/документами удобнее, решение — за пользователем.
class ScreencastPage extends StatefulWidget {
  const ScreencastPage({super.key, required this.url});

  final String url;

  @override
  State<ScreencastPage> createState() => _ScreencastPageState();
}

class _ScreencastPageState extends State<ScreencastPage> {
  late final WebViewController controller;

  @override
  void initState() {
    super.initState();
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
    unawaited(WakelockPlus.enable());
    controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..loadRequest(Uri.parse(widget.url));
  }

  @override
  void dispose() {
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    unawaited(WakelockPlus.disable());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        backgroundColor: Colors.black,
        body: Stack(
          children: [
            Positioned.fill(child: WebViewWidget(controller: controller)),
            // Маленькая полупрозрачная кнопка выхода — не мешает управлению.
            Positioned(
              top: 8,
              left: 8,
              child: SafeArea(
                child: Container(
                  decoration: BoxDecoration(
                    color: Colors.black.withOpacity(0.45),
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: IconButton(
                    icon: const Icon(Icons.close, color: Colors.white),
                    tooltip: 'Stäng',
                    onPressed: () => Navigator.of(context).maybePop(),
                  ),
                ),
              ),
            ),
          ],
        ),
      );
}
