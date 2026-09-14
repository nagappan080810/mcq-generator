#!/bin/sh
set -e

echo "[entrypoint] Starting Ollama server on ${OLLAMA_HOST}..."
ollama serve &
OLLAMA_PID=$!

echo "[entrypoint] Waiting for Ollama to become healthy..."
i=0
until ollama list > /dev/null 2>&1; do
  i=$((i + 1))
  [ "$i" -ge 60 ] && break
  sleep 2
done
echo "[entrypoint] Ollama is healthy."

echo "[entrypoint] Pulling model ${OLLAMA_MODEL} in background..."
ollama pull "${OLLAMA_MODEL}" &
PULL_PID=$!

wait $PULL_PID
echo "[entrypoint] Model ${OLLAMA_MODEL} ready."

wait $OLLAMA_PID
