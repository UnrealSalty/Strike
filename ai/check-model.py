"""Load and invoke the published model once; this is not an accuracy benchmark."""
import hashlib
import json
from pathlib import Path

import numpy as np
import tensorflow as tf

model = Path(__file__).parent / "src/main/assets/models/yolo26n.tflite"
expected = "fc6755446dcb17a01d926420c1b6becc69e58b60b8371d3b802054a401c16602"
if hashlib.sha256(model.read_bytes()).hexdigest() != expected:
    raise SystemExit("Model checksum does not match this snapshot")

interpreter = tf.lite.Interpreter(model_path=str(model), num_threads=2)
interpreter.allocate_tensors()
inputs = interpreter.get_input_details()
outputs = interpreter.get_output_details()
if len(inputs) != 1 or len(outputs) != 1:
    raise SystemExit("Expected one model input and one output")
if inputs[0]["shape"].tolist() != [1, 640, 640, 3] or inputs[0]["dtype"] != np.float32:
    raise SystemExit("Input does not match the detector contract")
if outputs[0]["shape"].tolist() != [1, 300, 6] or outputs[0]["dtype"] != np.float32:
    raise SystemExit("Output does not match the published model")

interpreter.set_tensor(inputs[0]["index"], np.zeros((1, 640, 640, 3), dtype=np.float32))
interpreter.invoke()
boxes = interpreter.get_tensor(outputs[0]["index"])
if not np.isfinite(boxes).all():
    raise SystemExit("Inference returned non-finite values")

print(json.dumps({
    "tensorflow": tf.__version__,
    "sha256": expected,
    "input": inputs[0]["shape"].tolist(),
    "output": outputs[0]["shape"].tolist(),
    "zero_image_inference": "PASS",
    "accuracy_validation": "not performed",
}, indent=2))
