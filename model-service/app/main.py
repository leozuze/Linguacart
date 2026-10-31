"""FastAPI app: GET /health, POST /translate, POST /embed. Inference only.

No glossary, ranking, caching, catalogue storage or protected-field checks here;
Java owns all of that (docs/01-ARCHITECTURE.md).

STUB_MODELS=true swaps in fake models (app/stub.py) so teammates can build against the API
without downloading weights. Real models are imported lazily, so stub mode needs no torch.
"""
from __future__ import annotations

import hmac
import logging
from contextlib import asynccontextmanager
from typing import TYPE_CHECKING

import uvicorn
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .config import get_settings
from .errors import ModelUnavailableError
from .schemas import (
    EmbedRequest,
    EmbedResponse,
    HealthResponse,
    TranslateRequest,
    TranslateResponse,
)

if TYPE_CHECKING:
    from .embedding import Embedder
    from .translation import Translator

logging.basicConfig(
    level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s"
)
log = logging.getLogger("model-service")


def create_app(
    translator: "Translator | None" = None,
    embedder: "Embedder | None" = None,
    load_models: bool = True,
) -> FastAPI:
    """Build the app. Tests pass fake models and load_models=False."""
    settings = get_settings()
    if settings.stub_models:
        from .stub import StubEmbedder, StubTranslator

        if translator is None:
            translator = StubTranslator()
        if embedder is None:
            embedder = StubEmbedder()
    if translator is None:
        from .translation import Translator

        translator = Translator(
            settings.translation_model,
            settings.device,
            settings.max_input_tokens,
            settings.max_new_tokens,
            settings.num_beams,
            settings.batch_size,
            settings.no_repeat_ngram_size,
        )
    if embedder is None:
        from .embedding import Embedder

        embedder = Embedder(
            settings.embedding_model,
            settings.device,
            settings.max_input_tokens,
            settings.batch_size,
        )

    def _safe_load(name: str, load) -> None:
        # Decision #4: the service still starts if a model fails to load;
        # /health reports "unavailable" and the routes return 503.
        try:
            load()
            log.info("%s model loaded", name)
        except Exception:
            log.exception("%s model failed to load", name)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if settings.stub_models:
            log.warning("STUB_MODELS=true: fake translations and embeddings, NOT for evaluation")
        else:
            import torch

            if settings.torch_threads > 0:
                torch.set_num_threads(settings.torch_threads)
            log.info("torch threads: %d, device: %s", torch.get_num_threads(), settings.device)
        if load_models:
            _safe_load("translation", lambda: translator.load(settings.source_language))
            _safe_load("embedding", embedder.load)
        yield

    app = FastAPI(title="LinguaCart model service", version="0.1.0", lifespan=lifespan)
    app.state.translator = translator
    app.state.embedder = embedder

    if settings.api_key:
        open_paths = {"/health", "/docs", "/openapi.json"}

        @app.middleware("http")
        async def _require_key(request: Request, call_next):
            given = request.headers.get("x-api-key", "")
            if request.url.path in open_paths or hmac.compare_digest(given, settings.api_key):
                return await call_next(request)
            return JSONResponse(status_code=401, content={"detail": "Missing or invalid X-API-Key"})

    # ---- JSON-only error responses; Java reads "detail" in every case ----
    @app.exception_handler(ModelUnavailableError)
    async def _unavailable(request: Request, exc: ModelUnavailableError):
        return JSONResponse(status_code=503, content={"detail": str(exc)})

    @app.exception_handler(RequestValidationError)
    async def _invalid(request: Request, exc: RequestValidationError):
        errors = [
            {"loc": list(e["loc"]), "msg": e["msg"], "type": e["type"]}
            for e in exc.errors()
        ]
        return JSONResponse(status_code=422, content={"detail": errors})

    @app.exception_handler(Exception)
    async def _internal(request: Request, exc: Exception):
        log.exception("Unhandled error on %s", request.url.path)
        return JSONResponse(status_code=500, content={"detail": "Internal server error"})

    # ---- routes (plain `def`, so FastAPI runs inference in a worker thread) ----
    @app.get("/health", response_model=HealthResponse)
    def health() -> HealthResponse:
        t_ok, e_ok = app.state.translator.loaded, app.state.embedder.loaded
        return HealthResponse(
            status="ok" if (t_ok and e_ok) else "degraded",
            translation_model="loaded" if t_ok else "unavailable",
            embedding_model="loaded" if e_ok else "unavailable",
        )

    @app.post("/translate", response_model=TranslateResponse)
    def translate(req: TranslateRequest) -> TranslateResponse:
        out = app.state.translator.translate(
            req.texts, req.sourceLanguage, req.targetLanguage
        )
        return TranslateResponse(translations=out)

    @app.post("/embed", response_model=EmbedResponse)
    def embed(req: EmbedRequest) -> EmbedResponse:
        vectors = app.state.embedder.embed([(i.type, i.text) for i in req.inputs])
        return EmbedResponse(embeddings=vectors)

    return app


app = create_app()

if __name__ == "__main__":
    # 127.0.0.1: only the Java backend on this machine should call this service.
    uvicorn.run("app.main:app", host="127.0.0.1", port=get_settings().model_service_port)
