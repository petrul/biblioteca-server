/**
 * Small streamable-HTTP MCP client used by chat.mjs and search.mjs.
 * It deliberately has no Biblioteca-specific search logic: the server's
 * tools and descriptions are the source of truth.
 */

export function endpointFrom(base, suffix) {
  const value = String(base ?? '').replace(/\/$/, '');
  return value.endsWith(suffix) ? value : `${value}${suffix}`;
}

function decode(response, text) {
  if ((response.headers.get('content-type') ?? '').includes('text/event-stream')) {
    const data = text.split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice('data:'.length).trim())
      .join('');
    return data ? JSON.parse(data) : {};
  }
  return text.trim() ? JSON.parse(text) : {};
}

export class McpClient {
  constructor({ endpoint, token, timeoutMs = 30_000 }) {
    if (!endpoint) throw new Error('BIBLIOTECA_EXTERNAL_URL is required to locate the MCP server');
    if (!token) throw new Error('BIBLIOTECA_INTERNAL_API_TOKEN is required for MCP access');
    this.endpoint = endpoint;
    this.token = token;
    this.timeoutMs = timeoutMs;
    this.session = null;
    this.nextId = 1;
  }

  async initialize() {
    const initialized = await this.rpc({
      jsonrpc: '2.0',
      id: this.nextId++,
      method: 'initialize',
      params: {
        protocolVersion: '2025-06-18',
        capabilities: {},
        clientInfo: { name: 'biblioteca-ollama-chat', version: '0.1' },
      },
    }, false);
    this.session = initialized.response.headers.get('mcp-session-id');
    if (!this.session) throw new Error('MCP initialize did not return a session id');
    await this.rpc({ jsonrpc: '2.0', method: 'notifications/initialized' });
    return initialized.body.result?.serverInfo;
  }

  async tools() {
    const result = await this.rpc({ jsonrpc: '2.0', id: this.nextId++, method: 'tools/list', params: {} });
    return result.body.result?.tools ?? [];
  }

  async call(name, args = {}) {
    const result = await this.rpc({
      jsonrpc: '2.0',
      id: this.nextId++,
      method: 'tools/call',
      params: { name, arguments: args },
    });
    const content = result.body.result?.content ?? [];
    return content.filter((part) => part.type === 'text').map((part) => part.text).join('\n');
  }

  async rpc(body, withSession = true) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const response = await fetch(this.endpoint, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${this.token}`,
          'Content-Type': 'application/json',
          Accept: 'application/json, text/event-stream',
          ...(withSession && this.session ? { 'Mcp-Session-Id': this.session } : {}),
        },
        body: JSON.stringify(body),
        signal: controller.signal,
      });
      const text = await response.text();
      if (!response.ok) throw new Error(`MCP ${response.status}: ${text.slice(0, 500)}`);
      return { response, body: decode(response, text) };
    } catch (error) {
      if (error?.name === 'AbortError') throw new Error(`MCP request timed out after ${this.timeoutMs}ms`);
      throw error;
    } finally {
      clearTimeout(timer);
    }
  }
}

