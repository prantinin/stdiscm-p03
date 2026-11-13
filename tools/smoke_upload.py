"""Upload ONE video and print the reply. This is the seed of producer/client.py (owner: M1).

  python -m tools.smoke_upload sample_videos/producer1/sample15.mp4
  python -m tools.smoke_upload some.mp4 --addr 10.0.0.12:50051 --id P1
"""

import argparse
import os

import grpc

from common import media_pb2, media_pb2_grpc
from consumer.dedupe import sha256_of_file

CHUNK_SIZE = 256 * 1024


def chunk_stream(path, producer_id):
    """Yield the meta message first, then the file in 256 KB pieces (never the whole file in memory)."""
    yield media_pb2.UploadChunk(meta=media_pb2.UploadMeta(
        producer_id=producer_id,
        filename=os.path.basename(path),
        size_bytes=os.path.getsize(path),
        sha256=sha256_of_file(path),
    ))
    with open(path, "rb") as f:
        while True:
            data = f.read(CHUNK_SIZE)
            if not data:
                break
            yield media_pb2.UploadChunk(data=data)


def upload(stub, path, producer_id, timeout_s=30):
    return stub.Upload(chunk_stream(path, producer_id), timeout=timeout_s)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("path")
    ap.add_argument("--addr", default="localhost:50051")
    ap.add_argument("--id", default="P1")
    args = ap.parse_args()

    channel = grpc.insecure_channel(args.addr)      # one channel, created once
    stub = media_pb2_grpc.MediaUploadServiceStub(channel)
    try:
        r = upload(stub, args.path, args.id)
        print(f"{media_pb2.UploadStatus.Name(r.status)}: {r.message} "
              f"(stored_name={r.stored_name!r}, retry_after_ms={r.retry_after_ms})")
    except grpc.RpcError as e:
        print(f"gRPC error: {e.code().name}: {e.details()}")