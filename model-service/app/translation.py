"""NLLB wrapper: English <-> Shona. Inference only (no glossary, no caching)."""
from __future__ import annotations

import logging
import re
import threading

import torch
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

from .errors import ModelUnavailableError

log = logging.getLogger(__name__)

# A sentence ends at . ! ? followed by whitespace and a capital letter or digit.
# Known limit: "approx. 5 kg" is split after "approx." (rare, acceptable).
_SENTENCE_BOUNDARY = re.compile(r"(?<=[.!?])\s+(?=[A-Z0-9\"'(\[])")


def split_sentences(text: str) -> list[str]:
    return [p.strip() for p in _SENTENCE_BOUNDARY.split(text) if p and p.strip()]


def has_letters(text: str) -> bool:
    """False for '!!!', '1234', '$2.99': NLLB hallucinates on these, so skip them."""
    return any(ch.isalpha() for ch in text)


class Translator:
    def __init__(
        self,
        model_name: str,
        device: str,
        max_input_tokens: int,
        max_new_tokens: int,
        num_beams: int,
        batch_size: int,
        no_repeat_ngram_size: int = 0,
    ) -> None:
        self.model_name = model_name
        self.device = device
        self.max_input_tokens = max_input_tokens
        self.max_new_tokens = max_new_tokens
        self.num_beams = num_beams
        self.batch_size = batch_size
        self.no_repeat_ngram_size = no_repeat_ngram_size
        self._tokenizer = None
        self._model = None
        # translate() mutates tokenizer.src_lang, so concurrent requests must queue.
        self._lock = threading.Lock()

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
        try:
            # We always pass max_new_tokens; clearing the default max_length (200)
            # removes the "both max_new_tokens and max_length are set" warning.
            model.generation_config.max_length = None
        except Exception:  # pragma: no cover - defensive, depends on transformers version
            log.debug("Could not clear generation_config.max_length")
        # Assign only after both loaded, so a failure leaves loaded == False.
        self._tokenizer, self._model = tokenizer, model

    # ------------------------------------------------------------------ public
    def translate(
        self, texts: list[str], source_language: str, target_language: str
    ) -> list[str]:
        """Translate texts. Output has the same length and order as the input.

        Each text is split into lines, then sentences, so nothing is dropped or
        silently truncated. Line breaks are preserved; sentences on one line are
        rejoined with a single space.
        """
        if not self.loaded:
            raise ModelUnavailableError("Translation model is not loaded")
        with self._lock:
            return self._translate_locked(texts, source_language, target_language)

    # ----------------------------------------------------------------- private
    def _translate_locked(
        self, texts: list[str], source_language: str, target_language: str
    ) -> list[str]:
        tok = self._tokenizer
        # NLLB: source language goes on the tokenizer; the target language is
        # forced as the first generated token (forced_bos_token_id).
        tok.src_lang = source_language
        forced_bos = tok.convert_tokens_to_ids(target_language)
        if forced_bos is None or forced_bos == tok.unk_token_id:
            raise ValueError(f"Unknown target language token: {target_language}")

        # texts -> lines -> units (a unit is one sentence, or one chunk of a long one)
        plans = [[self._units(line) for line in t.split("\n")] for t in texts]

        # Translate each distinct unit once. Sorting by length keeps padding small.
        unique = list(
            dict.fromkeys(
                u for plan in plans for line in plan for u in line if has_letters(u)
            )
        )
        unique.sort(key=len)
        translated: dict[str, str] = {}
        for start in range(0, len(unique), self.batch_size):
            batch = unique[start : start + self.batch_size]
            for src, out in zip(batch, self._generate(batch, forced_bos)):
                translated[src] = out

        results: list[str] = []
        for text, plan in zip(texts, plans):
            if not text.strip():
                results.append(text)  # empty / whitespace-only comes back unchanged
                continue
            lines = [" ".join(translated.get(u, u) for u in line) for line in plan]
            results.append("\n".join(lines))
        return results

    def _units(self, line: str) -> list[str]:
        units: list[str] = []
        for sentence in split_sentences(line):
            units.extend(self._fit(sentence))
        return units

    def _fit(self, sentence: str) -> list[str]:
        """Cut a sentence longer than max_input_tokens into word chunks."""
        n_tokens = len(self._tokenizer(sentence, add_special_tokens=False)["input_ids"])
        limit = self.max_input_tokens - 8  # room for language + end-of-sentence tokens
        if n_tokens <= limit:
            return [sentence]
        words = sentence.split()
        per_chunk = max(1, int(len(words) * limit / n_tokens * 0.9))
        return [
            " ".join(words[i : i + per_chunk]) for i in range(0, len(words), per_chunk)
        ]

    def _generate(self, batch: list[str], forced_bos: int) -> list[str]:
        tok, model = self._tokenizer, self._model
        enc = tok(
            batch,
            return_tensors="pt",
            padding=True,
            truncation=True,
            max_length=self.max_input_tokens,
        ).to(self.device)
        # Shona output is rarely more than ~2-3x the English token count, so cap
        # generation relative to the input to stop runaway loops early.
        cap = min(self.max_new_tokens, enc["input_ids"].shape[1] * 3 + 16)
        kwargs = dict(
            forced_bos_token_id=forced_bos,
            max_new_tokens=cap,
            num_beams=self.num_beams,
        )
        if self.no_repeat_ngram_size > 0:
            kwargs["no_repeat_ngram_size"] = self.no_repeat_ngram_size
        with torch.inference_mode():
            out = model.generate(**enc, **kwargs)
        return tok.batch_decode(out, skip_special_tokens=True)
