import http from 'node:http';
import { createHash, randomUUID, timingSafeEqual } from 'node:crypto';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { cert, getApps, initializeApp } from 'firebase-admin/app';
import { getMessaging } from 'firebase-admin/messaging';

const PORT = Number(process.env.AGENT_PULSE_PORT || 8787);
const HOST = process.env.AGENT_PULSE_HOST || '127.0.0.1';
const TOKEN = process.env.AGENT_PULSE_TOKEN || '';
const DATA_FILE = resolve(process.env.AGENT_PULSE_DATA_FILE || './data/state.json');
const SERVICE_ACCOUNT_FILE = process.env.FIREBASE_SERVICE_ACCOUNT || '';
const FIREBASE_CONFIG_FILE = process.env.FIREBASE_CLIENT_CONFIG || '';
const MAX_EVENTS = 500;

if (TOKEN.length < 24) throw new Error('AGENT_PULSE_TOKEN must contain at least 24 characters');
if (!SERVICE_ACCOUNT_FILE) throw new Error('FIREBASE_SERVICE_ACCOUNT is required');
if (!FIREBASE_CONFIG_FILE) throw new Error('FIREBASE_CLIENT_CONFIG is required');

const [serviceAccount, clientConfigRaw] = await Promise.all([
  readJson(SERVICE_ACCOUNT_FILE),
  readJson(FIREBASE_CONFIG_FILE),
]);
const firebase = normalizeFirebaseClientConfig(clientConfigRaw);
if (!getApps().length) initializeApp({ credential: cert(serviceAccount) });
const messaging = getMessaging();
const state = await loadState();
let writeQueue = Promise.resolve();

const server = http.createServer(async (req, res) => {
  try {
    setSecurityHeaders(res);
    if (req.method === 'GET' && req.url === '/health') {
      return json(res, 200, { ok: true, devices: state.devices.length, events: state.events.length });
    }
    if (!authorized(req)) return json(res, 401, { error: 'Invalid pairing token' });

    const url = new URL(req.url || '/', 'http://localhost');
    if (req.method === 'GET' && url.pathname === '/v1/config') {
      return json(res, 200, { firebase });
    }
    if (req.method === 'POST' && url.pathname === '/v1/devices') {
      const body = await readBody(req);
      if (typeof body.fcmToken !== 'string' || body.fcmToken.length < 20) {
        return json(res, 400, { error: 'A valid fcmToken is required' });
      }
      const device = {
        fcmToken: body.fcmToken,
        platform: String(body.platform || 'android'),
        appVersion: String(body.appVersion || ''),
        updatedAt: Date.now(),
      };
      state.devices = state.devices.filter((item) => item.fcmToken !== device.fcmToken);
      state.devices.push(device);
      await saveState();
      return json(res, 200, { ok: true });
    }
    if (req.method === 'GET' && url.pathname === '/v1/events') {
      const since = Number(url.searchParams.get('since') || 0);
      const events = state.events.filter((event) => event.timestamp >= since).slice(-100);
      return json(res, 200, { events });
    }
    if (req.method === 'POST' && url.pathname === '/v1/events') {
      const event = normalizeEvent(await readBody(req));
      await storeAndPush(event);
      return json(res, 202, { ok: true, id: event.id });
    }
    if (req.method === 'POST' && url.pathname === '/v1/test') {
      const event = normalizeEvent({
        type: 'completed',
        provider: 'agent-pulse',
        title: 'Push delivery is working',
        message: 'Your relay reached this Android device through Firebase Cloud Messaging.',
        project: 'setup',
      });
      await storeAndPush(event);
      return json(res, 202, { ok: true, id: event.id });
    }
    return json(res, 404, { error: 'Not found' });
  } catch (error) {
    console.error(new Date().toISOString(), error);
    return json(res, 500, { error: error.message || 'Internal error' });
  }
});

server.listen(PORT, HOST, () => {
  console.log(`Agent Pulse relay listening on http://${HOST}:${PORT}`);
  console.log(`Registered devices: ${state.devices.length}`);
});

async function storeAndPush(event) {
  state.events.push(event);
  state.events = state.events.slice(-MAX_EVENTS);
  await saveState();
  if (!state.devices.length) return;

  const response = await messaging.sendEachForMulticast({
    tokens: state.devices.map((device) => device.fcmToken),
    data: Object.fromEntries(Object.entries(event).map(([key, value]) => [key, String(value)])),
    android: {
      priority: 'high',
      ttl: 24 * 60 * 60 * 1000,
    },
  });

  const invalid = new Set();
  response.responses.forEach((item, index) => {
    if (!item.success && isInvalidTokenError(item.error?.code)) {
      invalid.add(state.devices[index].fcmToken);
    }
  });
  if (invalid.size) {
    state.devices = state.devices.filter((device) => !invalid.has(device.fcmToken));
    await saveState();
  }
  if (response.failureCount) {
    console.warn(`FCM: ${response.successCount} delivered, ${response.failureCount} failed`);
  }
}

function normalizeEvent(input) {
  const allowed = new Set(['approval', 'question', 'completed', 'error']);
  const type = allowed.has(input.type) ? input.type : 'completed';
  const defaults = {
    approval: 'Permission required',
    question: 'Agent is waiting for you',
    completed: 'Task completed',
    error: 'Agent run failed',
  };
  return {
    id: String(input.id || randomUUID()),
    type,
    provider: clip(input.provider || 'agent', 30),
    title: clip(input.title || defaults[type], 100),
    message: clip(input.message || defaults[type], 1500),
    project: clip(input.project || '', 100),
    timestamp: Number(input.timestamp) || Date.now(),
  };
}

function normalizeFirebaseClientConfig(raw) {
  if (raw.project_info && Array.isArray(raw.client)) {
    const androidClient = raw.client.find(
      (item) => item.client_info?.android_client_info?.package_name === 'dev.agentpulse.app'
    );
    const apiKey = androidClient?.api_key?.[0]?.current_key;
    if (!androidClient?.client_info?.mobilesdk_app_id || !apiKey) {
      throw new Error('Firebase client config has no dev.agentpulse.app Android client');
    }
    return {
      appId: androidClient?.client_info?.mobilesdk_app_id,
      apiKey,
      projectId: raw.project_info.project_id,
      senderId: raw.project_info.project_number,
    };
  }
  return {
    appId: raw.appId,
    apiKey: raw.apiKey,
    projectId: raw.projectId,
    senderId: raw.senderId || raw.messagingSenderId,
  };
}

function authorized(req) {
  const candidate = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  const expected = Buffer.from(createHash('sha256').update(TOKEN).digest('hex'));
  const actual = Buffer.from(createHash('sha256').update(candidate).digest('hex'));
  return timingSafeEqual(expected, actual);
}

function isInvalidTokenError(code = '') {
  return code.includes('registration-token-not-registered') || code.includes('invalid-registration-token');
}

function clip(value, max) {
  return String(value).replace(/[\u0000-\u001f]/g, ' ').trim().slice(0, max);
}

async function readBody(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 64 * 1024) throw new Error('Request body is too large');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
}

async function readJson(path) {
  return JSON.parse(await readFile(resolve(path), 'utf8'));
}

async function loadState() {
  try {
    const parsed = await readJson(DATA_FILE);
    return {
      devices: Array.isArray(parsed.devices) ? parsed.devices : [],
      events: Array.isArray(parsed.events) ? parsed.events : [],
    };
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    return { devices: [], events: [] };
  }
}

function saveState() {
  writeQueue = writeQueue.then(async () => {
    await mkdir(dirname(DATA_FILE), { recursive: true });
    const temp = `${DATA_FILE}.tmp`;
    await writeFile(temp, JSON.stringify(state, null, 2), { mode: 0o600 });
    await rename(temp, DATA_FILE);
  });
  return writeQueue;
}

function setSecurityHeaders(res) {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'no-referrer');
}

function json(res, status, body) {
  if (res.headersSent) return;
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
}
