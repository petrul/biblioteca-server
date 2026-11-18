# BGE-M3 information retrieval prototype

Does BGE-M3 do real IR - passages *about* a query, not passages *like* it - on
Biblioteca's own paragraphs? It does, once all three of its outputs are used
and headings and short lines are kept out of the candidates.

Today's vector search uses BGE-M3's dense vector only (through Ollama, whose
GGUF `bge-m3` is the bare encoder with CLS pooling: no sparse or ColBERT
head). For "Cumani și pecenegi" its top 10 are all 20-44 character lines
("Osmanlii și bostangii", "Capitaneus et Commissarius.") - none about the
Cumans, while the library has Anna Comnena, Vlahuță and Eminescu on them.

## The three modes (`m3.py`)

One forward pass of the XLM-RoBERTa encoder gives a vector per token; the
checkpoint's two extra heads turn them into:

- **dense** - the [CLS] vector, normalized: one point per text, cosine.
  Identical to Ollama's `bge-m3` (cosine 1.0, checked).
- **sparse** - `sparse_linear.pt`: one learned weight per token, a weighted
  bag of words; score = sum of query weight x passage weight over shared
  tokens. Exact names and terms; no synonyms.
- **multi-vector (ColBERT)** - `colbert_linear.pt`: a vector per token; score
  = mean over query tokens of their best-matching passage token (MaxSim).
  Each query word finds its own match anywhere in a long passage.

Combined as in the BGE-M3 paper: `dense + 0.3 sparse + colbert`.

## The test (`queries.py`, `build_corpus.py`, `evaluate.py`)

202 real paragraphs from the test stage's search API: topical paragraphs
(from Lucene hits, 150-3000 characters), today's dense top 15 for every
query (the hard negatives, median 41 characters), and paragraphs on
unrelated topics. Six queries, judged by a regex per topic: two rare names;
the same topic paraphrased with no name in it; an event; a Romanian query
over Romanian and French *War and Peace*; a subject; and an English query
over Romanian texts.

Means over the six queries (`results.txt` has each query's top hits):

| method | P@10 | nDCG@10 | MRR |
|---|---|---|---|
| dense (today) | 0.47 | 0.51 | 0.76 |
| dense, passages only (>= 150 chars) | 0.85 | 0.83 | 0.83 |
| sparse | 0.80 | 0.81 | 0.89 |
| multi-vector | 0.63 | 0.69 | 0.92 |
| dense + sparse + multi | 0.62 | 0.67 | 0.92 |
| **all three, passages only** | **0.90** | **0.90** | **0.92** |

"Cumani și pecenegi": dense today P@10 0.0 -> all three, passages only 1.0.
The paraphrase ("popoarele nomade din stepă care atacau Bizanțul") is where
meaning counts: dense 0.6 beats sparse 0.4 (Toynbee on the Avars attacking
Constantinople ranks first), and the combination keeps it.

What it shows:
- short lines are the first problem: every mode, even the combination
  (0.62), is dragged down by them; filtering them is the biggest single win;
- sparse carries rare names (and partly cross-language: "epidemic" shares
  sub-words with "epidemie"), dense carries paraphrases, multi-vector puts a
  relevant passage first most often (MRR 0.92);
- no single mode wins every query; the combination does.

Caveats: a small corpus, labels by regex (spot-checked by reading),
passages truncated at 1024 tokens. A sample, not a benchmark.

## Run

Needs PyTorch with a real BLAS - Debian's `python3-torch` is
`BLAS_INFO=generic` and roughly 100x slower (it never finished). On zmeu:

    python3 -m venv --system-site-packages ~/.cache/bge-m3-ir-venv
    ~/.cache/bge-m3-ir-venv/bin/pip install transformers
    ~/.cache/bge-m3-ir-venv/bin/pip install --ignore-installed --no-deps \
        --index-url https://download.pytorch.org/whl/cpu torch
    python3 build_corpus.py https://test.scriptorium.ro     # corpus.json
    nice -n 15 ~/.cache/bge-m3-ir-venv/bin/python evaluate.py > results.txt

CPU only (prod's GPU is Ollama's): about two minutes for the corpus,
encodings cached in `encoded.pkl`.
