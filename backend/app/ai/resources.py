"""Memory guard for running DINOv2 on a shared server.

On the shared Hostinger VPS another application can briefly need most of the RAM (the museum's
3D generation reached ~7.7 GB of 8 GB). Before DINOv2-heavy work GEO Tree checks how much memory
is really available and defers the work instead of risking an out-of-memory kill:

* training jobs stay QUEUED (the previous model stays active) until memory is available again;
* new dataset images are stored at once, their feature is computed later by the training job;
* a leaf assessment answers 503 AI_BUSY, so the phone records a failed attempt it can retry.

The host check and the container-headroom check are independent (see MemoryGuard).
Thresholds of 0 disable the guard (the default for local development).
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

logger = logging.getLogger(__name__)

MEMINFO = Path("/proc/meminfo")
CGROUP_MAX = Path("/sys/fs/cgroup/memory.max")
CGROUP_CURRENT = Path("/sys/fs/cgroup/memory.current")
CGROUP_STAT = Path("/sys/fs/cgroup/memory.stat")


def _host_available_mb(meminfo: Path = MEMINFO) -> int | None:
    try:
        for line in meminfo.read_text().splitlines():
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) // 1024
    except (OSError, ValueError, IndexError):
        return None
    return None


def _reclaimable_bytes(stat: Path) -> int:
    """Inactive page cache the kernel drops before it would OOM-kill (what `docker stats` excludes)."""
    try:
        for line in stat.read_text().splitlines():
            key, _, value = line.partition(" ")
            if key == "inactive_file":
                return int(value)
    except (OSError, ValueError):
        return 0
    return 0


def _cgroup_headroom_mb(limit: Path = CGROUP_MAX, current: Path = CGROUP_CURRENT, stat: Path = CGROUP_STAT) -> int | None:
    """Limit minus the memory the container really holds (current usage without reclaimable cache)."""
    try:
        raw = limit.read_text().strip()
        if raw == "max":
            return None
        used = max(0, int(current.read_text().strip()) - _reclaimable_bytes(stat))
        return max(0, int(raw) - used) // (1024 * 1024)
    except (OSError, ValueError):
        return None


@dataclass(frozen=True)
class MemorySnapshot:
    """Free memory on the host and headroom below this container's own memory limit (MB)."""

    host_mb: int | None
    container_headroom_mb: int | None


def memory_snapshot() -> MemorySnapshot:
    """Unmeasurable values are None (e.g. not Linux, or no container limit): they never block work."""
    return MemorySnapshot(_host_available_mb(), _cgroup_headroom_mb())


@dataclass(frozen=True)
class GuardDecision:
    allowed: bool
    available_mb: int | None
    required_mb: int
    container_headroom_mb: int | None = None
    container_required_mb: int = 0

    @property
    def message(self) -> str | None:
        if self.allowed:
            return None
        return (
            f"The server is low on memory ({self.available_mb} MB free on the host, {self.required_mb} MB needed; "
            f"{self.container_headroom_mb} MB left in the GEO Tree container, {self.container_required_mb} MB needed). "
            "AI work is postponed until memory is available again."
        )


class MemoryGuard:
    """Two independent checks, because they protect different things:

    * host free memory >= the operation's threshold: protects the other applications (the museum);
    * container headroom (limit minus non-reclaimable usage) >= ``container_min_mb`` for training,
      or ``container_inference_mb`` for one assessment: protects GEO Tree from its own memory limit.
      Training may have to load DINOv2 in a fresh process; an assessment only adds one image decode
      and one forward pass to an already resident model, so it needs less. Both must stay well below
      the container limit, otherwise the work could never run.
    """

    def __init__(
        self,
        training_min_mb: int,
        inference_min_mb: int,
        container_min_mb: int = 0,
        probe: Callable[[], MemorySnapshot] = memory_snapshot,
        container_inference_mb: int | None = None,
    ):
        self.training_min_mb = training_min_mb
        self.inference_min_mb = inference_min_mb
        self.container_min_mb = container_min_mb
        self.container_inference_mb = container_min_mb if container_inference_mb is None else container_inference_mb
        self._probe = probe

    @property
    def enabled(self) -> bool:
        return self.training_min_mb > 0 or self.inference_min_mb > 0 or self.container_min_mb > 0 or self.container_inference_mb > 0

    def _check(self, required_mb: int, container_required_mb: int) -> GuardDecision:
        if required_mb <= 0 and container_required_mb <= 0:
            return GuardDecision(True, None, 0)
        snap = self._probe()
        host_ok = required_mb <= 0 or snap.host_mb is None or snap.host_mb >= required_mb
        container_ok = (container_required_mb <= 0 or snap.container_headroom_mb is None
                        or snap.container_headroom_mb >= container_required_mb)
        return GuardDecision(host_ok and container_ok, snap.host_mb, required_mb,
                             snap.container_headroom_mb, container_required_mb)

    def training(self) -> GuardDecision:
        """Feature extraction for new images and classifier-head training."""
        return self._check(self.training_min_mb, self.container_min_mb)

    def inference(self) -> GuardDecision:
        """One leaf assessment (the backbone is already loaded or about to be)."""
        return self._check(self.inference_min_mb, self.container_inference_mb)
