"""gRPC client helpers for the Producer (owner: M1).

One channel is created once at startup and shared by all producer threads
(gRPC channels are thread-safe and reconnect on their own).
"""

import os

import grpc

from common import media_pb2, media_pb2_grpc
from consumer.dedupe import sha256_of_file      # same hashing code as the Consumer

CHUNK_SIZE = min(int(os.environ.get("CHUNK_SIZE_KB", "256")), 256) * 1024   # spec: <= 256 KB
VIDEO_EXTENSIONS = {".mp4", ".mkv", ".avi", ".mov", ".webm"}
MSG_LIMIT = 8 * 1024 * 1024


def make_channel(addr):
    return grpc.insecure_channel(addr, options=[
        ("grpc.max_send_message_length", MSG_LIMIT),
        ("grpc.max_receive_message_length", MSG_LIMIT),
    ])


def make_stub(channel):
    return media_pb2_grpc.MediaUploadServiceStub(channel)


def make_meta(path, producer_id):
    """Hash once per file; the hash is reused if the upload has to be retried."""
    return media_pb2.UploadMeta(
        producer_id=producer_id,
        filename=os.path.basename(path),
        size_bytes=os.path.getsize(path),
        sha256=sha256_of_file(path),
    )


def chunk_stream(path, meta):
    """Yield the meta message first, then the file in pieces. Never the whole file in memory."""
    yield media_pb2.UploadChunk(meta=meta)
    with open(path, "rb") as f:
        while True:
            data = f.read(CHUNK_SIZE)
            if not data:
                break
            yield media_pb2.UploadChunk(data=data)


def deadline_for(size_bytes):
    """Minimum 30 s, plus 2 s per MB."""
    return 30.0 + 2.0 * size_bytes / (1024 * 1024)


def upload(stub, path, meta):
    return stub.Upload(chunk_stream(path, meta), timeout=deadline_for(meta.size_bytes))


def queue_status(stub):
    return stub.GetQueueStatus(media_pb2.QueueStatusRequest(), timeout=5)