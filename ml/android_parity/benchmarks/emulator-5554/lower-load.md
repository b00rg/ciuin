# Student S lower-load benchmark repeat

The INT8 p50 improvement reproduced on `emulator-5554`. INT8 was faster in all six lower-load pairs, including the three pairs run in each order.

| Series | Host one-minute load at run starts | FP32 pooled p50 | INT8 pooled p50 | INT8 change | Pairs with lower INT8 p50 |
|---|---:|---:|---:|---:|---:|
| First | 16.51–18.14 | 0.744 ms | 0.681 ms | −8.52% | 5/6 |
| Lower load | 9.93–11.45 | 0.754 ms | 0.681 ms | −9.66% | 6/6 |

In the repeat, the median paired p50 difference was −0.075 ms. All six differences fell between −0.106 and −0.045 ms. The exact two-sided sign test over the six pairs gives p = 0.03125, treating pairs as independent. The guest load at run starts was 1.37–1.58 on four vCPUs, compared with 1.69–2.12 in the first series. Both series ran on the same 10-logical-CPU host.

| Other measurements in the repeat | FP32 | INT8 | INT8 − FP32 |
|---|---:|---:|---:|
| Pooled warm p95 | 1.490 ms | 1.446 ms | −0.044 ms |
| Median load to interpreter-ready | 5.429 ms | 6.454 ms | +1.025 ms |
| Median peak process RSS | 115.49 MiB | 115.61 MiB | +0.12 MiB |

Warm p95 was lower in three of six pairs, and the peak RSS ranges overlapped. The consistent result across both sessions is the warmed single-window median. The two sessions describe this emulator configuration.

## Run setup and evidence

The repeat used the same pinned FP32 and INT8 files, LiteRT 2.4.0 AARs, Android API 35 arm64 guest, single built-in CPU thread, and disabled XNNPACK as the first series. Each model had six fresh processes, 195 warmup and 1,300 measured calls per process, and the same 65 Kotlin-preprocessed windows. That gives 7,800 timed calls per model. All 12 runs passed score verification on the first attempt. Model and output digest sets match the first series. FP32 remains Ciúin's default.

[First series summary](runs/summary.json) · [Repeat summary and per-run measurements](quiet-runs/summary.json) · [Repeat attempts](quiet-runs/events.tsv) · [Before](quiet-runs/before-measurement-load.log) and [after](quiet-runs/after-measurement-load.log) host and guest snapshots

Use the same [benchmark commands](../../README.md#student-s-benchmark), with a fresh output directory. The comparison script can also regenerate the repeat summary from `ml/android_parity/benchmarks/emulator-5554/quiet-runs`. The [first report](README.md) defines the load and memory measurements.
