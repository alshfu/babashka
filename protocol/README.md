# protocol/

Машиночитаемая часть протокола — общая для всех клиентов. Текстовое описание:
[`docs/protocol.md`](../docs/protocol.md).

| Файл | Что это |
|---|---|
| `test-vectors.json` | Векторы для крипто пары: транскрипт, MAC обеих сторон, токен журнала, нормализация отпечатка DTLS |
| `messages.schema.json` | JSON Schema сообщений сигналинга и управляющего канала |

## Тест-векторы

Их прогоняют **три** реализации, и все три обязаны сойтись:

| Реализация | Где тест |
|---|---|
| Node (сервер, эталон) | `server/test/vectors.test.js` |
| Web Crypto (веб-панель) | `web/test/vectors.test.js` |
| Kotlin (Android) | `android/core/src/test/java/ru/pult/core/PairAuthTest.kt` |

Расхождение означает, что телефон бабушки и панель внука не смогут подтвердить пару —
то есть помощь не поднимется вообще. Поэтому векторы меняются только вместе с
[`docs/protocol.md`](../docs/protocol.md) §4 и записью в [`docs/decisions.md`](../docs/decisions.md).

Пересоздать (нужно, только если менялся сам алгоритм):

```bash
node protocol/gen-vectors.mjs
```

## Что проверяют векторы

- транскрипт `pult/v1/auth ‖ sessionId ‖ nonceH ‖ nonceG ‖ fpH ‖ fpG`, разделитель — `\n`;
- `macG` считается с префиксом `grandma\n`, `macH` — с `helper\n` (роли не взаимозаменяемы);
- нормализацию отпечатка DTLS: алгоритм в нижнем регистре, hex — в верхнем;
- `journalToken = HMAC(pairSecret, "pult/v1/journal")`;
- что подмена **любой** части транскрипта ломает MAC — это и есть защита от MITM
  на сигналинге.
