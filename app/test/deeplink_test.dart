import 'package:flutter_test/flutter_test.dart';

/// Парсинг перехваченного диплинка — та же логика, что в _onDeeplink.
bool isBankIdDeeplink(Uri uri) => uri.scheme == 'bankid';

void main() {
  test('bankid-диплинк распознаётся', () {
    final uri = Uri.parse(
        'bankid:///?autostarttoken=7c4f2a50-9b0d-4f2b-9f3d-2f8a1b2c3d4e&redirect=null');
    expect(isBankIdDeeplink(uri), isTrue);
    expect(uri.queryParameters['autostarttoken'],
        '7c4f2a50-9b0d-4f2b-9f3d-2f8a1b2c3d4e');
  });

  test('чужие схемы отбрасываются', () {
    expect(isBankIdDeeplink(Uri.parse('https://example.com/x')), isFalse);
    expect(isBankIdDeeplink(Uri.parse('swedbank://login')), isFalse);
  });
}
