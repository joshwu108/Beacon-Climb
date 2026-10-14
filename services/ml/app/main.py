from fastapi import FastAPI

app = FastAPI(title="Beacon Climb ML")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}
