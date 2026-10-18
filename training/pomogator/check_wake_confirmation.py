"""Regression on existing validation only; no training or threshold search, no test access."""
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort

from train_personal import digest


def run_lengths(scores, threshold):
    result, length = [], 0
    for score in scores:
        length = length + 1 if np.isfinite(score) and score >= threshold else 0
        result.append(length)
    return np.asarray(result)


def main():
    root = Path(__file__).resolve().parents[2]
    run = root / 'training/pomogator/private/runs/personal-v2'
    output = run / 'confirmation-3-validation-report.json'
    if output.exists():
        raise ValueError('Preserve the previous regression report')
    training_report = json.loads((run / 'training-report.json').read_text(encoding='utf-8'))
    metadata = json.loads((run / 'dataset.json').read_text(encoding='utf-8'))
    model = run / 'hey-pomogator-personal.onnx'
    if digest(model) != training_report['onnx_sha256'] or training_report['suggested_threshold'] != 0.65:
        raise ValueError('Personal model or fixed threshold changed')
    session = ort.InferenceSession(str(model), providers=['CPUExecutionProvider'])
    name = session.get_inputs()[0].name
    streams = {}
    for split, file in [('positive', 'validation-stream.npy'), ('negative', 'negative-validation-stream.npy')]:
        features = np.load(run / file)
        scores = np.asarray([session.run(None, {name: frame[None]})[0].item() for frame in features])
        if not np.isfinite(scores).all():
            raise ValueError('Non-finite scores')
        scores[:32] = 0  # same Android warm-up
        streams[split] = scores
    rows = []
    scores = streams['positive']
    times = (np.arange(len(scores)) + 1) * 0.08
    intervals = metadata['validation_positive_intervals']
    for index, (start, end) in enumerate(intervals):
        finish = min(end + 0.9, intervals[index + 1][0]) if index + 1 < len(intervals) else end + 0.9
        window = scores[(times >= start - 0.08) & (times < finish)]
        lengths = run_lengths(window, 0.65)
        one, three = np.flatnonzero(lengths >= 1), np.flatnonzero(lengths >= 3)
        rows.append({'validation_phrase': index + 1, 'single_frame_hit': bool(len(one)),
                     'three_frame_hit': bool(len(three)), 'longest_run_frames': int(lengths.max(initial=0)),
                     'additional_detection_delay_ms': int((three[0] - one[0]) * 80) if len(one) and len(three) else None})
    negative_lengths = run_lengths(streams['negative'], 0.65)
    negatives = {}
    for count in (1, 3):
        # Diagnostic 2s cooldown, as prior validation; not Android command simulation.
        events, next_allowed = 0, 0
        for index, length in enumerate(negative_lengths):
            if length == count and index >= next_allowed:
                events += 1
                next_allowed = index + 25
        negatives[str(count)] = events
    result = {'model_sha256': digest(model), 'threshold': 0.65, 'confirmation_frames': 3,
              'frame_ms': 80, 'single_frame_hits': sum(row['single_frame_hit'] for row in rows),
              'three_frame_hits': sum(row['three_frame_hit'] for row in rows), 'positive_total': len(rows),
              'negative_events_2s_cooldown': negatives,
              'negative_source_duration_seconds': metadata['negative_validation_duration'],
              'phrases': rows, 'test_accessed': False, 'training_performed': False,
              'validation_feature_sha256': digest(run / 'validation-stream.npy'),
              'negative_feature_sha256': digest(run / 'negative-validation-stream.npy'),
              'limitations': 'Previously used validation, not new independent evaluation. Correlated frames. '
              'No audio from reported field false trigger was saved, so its rejection cannot be proven. '
              'Window detection only; not full Android six-second command state simulation.'}
    output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
