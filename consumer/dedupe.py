"""Thread-safe duplicate registry used by the consumer (owner: M1).

A video is identified by its SHA-256. A hash is in one of three states:
  * unknown  - never seen
  * pending  - an upload with this hash was accepted and is still being received,
               queued, or processed
  * stored   - a worker finished processing it (dedupe.commit)

M2 uses it like this:
  server.Upload:   if not dedupe.reserve_pending(sha): return DUPLICATE
                   ... if the queue is full / the upload fails: dedupe.release_pending(sha)
  worker, on success: dedupe.commit(sha, stored_name)
  worker, on failure: dedupe.release_pending(sha)
"""

import hashlib
import threading
from pathlib import Path

CHUNK = 1024 * 1024  # hash files in 1 MB reads, never the whole file in memory


def sha256_of_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            block = f.read(CHUNK)
            if not block:
                break
            h.update(block)
    return h.hexdigest()


class DedupeRegistry:
    def __init__(self):
        self._lock = threading.Lock()
        self._stored = {}      # sha256 -> stored_name
        self._pending = set()  # sha256 values currently reserved

    @staticmethod
    def _norm(sha256):
        return sha256.strip().lower()

    def reserve_pending(self, sha256):
        """Atomic check-and-reserve.
        Returns True if the caller now owns this hash and may continue with the upload.
        Returns False if the hash is already stored or pending (a duplicate)."""
        sha = self._norm(sha256)
        with self._lock:
            if sha in self._stored or sha in self._pending:
                return False
            self._pending.add(sha)
            return True

    def commit(self, sha256, stored_name):
        """The video was saved. Move the hash from pending to stored."""
        sha = self._norm(sha256)
        with self._lock:
            self._pending.discard(sha)
            self._stored[sha] = stored_name

    def release_pending(self, sha256):
        """The upload failed or was dropped. Free the hash so it can be uploaded again.
        Safe to call for a hash that is not pending."""
        sha = self._norm(sha256)
        with self._lock:
            self._pending.discard(sha)

    def is_known(self, sha256):
        sha = self._norm(sha256)
        with self._lock:
            return sha in self._stored or sha in self._pending

    def counts(self):
        """(stored, pending) - handy for logging and the GUI status bar."""
        with self._lock:
            return len(self._stored), len(self._pending)

    def rebuild_from_folder(self, uploads_dir):
        """Startup: hash every finished video in uploads_dir and mark it stored.
        Skips sub-folders (like uploads/tmp) and unfinished .part/.tmp files.
        Returns the number of files loaded.

        Note: if compression is on, the saved file is a re-encoded copy, so its hash
        differs from the original the producer sends. After a restart, those videos
        will not be detected as duplicates unless you also persist the original hashes."""
        found = {}
        for p in Path(uploads_dir).iterdir():
            if not p.is_file() or ".part" in p.suffixes or ".tmp" in p.name:
                continue
            found[sha256_of_file(p).lower()] = p.name   # slow part, done outside the lock
        with self._lock:
            self._stored.update(found)
            self._pending -= set(found)
        return len(found)