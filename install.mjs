#!/usr/bin/env node
import { randomBytes } from 'node:crypto';
import { cp, chmod, mkdir, readFile, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { execFileSync } from 'node:child_process';
import { createInterface } from 'node:readline/promises';
import { stdin as input, stdout as output } from 'node:process';

const args = parseArgs(process.argv.slice(2));
const sourceRoot = dirname(new URL(import.meta.url).pathname);
const home = homedir();
const installDir = join(home, '.local', 'share', 'agent-pulse');
const configDir = join(home, '.config', 'agent-pulse');
const relayDir = join(installDir, 'relay');
const hookDir = join(installDir, 'hooks');
const rl = createInterface({ input, output });

try {
  assertCommand('node');
  assertNodeVersion();
  const relayUrl = args['relay-url'] || await ask('Public HTTPS relay URL (example: https://pulse.example.com)');
  if (!/^https:\/\//.test(relayUrl)) throw new Error('The public relay URL must start with https://');
  const serviceAccountPath = resolve(args['firebase-service-account'] || await ask('Path to Firebase service-account JSON'));
  const clientConfigPath = resolve(args['firebase-client-config'] || await ask('Path to Android google-services.json'));
  await validateJson(serviceAccountPath, ['project_id', 'private_key', 'client_email']);
  await validateGoogleServices(clientConfigPath);
  const token = args.token || randomBytes(32).toString('base64url');

  await mkdir(relayDir, { recursive: true });
  await mkdir(hookDir, { recursive: true });
  await mkdir(configDir, { recursive: true });
  await cp(join(sourceRoot, 'relay'), relayDir, { recursive: true, force: true });
  await cp(join(sourceRoot, 'hooks', 'agent-pulse-hook.mjs'), join(hookDir, 'agent-pulse-hook.mjs'));
  await cp(serviceAccountPath, join(configDir, 'firebase-service-account.json'));
  await cp(clientConfigPath, join(configDir, 'google-services.json'));
  await chmod(join(configDir, 'firebase-service-account.json'), 0o600);
  await chmod(join(configDir, 'google-services.json'), 0o600);

  output.write('Installing relay dependencies...\n');
  execFileSync('npm', ['install', '--omit=dev', '--no-audit', '--no-fund'], {
    cwd: relayDir,
    stdio: 'inherit',
  });

  const envFile = join(configDir, 'relay.env');
  await writeFile(envFile, [
    'AGENT_PULSE_HOST=127.0.0.1',
    `AGENT_PULSE_PORT=${args.port || '8787'}`,
    `AGENT_PULSE_TOKEN=${token}`,
    `AGENT_PULSE_DATA_FILE=${join(installDir, 'data', 'state.json')}`,
    `FIREBASE_SERVICE_ACCOUNT=${join(configDir, 'firebase-service-account.json')}`,
    `FIREBASE_CLIENT_CONFIG=${join(configDir, 'google-services.json')}`,
    '',
  ].join('\n'), { mode: 0o600 });
  await writeFile(join(configDir, 'client.env'), [
    `AGENT_PULSE_RELAY_URL=${relayUrl.replace(/\/$/, '')}`,
    `AGENT_PULSE_TOKEN=${token}`,
    '',
  ].join('\n'), { mode: 0o600 });

  await installCodexHooks(join(hookDir, 'agent-pulse-hook.mjs'));
  await installClaudeHooks(join(hookDir, 'agent-pulse-hook.mjs'));
  const serviceResult = args['no-systemd'] ? 'skipped' : await installSystemd(envFile, relayDir);

  output.write('\nAgent Pulse installed.\n\n');
  output.write(`Relay URL:    ${relayUrl.replace(/\/$/, '')}\n`);
  output.write(`Pairing token: ${token}\n`);
  output.write(`Local port:    ${args.port || '8787'}\n`);
  output.write(`Systemd:       ${serviceResult}\n\n`);
  output.write('Next: point your HTTPS reverse proxy at 127.0.0.1:' + (args.port || '8787') + ', install the APK, and enter the URL/token above.\n');
  output.write('Codex: run /hooks once and trust the new Agent Pulse hooks.\n');
} finally {
  rl.close();
}

async function installCodexHooks(hookPath) {
  const path = join(home, '.codex', 'hooks.json');
  const config = await readJsonOr(path, { hooks: {} });
  config.hooks ||= {};
  mergeHook(config.hooks, 'PermissionRequest', null, hookCommand(hookPath, 'codex', 'approval'));
  mergeHook(config.hooks, 'Stop', null, hookCommand(hookPath, 'codex', 'auto'));
  await writeJson(path, config);
}

async function installClaudeHooks(hookPath) {
  const path = join(home, '.claude', 'settings.json');
  const config = await readJsonOr(path, {});
  config.hooks ||= {};
  mergeHook(config.hooks, 'PermissionRequest', null, hookCommand(hookPath, 'claude', 'approval'));
  mergeHook(config.hooks, 'Notification', 'permission_prompt|idle_prompt', hookCommand(hookPath, 'claude', 'auto'));
  mergeHook(config.hooks, 'Stop', null, hookCommand(hookPath, 'claude', 'auto'));
  await writeJson(path, config);
}

function mergeHook(hooks, event, matcher, command) {
  const groups = Array.isArray(hooks[event]) ? hooks[event] : [];
  for (const group of groups) {
    if (Array.isArray(group.hooks)) {
      group.hooks = group.hooks.filter((hook) => !String(hook.command || '').includes('agent-pulse-hook.mjs'));
    }
  }
  const group = { hooks: [{ type: 'command', command, timeout: 8 }] };
  if (matcher) group.matcher = matcher;
  groups.push(group);
  hooks[event] = groups;
}

function hookCommand(path, provider, type) {
  return `${JSON.stringify(process.execPath)} ${JSON.stringify(path)} ${provider} ${type}`;
}

async function installSystemd(envFile, relayDir) {
  if (process.platform !== 'linux') return 'not available on this OS';
  const unit = `[Unit]\nDescription=Agent Pulse notification relay\nAfter=network-online.target\nWants=network-online.target\n\n[Service]\nType=simple\nWorkingDirectory=${relayDir}\nEnvironmentFile=${envFile}\nExecStart=${process.execPath} ${join(relayDir, 'server.mjs')}\nRestart=on-failure\nRestartSec=3\nNoNewPrivileges=true\nPrivateTmp=true\nProtectSystem=strict\nReadWritePaths=${join(installDir, 'data')}\n\n[Install]\nWantedBy=default.target\n`;
  const userDir = join(home, '.config', 'systemd', 'user');
  await mkdir(userDir, { recursive: true });
  await mkdir(join(installDir, 'data'), { recursive: true });
  await writeFile(join(userDir, 'agent-pulse.service'), unit);
  try {
    execFileSync('systemctl', ['--user', 'daemon-reload'], { stdio: 'ignore' });
    execFileSync('systemctl', ['--user', 'enable', '--now', 'agent-pulse.service'], { stdio: 'ignore' });
    return 'enabled and running (user service)';
  } catch {
    return `unit installed; start manually with systemctl --user enable --now agent-pulse`;
  }
}

async function ask(question) {
  if (args.yes) throw new Error(`Missing required option for non-interactive install: ${question}`);
  return (await rl.question(`${question}: `)).trim();
}

function parseArgs(values) {
  const result = {};
  for (let index = 0; index < values.length; index++) {
    const item = values[index];
    if (item === '--yes' || item === '-y' || item === '--no-systemd') result[item.replace(/^--?/, '')] = true;
    else if (item.startsWith('--')) result[item.slice(2)] = values[++index];
  }
  return result;
}

function assertCommand(command) {
  try { execFileSync(command, ['--version'], { stdio: 'ignore' }); }
  catch { throw new Error(`${command} is required`); }
}

function assertNodeVersion() {
  if (Number(process.versions.node.split('.')[0]) < 20) throw new Error('Node.js 20 or newer is required');
}

async function validateJson(path, fields) {
  const value = JSON.parse(await readFile(path, 'utf8'));
  for (const field of fields) if (!value[field]) throw new Error(`${basename(path)} is missing ${field}`);
}

async function validateGoogleServices(path) {
  const value = JSON.parse(await readFile(path, 'utf8'));
  if (!value.project_info?.project_id || !value.client?.length) {
    throw new Error('google-services.json is not a valid Android Firebase client config');
  }
}

async function readJsonOr(path, fallback) {
  try { return JSON.parse(await readFile(path, 'utf8')); }
  catch (error) {
    if (error.code === 'ENOENT') return structuredClone(fallback);
    throw new Error(`Cannot parse existing ${path}: ${error.message}`);
  }
}

async function writeJson(path, value) {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, JSON.stringify(value, null, 2) + '\n');
}
