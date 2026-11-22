// Rings the sharing phone. Get OTP posts { to, m } here: `to` is the pair's Firebase topic and `m`
// the request, sealed with the pair's key, so this worker (and Google) can't read it. The phone
// opens it and rings only if it was sealed by its partner in the last few minutes.
// Setup: docs/PLAY.md. Secret: FIREBASE_SERVICE_ACCOUNT, the service account JSON from Firebase.
export default {
  async fetch(req, env) {
    if (req.method !== 'POST') return new Response('POST only', { status: 405 });
    const { to, m } = await req.json().catch(() => ({}));
    if (!/^w[0-9a-f]{32}$/.test(to ?? '') || typeof m !== 'string' || m.length > 2048) {
      return new Response('bad request', { status: 400 });
    }
    // ponytail: no rate limit. A stranger who guessed a topic could only make a phone check a
    // message it then throws away. Add Cloudflare's rate limiting binding if abuse shows up.
    const account = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT);
    const sent = await fetch(`https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`, {
      method: 'POST',
      headers: { authorization: `Bearer ${await token(account)}`, 'content-type': 'application/json' },
      // High priority wakes a phone in Doze. A request older than a minute is useless, so drop it.
      body: JSON.stringify({ message: { topic: to, data: { m }, android: { priority: 'HIGH', ttl: '60s' } } }),
    });
    return new Response(null, { status: sent.ok ? 204 : 502 });
  },
};

const b64url = bytes => btoa(String.fromCharCode(...bytes)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
const json64 = o => b64url(new TextEncoder().encode(JSON.stringify(o)));
let cached; // Google's access token lasts an hour: reuse it while this worker stays warm.

/** Swaps a JWT signed with the service account's key for a Google access token. */
export async function token(account, now = Date.now()) {
  if (cached?.key === account.private_key_id && cached.until > now) return cached.value;
  const iat = Math.floor(now / 1000);
  const unsigned = json64({ alg: 'RS256', typ: 'JWT' }) + '.' + json64({
    iss: account.client_email, scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token', iat, exp: iat + 3600,
  });
  const der = Uint8Array.from(atob(account.private_key.replace(/-----[^-]+-----|\s/g, '')), c => c.charCodeAt(0));
  const key = await crypto.subtle.importKey('pkcs8', der, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const signature = new Uint8Array(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(unsigned)));
  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${unsigned}.${b64url(signature)}` }),
  });
  if (!res.ok) throw new Error(`token ${res.status}`);
  const { access_token, expires_in } = await res.json();
  cached = { key: account.private_key_id, value: access_token, until: now + (expires_in - 300) * 1000 };
  return access_token;
}
