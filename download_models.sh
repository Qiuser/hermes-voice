#!/bin/bash
# Download model files for Hermes Voice Android app
# Usage: ./download_models.sh [--full]
#   Default: minimal setup (~175MB) - TTS + VAD + KWS + JNI
#   --full: include offline STT SenseVoice (~350MB total)

set -e

ASSETS_DIR="app/src/main/assets"
JNILIBS_DIR="app/src/main/jniLibs"

echo "=== Hermes Voice Model Downloader ==="
echo ""

# Check if we're in the right directory
if [ ! -f "app/build.gradle.kts" ]; then
    echo "Error: Run this script from the project root directory"
    exit 1
fi

mkdir -p "$ASSETS_DIR/sherpa-onnx"
mkdir -p "$ASSETS_DIR/sherpa-onnx-kws"
mkdir -p "$ASSETS_DIR/sherpa-onnx-tts"
mkdir -p "$JNILIBS_DIR"

# --- 1. JNI Libraries (required) ---
echo "[1/4] Downloading Sherpa-ONNX JNI libraries..."
if [ -f "$JNILIBS_DIR/arm64-v8a/libsherpa-onnx-jni.so" ]; then
    echo "  Already exists, skipping."
else
    cd "$JNILIBS_DIR"
    wget -q --show-progress https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.3/sherpa-onnx-v1.13.3-android.tar.bz2
    tar xjf sherpa-onnx-v1.13.3-android.tar.bz2
    mv jniLibs/arm64-v8a .
    rm -rf jniLibs sherpa-onnx-v1.13.3-android.tar.bz2
    cd - > /dev/null
    echo "  Done."
fi

# --- 2. VAD (required, ~630KB) ---
echo "[2/4] Downloading Silero VAD..."
if [ -f "$ASSETS_DIR/sherpa-onnx/silero_vad.onnx" ]; then
    echo "  Already exists, skipping."
else
    wget -q --show-progress -O "$ASSETS_DIR/sherpa-onnx/silero_vad.onnx" \
        https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
    echo "  Done."
fi

# --- 3. KWS - Wake Word (required, ~5MB) ---
echo "[3/4] Downloading KWS model (wake word detection)..."
if [ -f "$ASSETS_DIR/sherpa-onnx-kws/encoder.onnx" ]; then
    echo "  Already exists, skipping."
else
    TMPDIR=$(mktemp -d)
    wget -q --show-progress -O "$TMPDIR/kws.tar.bz2" \
        https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01.tar.bz2
    tar xjf "$TMPDIR/kws.tar.bz2" -C "$TMPDIR"
    KWS_SRC="$TMPDIR/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01"
    cp "$KWS_SRC/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$ASSETS_DIR/sherpa-onnx-kws/encoder.onnx"
    cp "$KWS_SRC/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$ASSETS_DIR/sherpa-onnx-kws/decoder.onnx"
    cp "$KWS_SRC/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" "$ASSETS_DIR/sherpa-onnx-kws/joiner.onnx"
    cp "$KWS_SRC/tokens.txt" "$ASSETS_DIR/sherpa-onnx-kws/tokens.txt"
    rm -rf "$TMPDIR"
    echo "  Done."
fi

# --- 4. TTS - Matcha zh-en (required, ~125MB) ---
echo "[4/4] Downloading TTS model (Matcha Chinese+English)..."
if [ -f "$ASSETS_DIR/sherpa-onnx-tts/model-steps-3.onnx" ]; then
    echo "  Already exists, skipping."
else
    TMPDIR=$(mktemp -d)
    wget -q --show-progress -O "$TMPDIR/tts.tar.bz2" \
        https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/matcha-icefall-zh-en.tar.bz2
    tar xjf "$TMPDIR/tts.tar.bz2" -C "$TMPDIR"
    TTS_SRC="$TMPDIR/matcha-icefall-zh-en"
    cp -r "$TTS_SRC"/* "$ASSETS_DIR/sherpa-onnx-tts/"
    rm -f "$ASSETS_DIR/sherpa-onnx-tts/README.md"
    rm -rf "$TMPDIR"

    # Download vocoder
    wget -q --show-progress -O "$ASSETS_DIR/sherpa-onnx-tts/vocos.onnx" \
        https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos-16khz-univ.onnx
    echo "  Done."
fi

# --- Optional: Offline STT (SenseVoice, ~230MB) ---
if [ "$1" = "--full" ]; then
    echo ""
    echo "[Optional] Downloading offline STT model (SenseVoice)..."
    if [ -f "$ASSETS_DIR/sherpa-onnx/model.int8.onnx" ]; then
        echo "  Already exists, skipping."
    else
        TMPDIR=$(mktemp -d)
        wget -q --show-progress -O "$TMPDIR/stt.tar.bz2" \
            https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2
        tar xjf "$TMPDIR/stt.tar.bz2" -C "$TMPDIR"
        STT_SRC="$TMPDIR/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
        cp "$STT_SRC/model.int8.onnx" "$ASSETS_DIR/sherpa-onnx/"
        cp "$STT_SRC/tokens.txt" "$ASSETS_DIR/sherpa-onnx/"
        rm -rf "$TMPDIR"
        echo "  Done."
    fi
fi

echo ""
echo "=== Complete ==="
echo ""
echo "File structure:"
find "$ASSETS_DIR" -type f | sort | while read f; do
    size=$(du -h "$f" | cut -f1)
    echo "  $f ($size)"
done
find "$JNILIBS_DIR" -type f | sort | while read f; do
    size=$(du -h "$f" | cut -f1)
    echo "  $f ($size)"
done
echo ""
if [ "$1" = "--full" ]; then
    echo "Mode: FULL (online + offline STT)"
else
    echo "Mode: MINIMAL (online STT only)"
    echo "  Run './download_models.sh --full' to add offline STT fallback (+230MB)"
fi
echo ""
echo "Now run: cd app && ./gradlew assembleDebug"
