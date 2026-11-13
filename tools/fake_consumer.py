"""A fake Consumer for developing the Producer without waiting for M2 (owner: M1).

It speaks the real media.proto, checks every upload, and lets you force the
replies your Producer has to handle. It does NOT save files or run a queue.

Examples (run from the repo root):
  python -m tools.fake_consumer                      # accepts everything it receives correctly
  python -m tools.fake_consumer --force drop         # always DROPPED_QUEUE_FULL
  python -m tools.fake_consumer --drop-every 3       # every 3rd upload is dropped
  python -m tools.fake_consumer --force duplicate    # always DUPLICATE
  python -m tools.fake_consumer --delay-ms 2000      # slow server, to test deadlines
"""

import argparse
import hashlib
import logging
import threading
import time
from concurrent import futures

import grpc

from common import media_pb2, media_pb2_grpc

log = logging.getLogger("fake_consumer")

RETRY_AFTER_MS = 500


class FakeConsumer(media_pb2_grpc.MediaUploadServiceServicer):
    def __init__(self, force, drop_every, delay_ms, capacity):
        self.force = force
        self.drop_every = drop_every
        self.delay_ms = delay_ms
        self.capacity = capacity
        self._lock = threading.Lock()
        self._count = 0
        self._seen = set()          # sha256 values already accepted

    def _result(self, status, message, stored_name="", retry_after_ms=0):
        return media_pb2.UploadResult(
            status=status, message=message,
            stored_name=stored_name, retry_after_ms=retry_after_ms,
        )

    def Upload(self, request_iterator, context):
        first = next(request_iterator, None)
        if first is None or first.WhichOneof("payload") != "meta":
            log.warning("REJECTED_INVALID first message was not meta")
            return self._result(media_pb2.REJECTED_INVALID, "first message must be meta")
        meta = first.meta
        who = f"{meta.producer_id}:{meta.filename}"

        with self._lock:
            self._count += 1
            n = self._count

        # Decide BEFORE receiving any bytes, like the real Consumer will.
        if self.force == "duplicate":
            log.info("%s DUPLICATE (forced)", who)
            return self._result(media_pb2.DUPLICATE, "forced duplicate")
        if self.force == "drop" or (self.drop_every and n % self.drop_every == 0):
            log.info("%s DROPPED_QUEUE_FULL (upload #%d)", who, n)
            return self._result(media_pb2.DROPPED_QUEUE_FULL, "queue full",
                                retry_after_ms=RETRY_AFTER_MS)
        with self._lock:
            if meta.sha256 in self._seen:
                log.info("%s DUPLICATE (hash seen before)", who)
                return self._result(media_pb2.DUPLICATE, "already uploaded")

        # Receive the bytes and check them, which tests the Producer's hashing and chunking.
        h = hashlib.sha256()
        received = 0
        for chunk in request_iterator:
            if chunk.WhichOneof("payload") != "data":
                return self._result(media_pb2.REJECTED_INVALID, "extra meta message")
            h.update(chunk.data)
            received += len(chunk.data)
            if len(chunk.data) > 256 * 1024:
                return self._result(media_pb2.REJECTED_INVALID, "chunk bigger than 256 KB")
        if self.delay_ms:
            time.sleep(self.delay_ms / 1000)

        if received != meta.size_bytes:
            log.warning("%s REJECTED size %d != %d", who, received, meta.size_bytes)
            return self._result(media_pb2.REJECTED_INVALID,
                                f"size mismatch: got {received}, meta said {meta.size_bytes}")
        if h.hexdigest() != meta.sha256.lower():
            log.warning("%s REJECTED hash mismatch", who)
            return self._result(media_pb2.REJECTED_INVALID, "sha256 mismatch")

        with self._lock:
            self._seen.add(meta.sha256.lower())
        log.info("%s ACCEPTED %d bytes", who, received)
        return self._result(media_pb2.ACCEPTED, "ok", stored_name=f"{meta.producer_id}_{meta.filename}")

    def GetQueueStatus(self, request, context):
        full = self.force == "drop"
        return media_pb2.QueueStatusResponse(
            queue_length=self.capacity if full else 0,
            queue_capacity=self.capacity,
            full=full,
        )


def serve(port, force, drop_every, delay_ms, capacity):
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=16, thread_name_prefix="rpc"))
    media_pb2_grpc.add_MediaUploadServiceServicer_to_server(
        FakeConsumer(force, drop_every, delay_ms, capacity), server)
    server.add_insecure_port(f"[::]:{port}")
    server.start()
    log.info("fake consumer listening on port %d (force=%s, drop_every=%d, delay_ms=%d)",
             port, force, drop_every, delay_ms)
    return server


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=50051)
    ap.add_argument("--force", choices=["none", "drop", "duplicate"], default="none")
    ap.add_argument("--drop-every", type=int, default=0, help="drop every Nth upload")
    ap.add_argument("--delay-ms", type=int, default=0)
    ap.add_argument("--capacity", type=int, default=5, help="q reported by GetQueueStatus")
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s | %(threadName)s | %(message)s")
    srv = serve(args.port, args.force, args.drop_every, args.delay_ms, args.capacity)
    try:
        srv.wait_for_termination()
    except KeyboardInterrupt:
        srv.stop(grace=1)