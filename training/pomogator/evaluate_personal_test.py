"""One fixed-threshold held-out evaluation; never trains or searches thresholds."""
import argparse
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort

from train_personal import Features, digest, read_wav


def score_windows(scores, clips, decisions, threshold):
    times = (np.arange(len(scores)) + 1) * 0.08
    results = []
    for index, clip in enumerate(clips):
        # Do not borrow evidence from the next utterance. Warm-up matches
        # validation: discard the first 32 feature frames.
        finish = clip['source_end'] + 0.9
        if index + 1 < len(clips):
            finish = min(finish, clips[index + 1]['source_start'])
        mask = ((times >= clip['source_start'] - 0.08) & (times < finish)
                & (np.arange(len(scores)) >= 32))
        selected = np.flatnonzero(mask)
        peak = float(scores[selected].max()) if len(selected) else None
        accepted = decisions['test/' + clip['file']]
        results.append({'file': clip['file'], 'human_full_single_phrase': accepted,
                        'source_start': clip['source_start'], 'source_end': clip['source_end'],
                        'peak': peak, 'threshold_reached': peak is not None and peak >= threshold})
    positives = [item for item in results if item['human_full_single_phrase']]
    return {'threshold': threshold, 'detected': sum(item['threshold_reached'] for item in positives),
            'total_approved': len(positives), 'clips': results}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    private = root / 'training/pomogator/private'
    if not args.output.resolve().is_relative_to(private.resolve()):
        raise ValueError('Detailed evaluation must stay private')
    if args.output.exists():
        raise ValueError('Evaluation already exists; preserve the first test result')
    manifest_path = private / 'candidates/test-v1/review.json'
    decisions_path = private / 'test-review-decisions.json'
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    review = json.loads(decisions_path.read_text(encoding='utf-8'))
    frozen = manifest['frozen_evaluation']
    model = args.run / 'hey-pomogator-personal.onnx'
    report = args.run / 'training-report.json'
    if (digest(model) != frozen['model_sha256'] or
            digest(report) != frozen['training_report_sha256'] or
            review['frozen_evaluation'] != frozen):
        raise ValueError('Model, report or reviewed frozen configuration changed')
    expected = {'test/' + item['file'] for item in manifest['clips']}
    if (set(review['decisions']) != expected or
            any(type(value) is not bool for value in review['decisions'].values())):
        raise ValueError('Complete boolean review of all test candidates required')
    wav = private / 'analysis/test-v1/recording-16000.wav'
    if (digest(wav) != manifest['source_pcm_sha256'] or
            review['source_pcm_sha256'] != {'test': digest(wav)}):
        raise ValueError('Test source provenance mismatch')
    audio = read_wav(wav)
    features = Features(root / '.tools/wakeword/downloads').streaming(audio)
    comparisons = {}
    for name, path, threshold in [
        ('personal_v2', model, frozen['threshold']),
        ('generic_baseline', root / 'training/pomogator/model/pomogator.onnx', 0.5),
    ]:
        session = ort.InferenceSession(str(path), providers=['CPUExecutionProvider'])
        scores = np.asarray([float(session.run(None, {session.get_inputs()[0].name: value[None]})[0][0, 0])
                             for value in features])
        if not np.isfinite(scores).all():
            raise ValueError('Non-finite classifier output')
        comparisons[name] = score_windows(scores, manifest['clips'], review['decisions'], threshold)
        comparisons[name]['model_sha256'] = digest(path)
    rejected = [item for item in manifest['clips'] if not review['decisions']['test/' + item['file']]]
    result = {'frozen_evaluation': frozen, 'test_pcm_sha256': digest(wav),
              'review_sha256': digest(decisions_path), 'candidate_manifest_sha256': digest(manifest_path),
              'duration_seconds': len(audio) / 16000, 'candidate_count': len(manifest['clips']),
              'excluded_candidates': [item['file'] for item in rejected],
              'excluded_candidate_speech_seconds': sum(item['source_end'] - item['source_start'] for item in rejected),
              'comparisons': comparisons, 'training_performed': False,
              'limitations': 'Positive-window detection only, not Android six-second command-window simulation. '
              'Rejected clips are unscored, NOT negatives; their activation is not a false alarm. '
              'No fully labelled negative test session; no held-out false-alarm-rate claim. '
              'Candidate segmentation may miss speech; this is not recall over every utterance in the original recording.'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=True, indent=2))


if __name__ == '__main__':
    main()
