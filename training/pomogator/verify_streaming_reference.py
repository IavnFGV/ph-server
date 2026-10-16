"""CPU reference for the Android synthetic PCM test; uses the same three ONNX files.

Run with numpy and onnxruntime installed. No microphone or user audio is used.
"""
from pathlib import Path
import numpy as np
import onnxruntime as ort

root = Path(__file__).resolve().parents[2]
options = ort.SessionOptions()
options.intra_op_num_threads = 1
assets = root / '.tools/wakeword/downloads'
mel_session = ort.InferenceSession(str(assets / 'melspectrogram.onnx'), options)
embedding_session = ort.InferenceSession(str(assets / 'embedding_model.onnx'), options)
classifier = ort.InferenceSession(str(root / 'training/pomogator/model/pomogator.onnx'), options)
context = np.zeros(480, np.float32)
mel_history = np.ones((76, 32), np.float32)
features = np.zeros((16, 96), np.float32)
frame = np.array([3000 if index % 80 < 40 else -3000 for index in range(1280)], np.float32)
for _ in range(40):
    pcm = np.concatenate((context, frame))
    context = pcm[-480:]
    mel = mel_session.run(None, {'input': pcm[None]})[0].reshape(-1, 32) / 10 + 2
    assert mel.shape == (8, 32)
    mel_history = np.concatenate((mel_history, mel))[-76:]
    embedding = embedding_session.run(None, {'input_1': mel_history[None, :, :, None]})[0].reshape(1, 96)
    features = np.concatenate((features, embedding))[-16:]
score = classifier.run(None, {classifier.get_inputs()[0].name: features[None]})[0].item()
print(f'REFERENCE_SCORE={score:.12f}')
print(f'REFERENCE_FEATURE_0={features[-1, 0]:.12f}')
assert abs(score - 0.000873178244) < 0.0001
