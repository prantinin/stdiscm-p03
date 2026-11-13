"""Turn the Producer and Consumer logs into the slide tables and check that no video went missing (owner: M1).

  python -m tools.summarize_logs --producer producer.log
  python -m tools.summarize_logs --producer producer.log --consumer consumer.log
  python -m tools.summarize_logs --producer producer.log --consumer consumer.log --markdown

Exit code: 0 = every check passed, 1 = a check failed, 2 = a log file could not be read.

LOG FORMAT (all programs): one line per event
    2026-11-13 13:22:52,130 | <thread name> | <message>

PRODUCER lines this tool reads (already written by the Producer):
    starting: N video(s) in <folder>
    ACCEPTED <file> (<bytes> bytes, <s>s)
    DUPLICATE <file>: ...        REJECTED <file>: ...        FAILED <file>: ...
    DROPPED <file> (queue full), retry i/n in ...        <- not final, counted as a retry
    DROPPED <file> (queue full), giving up               <- final
    RETRY <file>: ...            thread crashed

CONSUMER lines this tool reads (M2: please log exactly these):
    <producer_id>:<file> ACCEPTED|DROPPED_QUEUE_FULL|DUPLICATE|REJECTED_INVALID ...   one per upload decision
    SAVED <stored_name>                                    a worker finished the video   (optional)
    WORKER_REJECTED <stored_name>: <reason>                a worker found it was not a video (optional)
"""

import argparse
import re
import sys
from collections import defaultdict
from datetime import datetime
from pathlib import Path

LINE = re.compile(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d),(\d{1,6}) \| (.+?) \| (.*)$")

P_STARTING = re.compile(r"^starting: (\d+) video")
P_ACCEPTED = re.compile(r"^ACCEPTED (.+) \((\d+) bytes, [\d.]+s\)$")
C_DECISION = re.compile(
    r"^([^:\s]+):(.+?) (ACCEPTED|DROPPED_QUEUE_FULL|DUPLICATE|REJECTED_INVALID|REJECTED)\b")
C_SAVED = re.compile(r"^SAVED\b")
C_WORKER_REJECTED = re.compile(r"^WORKER_REJECTED\b")

PRODUCER_KEYS = ("started", "accepted", "dropped", "duplicate", "rejected", "failed",
                 "retries", "drop_retries", "bytes")
CONSUMER_KEYS = ("accepted", "dropped", "duplicate", "rejected")


# ---- reading ---------------------------------------------------------------

def read_text(path):
    """Read a log file. Handles UTF-8 and the UTF-16 that PowerShell's `>` and `2>` produce."""
    raw = Path(path).read_bytes()
    if raw.startswith((b"\xff\xfe", b"\xfe\xff")) or b"\x00" in raw[:200]:
        return raw.decode("utf-16", errors="replace")
    return raw.decode("utf-8-sig", errors="replace")


def parse_lines(text):
    """Yield (timestamp, thread, message) for every well-formed log line; other lines are ignored."""
    for line in text.splitlines():
        m = LINE.match(line.strip("\ufeff \t"))
        if not m:
            continue
        ts = datetime.strptime(f"{m.group(1)}.{m.group(2)}", "%Y-%m-%d %H:%M:%S.%f")
        yield ts, m.group(3).strip(), m.group(4)


# ---- parsing ---------------------------------------------------------------

class Times:
    def __init__(self):
        self.first = None
        self.last = None

    def see(self, ts):
        if self.first is None or ts < self.first:
            self.first = ts
        if self.last is None or ts > self.last:
            self.last = ts

    def elapsed(self):
        return (self.last - self.first).total_seconds() if self.first else 0.0


def parse_producer(texts):
    rows = defaultdict(lambda: dict.fromkeys(PRODUCER_KEYS, 0))
    crashes = []
    times = Times()
    for text in texts:
        for ts, thread, msg in parse_lines(text):
            times.see(ts)
            row = rows[thread]
            m = P_STARTING.match(msg)
            if m:
                row["started"] += int(m.group(1))
                continue
            m = P_ACCEPTED.match(msg)
            if m:
                row["accepted"] += 1
                row["bytes"] += int(m.group(2))
            elif msg.startswith("DUPLICATE "):
                row["duplicate"] += 1
            elif msg.startswith("REJECTED "):
                row["rejected"] += 1
            elif msg.startswith("FAILED "):
                row["failed"] += 1
            elif msg.startswith("DROPPED "):
                if "giving up" in msg:
                    row["dropped"] += 1
                else:
                    row["drop_retries"] += 1
                    row["retries"] += 1
            elif msg.startswith("RETRY "):
                row["retries"] += 1
            elif msg.startswith("thread crashed"):
                crashes.append(thread)
    # keep only real producer threads (they must have started or finished something)
    rows = {t: r for t, r in rows.items() if any(r.values())}
    return dict(sorted(rows.items())), crashes, times


def parse_consumer(texts):
    rows = defaultdict(lambda: dict.fromkeys(CONSUMER_KEYS, 0))
    saved = worker_rejected = 0
    for text in texts:
        for _ts, _thread, msg in parse_lines(text):
            m = C_DECISION.match(msg)
            if m:
                key = {"ACCEPTED": "accepted", "DROPPED_QUEUE_FULL": "dropped", "DUPLICATE": "duplicate",
                       "REJECTED_INVALID": "rejected", "REJECTED": "rejected"}[m.group(3)]
                rows[m.group(1)][key] += 1
            elif C_SAVED.match(msg):
                saved += 1
            elif C_WORKER_REJECTED.match(msg):
                worker_rejected += 1
    return dict(sorted(rows.items())), saved, worker_rejected


# ---- checks ----------------------------------------------------------------

def outcomes(row):
    return row["accepted"] + row["dropped"] + row["duplicate"] + row["rejected"] + row["failed"]


def build_checks(prod, crashes, cons, saved, worker_rejected):
    """Return a list of (PASS|FAIL|SKIP, text)."""
    checks = []

    for thread, row in prod.items():
        ok = outcomes(row) == row["started"]
        detail = (f"{thread}: {row['started']} files started, {outcomes(row)} ended in an outcome"
                  + ("" if ok else "  -> a file has no outcome (thread died or the log is incomplete)"))
        checks.append(("PASS" if ok else "FAIL", detail))

    if crashes:
        checks.append(("FAIL", "thread crashed in the Producer log: " + ", ".join(sorted(set(crashes)))))
    else:
        checks.append(("PASS", "no producer thread crashed"))

    if cons is None:
        checks.append(("SKIP", "no consumer log given, producer-vs-consumer checks not run"))
        return checks

    for thread, row in prod.items():
        c = cons.get(thread, dict.fromkeys(CONSUMER_KEYS, 0))
        pairs = [
            ("accepted", row["accepted"], c["accepted"]),
            ("duplicate", row["duplicate"], c["duplicate"]),
            ("rejected", row["rejected"], c["rejected"]),
            # the Consumer sees every drop, including the ones the Producer later retried
            ("drops (final + retried)", row["dropped"] + row["drop_retries"], c["dropped"]),
        ]
        for name, from_producer, from_consumer in pairs:
            ok = from_producer == from_consumer
            checks.append(("PASS" if ok else "FAIL",
                           f"{thread} {name}: producer {from_producer}, consumer {from_consumer}"))

    unknown = sorted(set(cons) - set(prod))
    if unknown:
        checks.append(("FAIL", "consumer saw uploads from producers missing in the producer log: "
                       + ", ".join(unknown)))

    accepted_total = sum(c["accepted"] for c in cons.values())
    if saved or worker_rejected:
        ok = accepted_total == saved + worker_rejected
        checks.append(("PASS" if ok else "FAIL",
                       f"consumer accepted {accepted_total} = SAVED {saved} + WORKER_REJECTED {worker_rejected}"
                       + ("" if ok else "  -> an accepted video was never finished (queue not drained yet, "
                          "or a worker lost it)")))
    else:
        checks.append(("SKIP", "no SAVED lines in the consumer log, worker check not run"))
    return checks


# ---- output ----------------------------------------------------------------

def table(headers, rows, markdown):
    cells = [[str(c) for c in r] for r in rows]
    if markdown:
        out = ["| " + " | ".join(headers) + " |", "|" + "|".join("---" for _ in headers) + "|"]
        out += ["| " + " | ".join(r) + " |" for r in cells]
        return "\n".join(out)
    widths = [max(len(h), *(len(r[i]) for r in cells)) for i, h in enumerate(headers)]
    fmt = "  ".join(f"{{:>{w}}}" for w in widths)
    return "\n".join([fmt.format(*headers)] + [fmt.format(*r) for r in cells])


def mb(n):
    return f"{n / (1024 * 1024):.1f}"


def render(prod, times, cons, saved, worker_rejected, checks, markdown):
    out = []
    head = lambda s: out.append(("\n### " if markdown else "\n== ") + s + ("" if markdown else " =="))

    head("Producer")
    keys = ("started", "accepted", "dropped", "duplicate", "rejected", "failed", "retries")
    rows = [[t] + [r[k] for k in keys] + [mb(r["bytes"])] for t, r in prod.items()]
    total = {k: sum(r[k] for r in prod.values()) for k in PRODUCER_KEYS}
    rows.append(["TOTAL"] + [total[k] for k in keys] + [mb(total["bytes"])])
    out.append(table(["thread", "files", "accepted", "dropped", "duplicate", "rejected",
                      "failed", "retries", "MB"], rows, markdown))
    out.append(f"\nelapsed: {times.elapsed():.1f}s (first to last producer log line)")

    if cons is not None:
        head("Consumer")
        rows = [[p] + [r[k] for k in CONSUMER_KEYS] for p, r in cons.items()]
        ctotal = {k: sum(r[k] for r in cons.values()) for k in CONSUMER_KEYS}
        rows.append(["TOTAL"] + [ctotal[k] for k in CONSUMER_KEYS])
        out.append(table(["producer", "accepted", "dropped (events)", "duplicate", "rejected"], rows, markdown))
        out.append(f"\nSAVED lines: {saved}   WORKER_REJECTED lines: {worker_rejected}")

    head("Checks")
    for status, text in checks:
        out.append(f"[{status}] {text}" if not markdown else f"- **{status}** {text}")
    failed = sum(1 for s, _ in checks if s == "FAIL")
    out.append(("\nRESULT: ALL CHECKS PASSED" if not failed else f"\nRESULT: {failed} CHECK(S) FAILED"))
    return "\n".join(out)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Summarize Producer/Consumer logs and check the counts add up")
    ap.add_argument("--producer", nargs="+", required=True, metavar="LOG", help="producer log file(s)")
    ap.add_argument("--consumer", nargs="+", default=None, metavar="LOG", help="consumer log file(s)")
    ap.add_argument("--markdown", action="store_true", help="print tables in Markdown (for the slides)")
    args = ap.parse_args(argv)

    try:
        prod_texts = [read_text(p) for p in args.producer]
        cons_texts = [read_text(p) for p in args.consumer] if args.consumer else None
    except OSError as e:
        print(f"cannot read log file: {e}", file=sys.stderr)
        return 2

    prod, crashes, times = parse_producer(prod_texts)
    if not prod:
        print("no producer log lines found. Is the file in the format "
              "'time | thread | message'?", file=sys.stderr)
        return 2
    cons, saved, worker_rejected = parse_consumer(cons_texts) if cons_texts is not None else (None, 0, 0)

    checks = build_checks(prod, crashes, cons, saved, worker_rejected)
    print(render(prod, times, cons, saved, worker_rejected, checks, args.markdown))
    return 1 if any(s == "FAIL" for s, _ in checks) else 0


if __name__ == "__main__":
    sys.exit(main())