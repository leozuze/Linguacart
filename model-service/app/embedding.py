"""multilingual-e5 wrapper: prefixed, mean-pooled, L2-normalised embeddings."""
from __future__ import annotations

import logging
from collections.abc import Sequence

import torch
import torch.nn.functional as F
from transformers import AutoModel, AutoTokenizer

from .errors import ModelUnavailableError

log = logging.getLogger(__name__)

# E5 requires these exact prefixes (docs/03).
PREFIXES = {"query": "query: ", "passage": "passage: "}


def with_prefix(kind: str, text: str) -> str:
    """Add the E5 prefix for 'query' or 'passage'; anything else is an error."""
    if kind not in PREFIXES:
        raise ValueError(f"Unknown input type: {kind!r}")
    return PREFIXES[kind] + text


class Embedder:
    def __init__(
        self, model_name: str, device: str, max_input_tokens: int, batch_size: int
    ) -> None:
        self.model_name = model_name
        self.device = device
        self.max_input_tokens = max_input_tokens
        self.batch_size = batch_size
        self._tokenizer = None
        self._model = None

    @property
    def loaded(self) -> bool:
        return self._model is not None and self._tokenizer is not None

    @property
    def model_version(self) -> str:
        """Hugging Face snapshot hash, recorded in the embeddings file."""
        if not self.loaded:
            return "unknown"
        return getattr(self._model.config, "_commit_hash", None) or "unknown"

    def load(self) -> None:
        log.info("Loading embedding model %s on %s", self.model_name, self.device)
        tokenizer = AutoTokenizer.from_pretrained(self.model_name)
        model = AutoModel.from_pretrained(self.model_name)
        model.to(self.device).eval()
        self._tokenizer, self._model = tokenizer, model

    def embed(self, items: Sequence[tuple[str, str]]) -> list[list[float]]:
        """items: (type, text) pairs, type in {'query', 'passage'}.

        Returns one L2-normalised vector per item, same order as the input.
        """
        if not self.loaded:
            raise ModelUnavailableError("Embedding model is not loaded")
        tok, model = self._tokenizer, self._model
        prefixed = [with_prefix(kind, text) for kind, text in items]

        vectors: list[list[float]] = []
        for start in range(0, len(prefixed), self.batch_size):
            batch = prefixed[start : start + self.batch_size]
            enc = tok(
                batch,
                return_tensors="pt",
                padding=True,
                truncation=True,
                max_length=self.max_input_tokens,
            ).to(self.device)
            with torch.inference_mode():
                hidden = model(**enc).last_hidden_state
            # Attention-masked mean pooling (what E5 expects), then L2 normalise
            # so Java can use dot product or cosine interchangeably.
            mask = enc["attention_mask"].unsqueeze(-1).to(hidden.dtype)
            pooled = (hidden * mask).sum(dim=1) / mask.sum(dim=1).clamp(min=1e-9)
            pooled = F.normalize(pooled, p=2, dim=1)
            vectors.extend(pooled.cpu().tolist())
        return vectors
