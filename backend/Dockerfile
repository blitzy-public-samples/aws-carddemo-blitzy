# CardDemo Backend — FastAPI service image (Python 3.13).
# Application source (app/main.py etc.) is created by implementation agents; this image
# is ready to build once that source exists.
FROM python:3.13-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# Minimal build/runtime deps for PostgreSQL client libs (psycopg2-binary ships wheels,
# but libpq is kept available for tooling).
RUN apt-get update \
    && apt-get install -y --no-install-recommends libpq5 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt ./
RUN pip install --upgrade pip \
    && pip install -r requirements.txt

COPY . .

EXPOSE 8000

# Entry point (app.main:app) is provided by implementation agents.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
