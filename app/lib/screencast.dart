import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

/// Показ экрана телефона (lowlat: H.264 по WebSocket → WebCodecs) внутри приложения.
/// Страница /panel/lowlat.html сама шлёт тапы по тому же сокету — это полный пульт,
/// а не только просмотр.
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
    controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..loadRequest(Uri.parse(widget.url));
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Skärmsändning')),
        body: WebViewWidget(controller: controller),
      );
}
