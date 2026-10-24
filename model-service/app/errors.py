"""Shared exceptions."""


class ModelUnavailableError(RuntimeError):
    """A model failed to load or has not been loaded yet.

    main.py maps this to HTTP 503 with a JSON {"detail": ...} body.
    """
