"""Generate the NumPy reference values in Stage0AudioAnalyzerTest.kt.

Run from the repository root with NumPy installed:
    python3 ml/tests/stage0_numpy_reference.py
"""

import numpy as np


def a_weighting(frequencies_hz):
    f_squared = frequencies_hz**2
    low, mid_low, mid_high, high = np.square([20.598997, 107.65265, 737.86223, 12194.217])
    denominator = (
        (f_squared + low)
        * np.sqrt((f_squared + mid_low) * (f_squared + mid_high))
        * (f_squared + high)
    )
    return 10 ** (2 / 20) * high * f_squared**2 / denominator


def analyze(windows, sample_rate_hz):
    fft_size = 1 << (sample_rate_hz - 1).bit_length()
    frequencies_hz = np.fft.rfftfreq(fft_size, d=1 / sample_rate_hz)
    a_gain = a_weighting(frequencies_hz)
    bin_weights = np.full(frequencies_hz.shape, 2.0)
    bin_weights[[0, -1]] = 1.0
    rms_scale = np.sqrt(bin_weights / (fft_size * sample_rate_hz))

    previous = None
    for window in windows:
        samples = window.astype(np.float64)
        magnitude = np.abs(np.fft.rfft(samples - samples.mean(), n=fft_size)) * rms_scale
        power = np.sum((a_gain * magnitude) ** 2)
        level = 10 * np.log10(power) if power else -np.inf
        flux = 0.0 if previous is None else np.linalg.norm(np.maximum(magnitude - previous, 0))
        yield level, flux
        previous = magnitude


def tone_windows(sample_rate_hz):
    seconds = np.arange(sample_rate_hz) / sample_rate_hz
    low_tone = np.cos(2 * np.pi * 100 * seconds)
    middle_tone = np.cos(2 * np.pi * 1234 * seconds)
    high_tone = np.cos(2 * np.pi * (sample_rate_hz * 7 // 16) * seconds)
    low = (0.125 + 0.5 * low_tone).astype(np.float32)
    mixed = (0.125 + 0.25 * low_tone + 0.4 * middle_tone).astype(np.float32)
    high = (0.6 * high_tone).astype(np.float32)
    return low, low, mixed, high, np.zeros(sample_rate_hz, dtype=np.float32), low


def main():
    for sample_rate_hz in (16000, 48000):
        print(sample_rate_hz)
        for level, flux in analyze(tone_windows(sample_rate_hz), sample_rate_hz):
            level_literal = f"{level:.12f}" if np.isfinite(level) else "Double.NEGATIVE_INFINITY"
            print(f"    Stage0AudioMetrics({level_literal}, {flux:.12f}),")

    sample_rate_hz = 8192
    nyquist = np.where(np.arange(sample_rate_hz) % 2 == 0, 0.5, -0.5).astype(np.float32)
    _, (level, flux) = analyze([np.zeros_like(nyquist), nyquist], sample_rate_hz)
    print(f"{sample_rate_hz} Hz Nyquist: Stage0AudioMetrics({level:.12f}, {flux:.12f})")


if __name__ == "__main__":
    main()
