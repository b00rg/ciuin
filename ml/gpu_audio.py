"""GPU audio helpers for the PANNs teacher (CNN14 runs at 32 kHz; our audio is 16 kHz)."""

from __future__ import annotations

_FILTERS = {}


def upsample2(x):
    """[B, T] float tensor at 16 kHz -> [B, 2T] at 32 kHz, on x's device.

    Zero-stuff, then low-pass at the old Nyquist with a Hann-windowed sinc
    (129 taps) as one conv1d. Same idea as scipy's resample_poly(x, 2, 1),
    without the CPU round trip per batch.
    """
    import torch
    import torch.nn.functional as F

    key = (x.device, x.dtype)
    if key not in _FILTERS:
        k = 64
        n = torch.arange(-k, k + 1, device=x.device, dtype=torch.float32)
        h = torch.sinc(n / 2) * (0.5 + 0.5 * torch.cos(torch.pi * n / (k + 1)))
        _FILTERS[key] = (h.to(x.dtype)[None, None, :], k)
    h, k = _FILTERS[key]
    y = x.new_zeros(x.shape[0], 2 * x.shape[1])
    y[:, ::2] = x
    return F.conv1d(y[:, None, :], h, padding=k)[:, 0, :]


def fp32_frontend(net):
    """Keep CNN14's STFT + log-mel in float32 under autocast.

    In float16 the power spectrogram overflows (> 65,504) -> inf -> log -> NaN,
    and every training step is skipped. The conv layers after it stay in fp16.
    """
    import torch

    class FP32(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, x):
            with torch.autocast(device_type="cuda", enabled=False):
                return self.m(x.float())

    core = getattr(net, "module", net)
    if not isinstance(core.spectrogram_extractor, FP32):
        core.spectrogram_extractor = FP32(core.spectrogram_extractor)
        core.logmel_extractor = FP32(core.logmel_extractor)
    return net
