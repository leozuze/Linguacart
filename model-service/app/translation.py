"""NLLB wrapper: English -> Shona. Inference only (no glossary, no caching)."""
from __future__ import annotations

import logging

import torch
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

from .errors import ModelUnavailableError

log = logging.getLogger(__name__)


class Translator:
    def __init__(
        self,
        model_name: str,
        device: str,
        max_input_tokens: int,
        max_new_tokens: int,
        num_beams: int,
        batch_size: int,
    ) -> None:
        self.model_name = model_name
        self.device = device
        self.max_input_tokens = max_input_tokens
        self.max_new_tokens = max_new_tokens
        self.num_beams = num_beams
        self.batch_size = batch_size
        self._tokenizer = None
        self._model = None

    @property
    def loaded(self) -> bool:
        return self._model is not None and self._tokenizer is not None

    def load(self, source_language: str) -> None:
        """Load tokenizer and model. Honours HF_HUB_OFFLINE automatically."""
        log.info("Loading translation model %s on %s", self.model_name, self.device)
        tokenizer = AutoTokenizer.from_pretrained(
            self.model_name, src_lang=source_language
        )
        model = AutoModelForSeq2SeqLM.from_pretrained(self.model_name)
        model.to(self.device).eval()
        # Assign only after both loaded, so a failure leaves loaded == False.
        self._tokenizer, self._model = tokenizer, model

    def translate(
        self, texts: list[str], source_language: str, target_language: str
    ) -> list[str]:
        """Translate texts. Output has the same length and order as the input."""
        if not self.loaded:
            raise ModelUnavailableError("Translation model is not loaded")
        tok, model = self._tokenizer, self._model

        # NLLB: source language goes on the tokenizer; the target language is
        # forced as the first generated token (forced_bos_token_id).
        tok.src_lang = source_language
        forced_bos = tok.convert_tokens_to_ids(target_language)
        if forced_bos is None or forced_bos == tok.unk_token_id:
            raise ValueError(f"Unknown target language token: {target_language}")

        # Start from the input so empty/whitespace-only strings come back unchanged.
        results = list(texts)
        todo = [i for i, t in enumerate(texts) if t.strip()]

        for start in range(0, len(todo), self.batch_size):
            idx = todo[start : start + self.batch_size]
            batch = [texts[i] for i in idx]
            enc = tok(
                batch,
                return_tensors="pt",
                padding=True,
                truncation=True,
                max_length=self.max_input_tokens,
            ).to(self.device)
            with torch.inference_mode():
                out = model.generate(
                    **enc,
                    forced_bos_token_id=forced_bos,
                    max_new_tokens=self.max_new_tokens,
                    num_beams=self.num_beams,
                )
            decoded = tok.batch_decode(out, skip_special_tokens=True)
            for i, text in zip(idx, decoded):
                results[i] = text
        return results
