"""ffmpeg / ffprobe helpers used by the consumer worker threads (owner: M1).

Rules this module follows (spec section 4.4):
  * Every function raises MediaError on a bad file or an ffmpeg failure.
  * Every external command has a timeout, so a stuck ffmpeg cannot freeze a worker.
  * Output is written to a unique temp file and then moved into place with
    os.replace(), so a failed run never leaves a half-written file at `dst`.
  * No global state, so c worker threads can call these at the same time.
"""

import os
import subprocess
import uuid
from pathlib import Path

TIMEOUT_S = 120
PREVIEW_SECONDS = 10


class MediaError(Exception):
    """The file is not a usable video, or ffmpeg/ffprobe failed."""


def _run(cmd):
    """Run a command, return its stdout, raise MediaError on any failure."""
    try:
        result = subprocess.run(
            cmd, capture_output=True, text=True, timeout=TIMEOUT_S
        )
    except subprocess.TimeoutExpired:
        raise MediaError(f"{cmd[0]} timed out after {TIMEOUT_S}s")
    except FileNotFoundError:
        raise MediaError(f"{cmd[0]} not found; is ffmpeg installed and on PATH?")
    if result.returncode != 0:
        tail = result.stderr.strip()[-300:]
        raise MediaError(f"{cmd[0]} failed: {tail}")
    return result.stdout


def _tmp_path(dst):
    """Unique temp name next to dst. Keeps the extension so ffmpeg picks the right format."""
    p = Path(dst)
    return p.with_name(f"{p.stem}.{uuid.uuid4().hex[:8]}.tmp{p.suffix}")


def _encode(build_cmd, dst):
    """Run build_cmd(tmp_path), then atomically move the temp file to dst."""
    tmp = _tmp_path(dst)
    try:
        _run(build_cmd(str(tmp)))
        os.replace(tmp, dst)
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise


def probe_duration(path):
    """Return the video length in seconds. Raises MediaError if it is not a video."""
    out = _run([
        "ffprobe", "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=nw=1:nk=1",
        str(path),
    ])
    try:
        duration = float(out.strip())
    except ValueError:
        raise MediaError(f"no duration found in {path}")
    if duration <= 0:
        raise MediaError(f"invalid duration {duration} in {path}")
    return duration


def make_preview(src, dst):
    """First 10 seconds as a small H.264 mp4 (360p, no audio).
    A video shorter than 10 s gives a preview of the whole video."""
    def build(tmp):
        return [
            "ffmpeg", "-y", "-i", str(src),
            "-t", str(PREVIEW_SECONDS),
            "-an",
            "-vf", "scale=-2:360",
            "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
            "-movflags", "+faststart",
            tmp,
        ]
    _encode(build, dst)


def make_thumbnail(src, dst):
    """One JPEG frame, 320 px wide. Uses the 1 s mark, or the first frame for very short videos."""
    seek = 1 if probe_duration(src) >= 1 else 0

    def build(tmp):
        return [
            "ffmpeg", "-y", "-ss", str(seek), "-i", str(src),
            "-frames:v", "1",
            "-vf", "scale=320:-1",
            tmp,
        ]
    _encode(build, dst)


def compress(src, dst, crf=28):
    """Re-encode with libx264 at the given CRF. Returns (old_size, new_size) in bytes."""
    old_size = os.path.getsize(src)

    def build(tmp):
        return [
            "ffmpeg", "-y", "-i", str(src),
            "-c:v", "libx264", "-crf", str(crf), "-preset", "veryfast",
            "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "96k",
            "-movflags", "+faststart",
            tmp,
        ]
    _encode(build, dst)
    return old_size, os.path.getsize(dst)