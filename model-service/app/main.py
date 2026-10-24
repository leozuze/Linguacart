from fastapi import FastAPI

app = FastAPI(title="LinguaCart Model Service")


@app.get("/health")
def health():
    return {
        "status": "ok",
        "translation_model": "not_loaded",
        "embedding_model": "not_loaded"
    }
