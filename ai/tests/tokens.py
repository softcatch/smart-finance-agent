import time

import jwt

# 테스트용 JWT 키. conftest가 SMART_JWT_SECRET에 넣는다(Spring처럼 32자 이상).
SECRET = "test-secret-that-is-at-least-32-bytes"


# Spring이 발급하는 것과 같은 HS256 JWT. 만료는 1시간 뒤(Spring 기본값).
def bearer(sub: str = "7", secret: str = SECRET, **claims) -> str:
    payload = {"sub": sub, "exp": int(time.time()) + 3600, **claims}
    return "Bearer " + jwt.encode(payload, secret, algorithm="HS256")
