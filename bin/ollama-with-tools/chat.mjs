#!/usr/bin/env node
import { endpointFrom, McpClient } from './mcp-client.mjs';

const MODEL = 'qwen3.5:9b';
// Keep the generating model resident on the shared Ollama instance for the
// same six-hour window as the NestJS chat adaptor.  Without this, Ollama's
// five-minute default unloads Qwen between questions and the next request
// pays the full model-load latency.
const KEEP_ALIVE = '6h';
const MAX_ROUNDS = 6;
const MAX_TOOL_RESULT_CHARS = 12_000;
const DEFAULT_TOOLS = [
  'search_authors', 'search_div_heads', 'search_lucene', 'search_grep',
  'search_vector', 'search_ann', 'div_by_path', 'div_toc', 'div_paragraphs',
  'read_text', 'quote_fragment', 'works_catalog', 'author_details', 'author_works',
];

function args() {
  const result = { question: '', work: '', allTools: false, tools: DEFAULT_TOOLS };
  const argv = process.argv.slice(2);
  const question = [];
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--question') result.question = argv[++i] ?? '';
    else if (arg === '--work') result.work = argv[++i] ?? '';
    else if (arg === '--all-tools') result.allTools = true;
    else if (arg === '--tools') result.tools = (argv[++i] ?? '').split(',').filter(Boolean);
    else question.push(arg);
  }
  if (!result.question && question.length) result.question = question.join(' ');
  return result;
}

function rootWork(path) {
  return String(path ?? '').replace(/^\/+|\/+$/g, '').split('/').slice(0, 2).join('/');
}

function inWork(path, work) {
  const normalized = String(path ?? '').replace(/^\/+/, '');
  return !work || normalized === work || normalized.startsWith(`${work}/`);
}

function scopeArgs(name, supplied, work) {
  const value = { ...(supplied ?? {}) };
  const scopedSearches = new Set(['search_lucene', 'search_vector', 'search_div_heads']);
  if (work && (scopedSearches.has(name) || name === 'search_grep')) {
    const scopes = value.scope ?? (work ? [work] : []);
    if (scopes.length !== 1 || rootWork(scopes[0]) !== work)
      throw new Error(`${name} must stay inside the active work`);
    value.scope = [work];
  }
  for (const key of ['path', 'divPath']) {
    if (value[key] && !inWork(value[key], work)) throw new Error(`${name} path is outside the active work`);
  }
  return value;
}

function compact(text) {
  if (text.length <= MAX_TOOL_RESULT_CHARS) return text;
  try {
    const parsed = JSON.parse(text);
    if (typeof parsed === 'string') return `${parsed.slice(0, MAX_TOOL_RESULT_CHARS)}\n[truncated]`;
    if (Array.isArray(parsed)) {
      return JSON.stringify(parsed.slice(0, 8).map((item) => ({
        ...item,
        content: typeof item.content === 'string' ? item.content.slice(0, 1_200) : item.content,
      })));
    }
  } catch { /* fall through to a plain text bound */ }
  return `${text.slice(0, MAX_TOOL_RESULT_CHARS)}\n[truncated]`;
}

function ollamaEndpoint() {
  const base = String(process.env.OLLAMA_URL ?? '').replace(/\/$/, '');
  if (!base) throw new Error('OLLAMA_URL is required');
  return base.endsWith('/api/chat') ? base : `${base}/api/chat`;
}

const options = args();
if (!options.question.trim()) throw new Error('usage: chat.mjs [--work author/opus] "question"');
const bibliotecaUrl = process.env.BIBLIOTECA_EXTERNAL_URL;
const client = new McpClient({
  endpoint: endpointFrom(bibliotecaUrl, '/mcp'),
  token: process.env.BIBLIOTECA_INTERNAL_API_TOKEN,
});
const serverInfo = await client.initialize();
const advertised = await client.tools();
const allow = options.allTools ? null : new Set(options.tools);
const selected = advertised.filter((tool) => !allow || allow.has(tool.name));
if (!selected.length) throw new Error('No selected tools were advertised by the MCP server');
const selectedNames = new Set(selected.map((tool) => tool.name));
const tools = selected.map((tool) => ({
  type: 'function',
  function: {
    name: tool.name,
    description: tool.name === 'search_grep'
      ? `${tool.description ?? ''} This chat client permits it only inside one active work.`
      : (tool.description ?? ''),
    parameters: tool.inputSchema ?? { type: 'object', properties: {} },
  },
}));

const system = [
  "You are Biblioteca's master librarian.",
  'Use the corpus tools before answering factual questions; never answer from memory when a tool can verify it.',
  'For broad questions, begin with semantic and Lucene search, compare evidence, then read relevant text.',
  'Use shallow grep only inside one already identified work.',
  options.work ? `The active work is ${options.work}; every tool path and search scope must remain inside it.` : '',
  'Cite stable Biblioteca paths in the answer and distinguish evidence from inference.',
].filter(Boolean).join(' ');
const messages = [{ role: 'system', content: system }, { role: 'user', content: options.question.trim() }];
let usedTool = false;

for (let round = 1; round <= MAX_ROUNDS; round++) {
  const response = await fetch(ollamaEndpoint(), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: MODEL, messages, tools, stream: false, think: false, keep_alive: KEEP_ALIVE }),
  });
  if (!response.ok) throw new Error(`Ollama ${response.status}: ${(await response.text()).slice(0, 500)}`);
  const message = (await response.json()).message;
  messages.push(message);
  const calls = message.tool_calls ?? [];
  if (calls.length === 0) {
    if (!usedTool) {
      messages.push({ role: 'user', content: 'Search the Biblioteca corpus first, then answer.' });
      continue;
    }
    console.log(message.content);
    process.exit(0);
  }
  for (const call of calls) {
    const name = call.function?.name;
    let result;
    try {
      if (!selectedNames.has(name)) throw new Error(`tool ${name} was not offered by this chat client`);
      const toolArgs = scopeArgs(name, call.function?.arguments, options.work);
      usedTool = true;
      result = await client.call(name, toolArgs);
      result = compact(result);
    } catch (error) {
      result = JSON.stringify({ error: error.message });
    }
    messages.push({ role: 'tool', tool_name: name, content: result });
  }
}
throw new Error(`Ollama did not finish after ${MAX_ROUNDS} tool rounds (MCP ${serverInfo?.version ?? 'unknown'})`);

