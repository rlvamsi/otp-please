// Plays the partner phone: the same pairing, key derivation and sealing as Core.kt, written independently.
//   node partner.mjs "otpair2:…" pair              scan the QR text; prints the link (otpair1:…) and pair number
//   node partner.mjs "otpair1:…" want              ask the phone for a code
//   node partner.mjs "otpair1:…" asking            as the sharing phone: "the code is here, waiting for a yes"
//   node partner.mjs "otpair1:…" no                as the sharing phone: "not sent"
//   node partner.mjs "otpair1:…" otp 482913 HDFCBK "OTP is 482913"   as the sharing phone: send the code
//   node partner.mjs "otpair1:…" read 60s          show what is on the relay
//   node partner.mjs "otpair1:…" sealwant          print a sealed "want", for DebugWake (see src/debug)
//   node partner.mjs "otpair1:…" wake https://…    ask through the Firebase worker (worker/), like Get OTP does
import crypto from 'node:crypto';
const [text, cmd, ...rest] = process.argv.slice(2);
const b64 = b => Buffer.from(b).toString('base64url');
const hmacWith = (k, d) => crypto.createHmac('sha256', k).update(d).digest();

function link(secret) {
  const topic = 'otpair-' + hmacWith(secret, 'topic').subarray(0, 16).toString('hex'), key = hmacWith(secret, 'key');
  const check = String(hmacWith(secret, 'check').readUInt16BE(0) % 10000).padStart(4, '0');
  const seal = p => { const n = crypto.randomBytes(12), c = crypto.createCipheriv('aes-256-gcm', key, n);
    return Buffer.concat([n, c.update(p, 'utf8'), c.final(), c.getAuthTag()]).toString('base64url'); };
  const open = s => { const b = Buffer.from(s, 'base64url'), d = crypto.createDecipheriv('aes-256-gcm', key, b.subarray(0, 12));
    d.setAuthTag(b.subarray(b.length - 16)); return Buffer.concat([d.update(b.subarray(12, b.length - 16)), d.final()]).toString(); };
  // type, from, time written, code, sender, body
  const post = (type, code = '', sender = '', body = '', at = Date.now()) =>
    fetch(`https://ntfy.sh/${topic}`, { method: 'POST', body: seal([type, 'node-partner', at, code, sender, body].join('\n')), headers: { Firebase: 'no' } })
      .then(r => console.log('posted', type, r.status));
  const read = async since => { const t = await (await fetch(`https://ntfy.sh/${topic}/json?poll=1&since=${since}`)).text();
    return t.trim().split('\n').filter(Boolean).map(l => JSON.parse(l).message); };
  const push = 'w' + hmacWith(secret, 'push').subarray(0, 16).toString('hex');
  const want = () => seal(['want', 'node-partner', Date.now(), '', '', ''].join('\n'));
  return { secret, check, post, open, read, push, want };
}

if (cmd === 'pair') {
  const shower = Buffer.from(text.replace('otpair2:', ''), 'base64url'); // the other phone's public key
  const me = crypto.createECDH('prime256v1'); const mine = me.generateKeys();
  const meet = link(crypto.createHash('sha256').update(Buffer.concat([Buffer.from('otpair-meet'), shower])).digest());
  const final = link(hmacWith(me.computeSecret(shower), Buffer.concat([Buffer.from('otpair-link'), shower, mine])));
  await meet.post('hello', b64(mine));
  console.log('LINK otpair1:' + b64(final.secret)); console.log('PAIR NUMBER', final.check);
} else {
  const l = link(Buffer.from(text.replace('otpair1:', ''), 'base64url'));
  if (['want', 'asking', 'no'].includes(cmd)) await l.post(cmd); // ask for a code; or, as the sharing phone, say it is ringing / refused
  if (cmd === 'otp') await l.post('otp', rest[0], rest[1], rest[2], rest[3] ? Date.now() - Number(rest[3]) * 1000 : Date.now()); // 4th: seconds old
  if (cmd === 'sealwant') console.log(l.want());
  if (cmd === 'wake') { const r = await fetch(rest[0], { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ to: l.push, m: l.want() }) }); console.log('wake', r.status, await r.text()); }
  if (cmd === 'read') for (const m of await l.read(rest[0] || '60s')) { console.log('RAW ON RELAY:', m.slice(0, 40) + '…'); console.log('DECRYPTED:', JSON.stringify(l.open(m))); }
}
