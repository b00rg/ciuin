"""Acceptance tests for the Handoff 1 files in ml/export/ (#3).

    python3 -m pytest ml/tests/test_handoff1.py            # fast checks (~10 s)
    SLOW=1 python3 -m pytest ml/tests/test_handoff1.py     # + re-score the test split from raw audio

The parity test re-implements preprocess.md from scratch (stdlib `wave`
reader, the doc's windowing and silence gate) instead of reusing
ml/preprocess.py, so it checks the spec the Kotlin port follows, not just
the Python code against itself.
"""

from __future__ import annotations

import json
import math
import os
import sys
import wave
from pathlib import Path

import numpy as np
import pytest

ML = Path(__file__).resolve().parents[1]
EXPORT = ML / "export"
REPO = ML.parent
sys.path.insert(0, str(ML))

STUDENT = EXPORT / "audio-student-s.tflite"
TEACHER = EXPORT / "audio-teacher.tflite"
CLIPS = EXPORT / "test_clips"
N_CLASSES, WINDOW, HOP, SR = 9, 15_600, 8_000, 16_000

has_data = pytest.mark.skipif(
    not (REPO / "data" / "processed" / "audio_index.sqlite").exists()
    or not (REPO / "data" / "raw" / "audio").exists(),
    reason="needs the downloaded datasets and clip index")
has_cache = pytest.mark.skipif(
    not (REPO / "data" / "cache" / "teacher" / "teacher_head.keras").exists(),
    reason="needs data/cache/teacher (run ml/teacher.py)")


def interpreter(path: Path):
    from ai_edge_litert.interpreter import Interpreter

    it = Interpreter(model_path=str(path))
    it.allocate_tensors()
    i, o = it.get_input_details()[0], it.get_output_details()[0]

    def run(w: np.ndarray) -> np.ndarray:
        it.set_tensor(i["index"], w[None].astype(np.float32))
        it.invoke()
        return it.get_tensor(o["index"])[0].copy()

    return run, i, o


# ---------------------------------------------------------------- preprocess.md, from scratch

def read_wav_spec(path: Path) -> np.ndarray:
    """Step 2-3 of preprocess.md for 16-bit PCM: int16 / 32768, average channels."""
    with wave.open(str(path)) as f:
        assert f.getsampwidth() == 2, "test clips should be 16-bit PCM"
        assert f.getframerate() == SR
        raw = np.frombuffer(f.readframes(f.getnframes()), "<i2").astype(np.float32) / 32768.0
        return raw.reshape(-1, f.getnchannels()).mean(1)


def windows_spec(x: np.ndarray) -> list[tuple[int, np.ndarray]]:
    """The 'Windowing' pseudocode in preprocess.md."""
    if len(x) <= WINDOW:
        return [(0, np.pad(x, (0, WINDOW - len(x))))]
    starts = list(range(0, len(x) - WINDOW + 1, HOP))
    if starts[-1] != len(x) - WINDOW:
        starts.append(len(x) - WINDOW)
    return [(s, x[s:s + WINDOW]) for s in starts]


def level_dbfs(w: np.ndarray) -> float:
    return 10 * math.log10(float(np.mean(w.astype(np.float64) ** 2)) + 1e-12)


def active_spec(x: np.ndarray) -> list[tuple[int, np.ndarray]]:
    ws = windows_spec(x)
    active = [(s, w) for s, w in ws if level_dbfs(w) >= -60.0]
    return active or [max(ws, key=lambda sw: level_dbfs(sw[1]))]


# ---------------------------------------------------------------- tests

def test_files_present():
    for name in ("audio-student-s.tflite", "audio-teacher.tflite", "calibration.npy",
                 "results.json", "labels.txt", "model_io.md", "preprocess.md",
                 "test_clips/expected.json"):
        assert (EXPORT / name).exists(), name
    assert len(list(CLIPS.glob("*.wav"))) == 10


@pytest.mark.parametrize("model", [STUDENT, TEACHER], ids=["student-s", "teacher"])
def test_contract(model):
    from verify_tflite import verify

    assert verify(model) == []
    _, i, o = interpreter(model)
    assert list(i["shape"]) == [1, WINDOW] and i["dtype"] == np.float32
    assert list(o["shape"]) == [1, N_CLASSES] and o["dtype"] == np.float32


def test_labels_match_expected():
    labels = (EXPORT / "labels.txt").read_text().split()
    assert len(labels) == N_CLASSES
    assert json.loads((CLIPS / "expected.json").read_text())["classes"] == labels


def test_parity_from_spec():
    """WAV -> preprocess.md (independent) -> student .tflite == expected.json."""
    exp = json.loads((CLIPS / "expected.json").read_text())
    assert exp["model"] == STUDENT.name
    run, _, _ = interpreter(STUDENT)
    classes = exp["classes"]
    for clip in exp["clips"]:
        x = read_wav_spec(CLIPS / clip["file"])
        act = active_spec(x)
        assert [s for s, _ in act] == [w["start_sample"] for w in clip["windows"]], clip["file"]
        probs = np.array([run(w) for _, w in act])
        np.testing.assert_allclose(probs, [w["probabilities"] for w in clip["windows"]], atol=1e-5,
                                   err_msg=clip["file"])
        clip_p = probs.mean(0)
        np.testing.assert_allclose(clip_p, clip["clip_probabilities"], atol=1e-5, err_msg=clip["file"])
        assert classes[int(clip_p.argmax())] == clip["expected_top_class"], clip["file"]
        assert abs(clip_p.sum() - 1) < 1e-4


def test_test_clips_cover_classes():
    exp = json.loads((CLIPS / "expected.json").read_text())
    assert {c["true_class"] for c in exp["clips"]} == set(exp["classes"])


def test_calibration():
    cal = np.load(EXPORT / "calibration.npy")
    assert cal.shape == (200, WINDOW) and cal.dtype == np.float32
    assert np.isfinite(cal).all() and np.abs(cal).max() <= 1.0
    levels = np.array([level_dbfs(w) for w in cal])
    assert (levels >= -60).mean() > 0.95, "calibration windows should be mostly non-silent"
    assert len({w.tobytes() for w in cal}) == 200, "duplicate calibration windows"
    # Spread over classes: the student's predictions shouldn't collapse onto one class.
    run, _, _ = interpreter(STUDENT)
    top = np.bincount([int(run(w).argmax()) for w in cal], minlength=N_CLASSES)
    assert (top > 0).sum() >= 7, top


def test_results_json():
    r = json.loads((EXPORT / "results.json").read_text())
    for key, path in (("audio-student-s", STUDENT),):
        m = r[key]
        assert m["size_mb"] == round(path.stat().st_size / 1e6, 2)
        f1 = m["test"]["per_class_f1"]
        assert len(f1) == N_CLASSES
        assert abs(m["macro_f1"] - np.mean(list(f1.values()))) < 1e-3
        assert m["accuracy"] == m["test"]["accuracy"]
        assert 0 < m["macro_f1"] <= 1
    assert r["teacher"]["macro_f1"] > r["audio-student-s"]["macro_f1"], "student beating teacher would be suspicious"


@has_cache
def test_soft_label_cache():
    import soft_labels as SL
    import teacher as T

    for ds in ("esc50", "us8k"):
        with np.load(SL.logits_path(ds)) as s, np.load(T.shard_path(ds)) as e:
            assert np.array_equal(s["keys"], e["keys"])
            assert np.array_equal(s["clip"], e["clip"])
            assert s["logits"].shape == (len(e["clip"]), N_CLASSES)
            p = SL.soften(s["logits"][:100], 3.0)
            np.testing.assert_allclose(p.sum(1), 1, atol=1e-5)


@has_data
@pytest.mark.skipif(not os.environ.get("SLOW"), reason="slow: set SLOW=1")
def test_rescore_test_split():
    """Slow: the .tflite re-scores the test split close to what results.json reports."""
    import preprocess as pp
    import teacher as T
    from concurrent.futures import ThreadPoolExecutor

    run, _, _ = interpreter(STUDENT)
    classes = (EXPORT / "labels.txt").read_text().split()
    clips = [c for c in T.index_clips() if c["split"] == "test"]

    def windows_of(c):
        return T.clip_windows(c)[1], c["class_index"]

    pred, true = [], []
    with ThreadPoolExecutor(8) as pool:
        for w, y in pool.map(windows_of, clips):
            pred.append(int(np.mean([run(x) for x in w], 0).argmax()))
            true.append(y)
    m = T.metrics(np.array(pred), np.array(true), classes)
    r = json.loads((EXPORT / "results.json").read_text())["audio-student-s"]
    print(f"tflite re-score: acc {m['accuracy']} macro-F1 {m['macro_f1']} vs reported "
          f"{r['accuracy']} / {r['macro_f1']}")
    assert abs(m["accuracy"] - r["accuracy"]) < 0.01
    assert abs(m["macro_f1"] - r["macro_f1"]) < 0.01
