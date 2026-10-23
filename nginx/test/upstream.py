"""nginx 검증용 가짜 upstream(nginx/test.sh). 받은 요청을 그대로 보여 주고, 스트림 응답을 흉내 낸다."""

import json
import socket
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STREAM_PATHS = ("/api/v1/notifications/stream", "/slow-stream")


class Handler(BaseHTTPRequestHandler):
    # HTTP/1.0이라 본문은 연결이 닫혀야 끝난다 — 스트림을 흉내 내기에 충분하다.

    def reply(self, body: str, content_type: str = "application/json") -> None:
        data = body.encode()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def stream(self) -> None:
        # 연결 즉시 주석 한 줄, 이어서 1초마다 이벤트 5개(알림 스트림처럼)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        self.wfile.write(b": connected\n\n")
        self.wfile.flush()
        for i in range(5):
            time.sleep(1)
            self.wfile.write(f"event: notification\ndata: {i}\n\n".encode())
            self.wfile.flush()

    def handle_any(self) -> None:
        path = self.path.split("?")[0]
        if path in STREAM_PATHS:
            self.stream()
        elif path.startswith("/echo"):
            headers = {k.lower(): v for k, v in self.headers.items()}
            self.reply(json.dumps({"host": socket.gethostname(), "path": self.path, "headers": headers}))
        else:
            self.reply('{"success":true}')

    do_GET = do_POST = handle_any

    def log_message(self, *args) -> None:  # 요청마다 로그를 남기지 않는다
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
