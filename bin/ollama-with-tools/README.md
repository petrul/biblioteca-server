# Ollama with Biblioteca MCP tools

These small scripts are a deliberately thin client: they initialize the
Biblioteca streamable-HTTP MCP endpoint, fetch its tool list and descriptions,
translate the selected tools to Ollama function tools, execute Qwen's tool
calls through MCP, and feed the results back to Ollama.

They do not implement a second RAG system. The server's MCP tools remain the
source of truth for search and text retrieval.

## Requirements

Load a Biblioteca profile first. The profile must provide the existing
`BIBLIOTECA_EXTERNAL_URL`, `BIBLIOTECA_INTERNAL_API_TOKEN` and `OLLAMA_URL`
values. Never print the token.

The default model is the code constant `qwen3.5:9b`.

## Rake task

The easiest invocation loads the profile and asks one question:

```bash
rake 'chat[zmeu,Who wrote A Christmas Carol?]'
```

For a work-scoped question:

```bash
rake 'chat[zmeu,Summarize this work and comment on its transformation theme.,dickens/a_christmas_carol]'
```

With no question argument, `rake chat[zmeu]` reads one question from stdin.

## Direct chat script

After loading a profile yourself:

```bash
source /home/apps/secrets/load-env.sh biblioteca/zmeu
node bin/ollama-with-tools/chat.mjs \
  --question 'Is there anything in the library about exile and homesickness?'
```

Scope the tools to an open work:

```bash
node bin/ollama-with-tools/chat.mjs \
  --work dickens/a_christmas_carol \
  --question 'Who is Jacob Marley and what does he represent?'
```

`search_grep` is accepted only with one active work. The client also bounds
tool rounds and large tool results, and rejects tool names not advertised by
the MCP server.

For protocol debugging, expose all advertised MCP tools (the default chat set
is intentionally smaller and more useful to Qwen):

```bash
node bin/ollama-with-tools/chat.mjs --all-tools \
  --question 'Which authors discuss colonial encounters?'
```

## Direct search probe

```bash
source /home/apps/secrets/load-env.sh biblioteca/zmeu
node bin/ollama-with-tools/search.mjs search_vector \
  'Polynesian aboriginal people giving green pebbles to colonists'

node bin/ollama-with-tools/search.mjs search_lucene 'green pebbles'

node bin/ollama-with-tools/search.mjs search_grep pebbles frazer/the_belief
```

The grep form requires the `author/opus` path and never performs a global
corpus scan.

