"""BGE-M3's three outputs from one forward pass, without FlagEmbedding.

The checkpoint (BAAI/bge-m3) is an XLM-RoBERTa encoder plus two small heads:
- dense:        the [CLS] token's hidden state, L2-normalized (1024 dims) -
                exactly what Ollama's bge-m3 returns;
- sparse:       sparse_linear.pt, hidden(1024) -> 1 weight per token, ReLU;
                per token id the max weight: a learned bag of words;
- multi-vector: colbert_linear.pt, hidden -> 1024 per token (all but [CLS]),
                L2-normalized: one vector per token, scored by MaxSim.
Scores follow FlagEmbedding: dense = dot, sparse = sum of q*d weights over
shared tokens, colbert = mean over query tokens of their best passage token.
"""
import glob, os
import torch
from transformers import AutoModel, AutoTokenizer

SNAPSHOT = glob.glob(os.path.expanduser("~/.cache/huggingface/hub/models--BAAI--bge-m3/snapshots/*/"))[0]

class BgeM3:
    def __init__(self, device="cpu"):
        self.device = device
        self.tokenizer = AutoTokenizer.from_pretrained(SNAPSHOT)
        self.model = AutoModel.from_pretrained(SNAPSHOT).to(device).eval()
        hidden = self.model.config.hidden_size
        self.sparse_linear = torch.nn.Linear(hidden, 1)
        self.sparse_linear.load_state_dict(torch.load(SNAPSHOT + "sparse_linear.pt", map_location=device))
        self.colbert_linear = torch.nn.Linear(hidden, hidden)
        self.colbert_linear.load_state_dict(torch.load(SNAPSHOT + "colbert_linear.pt", map_location=device))
        self.sparse_linear.to(device).eval(); self.colbert_linear.to(device).eval()
        self.skip = {self.tokenizer.cls_token_id, self.tokenizer.eos_token_id, self.tokenizer.pad_token_id, self.tokenizer.unk_token_id}

    @torch.no_grad()
    def encode(self, texts, max_length=512, batch_size=8):
        out = []
        for start in range(0, len(texts), batch_size):
            batch = self.tokenizer(texts[start:start + batch_size], padding=True, truncation=True,
                                   max_length=max_length, return_tensors="pt").to(self.device)
            hidden = self.model(**batch).last_hidden_state
            dense = torch.nn.functional.normalize(hidden[:, 0], dim=-1)
            token_weights = torch.relu(self.sparse_linear(hidden)).squeeze(-1)
            colbert = torch.nn.functional.normalize(self.colbert_linear(hidden[:, 1:]), dim=-1)
            for row in range(hidden.shape[0]):
                ids, mask = batch["input_ids"][row], batch["attention_mask"][row].bool()
                weights = {}
                for token_id, weight in zip(ids[mask].tolist(), token_weights[row][mask].tolist()):
                    if token_id not in self.skip and weight > 0:
                        weights[token_id] = max(weight, weights.get(token_id, 0))
                out.append({"dense": dense[row].cpu(), "sparse": weights,
                            "colbert": colbert[row][mask[1:]].cpu()})
        return out

    @staticmethod
    def dense_score(q, d): return float(q["dense"] @ d["dense"])
    @staticmethod
    def sparse_score(q, d): return sum(w * d["sparse"].get(t, 0.0) for t, w in q["sparse"].items())
    @staticmethod
    def colbert_score(q, d): return float((q["colbert"] @ d["colbert"].T).max(dim=1).values.mean())

    def tokens(self, sparse, top=8):
        """The heaviest sparse tokens, readable - what the model thinks matters."""
        best = sorted(sparse.items(), key=lambda kv: -kv[1])[:top]
        return [(self.tokenizer.convert_ids_to_tokens(t), round(w, 3)) for t, w in best]
