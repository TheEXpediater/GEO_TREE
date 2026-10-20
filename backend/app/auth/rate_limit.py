"""Slows down password guessing against one account.

After ``max_failures`` failed sign-ins for the same email within ``window_seconds``, further
attempts for that email are refused with 429 until the window has passed. In-memory on purpose:
the API runs as one process, and a restart simply clears the counters.
"""

from __future__ import annotations

import threading
import time
from collections import deque
from collections.abc import Callable


class LoginRateLimiter:
    def __init__(self, max_failures: int = 10, window_seconds: float = 15 * 60, clock: Callable[[], float] = time.monotonic):
        self.max_failures = max_failures
        self.window_seconds = window_seconds
        self._clock = clock
        self._failures: dict[str, deque[float]] = {}
        self._lock = threading.Lock()

    def _recent(self, key: str, now: float) -> deque[float]:
        attempts = self._failures.setdefault(key, deque())
        while attempts and now - attempts[0] > self.window_seconds:
            attempts.popleft()
        return attempts

    def retry_after(self, key: str) -> int | None:
        """Seconds until ``key`` may try again, or None when it is not limited."""
        if self.max_failures <= 0:
            return None
        with self._lock:
            now = self._clock()
            attempts = self._recent(key, now)
            if len(attempts) < self.max_failures:
                return None
            return max(1, int(self.window_seconds - (now - attempts[0])))

    def failed(self, key: str) -> None:
        with self._lock:
            self._recent(key, self._clock()).append(self._clock())

    def succeeded(self, key: str) -> None:
        with self._lock:
            self._failures.pop(key, None)
