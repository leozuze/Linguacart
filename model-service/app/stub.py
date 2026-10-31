"""Deterministic FAKE models so teammates can build against the API without downloading weights.

Enable with STUB_MODELS=true. NOT for evaluation and NOT real semantics.
- translate: eng->sna returns "[SN] <text>"; sna->eng looks up a few common words, else echoes.
- embed: hashed bag-of-words vectors (768-d, L2-normalised), so texts sharing words are similar.
"""
from __future__ import annotations

import hashlib
import math
import re
from collections.abc import Sequence

DIM = 768
_SN_EN = {
    "munyu": "salt", "shuga": "sugar", "sukari": "sugar", "mafuta ekubika": "cooking oil",
    "chibage": "maize", "mupunga": "rice", "mukaka": "milk", "foni": "phone", "tii": "tea",
    "kofi": "coffee", "sipo": "soap", "bhatiri": "battery", "mushonga": "medicine",
}


class StubTranslator:
    model_name = "stub"
    loaded = True

    def load(self, source_language: str) -> None:
        pass

    def translate(self, texts: list[str], source_language: str, target_language: str) -> list[str]:
        out = []
        for t in texts:
            if not t.strip():
                out.append(t)
            elif target_language == "sna_Latn":
                out.append(f"[SN] {t}")
            else:
                out.append(_SN_EN.get(t.strip().lower(), t))
        return out


class StubEmbedder:
    model_name = "stub-hash-768"
    model_version = "stub"
    loaded = True

    def load(self) -> None:
        pass

    def embed(self, items: Sequence[tuple[str, str]]) -> list[list[float]]:
        vectors = []
        for _kind, text in items:
            v = [0.0] * DIM
            for word in re.findall(r"[a-z0-9]+", text.lower()):
                h = int(hashlib.md5(word.encode("utf-8")).hexdigest(), 16)
                v[h % DIM] += 1.0
                v[(h >> 16) % DIM] += 0.5
            norm = math.sqrt(sum(x * x for x in v))
            if norm == 0:
                v[0], norm = 1.0, 1.0
            vectors.append([x / norm for x in v])
        return vectors
