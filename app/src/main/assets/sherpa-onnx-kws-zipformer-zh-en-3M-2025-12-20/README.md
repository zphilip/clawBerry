# KWS Model Setup Instructions

## Model: sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20

Download the following files from HuggingFace or ModelScope and place them in this directory:

  https://huggingface.co/csukuangfj/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20

Required files:
  - encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx
  - decoder-epoch-12-avg-2-chunk-16-left-64.onnx
  - joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx
  - tokens.txt

## Generating keywords.txt

Install sherpa-onnx CLI:
  pip install sherpa-onnx

Create keywords_raw.txt with one keyword per line (no tokens, just Chinese/English text):
  小爱同学
  你好小爪
  开始听

Run text2token:
  sherpa-onnx-cli text2token \
    --tokens tokens.txt \
    --tokens-type ppinyin \
    keywords_raw.txt keywords.txt

The generated keywords.txt will look like:
  x iǎo ài t óng x ué :2.0 #0.35 @小爱同学
  n ǐ h ǎo x iǎo zh uǎ :2.0 #0.35 @你好小爪
  k āi sh ǐ t īng :1.5 #0.30 @开始听

Replace the placeholder keywords.txt in this directory with the generated file.

## Note on APK size

Model files total ~38MB. Consider hosting them on your server and downloading on first launch
instead of bundling in the APK. Implement download logic in KwsManager.kt if needed.
