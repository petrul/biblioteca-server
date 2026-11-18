#!/usr/bin/env node
import { endpointFrom, McpClient } from './mcp-client.mjs';

const [name, ...rest] = process.argv.slice(2);
if (!name || !rest.length) throw new Error('usage: search.mjs TOOL "query" [work-path]');
const query = rest[0];
const work = rest[1];
const client = new McpClient({
  endpoint: endpointFrom(process.env.BIBLIOTECA_EXTERNAL_URL, '/mcp'),
  token: process.env.BIBLIOTECA_INTERNAL_API_TOKEN,
});
await client.initialize();
const args = { q: query, limit: 10 };
if (name === 'search_grep') {
  if (!work) throw new Error('search_grep requires one author/opus work path');
  args.scope = [work];
} else if (work) {
  args.scope = [work];
}
console.log(await client.call(name, args));

