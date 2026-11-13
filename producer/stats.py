"""Thread-safe counters for the Producer (owner: M1).

Every file ends in exactly ONE outcome, so for every thread:
    files = accepted + dropped + duplicate + rejected + failed
"""

import threading
from collections import defaultdict

OUTCOMES = ("accepted", "dropped", "duplicate", "rejected", "failed")
COLUMNS = ("files",) + OUTCOMES + ("retries", "waits", "bytes")


class Stats:
    def __init__(self):
        self._lock = threading.Lock()
        self._data = defaultdict(lambda: defaultdict(int))   # thread name -> counter name -> value

    def add(self, thread, key, n=1):
        with self._lock:
            self._data[thread][key] += n

    def finish_file(self, thread, outcome, bytes_sent=0):
        """Record the final outcome of one file (call exactly once per file)."""
        assert outcome in OUTCOMES, outcome
        with self._lock:
            row = self._data[thread]
            row["files"] += 1
            row[outcome] += 1
            row["bytes"] += bytes_sent

    def snapshot(self):
        """Copy of all counters: {thread: {column: value}}, safe to read without the lock."""
        with self._lock:
            return {t: {c: row.get(c, 0) for c in COLUMNS} for t, row in sorted(self._data.items())}

    def totals(self):
        snap = self.snapshot()
        return {c: sum(row[c] for row in snap.values()) for c in COLUMNS}


def format_table(stats, elapsed_s):
    snap = stats.snapshot()
    rows = [(t, r) for t, r in snap.items()] + [("TOTAL", stats.totals())]
    head = ["thread"] + list(COLUMNS[:-1]) + ["MB"]
    lines = [" ".join(f"{h:>9}" for h in head)]
    for name, r in rows:
        vals = [name] + [r[c] for c in COLUMNS[:-1]] + [f"{r['bytes'] / (1024 * 1024):.1f}"]
        lines.append(" ".join(f"{v:>9}" for v in vals))
    lines.append(f"elapsed: {elapsed_s:.1f}s")
    return "\n".join(lines)