"""Tests for tools/summarize_logs.py. Run from the repo root:  python -m pytest tests/test_summarize_logs.py -v"""

import logging
import socket

import pytest

from producer.main import run_producers
from producer.worker import Settings
from tools import summarize_logs
from tools.fake_consumer import serve

FMT = "%(asctime)s | %(threadName)s | %(message)s"


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture
def real_logs(tmp_path):
    """Run the real Producer against the fake consumer and capture both logs in files."""
    files = {"producer": tmp_path / "producer.log", "consumer": tmp_path / "consumer.log"}
    attached = []
    for logger_name, key in (("producer", "producer"), ("fake_consumer", "consumer")):
        lg = logging.getLogger(logger_name)
        h = logging.FileHandler(files[key], encoding="utf-8")
        h.setFormatter(logging.Formatter(FMT))
        lg.addHandler(h)
        lg.setLevel(logging.INFO)
        attached.append((lg, h))

    root = tmp_path / "videos"
    for i in (1, 2, 3):
        (root / f"producer{i}").mkdir(parents=True)
        for j in range(3):
            (root / f"producer{i}" / f"v{j}.mp4").write_bytes(f"{i}-{j}".encode() * 50_000)
    (root / "producer1" / "dup.mp4").write_bytes(b"shared video" * 20_000)
    (root / "producer2" / "dup.mp4").write_bytes(b"shared video" * 20_000)

    port = free_port()
    server = serve(port, "none", 3, 0, 5)                      # every 3rd upload is dropped
    settings = Settings(on_drop="retry", max_drop_retries=3, backoff_base_s=0.01)
    run_producers(3, root, f"localhost:{port}", settings)
    server.stop(grace=0)
    for lg, h in attached:
        lg.removeHandler(h)
        h.close()
    return files


def run(files, *extra, consumer=True):
    args = ["--producer", str(files["producer"])]
    if consumer:
        args += ["--consumer", str(files["consumer"])]
    return summarize_logs.main(args + list(extra))


def test_real_run_passes_all_checks(real_logs, capsys):
    assert run(real_logs) == 0
    out = capsys.readouterr().out
    assert "TOTAL" in out and "ALL CHECKS PASSED" in out and "[FAIL]" not in out


def test_producer_log_alone_works(real_logs, capsys):
    assert run(real_logs, consumer=False) == 0
    assert "no consumer log given" in capsys.readouterr().out


def test_missing_producer_outcome_is_caught(real_logs, capsys):
    lines = real_logs["producer"].read_text(encoding="utf-8").splitlines()
    i = next(n for n, l in enumerate(lines) if "| ACCEPTED " in l)
    del lines[i]                                               # a finished file vanishes from the log
    real_logs["producer"].write_text("\n".join(lines) + "\n", encoding="utf-8")
    assert run(real_logs, consumer=False) == 1
    assert "[FAIL]" in capsys.readouterr().out


def test_producer_consumer_mismatch_is_caught(real_logs, capsys):
    lines = real_logs["consumer"].read_text(encoding="utf-8").splitlines()
    i = next(n for n, l in enumerate(lines) if " ACCEPTED " in l and ":" in l.split("|")[2])
    del lines[i]
    real_logs["consumer"].write_text("\n".join(lines) + "\n", encoding="utf-8")
    assert run(real_logs) == 1
    assert "accepted: producer" in capsys.readouterr().out


def test_thread_crash_is_flagged(real_logs, capsys):
    with open(real_logs["producer"], "a", encoding="utf-8") as f:
        f.write("2026-11-13 10:00:00,000 | P1 | thread crashed\n")
    assert run(real_logs, consumer=False) == 1
    assert "crashed" in capsys.readouterr().out


def test_utf16_log_from_powershell_is_readable(real_logs, capsys):
    text = real_logs["producer"].read_text(encoding="utf-8")
    real_logs["producer"].write_text(text, encoding="utf-16")  # what `2> file` gives in Windows PowerShell 5
    assert run(real_logs) == 0


def test_markdown_output(real_logs, capsys):
    assert run(real_logs, "--markdown") == 0
    assert "|---|" in capsys.readouterr().out


def test_missing_file_gives_exit_code_2(tmp_path, capsys):
    assert summarize_logs.main(["--producer", str(tmp_path / "nope.log")]) == 2


def test_not_a_log_gives_exit_code_2(tmp_path, capsys):
    f = tmp_path / "x.log"
    f.write_text("hello\nworld\n")
    assert summarize_logs.main(["--producer", str(f)]) == 2


# ---- hand-written logs, to pin down the exact rules ----------------------------------

PRODUCER = """\
2026-11-13 10:00:00,000 | P1 | starting: 2 video(s) in x
2026-11-13 10:00:01,000 | P1 | DROPPED a.mp4 (queue full), retry 1/3 in 0.50s
2026-11-13 10:00:02,000 | P1 | ACCEPTED a.mp4 (1000 bytes, 0.10s)
2026-11-13 10:00:03,000 | P1 | ACCEPTED b.mp4 (2000 bytes, 0.10s)
2026-11-13 10:00:03,100 | P1 | done
"""


def consumer_log(saved):
    lines = [
        "2026-11-13 10:00:01,000 | rpc_0 | P1:a.mp4 DROPPED_QUEUE_FULL (upload #1)",
        "2026-11-13 10:00:02,000 | rpc_0 | P1:a.mp4 ACCEPTED 1000 bytes",
        "2026-11-13 10:00:03,000 | rpc_1 | P1:b.mp4 ACCEPTED 2000 bytes",
    ] + [f"2026-11-13 10:00:04,000 | worker-{i} | SAVED P1_{n}" for i, n in enumerate(saved)]
    return "\n".join(lines) + "\n"


def write(tmp_path, producer, consumer):
    p, c = tmp_path / "p.log", tmp_path / "c.log"
    p.write_text(producer)
    c.write_text(consumer)
    return ["--producer", str(p), "--consumer", str(c)]


def test_handwritten_drop_then_accept_passes(tmp_path):
    assert summarize_logs.main(write(tmp_path, PRODUCER, consumer_log(["a.mp4", "b.mp4"]))) == 0


def test_unsaved_video_is_caught(tmp_path, capsys):
    assert summarize_logs.main(write(tmp_path, PRODUCER, consumer_log(["a.mp4"]))) == 1
    assert "never finished" in capsys.readouterr().out