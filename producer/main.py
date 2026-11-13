"""Producer entry point (owner: M1).

  python -m producer.main --p 3 --root sample_videos --addr 10.0.0.12:50051
Thread Pi reads only <root>/producer<i>/ . CLI flags win over environment variables.
"""

import argparse
import logging
import os
import threading
import time
from pathlib import Path

from producer import client
from producer.stats import Stats, format_table
from producer.worker import ProducerThread, Settings


def run_producers(p, root, addr, settings, stop_event=None):
    """Start p threads, wait for them, return (stats, elapsed_seconds)."""
    stop_event = stop_event or threading.Event()
    stats = Stats()
    channel = client.make_channel(addr)          # created once; never exits if the Consumer is down
    stub = client.make_stub(channel)
    threads = [ProducerThread(i, Path(root) / f"producer{i}", stub, stats, settings, stop_event)
               for i in range(1, p + 1)]
    started = time.time()
    for t in threads:
        t.start()
    try:
        while any(t.is_alive() for t in threads):
            for t in threads:
                t.join(0.2)
    except KeyboardInterrupt:
        logging.getLogger("producer").warning("Ctrl+C: stopping")
        stop_event.set()
        for t in threads:
            t.join()
    channel.close()
    return stats, time.time() - started


def parse_args(argv=None):
    env = os.environ.get
    ap = argparse.ArgumentParser(description="Media upload Producer")
    ap.add_argument("--p", type=int, default=int(env("P", "1")), help="number of producer threads")
    ap.add_argument("--root", default=env("PRODUCER_ROOT", "./sample_videos"))
    ap.add_argument("--addr", default=env("CONSUMER_ADDR", "localhost:50051"))
    ap.add_argument("--on-drop", choices=["retry", "skip"], default="retry")
    ap.add_argument("--max-drop-retries", type=int, default=3)
    ap.add_argument("--log-file", default=None, help="also write the log to this file (for tools/summarize_logs.py)")
    ap.add_argument("--respect-backpressure", action="store_true",
                    help="bonus: wait while GetQueueStatus says the queue is full")
    return ap.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    if args.p < 1:
        raise SystemExit("--p must be at least 1")
    handlers = [logging.StreamHandler()]
    if args.log_file:
        handlers.append(logging.FileHandler(args.log_file, mode="w", encoding="utf-8"))
    logging.basicConfig(level=logging.INFO, format="%(asctime)s | %(threadName)s | %(message)s",
                        handlers=handlers)
    settings = Settings(on_drop=args.on_drop, max_drop_retries=args.max_drop_retries,
                        respect_backpressure=args.respect_backpressure)
    stats, elapsed = run_producers(args.p, args.root, args.addr, settings)
    print()
    print(format_table(stats, elapsed))


if __name__ == "__main__":
    main()