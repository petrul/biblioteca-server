"""BGE-M3 in its three modes (dense, sparse, multi-vector) over the judged corpus.

Each method ranks every passage of corpus.json for each query of queries.py;
judged by queries.is_relevant. Metrics: P@10 (share of the top 10 that is on
topic), nDCG@10 (rank-weighted), MRR (1 / rank of the first relevant one).
Runs on CPU (niced): prod's GPU stays Ollama's. Needs a PyTorch with a real
BLAS (the pip CPU wheel; Debian's python3-torch is BLAS_INFO=generic, ~100x slower).

  python evaluate.py            # table + top hits per query
  python evaluate.py --top 5    # more/less of each method's top list
"""
import json, math, os, pickle, sys
os.nice(15)
import torch
torch.set_num_threads(int(os.environ.get("THREADS", "6")))
from m3 import BgeM3
from queries import QUERIES, is_relevant

TOP = int(sys.argv[sys.argv.index("--top") + 1]) if "--top" in sys.argv else 3
MIN_LEN = 150        # the "passages only" filter: no headings, labels, short lines
CACHE = "encoded.pkl"

corpus = json.load(open("corpus.json"))
texts = [p["text"] for p in corpus]
m3 = BgeM3()

if os.path.exists(CACHE) and pickle.load(open(CACHE, "rb"))["texts"] == texts:
    docs = pickle.load(open(CACHE, "rb"))["docs"]
else:
    docs = m3.encode(texts, max_length=1024)
    pickle.dump({"texts": texts, "docs": docs}, open(CACHE, "wb"))
queries = m3.encode([q["q"] for q in QUERIES], max_length=64)

def scores(qi):
    """Every method's score for every passage, for query qi."""
    q = queries[qi]
    dense = [m3.dense_score(q, d) for d in docs]
    sparse = [m3.sparse_score(q, d) for d in docs]
    colbert = [m3.colbert_score(q, d) for d in docs]
    # the BGE-M3 paper's combination: dense + 0.3 sparse + colbert
    hybrid = [d + 0.3 * s + c for d, s, c in zip(dense, sparse, colbert)]
    longish = [len(t) >= MIN_LEN for t in texts]
    cut = lambda values: [v if keep else -1e9 for v, keep in zip(values, longish)]
    return {
        "dense (today)": dense,
        "dense, passages only": cut(dense),
        "sparse": sparse,
        "multi-vector": colbert,
        "dense+sparse+multi": hybrid,
        "all three, passages only": cut(hybrid),
    }

def metrics(ranking, relevant):
    top = ranking[:10]
    p10 = sum(relevant[i] for i in top) / 10
    ideal = sum(1 / math.log2(r + 2) for r in range(min(10, sum(relevant))))
    ndcg = sum(relevant[i] / math.log2(r + 2) for r, i in enumerate(top)) / ideal if ideal else 0
    first = next((r for r, i in enumerate(ranking) if relevant[i]), None)
    return p10, ndcg, (1 / (first + 1) if first is not None else 0)

totals = {}
for qi, query in enumerate(QUERIES):
    relevant = [is_relevant(query, t) for t in texts]
    print(f"\n### {query['q']!r}  ({sum(relevant)} relevant of {len(texts)})")
    for name, values in scores(qi).items():
        ranking = sorted(range(len(texts)), key=lambda i: -values[i])
        p10, ndcg, mrr = metrics(ranking, relevant)
        t = totals.setdefault(name, [0, 0, 0]); t[0] += p10; t[1] += ndcg; t[2] += mrr
        print(f"  {name:26s} P@10 {p10:.1f}  nDCG@10 {ndcg:.2f}  MRR {mrr:.2f}")
        for i in ranking[:TOP]:
            print(f"      {'+' if relevant[i] else '-'} {len(texts[i]):4d}ch  {texts[i][:95]}")

n = len(QUERIES)
print("\n### mean over", n, "queries")
print(f"  {'method':26s} {'P@10':>5s} {'nDCG@10':>8s} {'MRR':>5s}")
for name, (p, g, m) in totals.items():
    print(f"  {name:26s} {p/n:5.2f} {g/n:8.2f} {m/n:5.2f}")
