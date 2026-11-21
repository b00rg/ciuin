"""Compare TFLite operator outputs on host and Android using the same waveforms."""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
import hashlib
from importlib.metadata import version
import json
import mmap
from pathlib import Path

import flatbuffers
import numpy as np
from ai_edge_litert import schema_py_generated as schema
from ai_edge_litert.interpreter import Interpreter, OpResolverType

import preprocess
from verify_student_s_int8 import CLIPS

OPTIMIZED = OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES
REFERENCE = OpResolverType.BUILTIN_REF
FIRST_CLASSIFIER_CONV = 14


@dataclass
class Differences:
    elements: int = 0
    changed_elements: int = 0
    changed_windows: int = 0
    max_delta: float = 0

    def measure(self, host: np.ndarray, android: np.ndarray) -> bool:
        delta = np.abs(host.astype(np.float64) - android)
        changed = int(np.count_nonzero(delta))
        self.elements += delta.size
        self.changed_elements += changed
        self.changed_windows += bool(changed)
        self.max_delta = max(self.max_delta, float(delta.max()))
        return bool(changed)

    def describe(self, windows: int) -> str:
        return (f"{self.changed_windows}/{windows} windows, "
                f"{self.changed_elements:,}/{self.elements:,} elements, max delta {self.max_delta:.9g}")


def unpack(contents: bytes):
    return schema.ModelT.InitFromObj(schema.Model.GetRootAs(contents))


def pack(model, size: int) -> bytes:
    builder = flatbuffers.Builder(size)
    builder.Finish(model.Pack(builder), file_identifier=b"TFL3")
    return bytes(builder.Output())


def expose_outputs(contents: bytes) -> bytes:
    model = unpack(contents)
    graph, = model.subgraphs
    # Preserve the original probability output at index 0, then expose each
    # operator's outputs in execution order without changing any operator.
    graph.outputs = list(dict.fromkeys(
        [*graph.outputs, *(index for op in graph.operators for index in op.outputs)]
    ))
    return pack(model, len(contents))


def interpreter(contents: bytes, resolver=OPTIMIZED) -> Interpreter:
    instance = Interpreter(model_content=contents, num_threads=1, experimental_op_resolver_type=resolver)
    instance.allocate_tensors()
    return instance


def isolated_op(contents: bytes, index: int, dynamic_tensors: set[int]):
    model = unpack(contents)
    graph, = model.subgraphs
    if not 0 <= index < len(graph.operators):
        raise ValueError(f"operator index {index} is outside the graph")
    operator = graph.operators[index]
    graph.inputs = list(dict.fromkeys(tensor for tensor in operator.inputs if tensor in dynamic_tensors))
    graph.outputs = operator.outputs
    graph.operators = [operator]
    model.signatureDefs = None
    return pack(model, len(contents)), graph.inputs


def first_conv_rounding(traced: bytes, execution: Interpreter):
    graph, = unpack(traced).subgraphs
    operator = graph.operators[FIRST_CLASSIFIER_CONV]
    options = operator.builtinOptions
    inputs, weights, biases = operator.inputs
    output, = operator.outputs
    details = {tensor["index"]: tensor for tensor in execution.get_tensor_details()}
    weight_params = details[weights]["quantization_parameters"]
    weights_values = execution.get_tensor(weights)
    bias_values = execution.get_tensor(biases)
    if (
        execution._get_ops_details()[FIRST_CLASSIFIER_CONV]["op_name"] != "CONV_2D"
        or options.padding != schema.Padding.SAME
        or options.fusedActivationFunction != schema.ActivationFunctionType.RELU
        or (options.strideH, options.strideW, options.dilationHFactor, options.dilationWFactor) != (1, 1, 1, 1)
        or details[inputs]["dtype"] != np.int8
        or details[output]["dtype"] != np.int8
        or details[inputs]["shape"].tolist() != [1, 96, 64, 1]
        or weights_values.dtype != np.int8
        or weights_values.shape != (24, 3, 3, 1)
        or bias_values.dtype != np.int32
        or bias_values.shape != (24,)
        or weight_params["quantized_dimension"] != 0
        or weight_params["scales"].shape != (24,)
        or np.any(weight_params["zero_points"])
    ):
        raise ValueError("rounding check requires Student S's first INT8 classifier convolution")

    input_scale, input_zero = details[inputs]["quantization"]
    output_scale, output_zero = details[output]["quantization"]
    fractions, shifts = np.frexp(input_scale * weight_params["scales"].astype(np.float64) / output_scale)
    shifts = shifts.astype(np.int64)
    multipliers = np.floor(fractions * (1 << 31) + 0.5).astype(np.int64)
    if not np.all((shifts < 0) & (shifts >= -31) & (multipliers > 0) & (multipliers < (1 << 31))):
        raise ValueError("rounding check requires a positive fractional multiplier for each channel")
    single_shift = 31 - shifts
    double_shift = -shifts
    remainder_mask = (1 << double_shift) - 1
    filters = weights_values[..., 0].astype(np.int64)

    def reconstruct(input_codes: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        centered = input_codes[0, :, :, 0].astype(np.int64) - input_zero
        padded = np.pad(centered, 1)
        patches = np.lib.stride_tricks.sliding_window_view(padded, (3, 3))
        accumulator = np.einsum("hwxy,cxy->hwc", patches, filters) + bias_values
        product = accumulator * multipliers
        single = (product + (1 << (single_shift - 1))) >> single_shift
        # Double rounding first multiplies with rounding to a fixed-point high
        # word, then separately rounds the remaining power-of-two division.
        nudged = product + np.where(product >= 0, 1 << 30, 1 - (1 << 30))
        high_word = np.sign(nudged) * (np.abs(nudged) >> 31)
        threshold = (remainder_mask >> 1) + (high_word < 0)
        double = (high_word >> double_shift) + ((high_word & remainder_mask) > threshold)
        single_codes, double_codes = (
            np.clip(codes + output_zero, output_zero, 127).astype(np.int8)[None, ...]
            for codes in (single, double)
        )
        return single_codes, double_codes

    return inputs, output, reconstruct


def check_metadata(capture: dict, traced: bytes, execution: Interpreter) -> dict[int, dict]:
    if capture["model_sha256"] != hashlib.sha256(traced).hexdigest():
        raise ValueError("Android ran a different trace model")
    if capture["byte_order"] != "little":
        raise ValueError("expected a little-endian Android trace")

    inputs, outputs = execution.get_input_details(), execution.get_output_details()
    offset = 0
    tensors = {}
    for tensor, row in zip([*inputs, *outputs], capture["tensors"], strict=True):
        dtype = np.dtype(tensor["dtype"])
        shape = tensor["shape"].tolist()
        nbytes = int(np.prod(shape)) * dtype.itemsize
        index = int(tensor["index"])
        if (row["tensor_index"], row["name"], row["dtype"].lower(), row["shape"],
                row["offset"], row["nbytes"]) != (index, tensor["name"], dtype.name, shape, offset, nbytes):
            raise ValueError(f"mismatched trace tensor metadata for tensor {index}")
        offset += nbytes
        tensors[index] = row
    if offset != capture["record_bytes"]:
        raise ValueError("Android record size does not match the tensors")
    return tensors


def trace_comparison(source: bytes, trace_file: Path, capture_file: Path,
                     android_probabilities: Path | None, replay: list[int], check_rounding: bool) -> None:
    traced = trace_file.read_bytes()
    if traced != expose_outputs(source):
        raise ValueError("trace model must be the supplied model with its operator outputs exposed")
    baseline = interpreter(source)
    execution = interpreter(traced)
    waveform, = baseline.get_input_details()
    probabilities, = baseline.get_output_details()
    input_index, probability_index = waveform["index"], probabilities["index"]
    ops = execution._get_ops_details()
    capture = json.loads(capture_file.read_text())
    tensors = check_metadata(capture, traced, execution)
    fixture = json.loads((CLIPS / "expected.json").read_text())
    windows = [
        (clip["file"], start, window)
        for clip in fixture["clips"]
        for start, window in zip(
            *preprocess.active_window_starts(preprocess.load(CLIPS / clip["file"])), strict=True
        )
    ]
    device_probabilities = {}
    if android_probabilities is not None:
        device = json.loads(android_probabilities.read_text())
        if device["model_sha256"] != hashlib.sha256(source).hexdigest():
            raise ValueError("the uninstrumented Android capture used a different model")
        device_probabilities = {(clip["file"], window["start_sample"]): window["probabilities"]
                                for clip in device["clips"] for window in clip["windows"]}

    first_difference, first_int8_difference = Counter(), Counter()
    observations = {op["index"]: Differences() for op in ops}
    rounding = first_conv_rounding(traced, execution) if check_rounding else None
    rounding_input_differences = Differences()
    rounding_mismatches = Counter()
    rounding_elements = 0
    isolated = {}
    isolated_observations = {}
    if check_rounding and FIRST_CLASSIFIER_CONV not in replay:
        replay = [*replay, FIRST_CLASSIFIER_CONV]
    for index in replay:
        model, inputs = isolated_op(traced, index, set(tensors))
        isolated[index] = (inputs, interpreter(model), interpreter(model, REFERENCE))
        isolated_observations[index] = (Differences(), Differences())

    record_size = capture["record_bytes"]
    with capture_file.with_suffix(".bin").open("rb") as raw, mmap.mmap(raw.fileno(), 0, access=mmap.ACCESS_READ) as data:
        if len(data) != len(windows) * record_size:
            raise ValueError("Android trace size does not match the window count")
        for row, (record, (name, start, window)) in enumerate(zip(capture["records"], windows, strict=True)):
            offset = row * record_size
            if (record["file"], record["start_sample"], record["offset"]) != (name, start, offset):
                raise ValueError(f"unexpected trace record {row} for {name} at sample {start}")

            def get_android(index: int) -> np.ndarray:
                tensor = tensors[index]
                first = offset + tensor["offset"]
                dtype = np.dtype(tensor["dtype"].lower()).newbyteorder("<")
                return np.frombuffer(data[first:first + tensor["nbytes"]], dtype=dtype).reshape(tensor["shape"])

            device_wave = get_android(input_index)
            if device_wave.tobytes() != window[None, :].tobytes():
                raise ValueError(f"host and Android waveforms differ for {name} at sample {start}")
            for instance in (baseline, execution):
                instance.set_tensor(input_index, device_wave)
                instance.invoke()
            if baseline.get_tensor(probability_index).tobytes() != execution.get_tensor(probability_index).tobytes():
                raise ValueError(f"host trace changed probabilities for {name} at sample {start}")
            if device_probabilities and np.array(
                device_probabilities[name, start], dtype=np.float32
            ).tobytes() != get_android(probability_index).tobytes():
                raise ValueError(f"Android trace changed probabilities for {name} at sample {start}")

            first, first_int8 = None, None
            for op in ops:
                index = op["index"]
                for output in op["outputs"]:
                    host_tensor = execution.get_tensor(output)
                    if observations[index].measure(host_tensor, get_android(output)):
                        if first is None:
                            first = index
                        if first_int8 is None and host_tensor.dtype == np.int8:
                            first_int8 = index
            first_difference[first] += 1
            first_int8_difference[first_int8] += 1

            if rounding is not None:
                rounding_input, rounding_output, reconstruct = rounding
                host_input = execution.get_tensor(rounding_input)
                android_input = get_android(rounding_input)
                input_changed = rounding_input_differences.measure(host_input, android_input)
                host_codes, device_codes = execution.get_tensor(rounding_output), get_android(rounding_output)
                rounding_elements += host_codes.size
                host_predictions = reconstruct(host_input)
                android_predictions = reconstruct(android_input) if input_changed else host_predictions
                for runtime, actual, predictions in (
                    ("host", host_codes, host_predictions),
                    ("Android", device_codes, android_predictions),
                ):
                    for mode, prediction in zip(("single", "double"), predictions, strict=True):
                        rounding_mismatches[runtime, mode] += np.count_nonzero(actual != prediction)

            for index, (inputs, optimized, reference) in isolated.items():
                for tensor in inputs:
                    optimized.set_tensor(tensor, execution.get_tensor(tensor))
                optimized.invoke()
                for output in ops[index]["outputs"]:
                    if not np.array_equal(optimized.get_tensor(output), execution.get_tensor(output)):
                        raise ValueError(f"isolated host op {index} changed full-graph results")
                for instance, stats in zip((optimized, reference), isolated_observations[index], strict=True):
                    for tensor in inputs:
                        instance.set_tensor(tensor, get_android(tensor))
                    instance.invoke()
                    for output in ops[index]["outputs"]:
                        stats.measure(instance.get_tensor(output), get_android(output))

    count = len(windows)
    print(f"Host LiteRT {version('ai-edge-litert')} vs Android {capture['runtime']}")
    print(f"Matched host/Android input waveforms and host trace/original probabilities for {count} windows.")
    if device_probabilities:
        print(f"Matched Android trace/original probabilities for {count} windows.")
    print(f"First differing operator by window {dict(first_difference)}")
    print(f"First differing INT8 output by window {dict(first_int8_difference)}")
    for op in ops:
        index = op["index"]
        dtypes = "/".join(tensors[t]["dtype"] for t in op["outputs"])
        print(f"{index:2} {op['op_name']:<18} {dtypes:<8} {observations[index].describe(count)}")
    if isolated:
        print("Isolated host operators replaying exact Android inputs")
        for index, (optimized, reference) in isolated_observations.items():
            print(f"{index:2} {ops[index]['op_name']:<18} optimized {optimized.describe(count)}")
            print(f"{'':22} reference {reference.describe(count)}")
    if rounding is not None:
        print(f"Op {FIRST_CLASSIFIER_CONV} CONV_2D input differences: {rounding_input_differences.describe(count)}")
        print(f"Independent requantization of op {FIRST_CLASSIFIER_CONV} using each runtime's recorded INT8 inputs")
        for (runtime, mode), mismatches in rounding_mismatches.items():
            print(f"  {runtime:<7} {mode} rounding: {mismatches:,}/{rounding_elements:,} mismatches")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    expose = commands.add_parser("expose", help="Write a diagnostic TFLite with every operator output exposed")
    compare = commands.add_parser("compare", help="Compare a host run with a trace from the Android runner")
    for command in (expose, compare):
        command.add_argument("model", type=Path)
        command.add_argument("trace_model", type=Path)
    compare.add_argument("android_json", type=Path, help="Capture metadata with a .bin sidecar")
    compare.add_argument("--android-probabilities", type=Path, help="Optional scores from the original Android model")
    compare.add_argument("--replay", type=int, nargs="+", default=[], metavar="OP",
                         help="Replay these operator indices in isolation with the recorded Android inputs")
    compare.add_argument("--check-rounding", action="store_true",
                         help="Check both rounding formulas and replay Student S's first INT8 convolution")
    args = parser.parse_args()
    source = args.model.read_bytes()
    if args.command == "expose":
        if args.model.resolve() == args.trace_model.resolve():
            parser.error("trace model must be a separate file")
        args.trace_model.write_bytes(expose_outputs(source))
        print(f"Wrote {args.trace_model}")
    else:
        trace_comparison(source, args.trace_model, args.android_json, args.android_probabilities,
                         args.replay, args.check_rounding)


if __name__ == "__main__":
    main()
