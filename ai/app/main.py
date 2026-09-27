from fastapi import FastAPI

app = FastAPI(title="smart-ai")


# 내부 전용(Spring만 호출). 컨테이너 헬스 체크에도 쓴다.
@app.get("/internal/health")
def health() -> dict[str, str]:
    return {"status": "UP"}
