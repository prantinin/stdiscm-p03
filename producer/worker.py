"""One producer thread: reads its own folder and uploads every video (owner: M1)."""

import logging
import threading
import time
from dataclasses import dataclass
from pathlib import Path

import grpc

from common import media_pb2
from producer import client

log = logging.getLogger("producer")

NETWORK_ERRORS = (grpc.StatusCode.UNAVAILABLE, grpc.StatusCode.DEADLINE_EXCEEDED)


@dataclass
class Settings:
    on_drop: str = "retry"               # "retry" or "skip"
    max_drop_retries: int = 3
    max_net_retries: int = 3             # backoff 1 s, 2 s, 4 s
    backoff_base_s: float = 1.0
    respect_backpressure: bool = False
    backpressure_max_wait_s: float = 30.0


class ProducerThread(threading.Thread):
    def __init__(self, index, folder, stub, stats, settings, stop_event):
        super().__init__(name=f"P{index}")
        self.folder = Path(folder)
        self.stub = stub
        self.stats = stats
        self.settings = settings
        self.stop_event = stop_event

    def run(self):
        try:
            self._run()
        except Exception:                      # one crashed thread must not stop the others
            log.exception("thread crashed")

    def _run(self):
        files = self._list_files()
        if not files:
            log.info("nothing to upload from %s", self.folder)
            return
        log.info("starting: %d video(s) in %s", len(files), self.folder)
        for path in files:
            if self.stop_event.is_set():
                break
            self._upload_file(path)
        log.info("done")

    def _list_files(self):
        if not self.folder.is_dir():
            log.warning("folder not found: %s", self.folder)
            return []
        files = []
        for p in sorted(self.folder.iterdir()):
            if not p.is_file():
                continue
            if p.suffix.lower() in client.VIDEO_EXTENSIONS:
                files.append(p)
            else:
                log.info("skipping non-video file %s", p.name)
        return files

    def _finish(self, outcome, bytes_sent=0):
        self.stats.finish_file(self.name, outcome, bytes_sent)

    def _upload_file(self, path):
        try:
            meta = client.make_meta(path, self.name)
        except OSError as e:
            log.error("FAILED %s: cannot read file (%s)", path.name, e)
            self._finish("failed")
            return

        s = self.settings
        drops = 0
        net_attempt = 0
        while not self.stop_event.is_set():
            if s.respect_backpressure:
                self._wait_if_full()
            started = time.time()
            try:
                result = client.upload(self.stub, path, meta)
            except grpc.RpcError as e:
                code = e.code()
                if code in NETWORK_ERRORS and net_attempt < s.max_net_retries:
                    wait = s.backoff_base_s * (2 ** net_attempt)
                    net_attempt += 1
                    self.stats.add(self.name, "retries")
                    log.warning("RETRY %s: %s, try %d/%d in %.2fs",
                                path.name, code.name, net_attempt, s.max_net_retries, wait)
                    if self.stop_event.wait(wait):
                        break
                    continue
                log.error("FAILED %s: %s %s", path.name, code.name, e.details())
                self._finish("failed")
                return

            status = result.status
            if status == media_pb2.ACCEPTED:
                log.info("ACCEPTED %s (%d bytes, %.2fs)", path.name, meta.size_bytes, time.time() - started)
                self._finish("accepted", meta.size_bytes)
                return
            if status == media_pb2.DUPLICATE:
                log.info("DUPLICATE %s: %s", path.name, result.message)
                self._finish("duplicate")
                return
            if status == media_pb2.REJECTED_INVALID:
                log.warning("REJECTED %s: %s", path.name, result.message)
                self._finish("rejected")
                return
            if status == media_pb2.DROPPED_QUEUE_FULL:
                if s.on_drop == "retry" and drops < s.max_drop_retries:
                    drops += 1
                    wait = (result.retry_after_ms or 1000) / 1000
                    self.stats.add(self.name, "retries")
                    log.warning("DROPPED %s (queue full), retry %d/%d in %.2fs",
                                path.name, drops, s.max_drop_retries, wait)
                    if self.stop_event.wait(wait):
                        break
                    continue
                log.warning("DROPPED %s (queue full), giving up", path.name)
                self._finish("dropped")
                return
            log.error("FAILED %s: unknown status %s", path.name, status)
            self._finish("failed")
            return

        log.warning("FAILED %s: interrupted", path.name)
        self._finish("failed")

    def _wait_if_full(self):
        """Bonus: don't start a doomed upload while the Consumer says its queue is full."""
        deadline = time.time() + self.settings.backpressure_max_wait_s
        while not self.stop_event.is_set() and time.time() < deadline:
            try:
                if not client.queue_status(self.stub).full:
                    return
            except grpc.RpcError:
                return                          # cannot tell, so just try the upload
            self.stats.add(self.name, "waits")
            self.stop_event.wait(0.5)