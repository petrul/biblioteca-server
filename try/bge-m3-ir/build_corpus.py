"""Collects a small judged corpus from Biblioteca's own search API.

Passages are real Biblioteca paragraphs (the unit the vector index embeds):
- candidates per topic, from Lucene hits split into paragraphs;
- the live dense vector search's own top hits per query (today's winners,
  mostly short lines) - the hard negatives;
- paragraphs on unrelated topics - distractors.
Writes corpus.json: [{id, url, text, source}].
"""
import json, re, sys, urllib.parse, urllib.request
from queries import QUERIES, is_relevant

BASE = sys.argv[1] if len(sys.argv) > 1 else "https://test.scriptorium.ro"
MIN_PARA, MAX_PARA = 150, 3000
PER_TOPIC = 14          # relevant-looking paragraphs kept per Lucene query
DISTRACTOR_TOPICS = ["dragoste primăvară", "vânătoare pădure", "mare corabie furtună",
                     "biserica preot sat", "nunta mireasa", "școala învățător", "vin podgorie", "iarna zăpadă"]

def get(path, **query):
    url = f"{BASE}{path}?{urllib.parse.urlencode(query)}"
    with urllib.request.urlopen(url, timeout=90) as response:
        return json.load(response)

def paragraphs(hit):
    for i, para in enumerate(re.split(r"\n\s*\n", hit.get("content") or "")):
        text = re.sub(r"\s+", " ", para).strip()
        if MIN_PARA <= len(text) <= MAX_PARA:
            yield f"{hit['url']}#p{i}", hit["url"], text

corpus, seen = [], set()
def add(pid, url, text, source):
    if pid in seen or text in seen: return False
    seen.update([pid, text]); corpus.append(dict(id=pid, url=url, text=text, source=source)); return True

for query in QUERIES:
    if query["lucene"]:
        kept, per_div = 0, {}
        for hit in get("/api/search/lucene", q=query["lucene"], limit=40):
            for pid, url, text in paragraphs(hit):
                if kept >= PER_TOPIC: break
                # a few per work, so one long chapter cannot fill the topic
                if is_relevant(query, text) and per_div.get(url, 0) < 3 and add(pid, url, text, f"lucene:{query['id']}"):
                    per_div[url] = per_div.get(url, 0) + 1; kept += 1
        print(f"{query['id']:9s} {kept} topical paragraphs", file=sys.stderr)
    for hit in get("/api/search/vector", q=query["q"], limit=15):
        text = re.sub(r"\s+", " ", hit.get("content") or "").strip()
        if text: add(f"{hit['url']}#vec", hit["url"], text, f"dense-top:{query['id']}")

for topic in DISTRACTOR_TOPICS:
    n = 0
    for hit in get("/api/search/lucene", q=topic, limit=10):
        for pid, url, text in paragraphs(hit):
            if n >= 8: break
            if add(pid, url, text, "distractor"): n += 1

json.dump(corpus, open("corpus.json", "w"), ensure_ascii=False, indent=1)
print(f"{len(corpus)} passages", file=sys.stderr)
for query in QUERIES:
    print(f"  {query['id']:9s} relevant: {sum(is_relevant(query, p['text']) for p in corpus)}", file=sys.stderr)
