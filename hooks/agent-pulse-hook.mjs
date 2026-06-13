#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { basename, join } from 'node:path';

const provider = (process.argv[2] || 'agent').toLowerCase();
const requestedType = (process.argv[3] || 'auto').toLowerCase();

try {
  const input = await readStdinJson();
  const config = await loadClientConfig();
  const event = buildEvent(provider, requestedType, input);
  await postEvent(config, event);
} catch (error) {
  // Hooks must never block or fail an agent session. Opt-in debug goes to stderr.
  if (process.env.AGENT_PULSE_DEBUG === '1') {
    console.error(`Agent Pulse hook: ${error.message}`);
  }
}
process.exit(0);

async function readStdinJson() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  const raw = Buffer.concat(chunks).toString('utf8').trim();
  return raw ? JSON.parse(raw) : {};
}

async function loadClientConfig() {
  const path = process.env.AGENT_PULSE_CLIENT_ENV ||
    join(homedir(), '.config', 'agent-pulse', 'client.env');
  const raw = await readFile(path, 'utf8');
  const env = {};
  for (const line of raw.split(/\r?\n/)) {
    const match = line.match(/^([A-Z0-9_]+)=(.*)$/);
    if (match) env[match[1]] = match[2].replace(/^['"]|['"]$/g, '');
  }
  if (!env.AGENT_PULSE_RELAY_URL || !env.AGENT_PULSE_TOKEN) {
    throw new Error('client.env is missing relay credentials');
  }
  return env;
}

function buildEvent(source, typeHint, input) {
  const cwd = stringValue(input.cwd || input.working_directory || process.cwd());
  const eventName = stringValue(input.hook_event_name || input.event_name || '');
  const notificationType = stringValue(input.notification_type || '');
  const toolName = stringValue(input.tool_name || input.tool || '');
  const assistantText = firstText(
    input.message,
    input.last_assistant_message,
    input.reason,
    input.prompt,
    input.notification,
    input.tool_input?.command,
    input.tool_input?.description,
    input.tool_input
  );
  const type = resolveType(typeHint, eventName, notificationType, assistantText);
  const title = titleFor(source, type, toolName);
  const fallback = {
    approval: `${toolName || 'A tool'} needs permission before the agent can continue.`,
    question: 'The agent is waiting for your input.',
    completed: 'The agent finished its current turn.',
    error: 'The agent reported an error.',
  }[type];
  return {
    type,
    provider: source,
    title,
    message: redact(assistantText || fallback).slice(0, 1200),
    project: cwd ? basename(cwd) : '',
    timestamp: Date.now(),
  };
}

function resolveType(typeHint, eventName, notificationType, text) {
  if (['approval', 'question', 'completed', 'error'].includes(typeHint)) return typeHint;
  const metadata = `${eventName} ${notificationType}`.toLowerCase();
  if (metadata.includes('permission')) return 'approval';
  if (metadata.includes('idle_prompt') || metadata.includes('question')) return 'question';
  if (metadata.includes('error') || metadata.includes('failure')) return 'error';
  if (/\?\s*$/.test(text) || /(?:which|what|would you|do you want|please choose)/i.test(text)) {
    return 'question';
  }
  return 'completed';
}

function titleFor(source, type, toolName) {
  const name = source === 'claude' ? 'Claude' : source === 'codex' ? 'Codex' : 'Agent';
  if (type === 'approval') return toolName ? `${name} needs permission: ${toolName}` : `${name} needs permission`;
  if (type === 'question') return `${name} is waiting for you`;
  if (type === 'error') return `${name} run failed`;
  return `${name} turn finished`;
}

function firstText(...values) {
  for (const value of values) {
    if (typeof value === 'string' && value.trim()) return value.trim();
    if (value && typeof value === 'object') {
      const text = JSON.stringify(value);
      if (text !== '{}') return text;
    }
  }
  return '';
}

function redact(value) {
  return stringValue(value)
    .replace(/(authorization\s*[:=]\s*)(?:bearer[\s_:=-]*)?[^\s"']+/ig, '$1[REDACTED]')
    .replace(/((?:api[_-]?key|token|secret|password|passwd|pwd)\s*[:=]\s*)[^\s,"'}]+/ig, '$1[REDACTED]')
    .replace(/\b(?:sk|xox[baprs]|gh[pousr])_[A-Za-z0-9_-]{12,}\b/g, '[REDACTED]')
    .replace(/-----BEGIN [^-]+-----[\s\S]*?-----END [^-]+-----/g, '[REDACTED KEY]');
}

async function postEvent(config, event) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 4000);
  try {
    const response = await fetch(`${config.AGENT_PULSE_RELAY_URL.replace(/\/$/, '')}/v1/events`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${config.AGENT_PULSE_TOKEN}`,
        'Content-Type': 'application/json',
        'User-Agent': 'AgentPulse-Hook/1.0',
      },
      body: JSON.stringify(event),
      signal: controller.signal,
    });
    if (!response.ok) throw new Error(`relay returned HTTP ${response.status}`);
  } finally {
    clearTimeout(timer);
  }
}

function stringValue(value) {
  return value == null ? '' : String(value);
}
