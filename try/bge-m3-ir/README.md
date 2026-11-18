# BGE-M3 information retrieval - a prototype and recommendations

Biblioteca's vector search finds passages *like* the query (similarity
search). This prototype asks whether the same model, BGE-M3, can also find
passages *about* the query (information retrieval, IR) on Biblioteca's own
paragraphs - and what it would take to offer that next to similarity search,
which stays as it is (More Like This, "find passages like this one").

**Answer: yes.** Using all of BGE-M3's outputs and keeping headings and short
lines out of the candidates, the share of on-topic passages in the top 10
goes from 0.47 to 0.90 over six judged queries; for "Cumani și pecenegi"
from 0 to 10 out of 10. No other model is needed.

State: 2026-11-18, on the test stage's corpus (6.06M indexed paragraphs).

## The problem

Today's vector search embeds every paragraph of 20+ characters with
BGE-M3's dense vector (through Ollama) and ranks by distance to the query's.
For "Cumani și pecenegi" the top 10 are all 20-44 character lines -
"Osmanlii și bostangii", "Capitaneus et Commissarius.", "Copaciul și
dovleacul" - none about the Cumans, while the library has Anna Comnena's
*Alexiada*, Vlahuță and Eminescu on them (Lucene finds those). BGE-M3 itself
scores the unrelated heading higher (cosine 0.56) than real Alexiada
paragraphs about Cuman armies (0.39-0.45): a short query is closest to other
short texts.

The pipeline is not at fault: Ollama's `/api/embed` vectors are normalized,
so the store's L2 ranking is exactly cosine ranking.

## BGE-M3's three outputs (`m3.py`)

One forward pass of the XLM-RoBERTa encoder gives a vector per token. The
checkpoint carries two small extra heads; together:

- **Dense** - the [CLS] token's vector, normalized: one point per text,
  compared by cosine. Finds meaning without shared words (a paraphrase, a
  synonym); a long paragraph is squeezed into one point, rare names blur,
  and short texts sit near everything. Indexed as one vector per passage
  (what Qdrant holds today). Identical to Ollama's `bge-m3` (cosine 1.0,
  checked).
- **Sparse** (lexical weights) - `sparse_linear.pt`: one learned weight per
  token, a weighted bag of words (for this query `Cuma 0.27, egi 0.23,
  ni 0.21, și 0.18, pec 0.15`). Score: the sum, over tokens shared by query
  and passage, of the two weights. Exact names, dates and terms; no
  synonyms. Indexed like Lucene, as an inverted index (Qdrant sparse
  vectors).
- **Multi-vector** (ColBERT, "late interaction") - `colbert_linear.pt`: a
  vector per token. Score: for each query token its best-matching passage
  token, averaged (MaxSim). Each word of the query finds its own match
  anywhere in a long passage, in context. Storage is one vector per token.

The BGE-M3 paper combines them: `dense + 0.3 * sparse + multi-vector`.

**Ollama exposes only the dense one.** Its `bge-m3` is a GGUF conversion of
the bare encoder with CLS pooling (`bert.pooling_type 2`), without either
head, and `/api/embed` returns one pooled vector per input. Sparse and
multi-vector need the full checkpoint (`BAAI/bge-m3`, as here).

## The test (`queries.py`, `build_corpus.py`, `evaluate.py`)

202 real paragraphs from the test stage's search API:
- topical paragraphs, 150-3000 characters, from Lucene hits;
- today's dense top 15 for every query - the hard negatives (median 41
  characters);
- paragraphs on unrelated topics - distractors.

Six queries, each judged by a regex per topic (spot-checked by reading):

| query | tests |
|---|---|
| Cumani și pecenegi | two rare names |
| popoarele nomade din stepă care atacau Bizanțul | the same topic, no name in it: meaning only (any steppe people counts) |
| uciderea lui Mihai Viteazul | an event |
| bătălia de la Borodino | Romanian query; Romanian and French *War and Peace* |
| epidemia de ciumă | a subject |
| the plague epidemic | English query over Romanian texts |

### The measures

- **P@10** (precision at 10): the share of the top 10 that is relevant.
  7 of 10 -> 0.7. Order inside the 10 does not count. "How much of the
  first page is useful?"
- **MRR** (mean reciprocal rank): 1 / the position of the first relevant
  result, averaged over the queries. First at 1 -> 1.0, at 2 -> 0.5, at 12
  -> 0.08. "How soon do I find something good?"
- **nDCG@10** (normalized discounted cumulative gain): each relevant result
  in the top 10 earns 1 / log2(rank + 1) - 1.0 at rank 1, 0.63 at 2, 0.29
  at 10 - summed and divided by the ideal list's score, so 0 to 1. Like
  P@10, but the top counts more. "How good is the first page, top first?"

### Results

Means over the six queries (`results.txt` has every query's top hits):

| method | P@10 | nDCG@10 | MRR |
|---|---|---|---|
| dense (today) | 0.47 | 0.51 | 0.76 |
| dense, passages only (>= 150 chars) | 0.85 | 0.83 | 0.83 |
| sparse | 0.80 | 0.81 | 0.89 |
| multi-vector | 0.63 | 0.69 | 0.92 |
| dense + sparse + multi | 0.62 | 0.67 | 0.92 |
| dense + sparse, passages only | 0.85 | 0.86 | 0.92 |
| **all three, passages only** | **0.90** | **0.90** | **0.92** |

- "Cumani și pecenegi": dense today P@10 0.0, MRR 0.08 (first relevant at
  12) -> all three, passages only 1.0 / 1.0.
- The paraphrase is where meaning counts: dense 0.6 beats sparse 0.4
  (Toynbee on the Avars attacking Constantinople first), the combination
  keeps it.
- Short lines are the first problem: every mode, even the combination
  (0.62), is dragged down by them. Leaving them out is the biggest single
  gain.
- Sparse carries rare names (and partly cross-language: "epidemic" shares
  sub-words with "epidemie"); dense carries paraphrases; multi-vector most
  often puts a relevant passage first.
- Multi-vector's own share, over dense + sparse on passages: +0.05 P@10, on
  two of six queries ("Cumani și pecenegi" 0.8 -> 1.0, "uciderea lui Mihai
  Viteazul" 0.7 -> 0.8).

Caveats: six queries, ~200 passages, regex judgments, passages truncated at
1024 tokens. A sample that shows the direction, not a benchmark.

## Sizing for the whole library

3,767 works, 318M words (3.2B characters), 6.06M indexed paragraphs (~52
words each); BGE-M3 averages 1.84 tokens per word here, so ~550-585M tokens.

| index | size |
|---|---|
| dense (today, Qdrant, int8 quantized + originals) | 35 GB on disk |
| sparse (~60 weighted tokens per paragraph) | a few GB |
| multi-vector, float32 / float16 / uint8 per number | ~2.3 TB / ~1.1 TB / ~0.56 TB |
| multi-vector, binary (1 bit per number) | ~70 GB, still needs originals to rescore |

The servers: zmeu 61 GB RAM (28 free), 138 GB free disk, a 12 GB RTX 3060
shared with prod's Ollama; srv2 15 GB RAM, 4 cores, 633 GB free disk, a NIC
that drops link about daily. A full multi-vector index fits none of them
well.

Encoding the corpus with the full model (needed for sparse): one forward
pass per paragraph - CPU ~1.6 paragraphs/s (weeks), the 3060 roughly half a
day to a night.

## Recommendations

Add an IR search mode next to the similarity mode; leave similarity as it is.

1. **Passages only.** Candidates for IR leave out headings (title search has
   them) and lines under ~150 characters. No reindex: a length filter on the
   existing collection, or a payload flag. The biggest single gain.
2. **Dense + sparse, fused.** Keep today's dense vectors; add BGE-M3's
   sparse weights as Qdrant sparse vectors in a new collection (or named
   vectors beside the dense ones), and query both with Qdrant's fusion
   (`dense + 0.3 * sparse`, or reciprocal rank fusion). Needs one pass of
   the full model over all paragraphs - at night on zmeu's GPU, throttled
   and paced like the Lucene build, since prod's Ollama shares it.
   Until then, Lucene can stand in for the sparse side (fused by rank).
3. **Multi-vector only as a reranker, if it earns its place.** No full
   index. Re-encode the top ~50 candidates at query time and reorder them by
   MaxSim: ~0.5 s on the GPU, several seconds on CPU. Turn it on only if a
   larger evaluation confirms the gain.
4. **Serve the full model.** One endpoint returning dense, sparse and
   multi-vector for given texts - `m3.py` is that code - in the
   sentence-transformers server on mini (`editii/sentence_transformers_
   server`) or a small service of its own. Ollama keeps serving dense.
5. **Measure before the big pass.** Grow `queries.py` to 20-30 real queries
   from the search logs, with judged passages, and track nDCG@10 for each
   change. Six queries are too few to justify a night of prod's GPU.
6. **The query side stays raw.** BGE-M3 needs no query instruction (unlike
   Qwen3-Embedding or E5); the same encoder serves similarity and IR.

## Run it

Needs a PyTorch with a real BLAS - Debian's `python3-torch` is
`BLAS_INFO=generic`, roughly 100x slower (it never finished here). And not
FlagEmbedding's encoder: its CPU multiprocessing pool deadlocked under
Python 3.14 - `m3.py` uses `transformers` directly. On zmeu:

    python3 -m venv --system-site-packages ~/.cache/bge-m3-ir-venv
    ~/.cache/bge-m3-ir-venv/bin/pip install transformers
    ~/.cache/bge-m3-ir-venv/bin/pip install --ignore-installed --no-deps \
        --index-url https://download.pytorch.org/whl/cpu torch
    cd try/bge-m3-ir
    python3 build_corpus.py https://test.scriptorium.ro     # corpus.json
    nice -n 15 ~/.cache/bge-m3-ir-venv/bin/python evaluate.py > results.txt

CPU only, niced (prod's GPU is Ollama's): about two minutes for the corpus,
the encodings cached in `encoded.pkl` (not committed).
