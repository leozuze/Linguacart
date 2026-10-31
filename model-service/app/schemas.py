"""Pydantic request/response models. Field names match docs/03 exactly."""
from typing import Literal

from pydantic import BaseModel, Field, field_validator, model_validator

from .config import get_settings

# Proposed change #1: both directions between English and Shona.
# To go back to the original spec, delete the second pair.
_SUPPORTED_PAIRS = {("eng_Latn", "sna_Latn"), ("sna_Latn", "eng_Latn")}


def _check_batch_size(items: list) -> list:
    """Reject oversized requests (decision #2). ValueError becomes HTTP 422."""
    limit = get_settings().max_items_per_request
    if len(items) > limit:
        raise ValueError(f"too many items: {len(items)} (max {limit})")
    return items


# ---------- /translate ----------
class TranslateRequest(BaseModel):
    sourceLanguage: Literal["eng_Latn", "sna_Latn"]
    targetLanguage: Literal["eng_Latn", "sna_Latn"]
    # min_length=1 -> empty list is a 422 (decision #3).
    # Empty *strings* inside the list are allowed and returned unchanged.
    texts: list[str] = Field(min_length=1)

    @field_validator("texts")
    @classmethod
    def _limit_texts(cls, v: list[str]) -> list[str]:
        return _check_batch_size(v)

    @model_validator(mode="after")
    def _check_pair(self) -> "TranslateRequest":
        if (self.sourceLanguage, self.targetLanguage) not in _SUPPORTED_PAIRS:
            raise ValueError(
                "unsupported language pair: only eng_Latn <-> sna_Latn is supported"
            )
        return self


class TranslateResponse(BaseModel):
    translations: list[str]


# ---------- /embed ----------
class EmbedInput(BaseModel):
    # Anything other than query/passage is a 422.
    type: Literal["query", "passage"]
    # Empty text would embed only the "query: " prefix, which is meaningless.
    text: str = Field(min_length=1)


class EmbedRequest(BaseModel):
    inputs: list[EmbedInput] = Field(min_length=1)

    @field_validator("inputs")
    @classmethod
    def _limit_inputs(cls, v: list[EmbedInput]) -> list[EmbedInput]:
        return _check_batch_size(v)


class EmbedResponse(BaseModel):
    embeddings: list[list[float]]


# ---------- /health ----------
class HealthResponse(BaseModel):
    # "degraded" if either model failed to load (decision #4).
    status: Literal["ok", "degraded"]
    translation_model: Literal["loaded", "unavailable"]
    embedding_model: Literal["loaded", "unavailable"]
