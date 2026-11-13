"""Tests for the Producer, run against tools/fake_consumer.py.
Run from the repo root:  python -m pytest tests/test_producer.py -v"""

import os
import socket

import pytest

from producer.main import run_producers
from producer.stats import OUTCOMES
from producer.worker import Settings
from tools.fake_consumer import serve


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture
def start_consumer():
    servers = []

    def start(**kw):
        port = free_port()
        args = dict(force="none", drop_every=0, delay_ms=0, capacity=5)
        args.update(kw)
        servers.append(serve(port, args["force"], args["drop_every"], args["delay_ms"], args["capacity"]))
        return f"localhost:{port}"

    yield start
    for s in servers:
        s.stop(grace=0)


def fast(**kw):
    """Settings with tiny backoff so the tests stay quick."""
    base = dict(on_drop="skip", max_drop_retries=3, max_net_retries=3, backoff_base_s=0.01,
                respect_backpressure=False, backpressure_max_wait_s=1.0)
    base.update(kw)
    return Settings(**base)


def make_file(folder, name, size, seed=None):
    folder.mkdir(parents=True, exist_ok=True)
    # deterministic content so identical seeds give identical files (and hashes)
    token = str(seed if seed is not None else name).encode()
    data = (token * (size // len(token) + 1))[:size]
    (folder / name).write_bytes(data)


def check_identity(stats):
    """Every file ends in exactly one outcome."""
    for row in list(stats.snapshot().values()) + [stats.totals()]:
        assert row["files"] == sum(row[o] for o in OUTCOMES)


def test_two_producers_all_accepted_and_non_video_skipped(tmp_path, start_consumer):
    make_file(tmp_path / "producer1", "a.mp4", 1_200_000)       # bigger than one 256 KB chunk
    make_file(tmp_path / "producer1", "b.mkv", 300_000)
    make_file(tmp_path / "producer1", "notes.txt", 100)
    make_file(tmp_path / "producer2", "c.mov", 500_000)
    stats, _ = run_producers(2, tmp_path, start_consumer(), fast())
    t = stats.totals()
    assert (t["files"], t["accepted"]) == (3, 3)
    assert t["bytes"] == 1_200_000 + 300_000 + 500_000
    check_identity(stats)


def test_same_video_in_two_folders_is_accepted_once(tmp_path, start_consumer):
    make_file(tmp_path / "producer1", "same.mp4", 400_000, seed="x")
    make_file(tmp_path / "producer2", "same.mp4", 400_000, seed="x")
    stats, _ = run_producers(2, tmp_path, start_consumer(), fast())
    t = stats.totals()
    assert (t["accepted"], t["duplicate"]) == (1, 1)
    check_identity(stats)


def test_forced_drop_with_skip(tmp_path, start_consumer):
    for i in range(3):
        make_file(tmp_path / "producer1", f"v{i}.mp4", 10_000)
    stats, _ = run_producers(1, tmp_path, start_consumer(force="drop"), fast(on_drop="skip"))
    t = stats.totals()
    assert (t["dropped"], t["accepted"], t["retries"]) == (3, 0, 0)
    check_identity(stats)


def test_forced_drop_with_retry_gives_up_after_limit(tmp_path, start_consumer):
    make_file(tmp_path / "producer1", "v.mp4", 10_000)
    stats, _ = run_producers(1, tmp_path, start_consumer(force="drop"),
                             fast(on_drop="retry", max_drop_retries=2))
    t = stats.totals()
    assert (t["dropped"], t["retries"]) == (1, 2)
    check_identity(stats)


def test_retry_after_drop_eventually_succeeds(tmp_path, start_consumer):
    for i in range(4):
        make_file(tmp_path / "producer1", f"v{i}.mp4", 10_000)
    stats, _ = run_producers(1, tmp_path, start_consumer(drop_every=2), fast(on_drop="retry"))
    t = stats.totals()
    assert t["accepted"] == 4 and t["retries"] >= 1
    check_identity(stats)


def test_consumer_down_retries_then_fails(tmp_path):
    make_file(tmp_path / "producer1", "v.mp4", 10_000)
    stats, _ = run_producers(1, tmp_path, f"localhost:{free_port()}", fast())
    t = stats.totals()
    assert (t["failed"], t["retries"]) == (1, 3)
    check_identity(stats)


def test_missing_and_empty_folders_do_not_crash(tmp_path, start_consumer):
    make_file(tmp_path / "producer1", "v.mp4", 10_000)
    (tmp_path / "producer3").mkdir()                            # empty; producer2 does not exist at all
    stats, _ = run_producers(3, tmp_path, start_consumer(), fast())
    assert stats.totals()["files"] == 1


def test_duplicate_forced_by_server(tmp_path, start_consumer):
    make_file(tmp_path / "producer1", "v.mp4", 10_000)
    stats, _ = run_producers(1, tmp_path, start_consumer(force="duplicate"), fast())
    assert stats.totals()["duplicate"] == 1


def test_backpressure_waits_while_queue_full(tmp_path, start_consumer):
    make_file(tmp_path / "producer1", "v.mp4", 10_000)
    stats, _ = run_producers(1, tmp_path, start_consumer(force="drop"),
                             fast(respect_backpressure=True, backpressure_max_wait_s=1.0))
    t = stats.totals()
    assert t["waits"] >= 1 and t["dropped"] == 1               # waited, then tried anyway after the limit
    check_identity(stats)


def test_many_threads_at_once(tmp_path, start_consumer):
    for i in range(1, 6):
        for j in range(4):
            make_file(tmp_path / f"producer{i}", f"v{j}.mp4", 300_000, seed=f"{i}-{j}")
    stats, _ = run_producers(5, tmp_path, start_consumer(), fast())
    t = stats.totals()
    assert (t["files"], t["accepted"]) == (20, 20)
    check_identity(stats)