"""Usage: python ml/smooth_eval.py [path/to/student.keras]   (default: data/cache/student/audio-student-s.keras)

How much does averaging the last k one-second windows help S on the test split?

Simulates the app: one window per second (cache windows are every 0.5 s, so every other one),
decision at each second = argmax of the mean softmax of the last k windows of the same clip.
"""
import os, sys, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "3")
import numpy as np
import teacher as T, student as ST

model_path = sys.argv[1] if len(sys.argv) > 1 else str(ST.FEAT_DIR / "audio-student-s.keras")
import keras
clips = T.index_clips()
d = T.load_windows(clips)
mel = ST.load_features(clips)
te = d["split"] == "test"
idx = np.flatnonzero(te)
model = keras.models.load_model(model_path, compile=False)
logits = model.predict(mel[idx].astype(np.float32)[..., None], batch_size=1024, verbose=0)
p = np.exp(logits - logits.max(1, keepdims=True)); p /= p.sum(1, keepdims=True)
clip, y = d["clip"][idx], d["y"][idx]; start = np.arange(len(idx))  # cache keeps windows in time order within a clip
classes = ST.load_labels()["classes"]

# one window per second: within each clip, sort by start and keep every other window
order = np.lexsort((start, clip))
seqs = []
for c in np.unique(clip):
    w = order[clip[order] == c][::2]
    seqs.append(w)

def macro_f1(pred, true):
    f = []
    for k in range(len(classes)):
        tp = np.sum((pred == k) & (true == k)); fp = np.sum((pred == k) & (true != k)); fn = np.sum((pred != k) & (true == k))
        f.append(0 if tp == 0 else 2 * tp / (2 * tp + fp + fn))
    return float(np.mean(f)), f

out = {}
for k in (1, 2, 3, 4, 5):
    pred, true = [], []
    for w in seqs:
        P = p[w]
        cs = np.cumsum(P, 0)
        for t in range(len(w)):
            lo = t - k
            s = cs[t] - (cs[lo] if lo >= 0 else 0)
            pred.append(int(np.argmax(s))); true.append(int(y[w[t]]))
    pred, true = np.array(pred), np.array(true)
    f1, per = macro_f1(pred, true)
    out[k] = {"window_acc": round(float(np.mean(pred == true)), 4), "window_macro_f1": round(f1, 4),
              "siren_f1": round(per[classes.index("siren")], 4), "alarm_beep_f1": round(per[classes.index("alarm_beep")], 4),
              "n_decisions": int(len(pred))}
    print(k, out[k], flush=True)
print("clips:", len(seqs), "median windows/clip at 1 s:", int(np.median([len(w) for w in seqs])))
json.dump(out, open(T.CACHE / f"smooth_{os.path.basename(model_path)}.json", "w"), indent=1)
