# textbase-nomic-embed-experiment

A throwaway experiment: embed a chunk of [textbase.scriptorium.ro](https://textbase.scriptorium.ro)'s
corpus with Ollama's `nomic-embed-text:v1.5`, store the vectors in the "int" Milvus, then query them.
Not part of the real textbase pipeline (see `textbase-nestjs`'s own vectorizer for that).

## Setup

```sh
uv sync
```

## Scripts

### `embed_novels.py`

Selects ~20 works spread across as many distinct languages as the corpus actually has (found 11-13
in practice - it's Romanian-classics-heavy with a long tail of French/English/Italian/Spanish/German/
Hungarian/Russian/Dutch/Catalan/Portuguese/Danish/Finnish), plus at least one Romanian and one French
Bible, walks each work's *entire* chapter tree down to leaf divs (no sub-divs - see
`textbase_client.walk_leaves`), embeds every leaf's plain-text content with `nomic-embed-text:v1.5`
(batches of 16, with a pause between batches to be a reasonable neighbour to whatever else is running
on that Ollama instance), and stores everything in Milvus's `test_experiment_embeddings` collection.

"Novel" is approximate - textbase's live API doesn't expose genre, so selection is really "random
work, language-diverse," not a genre filter.

```sh
uv run embed_novels.py --dry-run     # discover + count leaves only, no embedding
uv run embed_novels.py               # for real
uv run embed_novels.py --recreate    # drop + recreate the collection first
```

Last real run: 22 works (20 random + 2 explicit Bibles), 13 languages, **11,127 chapters embedded**.

### `find_neighbors.py`

Picks a random subchapter via textbase's `/util/random`, embeds it with the same encoder, and prints
the 20 closest stored chapters (url + text preview) from Milvus.

```sh
uv run find_neighbors.py
uv run find_neighbors.py --top-k 10 --full   # full chapter text instead of a preview
```

## Why HTML-scraping instead of the TOC API

`/api/divs/{id}/toc` and `/api/collections/system/by-language/{lang}` exist in biblioteca-server's
current source but returned 404 against both prod and the int deployment when checked (2026-09-03) -
the running builds predate them. `textbase_client.py` works around this using only what's actually
live: `/util/random`, `/api/search/divHeads`, and the decorated HTML reading-view pages' own child
links (a div with no direct-child links is a leaf). Language is detected locally with `langdetect`
rather than trusted from server metadata, for the same reason.

## Where things point

- textbase API: `https://textbase.scriptorium.ro` (prod, read-only traffic only)
- Ollama: `http://zmeu.local:11434`, model `nomic-embed-text:v1.5` (768-dim)
- Milvus: `http://mini.local:20112` (the **int** instance - see
  `~/work/scripts/docker/integration-deps`), collection `test_experiment_embeddings`
