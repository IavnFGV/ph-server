"""Local CPU fine-tuning of the openWakeWord classifier on reviewed personal audio.

Frozen ONNX feature extractors; original train/validation sessions stay disjoint.
The final test recording is deliberately not accessed by this script.
"""
import argparse
import copy
import hashlib
import json
import math
import random
import wave
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
from torch import nn

SEED = 20261016
RATE = 16000


def read_wav(path):
    with wave.open(str(path), 'rb') as source:
        if (source.getnchannels(), source.getsampwidth(), source.getframerate()) != (1, 2, RATE):
            raise ValueError(f'Invalid PCM format: {path}')
        return np.frombuffer(source.readframes(source.getnframes()), '<i2').astype(np.float32)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class Block(nn.Module):
    def __init__(self):
        super().__init__()
        self.fcn_layer = nn.Linear(32, 32)
        self.relu = nn.ReLU()
        self.layer_norm = nn.LayerNorm(32)

    def forward(self, x):
        return self.relu(self.layer_norm(self.fcn_layer(x)))


class Classifier(nn.Module):
    def __init__(self):
        super().__init__()
        self.flatten = nn.Flatten()
        self.layer1 = nn.Linear(16 * 96, 32)
        self.relu1 = nn.ReLU()
        self.layernorm1 = nn.LayerNorm(32)
        self.blocks = nn.ModuleList([Block()])
        self.last_layer = nn.Linear(32, 1)
        self.last_act = nn.Sigmoid()

    def forward(self, x):
        x = self.relu1(self.layernorm1(self.layer1(self.flatten(x))))
        for block in self.blocks:
            x = block(x)
        return self.last_act(self.last_layer(x))


class Features:
    def __init__(self, assets):
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        self.mel = ort.InferenceSession(str(assets / 'melspectrogram.onnx'), options)
        self.embedding = ort.InferenceSession(str(assets / 'embedding_model.onnx'), options)

    def batch(self, pcm):
        mel = self.mel.run(None, {'input': pcm.astype(np.float32)})[0].reshape(len(pcm), -1, 32) / 10 + 2
        windows = np.stack([mel[:, offset:offset + 76] for offset in range(0, 128, 8)], axis=1)
        output = self.embedding.run(None, {'input_1': windows.reshape(-1, 76, 32, 1)})[0]
        return output.reshape(len(pcm), 16, 96)

    def streaming(self, audio):
        context = np.zeros(480, np.float32)
        mel = np.ones((76, 32), np.float32)
        history = np.zeros((16, 96), np.float32)
        result = []
        for offset in range(0, len(audio) - 1279, 1280):
            pcm = np.concatenate((context, audio[offset:offset + 1280]))
            context = pcm[-480:]
            chunk = self.mel.run(None, {'input': pcm[None]})[0].reshape(-1, 32) / 10 + 2
            mel = np.concatenate((mel, chunk))[-76:]
            embedding = self.embedding.run(None, {'input_1': mel[None, :, :, None]})[0].reshape(1, 96)
            history = np.concatenate((history, embedding))[-16:]
            result.append(history.copy())
        return np.asarray(result, np.float32)


def frame(audio, rng, jitter=True):
    if len(audio) > 32000:
        raise ValueError('Positive clip exceeds two-second model input')
    result = np.zeros(32000, np.float32)
    trailing = int(rng.uniform(0, 0.2) * RATE) if jitter else 1600
    start = max(0, 32000 - len(audio) - trailing)
    result[start:start + len(audio)] = audio
    return result


def augment(audio, background, rng):
    speed = rng.uniform(0.9, 1.1)
    samples = np.interp(np.arange(0, len(audio) - 1, speed), np.arange(len(audio)), audio).astype(np.float32)
    if rng.random() < 0.45:
        # Sparse, decaying room reflections; no validation/test audio is used.
        ir = np.zeros(int(RATE * 0.18), np.float32)
        ir[0] = 1
        for delay in rng.integers(250, len(ir), size=5):
            ir[delay] += rng.uniform(0.03, 0.2) * math.exp(-delay / 1700)
        size = 1 << (len(samples) + len(ir) - 1).bit_length()
        samples = np.fft.irfft(np.fft.rfft(samples, size) * np.fft.rfft(ir, size), size)[:len(samples)].astype(np.float32)
    result = frame(samples, rng)
    result *= 10 ** (rng.uniform(-18, 5) / 20)
    start = int(rng.integers(0, len(background) - len(result)))
    noise = background[start:start + len(result)].copy()
    signal_rms = np.sqrt(np.mean(result ** 2) + 1e-6)
    noise_rms = np.sqrt(np.mean(noise ** 2) + 1e-6)
    result += noise * signal_rms / (noise_rms * 10 ** (rng.uniform(10, 30) / 20))
    peak = np.max(np.abs(result))
    if peak > 32000:
        result *= 32000 / peak
    return result


def approved(private, split, version):
    directory = private / 'candidates' / version
    manifest = json.loads((directory / 'review.json').read_text(encoding='utf-8'))
    review = json.loads((private / 'review-decisions.json').read_text(encoding='utf-8'))
    if review['source_pcm_sha256'][split] != manifest['source_pcm_sha256']:
        raise ValueError('Review belongs to a different source')
    result = []
    for item in manifest['clips']:
        key = split + '/' + item['file']
        if key not in review['decisions']:
            raise ValueError(f'Unreviewed clip: {key}')
        if review['decisions'][key]:
            result.append((directory / item['file'], item))
    return result


def prepare(root, private, output, augmentations, hard_negatives):
    if output.exists():
        raise ValueError('Use a new output directory; previous runs are preserved')
    output.mkdir(parents=True)
    rng = np.random.default_rng(SEED)
    training = approved(private, 'train', 'train-v2')
    validation = approved(private, 'validation', 'validation-v1')
    if len(training) < 20 or len(validation) < 5:
        raise ValueError('Insufficient reviewed positives')
    negative = read_wav(private / 'analysis/negative-v1/recording-16000.wav')
    # Fixed time split BEFORE augmentation. Four-second exclusion gap prevents overlapping windows.
    negative_train = negative[:132 * RATE]
    negative_validation = negative[136 * RATE:]
    if len(negative_validation) < 30 * RATE:
        raise ValueError('At least 30 seconds of held-out negative audio required')
    features = Features(root / '.tools/wakeword/downloads')
    positives, negatives = [], []
    for path, _ in training:
        audio = read_wav(path)
        for _ in range(augmentations):
            positives.append(augment(audio, negative_train, rng))
    for _ in range(len(positives) * 2):
        start = int(rng.integers(0, len(negative_train) - 32000))
        sample = negative_train[start:start + 32000].copy()
        sample *= 10 ** (rng.uniform(-12, 3) / 20)
        negatives.append(np.clip(sample, -32767, 32767))
    # Include silence/colored noise; all generated here, independent of evaluation sessions.
    for _ in range(160):
        noise = rng.normal(0, rng.uniform(0, 1000), 32000).astype(np.float32)
        negatives.append(noise)
    # Incomplete targets derived ONLY from reviewed training positives. Teach
    # complete-phrase detection without consuming any validation/test labels.
    for path, item in training:
        audio = read_wav(path)
        lead = int((item['source_start'] - item['clip_start']) * RATE)
        end = int((item['source_end'] - item['clip_start']) * RATE)
        speech = audio[lead:end]
        for index in range(hard_negatives):
            if index % 2:
                partial = speech[:int(len(speech) * rng.uniform(0.35, 0.65))]
            else:
                partial = speech[int(len(speech) * rng.uniform(0.30, 0.55)):]
            negatives.append(augment(partial, negative_train, rng))
    for name, audio in [('positive', positives), ('negative', negatives)]:
        batches = []
        for offset in range(0, len(audio), 8):
            batches.append(features.batch(np.asarray(audio[offset:offset + 8], np.float32)))
            if offset % 160 == 0:
                print(f'{name}: features {offset}/{len(audio)}', flush=True)
        np.save(output / (name + '.npy'), np.concatenate(batches))
    val_audio = read_wav(private / 'analysis/validation-v1/recording-16000.wav')
    val_features = features.streaming(val_audio)
    neg_features = features.streaming(negative_validation)
    np.save(output / 'validation-stream.npy', val_features)
    np.save(output / 'negative-validation-stream.npy', neg_features)
    metadata = {
        'target_phrase': 'Эй, Помогатор', 'seed': SEED,
        'train_originals': len(training), 'validation_originals': len(validation),
        'augmented_positive_count': len(positives), 'negative_count': len(negatives),
        'augmentation_variants_per_original': augmentations,
        'incomplete_target_negatives_per_original': hard_negatives,
        'review_sha256': digest(private / 'review-decisions.json'),
        'approved_train_sha256': {path.name: digest(path) for path, _ in training},
        'approved_validation_sha256': {path.name: digest(path) for path, _ in validation},
        'train_source_sha256': digest(private / 'analysis/train-v1/recording-16000.wav'),
        'validation_source_sha256': digest(private / 'analysis/validation-v1/recording-16000.wav'),
        'negative_source_sha256': digest(private / 'analysis/negative-v1/recording-16000.wav'),
        'negative_train_seconds': [0, 132], 'negative_validation_seconds': [136, len(negative) / RATE],
        'negative_validation_duration': len(negative_validation) / RATE,
        'validation_positive_intervals': [[item['source_start'], item['source_end']] for _, item in validation],
        'test_accessed': False,
    }
    (output / 'dataset.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(metadata, ensure_ascii=True, indent=2), flush=True)


def evaluate(network, val_features, neg_features, metadata, threshold):
    with torch.no_grad():
        scores = network(val_features).flatten().numpy()
        negatives = network(neg_features).flatten().numpy()
    positive_peaks = []
    for start, end in metadata['validation_positive_intervals']:
        begin = max(32, int(start / 0.08) - 1)
        finish = min(len(scores), int((end + 0.9) / 0.08) + 1)
        positive_peaks.append(float(scores[begin:finish].max()))
    valid_negatives = negatives[32:]
    false_events, next_allowed = 0, 0
    for index, score in enumerate(valid_negatives):
        if score >= threshold and index >= next_allowed:
            false_events += 1
            next_allowed = index + 25
    return {
        'threshold': float(threshold),
        'detected': sum(score >= threshold for score in positive_peaks),
        'total': len(positive_peaks), 'positive_peaks': positive_peaks,
        'negative_false_events_2s_cooldown': false_events,
        'negative_peak': float(valid_negatives.max()),
        'negative_validation_seconds': metadata['negative_validation_duration'],
    }


def fit(root, output, steps):
    torch.manual_seed(SEED)
    random.seed(SEED)
    torch.set_num_threads(4)
    rng = np.random.default_rng(SEED)
    metadata = json.loads((output / 'dataset.json').read_text(encoding='utf-8'))
    checkpoint = torch.load(root / 'training/pomogator/model/pomogator-checkpoint.pt',
                            map_location='cpu', weights_only=True)
    network = Classifier()
    network.load_state_dict(checkpoint['state_dict'], strict=True)
    positive = torch.from_numpy(np.load(output / 'positive.npy'))
    negative = torch.from_numpy(np.load(output / 'negative.npy'))
    val_features = torch.from_numpy(np.load(output / 'validation-stream.npy'))
    neg_features = torch.from_numpy(np.load(output / 'negative-validation-stream.npy'))
    network.eval()
    original_session = ort.InferenceSession(
        str(root / 'training/pomogator/model/pomogator.onnx'), providers=['CPUExecutionProvider'])
    probe = val_features[40:41]
    with torch.no_grad():
        torch_prediction = network(probe).numpy()
    onnx_prediction = original_session.run(None, {
        original_session.get_inputs()[0].name: probe.numpy()})[0]
    if not np.allclose(torch_prediction, onnx_prediction, atol=0.00001):
        raise ValueError('Initial checkpoint differs from original ONNX')
    baseline = evaluate(network, val_features, neg_features, metadata, 0.5)
    print('BASELINE', json.dumps(baseline), flush=True)
    optimizer = torch.optim.Adam(network.parameters(), lr=0.0001, weight_decay=0.0001)
    best_key, best_state, best_metrics = None, None, None
    for step in range(1, steps + 1):
        network.train()
        pos = positive[rng.integers(0, len(positive), 64)]
        neg = negative[rng.integers(0, len(negative), 64)]
        data = torch.cat((pos, neg))
        labels = torch.cat((torch.ones(64, 1), torch.zeros(64, 1)))
        weight = torch.cat((torch.ones(64, 1), torch.full((64, 1), 2.0)))
        loss = nn.functional.binary_cross_entropy(network(data), labels, weight=weight)
        optimizer.zero_grad()
        loss.backward()
        optimizer.step()
        if step % 100 == 0:
            network.eval()
            metrics = evaluate(network, val_features, neg_features, metadata, 0.5)
            separation = min(metrics['positive_peaks']) - metrics['negative_peak']
            key = (metrics['negative_false_events_2s_cooldown'],
                   -metrics['detected'], -separation)
            if best_key is None or key < best_key:
                best_key, best_state, best_metrics = key, copy.deepcopy(network.state_dict()), metrics
                torch.save({'state_dict': best_state, 'input_shape': (16, 96),
                            'model_type': 'dnn', 'layer_dim': 32, 'step': step},
                           output / 'personal-checkpoint.pt')
            print(f'STEP {step}: loss={loss.item():.4f} hit={metrics["detected"]}/{metrics["total"]} '
                  f'negative_events={metrics["negative_false_events_2s_cooldown"]} '
                  f'min_positive={min(metrics["positive_peaks"]):.4f} neg_peak={metrics["negative_peak"]:.4f}', flush=True)
    network.load_state_dict(best_state)
    network.eval()
    # Choose a margin threshold using validation only; keep the test untouched.
    threshold = round(float(np.clip(
        (min(best_metrics['positive_peaks']) + best_metrics['negative_peak']) / 2, 0.1, 0.95
    )), 2)
    calibrated = evaluate(network, val_features, neg_features, metadata, threshold)
    model_path = output / 'hey-pomogator-personal.onnx'
    torch.onnx.export(network, torch.zeros(1, 16, 96), str(model_path), opset_version=13, dynamo=False)
    onnx.checker.check_model(str(model_path))
    session = ort.InferenceSession(str(model_path), providers=['CPUExecutionProvider'])
    for index in (40, len(val_features) // 2, len(val_features) - 1):
        value = val_features[index:index + 1]
        with torch.no_grad():
            expected = network(value).numpy()
        actual = session.run(None, {session.get_inputs()[0].name: value.numpy()})[0]
        if not np.allclose(expected, actual, atol=0.00001):
            raise ValueError('ONNX export prediction mismatch')
    report = {
        'method': 'fine-tune classifier, frozen ONNX mel/embedding, CPU',
        'torch_version': torch.__version__, 'steps': steps, 'seed': SEED,
        'baseline_validation': baseline, 'personal_validation': best_metrics,
        'calibrated_validation': calibrated, 'suggested_threshold': threshold,
        'selected_step': torch.load(output / 'personal-checkpoint.pt', weights_only=True)['step'],
        'onnx_sha256': digest(model_path), 'onnx_bytes': model_path.stat().st_size,
        'initial_checkpoint_sha256': digest(root / 'training/pomogator/model/pomogator-checkpoint.pt'),
        'test_accessed': False, 'limitations': 'One speaker; 10 validation positives; ~60s negative holdout from same negative recording. No long-term false-activation claim.',
    }
    (output / 'training-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('RESULT', json.dumps(report, ensure_ascii=True), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('stage', choices=['prepare', 'fit'])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--augmentations', type=int, default=30)
    parser.add_argument('--steps', type=int, default=1200)
    parser.add_argument('--hard-negatives', type=int, default=0)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    private = root / 'training/pomogator/private'
    if not args.output.resolve().is_relative_to(private.resolve()):
        raise ValueError('Personal run output must stay in the Git-ignored private directory')
    if args.stage == 'prepare':
        prepare(root, private, args.output, args.augmentations, args.hard_negatives)
    else:
        fit(root, args.output, args.steps)


if __name__ == '__main__':
    main()
