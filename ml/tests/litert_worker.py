"""Run the student model on float32 waveform windows from a Kotlin desktop test."""

import sys

import numpy as np
from ai_edge_litert.interpreter import Interpreter


def main(model_path: str) -> None:
    model = Interpreter(model_path=model_path)
    model.allocate_tensors()
    input_tensor, = model.get_input_details()
    output_tensor, = model.get_output_details()
    assert tuple(input_tensor["shape"]) == (1, 15_600) and input_tensor["dtype"] == np.float32
    assert tuple(output_tensor["shape"]) == (1, 9) and output_tensor["dtype"] == np.float32

    requests = sys.stdin.buffer
    responses = sys.stdout.buffer
    responses.write(b"\x00")  # The interpreter is ready.
    responses.flush()

    # Match Java's DataOutputStream and DataInputStream byte order.
    window_bytes = 15_600 * np.dtype(">f4").itemsize
    while packet := requests.read(window_bytes):
        waveform = np.frombuffer(packet, dtype=">f4").astype(np.float32).reshape(1, 15_600)
        model.set_tensor(input_tensor["index"], waveform)
        model.invoke()
        probabilities = model.get_tensor(output_tensor["index"])
        responses.write(probabilities.astype(">f4").tobytes())
        responses.flush()


if __name__ == "__main__":
    main(sys.argv[1])
