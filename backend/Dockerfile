FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

# INSTALL_AI=true adds the DINOv2 leaf-assessment runtime (CPU PyTorch, NumPy, Pillow,
# scikit-learn). compose.yaml sets it for the local backend; the default keeps the image lean.
ARG INSTALL_AI=false

COPY requirements.txt requirements-ai.txt requirements-ai-torch.txt ./
RUN pip install --no-cache-dir -r requirements.txt \
    && if [ "$INSTALL_AI" = "true" ]; then pip install --no-cache-dir -r requirements-ai.txt -r requirements-ai-torch.txt; fi

COPY main.py .
COPY app ./app
# Operator utilities (e.g. scripts/bootstrap_admin.py for a production admin account).
COPY scripts ./scripts

RUN useradd --create-home geotree \
    && mkdir -p /app/uploads/tree_images /app/ai_data \
    && chown -R geotree /app/uploads /app/ai_data
USER geotree

EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
