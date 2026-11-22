// node worker/wake.test.mjs — checks the worker against a fake Google, with a throwaway key.
import assert from 'node:assert';
import crypto from 'node:crypto';
const { privateKey, publicKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const account = { project_id: 'demo', client_email: 'wake@demo.iam', private_key_id: 'k1',
  private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }) };
const calls = [];
globalThis.fetch = async (url, init) => {
  calls.push({ url, init });
  if (url.includes('oauth2')) {
    const jwt = new URLSearchParams(init.body).get('assertion').split('.');
    assert(crypto.verify('sha256', Buffer.from(jwt[0] + '.' + jwt[1]), publicKey, Buffer.from(jwt[2], 'base64url')), 'JWT signature');
    assert.equal(JSON.parse(Buffer.from(jwt[1], 'base64url')).iss, 'wake@demo.iam');
    return Response.json({ access_token: 'tok', expires_in: 3600 });
  }
  return new Response('{}', { status: 200 });
};
const { default: worker } = await import('./wake.js');
const env = { FIREBASE_SERVICE_ACCOUNT: JSON.stringify(account) };
const post = body => worker.fetch(new Request('https://w/', { method: 'POST', body: JSON.stringify(body) }), env);
const to = 'w' + 'a'.repeat(32);

assert.equal((await post({ to, m: 'sealed' })).status, 204);
const send = calls.find(c => c.url.includes('fcm.googleapis.com/v1/projects/demo/messages:send'));
assert.equal(send.init.headers.authorization, 'Bearer tok');
assert.deepEqual(JSON.parse(send.init.body).message, { topic: to, data: { m: 'sealed' }, android: { priority: 'HIGH', ttl: '60s' } });
await post({ to, m: 'again' });
assert.equal(calls.filter(c => c.url.includes('oauth2')).length, 1, 'token reused');
assert.equal((await post({ to: 'otpair-abc', m: 'x' })).status, 400);
assert.equal((await post({ to, m: 'x'.repeat(3000) })).status, 400);
assert.equal((await worker.fetch(new Request('https://w/'), env)).status, 405);
console.log('worker ok');
