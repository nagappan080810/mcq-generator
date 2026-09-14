#!/bin/sh
set -e

echo "[entrypoint] Starting Ollama server on ${OLLAMA_HOST}..."
ollama serve &
OLLAMA_PID=$!

echo "[entrypoint] Waiting for Ollama to become healthy..."
for i in $(seq 1 60); do
  if curl -sf http://localhost:11434/api/tags > /dev/null 2>&1; then
    echo "[entrypoint] Ollama is healthy."
    break
  fi
  sleep 2
done

echo "[entrypoint] Pulling model ${OLLAMA_MODEL}..."
ollama pull "${OLLAMA_MODEL}" &
PULL_PID=$!

wait $PULL_PID 2>/dev/null || true
echo "[entrypoint] Model ${OLLAMA_MODEL} ready."

wait $OLLAMA_PID
