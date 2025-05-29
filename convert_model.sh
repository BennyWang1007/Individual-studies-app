#!/bin/bash
set -e

MODEL_STG=5
MODEL_VER=4

MODEL_NAME=Qwen2.5-0.5B-Instruct-cl_24233news_${MODEL_STG}stg_v${MODEL_VER}-lr_adj
QUANTIZATION=q4bf16_1

OUTPUT_DIR=dist/${MODEL_NAME}-${QUANTIZATION}-MLC

mkdir -p ${OUTPUT_DIR}

mlc_llm convert_weight ./${MODEL_NAME}/ \
    --output ${OUTPUT_DIR} \
    --quantization ${QUANTIZATION} \
    --model-type qwen2

mlc_llm gen_config ./${MODEL_NAME}/ \
    --quantization $QUANTIZATION \
    --model-type qwen2 \
    --conv-template chatml \
    --context-window-size 2048 \
    --output ${OUTPUT_DIR}

# convert the model for android
mkdir -p dist/libs

TARGET_DEVICE=android

mlc_llm compile ./${OUTPUT_DIR}/mlc-chat-config.json \
    --device ${TARGET_DEVICE} \
    --system-lib-prefix "qwen2_${MODEL_STG}stg_v${MODEL_VER}_${QUANTIZATION}_" \
    -o ./dist/libs/${MODEL_NAME}-${QUANTIZATION}-${TARGET_DEVICE}.tar