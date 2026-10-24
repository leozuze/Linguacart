"""Pydantic request/response models. Field names match docs/03 exactly."""
from typing import Literal

from pydantic import BaseModel, Field, field_validator

from .config import get_settings


def _check_batch_size(items: list) -> list:
    """Reject oversized requests (decision #2). ValueError becomes HTTP 422."""
    limit = get_settings().max_items_per_request
    if len(items) > limit:
        raise ValueError(f"too many items: {len(items)} (max {limit})")
    return items


# ---------- /translate ----------
class TranslateRequest(BaseModel):
    # Literal types make FastAPI return 422 for any other language (v1 rule).
    sourceLanguage: Literal["eng_Latn"]
    targetLanguage: Literal["sna_Latn"]
    # min_length=1 -> empty list is a 422 (decision #3).
    # Empty *strings* inside the list are allowed and returned as "".
    texts: list[str] = Field(min_length=1)

    @field_validator("texts")
    @classmethod
    def _limit_texts(cls, v: list[str]) -> list[str]:
        return _check_batch_size(v)


class TranslateResponse(BaseModel):
    translations: list[str]


# ---------- /embed ----------
class EmbedInput(BaseModel):
    # Anything other than query/passage is a 422.
    type: Literal["query", "passage"]
    # Empty text would embed only the "query: " prefix, which is meaningless,
    # so it is rejected (new small decision, see notes).
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
