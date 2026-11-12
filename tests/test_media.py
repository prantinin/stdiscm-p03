import subprocess
from concurrent.futures import ThreadPoolExecutor

import pytest

from consumer import media


def _make_video(path, seconds, size="640x360", rate=24):
    subprocess.run(
        ["ffmpeg", "-y", "-f", "lavfi",
         "-i", f"testsrc=duration={seconds}:size={size}:rate={rate}",
         "-pix_fmt", "yuv420p", str(path)],
        check=True, capture_output=True,
    )


@pytest.fixture(scope="session")
def samples(tmp_path_factory):
    d = tmp_path_factory.mktemp("samples")
    long_video = d / "sample15.mp4"
    short_video = d / "short3.mp4"
    tiny_video = d / "tiny.mp4"          # under 1 second
    corrupt = d / "corrupt.mp4"
    fake = d / "fake.mp4"
    _make_video(long_video, 15)
    _make_video(short_video, 3)
    _make_video(tiny_video, 0.5)
    data = long_video.read_bytes()
    corrupt.write_bytes(data[: len(data) // 4])   # moov atom is at the end, so this is unreadable
    fake.write_text("hello, I am not a video")
    return {"long": long_video, "short": short_video, "tiny": tiny_video,
            "corrupt": corrupt, "fake": fake}


@pytest.fixture
def out(tmp_path):
    d = tmp_path / "out"
    d.mkdir()
    return d


def _no_temp_files(folder):
    return [p.name for p in folder.iterdir() if ".tmp" in p.name] == []


# ---- probe_duration -------------------------------------------------------

def test_probe_normal(samples):
    assert media.probe_duration(samples["long"]) == pytest.approx(15, abs=0.5)
    assert media.probe_duration(samples["short"]) == pytest.approx(3, abs=0.5)


@pytest.mark.parametrize("name", ["corrupt", "fake"])
def test_probe_bad_files(samples, name):
    with pytest.raises(media.MediaError):
        media.probe_duration(samples[name])


def test_probe_missing_file(tmp_path):
    with pytest.raises(media.MediaError):
        media.probe_duration(tmp_path / "nope.mp4")


# ---- make_preview ---------------------------------------------------------

def test_preview_long_video_is_10_seconds(samples, out):
    dst = out / "p.mp4"
    media.make_preview(samples["long"], dst)
    assert media.probe_duration(dst) == pytest.approx(10, abs=0.7)
    assert _no_temp_files(out)


def test_preview_short_video_is_whole_video(samples, out):
    dst = out / "p.mp4"
    media.make_preview(samples["short"], dst)
    assert media.probe_duration(dst) == pytest.approx(3, abs=0.7)


@pytest.mark.parametrize("name", ["corrupt", "fake"])
def test_preview_bad_file_leaves_nothing(samples, out, name):
    dst = out / "p.mp4"
    with pytest.raises(media.MediaError):
        media.make_preview(samples[name], dst)
    assert list(out.iterdir()) == []      # no final file and no leftover temp file


# ---- make_thumbnail -------------------------------------------------------

def test_thumbnail_normal(samples, out):
    dst = out / "t.jpg"
    media.make_thumbnail(samples["long"], dst)
    assert dst.stat().st_size > 0
    assert _no_temp_files(out)


def test_thumbnail_under_one_second(samples, out):
    dst = out / "t.jpg"
    media.make_thumbnail(samples["tiny"], dst)
    assert dst.stat().st_size > 0


def test_thumbnail_bad_file(samples, out):
    with pytest.raises(media.MediaError):
        media.make_thumbnail(samples["fake"], out / "t.jpg")
    assert list(out.iterdir()) == []


# ---- compress -------------------------------------------------------------

def test_compress_returns_sizes_and_plays(samples, out):
    dst = out / "c.mp4"
    old, new = media.compress(samples["short"], dst)
    assert old == samples["short"].stat().st_size
    assert new == dst.stat().st_size > 0
    assert media.probe_duration(dst) == pytest.approx(3, abs=0.7)
    assert _no_temp_files(out)


def test_compress_bad_file(samples, out):
    with pytest.raises(media.MediaError):
        media.compress(samples["corrupt"], out / "c.mp4")
    assert list(out.iterdir()) == []


# ---- threads (no shared state) ---------------------------------------------

def test_four_threads_at_once(samples, out):
    """4 workers use the same source video at the same time, like c consumer threads would."""
    def job(i):
        media.make_preview(samples["long"], out / f"p{i}.mp4")
        media.make_thumbnail(samples["long"], out / f"t{i}.jpg")
        return media.probe_duration(out / f"p{i}.mp4")

    with ThreadPoolExecutor(max_workers=4) as pool:
        durations = list(pool.map(job, range(4)))

    assert all(d == pytest.approx(10, abs=0.7) for d in durations)
    assert _no_temp_files(out)


def test_threads_mixed_good_and_bad(samples, out):
    """A bad video in one thread must not affect the others."""
    def job(i):
        src = samples["fake"] if i % 2 else samples["short"]
        try:
            media.make_preview(src, out / f"p{i}.mp4")
            return "ok"
        except media.MediaError:
            return "error"

    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(job, range(8)))

    assert results == ["ok", "error"] * 4
    assert _no_temp_files(out)