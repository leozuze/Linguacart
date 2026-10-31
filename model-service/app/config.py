"""Runtime configuration, read from environment variables or model-service/.env.

Env var names are case-insensitive, so TRANSLATION_MODEL maps to
`translation_model`, DEVICE to `device`, and so on.
"""
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env", env_file_encoding="utf-8", extra="ignore"
    )

    # --- from docs/03-MODEL-SERVICE-PYTHON.md ---
    translation_model: str = "facebook/nllb-200-distilled-600M"
    embedding_model: str = "intfloat/multilingual-e5-base"
    model_service_port: int = 8001
    device: str = "cpu"  # never hardcode cuda; set DEVICE=cuda only if available

    # --- limits (decision #2: the specs define none, so these are ours) ---
    max_input_tokens: int = 256    # per sentence; longer sentences are chunked
    max_new_tokens: int = 256      # cap on generated tokens per sentence
    num_beams: int = 4             # NLLB beam search width
    batch_size: int = 16           # sentences per forward pass
    max_items_per_request: int = 64  # larger requests are rejected with 422

    # --- generation guards (from the stress test) ---
    no_repeat_ngram_size: int = 4  # stops "ane ane ane..." loops; 0 disables
    torch_threads: int = 0         # 0 = torch default (4 on this machine)
    stub_models: bool = False      # True = fake models for teammates (app/stub.py); NOT for evaluation

    # --- v1 language pair for tokenizer init ---
    source_language: str = "eng_Latn"
    target_language: str = "sna_Latn"


@lru_cache
def get_settings() -> Settings:
    """Single cached Settings instance for the whole app."""
    return Settings()
