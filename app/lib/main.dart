import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'screencast.dart';

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
  // Senaste felet från setProxy (t.ex. saknad WRITE_SECURE_SETTINGS) —
  // visas på enhetssidan så man ser varför tunneln inte går.
  String proxyError = '';

  List<Device> devices = [];

  Future<void> init() async {
    _ch.setMethodCallHandler((call) async {
      if (call.method == 'changed') await refresh();
    });
    await _loadSettings();
    await refresh();
    await loadDevices();
    unawaited(connect());
    await refreshProxyState();
    // Забытый включённый прокси без живого туннеля — снять, иначе сеть мертва.
    if (proxyOn && !tunnelOnline) await _setProxy(false);
  }

  Future<void> connect() async {
    try {
      await _ch.invokeMethod<void>('reloadLink');
      await refresh();
    } catch (_) {}
  }

  Future<void> refresh() async {
    try {
      final state = await _ch.invokeMethod<Map<dynamic, dynamic>>('getState');
      online = state?['online'] == true;
      tunnelOnline = state?['tunnelOnline'] == true;
      tunnelStreams = (state?['tunnelStreams'] as int?) ?? 0;
      final raw = (state?['lastStatus'] as String?) ?? '';
      if (raw.isNotEmpty) {
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
      }
    } catch (_) {}
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
    try {
      await _ch.invokeMethod<void>('clearStatus');
    } catch (_) {}
    stage = SignStage.idle;
    lastError = '';
    notifyListeners();
  }

  Future<void> loadDevices() async {
    try {
      final raw = await _control.invokeMethod<String>('getDevices');
      final list = jsonDecode(raw ?? '[]') as List<dynamic>;
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
    try {
      await _ch.invokeMethod<void>('sendScreencast', on);
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
    try {
      final value = await _control.invokeMethod<String>('getProxy');
      proxyOn = value != null && value.contains('$tunnelPort');
    } catch (_) {}
  }

  /// Publik IP — via aktuell systemproxy, dvs. med tunnel ON är det den
  /// valda A-app-enhetens adress.
  Future<String> testPublicIp() async {
    try {
      final request = await HttpClient()
          .getUrl(Uri.parse('https://api.ipify.org?format=json'))
          .timeout(const Duration(seconds: 10));
      final response = await request.close();
      final body = await response.transform(utf8.decoder).join();
      final ip = (jsonDecode(body) as Map<String, dynamic>)['ip'] as String? ?? '?';
      return 'Publik IP: $ip';
    } catch (e) {
      return 'Det gick inte att kontrollera IP: $e';
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
    return '$base/panel/lowlat.html?room=demo&token=${Uri.encodeQueryComponent(token)}';
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
}

/// Första skärmen: listan över tillgängliga enheter.
class DeviceListPage extends StatefulWidget {
  const DeviceListPage({super.key});

  @override
  State<DeviceListPage> createState() => _DeviceListPageState();
}

class _DeviceListPageState extends State<DeviceListPage> {
  late final LinkBridge client;

  @override
  void initState() {
    super.initState();
    client = LinkBridge();
    client.init();
  }

  @override
  void dispose() {
    client.dispose();
    super.dispose();
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
        onTap: device.online ? () => _openDevice(device) : null,
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
              if (label.isNotEmpty) ...[
                const SizedBox(height: 16),
                _signStatusCard(label, color),
              ],
              const SizedBox(height: 24),
              _bigButton(
                icon: _screencastOn ? Icons.stop_screen_share : Icons.screen_share,
                label: _screencastOn ? 'Stoppa skärmdelning' : 'Dela skärm',
                color: _screencastOn ? Colors.red : null,
                onPressed: _toggleScreencast,
              ),
              const SizedBox(height: 12),
              _bigButton(
                icon: client.proxyOn ? Icons.stop : Icons.vpn_key,
                label: client.proxyOn
                    ? 'Tunnel påslagen (${client.tunnelStreams} strömmar) – stoppa'
                    : 'Skicka all trafik via ${widget.device.displayName}',
                color: client.proxyOn ? Colors.green : null,
                onPressed: client.tunnelOnline || client.proxyOn ? _toggleTunnel : null,
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
