FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
      osmium-tool curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY geofence_build.py .
COPY test/ ./test/
ENTRYPOINT ["python3", "geofence_build.py"]
CMD ["all", "--region", "HU", "--out", "/build", "--test-points", "test/hu-points.csv"]
