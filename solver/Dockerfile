FROM python:3.13-slim AS builder
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends g++ && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir --prefix=/install -r requirements.txt
COPY heatrouting ./heatrouting
ENV PYTHONPATH=/install/lib/python3.13/site-packages
COPY run_*.py ./
COPY tests ./tests
RUN python -m heatrouting.core.build && python -m unittest discover -s tests -v

FROM python:3.13-slim
RUN apt-get update && apt-get install -y --no-install-recommends libstdc++6 libgomp1 && rm -rf /var/lib/apt/lists/*     && useradd --create-home solver
WORKDIR /app
COPY --from=builder /install /usr/local
COPY --from=builder /app/heatrouting ./heatrouting
COPY run_*.py ./
ENV SOLVER_HOST=0.0.0.0 SOLVER_ENGINE=cpp PYTHONUNBUFFERED=1     OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MPLCONFIGDIR=/tmp/matplotlib
USER solver
EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=3s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/health', timeout=2)"
CMD ["python", "-m", "heatrouting.service"]
