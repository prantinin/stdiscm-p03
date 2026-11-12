"""Tests for consumer/dedupe.py. Run from the repo root:  python -m pytest tests/test_dedupe.py -v"""

import hashlib
import threading
from concurrent.futures import ThreadPoolExecutor

import pytest

from consumer.dedupe import DedupeRegistry, sha256_of_file

H1 = "a" * 64
H2 = "b" * 64


@pytest.fixture
def reg():
    return DedupeRegistry()


def test_first_reserve_wins_second_is_duplicate(reg):
    assert reg.reserve_pending(H1) is True
    assert reg.reserve_pending(H1) is False


def test_different_hashes_do_not_conflict(reg):
    assert reg.reserve_pending(H1) is True
    assert reg.reserve_pending(H2) is True


def test_release_allows_reserving_again(reg):
    assert reg.reserve_pending(H1)
    reg.release_pending(H1)
    assert reg.reserve_pending(H1) is True


def test_release_unknown_hash_is_harmless(reg):
    reg.release_pending(H1)
    assert reg.counts() == (0, 0)


def test_commit_makes_hash_a_permanent_duplicate(reg):
    assert reg.reserve_pending(H1)
    reg.commit(H1, "P1_video.mp4")
    assert reg.counts() == (1, 0)
    assert reg.reserve_pending(H1) is False
    reg.release_pending(H1)                  # must not un-store it
    assert reg.reserve_pending(H1) is False


def test_hash_case_and_spaces_are_normalised(reg):
    assert reg.reserve_pending("ABC" + "0" * 61)
    assert reg.reserve_pending(" abc" + "0" * 61 + " ") is False


def test_20_threads_same_hash_exactly_one_wins(reg):
    barrier = threading.Barrier(20)

    def job(_):
        barrier.wait()                       # make all 20 start at the same moment
        return reg.reserve_pending(H1)

    with ThreadPoolExecutor(max_workers=20) as pool:
        results = list(pool.map(job, range(20)))

    assert results.count(True) == 1
    assert results.count(False) == 19


def test_20_threads_different_hashes_all_win(reg):
    def job(i):
        return reg.reserve_pending(hashlib.sha256(str(i).encode()).hexdigest())

    with ThreadPoolExecutor(max_workers=20) as pool:
        assert all(pool.map(job, range(20)))
    assert reg.counts() == (0, 20)


def test_reserve_release_churn_stays_consistent(reg):
    """Many threads reserve and release the same hash; at the end it must be free."""
    def job(_):
        for _ in range(200):
            if reg.reserve_pending(H1):
                reg.release_pending(H1)

    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(job, range(8)))

    assert reg.counts() == (0, 0)
    assert reg.reserve_pending(H1) is True


def test_rebuild_from_folder(reg, tmp_path):
    (tmp_path / "a.mp4").write_bytes(b"video one")
    (tmp_path / "b.mp4").write_bytes(b"video two")
    (tmp_path / "c.mp4.part").write_bytes(b"unfinished")
    (tmp_path / "d.1234.tmp.mp4").write_bytes(b"temp")
    (tmp_path / "tmp").mkdir()
    (tmp_path / "tmp" / "e.mp4").write_bytes(b"inside a sub-folder")

    assert reg.rebuild_from_folder(tmp_path) == 2
    assert reg.counts() == (2, 0)
    assert reg.reserve_pending(sha256_of_file(tmp_path / "a.mp4")) is False
    assert reg.reserve_pending(hashlib.sha256(b"unfinished").hexdigest()) is True


def test_sha256_of_file_matches_hashlib(tmp_path):
    data = b"x" * (3 * 1024 * 1024 + 17)     # bigger than one 1 MB read
    f = tmp_path / "big.bin"
    f.write_bytes(data)
    assert sha256_of_file(f) == hashlib.sha256(data).hexdigest()