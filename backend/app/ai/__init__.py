"""Tamarind leaf condition recognition: frozen DINOv2 backbone + lightweight classifier head.

Only :mod:`app.ai.config` is imported unconditionally. Everything that needs NumPy, Pillow,
scikit-learn or PyTorch is imported only when ``AI_ENABLED`` is true, so a server without the
AI runtime (e.g. the hosted deployment) starts exactly as before.
"""
