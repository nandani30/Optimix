#!/bin/bash
# Optimix startup script
# Run this from the optimix-frontend folder: ./start.sh

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$SCRIPT_DIR/../optimix-backend"
JAR="$BACKEND_DIR/target/optimix-backend-1.0.0.jar"

echo "⚡ Optimix Startup"
echo "=================="

# Build backend if JAR doesn't exist
if [ ! -f "$JAR" ]; then
  echo "→ Building backend (first time setup, takes ~15s)..."
  cd "$BACKEND_DIR"
  mvn clean package -DskipTests -q
  echo "✓ Backend built"
fi

# Install frontend deps if needed
if [ ! -d "$SCRIPT_DIR/node_modules" ]; then
  echo "→ Installing frontend dependencies..."
  cd "$SCRIPT_DIR"
  npm install --silent
  echo "✓ Dependencies installed"
fi

echo "→ Starting Optimix..."
cd "$SCRIPT_DIR"
npm run electron:dev
