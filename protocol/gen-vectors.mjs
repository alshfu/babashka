import { createHmac } from 'node:crypto';
import { writeFileSync } from 'node:fs';

const b64u = (b) => Buffer.from(b).toString('base64url');
const mac = (secret, msg) => createHmac('sha256', Buffer.from(secret, 'base64url')).update(msg, 'utf8').digest('base64url');
const norm = (alg, hex) => `${alg.trim().toLowerCase()} ${hex.trim().toUpperCase()}`;

const secret = b64u(Buffer.from('0123456789abcdef0123456789abcdef', 'utf8')); // 32 байта, фиксированные
const cases = [
  { sessionId: 'sess-AAAAAAAAAAAAAAAA', nonceH: 'bm9uY2VILTAwMDAwMDAw', nonceG: 'bm9uY2VHLTAwMDAwMDAw',
    fpAlgH: 'SHA-256', fpHexH: 'aa:bb:cc:dd:ee:ff:00:11', fpAlgG: 'sha-256', fpHexG: '22:33:44:55:66:77:88:99' },
  { sessionId: 'sess-BBBBBBBBBBBBBBBB', nonceH: 'AAAAAAAAAAAAAAAAAAAAAA', nonceG: 'BBBBBBBBBBBBBBBBBBBBBB',
    fpAlgH: 'sha-384', fpHexH: '0a:1b:2c', fpAlgG: 'SHA-384', fpHexG: '3d:4e:5f' },
];

const out = {
  note: 'Общие векторы для Kotlin (android/core), Node (server) и Web Crypto (web/public). Расхождение = клиенты не договорятся.',
  version: 1,
  authContext: 'pult/v1/auth',
  journalContext: 'pult/v1/journal',
  pairSecretBase64url: secret,
  journalToken: mac(secret, 'pult/v1/journal'),
  revokeContext: 'pult/v1/revoke',
  revoke: { pairId: 'pair-REVOKE-0001', nonce: 'cmV2b2tlLW5vbmNlLTAwMDA', mac: mac(secret, `pult/v1/revoke\npair-REVOKE-0001\ncmV2b2tlLW5vbmNlLTAwMDA`) },
  fingerprintNormalization: [
    { algorithm: 'SHA-256', hex: 'aa:bb:cc', expected: norm('SHA-256', 'aa:bb:cc') },
    { algorithm: ' sha-256 ', hex: ' Aa:bB:cC ', expected: norm('sha-256', 'aa:bb:cc') },
  ],
  auth: cases.map((c) => {
    const fpH = norm(c.fpAlgH, c.fpHexH);
    const fpG = norm(c.fpAlgG, c.fpHexG);
    const transcript = ['pult/v1/auth', c.sessionId, c.nonceH, c.nonceG, fpH, fpG].join('\n');
    return { ...c, fpH, fpG, transcript, macG: mac(secret, `grandma\n${transcript}`), macH: mac(secret, `helper\n${transcript}`) };
  }),
};

writeFileSync(new URL('./test-vectors.json', import.meta.url), JSON.stringify(out, null, 2) + '\n');
console.log('готово:', out.auth.length, 'векторов');
