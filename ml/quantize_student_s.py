"""Calibrate Emma's FP32 TFLite student S and export its INT8 classifier."""

from __future__ import annotations

import argparse
from pathlib import Path

import flatbuffers
import numpy as np
from ai_edge_litert.interpreter import Interpreter, OpResolverType
from tensorflow.lite.python import convert
from tensorflow.lite.python import schema_py_generated as schema
from tensorflow.lite.python.optimize import calibrator

from verify_tflite import INPUT_SHAPE, N_CLASSES

EXPORT = Path(__file__).resolve().parent / "export"
FP32 = EXPORT / "audio-student-s.tflite"
CALIBRATION = EXPORT / "calibration.npy"
INT8 = EXPORT / "audio-student-s-int8.tflite"
CLASSIFIER_OPS = {"CONV_2D", "DEPTHWISE_CONV_2D", "FULLY_CONNECTED"}
QUANTIZATION_OPS = {"QUANTIZE", "DEQUANTIZE"}
SIGNATURE = {"serving_default": {"inputs": ["waveform"], "outputs": ["probabilities"]}}


def read_graph(model: bytes):
    interpreter = Interpreter(
        model_content=model,
        experimental_op_resolver_type=OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES,
    )
    interpreter.allocate_tensors()
    if interpreter.get_signature_list() != SIGNATURE:
        raise ValueError("expected serving_default(waveform) -> probabilities")
    for tensors, shape in (
        (interpreter.get_input_details(), INPUT_SHAPE),
        (interpreter.get_output_details(), [1, N_CLASSES]),
    ):
        if len(tensors) != 1 or list(tensors[0]["shape"]) != shape or tensors[0]["dtype"] != np.float32:
            raise ValueError(f"expected a single float32 tensor of shape {shape}")
    details = {tensor["index"]: tensor for tensor in interpreter.get_tensor_details()}
    return interpreter._get_ops_details(), details


def op_dtypes(op, tensors, field):
    return tuple(tensors[index]["dtype"].__name__ for index in op[field] if index >= 0)


def restore_float_max_pools(quantized: bytes) -> bytes:
    model = schema.ModelT.InitFromObj(schema.Model.GetRootAs(quantized))
    graph, = model.subgraphs
    codes = {code.builtinCode: index for index, code in enumerate(model.operatorCodes)}
    builtin = schema.BuiltinOperator
    pool_code = codes[builtin.MAX_POOL_2D]
    operators = []

    def float_tensor(index: int) -> int:
        original = graph.tensors[index]
        tensor = schema.TensorT()
        tensor.shape = original.shape
        tensor.shapeSignature = original.shapeSignature
        tensor.hasRank = original.hasRank
        tensor.type = schema.TensorType.FLOAT32
        tensor.name = original.name + b"/float_pool"
        graph.tensors.append(tensor)
        return len(graph.tensors) - 1

    def boundary(code: int, input_index: int, output_index: int):
        operator = schema.OperatorT()
        operator.opcodeIndex = codes[code]
        operator.inputs = [input_index]
        operator.outputs = [output_index]
        return operator

    # MLIR 2.21 propagates INT8 into max pools despite the node exclusions.
    # Max commutes with dequantization when the input and output share one scale
    # and zero point, so float pooling can use the existing calibrated boundaries.
    for operator in graph.operators:
        if operator.opcodeIndex != pool_code:
            operators.append(operator)
            continue
        input_index, = operator.inputs
        output_index, = operator.outputs
        input_tensor, output_tensor = graph.tensors[input_index], graph.tensors[output_index]
        if input_tensor.type == output_tensor.type == schema.TensorType.FLOAT32:
            operators.append(operator)
            continue
        before, after = input_tensor.quantization, output_tensor.quantization
        if (input_tensor.type != schema.TensorType.INT8 or output_tensor.type != schema.TensorType.INT8
                or len(before.scale) != 1 or not np.array_equal(before.scale, after.scale)
                or not np.array_equal(before.zeroPoint, after.zeroPoint)
                or operator.builtinOptions.fusedActivationFunction != schema.ActivationFunctionType.NONE):
            raise ValueError("expected same-scale INT8 max pooling with no fused activation")
        float_input, float_output = float_tensor(input_index), float_tensor(output_index)
        operator.inputs = [float_input]
        operator.outputs = [float_output]
        operators.extend((
            boundary(builtin.DEQUANTIZE, input_index, float_input),
            operator,
            boundary(builtin.QUANTIZE, float_output, output_index),
        ))

    model.operatorCodes[pool_code].version = 1
    graph.operators = operators
    builder = flatbuffers.Builder(len(quantized))
    builder.Finish(model.Pack(builder), file_identifier=b"TFL3")
    return bytes(builder.Output())


def verify_quantization(source_ops, source_tensors, targets, candidate):
    candidate_ops, candidate_tensors = read_graph(candidate)
    compute_ops = [op for op in candidate_ops if op["op_name"] not in QUANTIZATION_OPS]
    if [op["op_name"] for op in compute_ops] != [op["op_name"] for op in source_ops]:
        raise ValueError("quantisation changed the source compute operations")
    if compute_ops[min(targets) - 1]["index"] != min(targets) - 1:
        raise ValueError("quantisation inserted an integer boundary before the classifier convolutions")

    for source, op in zip(source_ops, compute_ops):
        inputs = op_dtypes(op, candidate_tensors, "inputs")
        outputs = op_dtypes(op, candidate_tensors, "outputs")
        if source["index"] in targets:
            expected_inputs, expected_outputs = ("int8", "int8", "int32"), ("int8",)
        else:
            expected_inputs = op_dtypes(source, source_tensors, "inputs")
            expected_outputs = op_dtypes(source, source_tensors, "outputs")
        if (inputs, outputs) != (expected_inputs, expected_outputs):
            raise ValueError(
                f"{op['op_name']} (source op {source['index']}): "
                f"expected {expected_inputs} -> {expected_outputs}, got {inputs} -> {outputs}"
            )

    for op in candidate_ops:
        inputs = ", ".join(op_dtypes(op, candidate_tensors, "inputs"))
        outputs = ", ".join(op_dtypes(op, candidate_tensors, "outputs"))
        print(f"  {op['index']:2} {op['op_name']:<18} {inputs} -> {outputs}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=INT8)
    output = parser.parse_args().output
    if output.suffix != ".tflite" or output.resolve() == FP32:
        parser.error("--output must name a separate .tflite file")

    fp32 = FP32.read_bytes()
    windows = np.load(CALIBRATION, allow_pickle=False)
    if windows.shape != (200, INPUT_SHAPE[1]) or windows.dtype != np.float32:
        raise ValueError("expected 200 float32 calibration windows of 15,600 samples")
    if not np.all(np.isfinite(windows)) or np.max(np.abs(windows)) > 1:
        raise ValueError("calibration samples must be finite and in [-1, 1]")

    ops, tensors = read_graph(fp32)
    log_ops = [op["index"] for op in ops if op["op_name"] == "LOG"]
    if len(log_ops) != 1:
        raise ValueError("expected one LOG op ending the waveform frontend")
    targets = {op["index"] for op in ops
               if op["index"] > log_ops[0] and op["op_name"] in CLASSIFIER_OPS}
    if len(targets) != 10:
        raise ValueError("expected ten classifier convolutions and dense ops")

    # Select by tensor name so the frontend CONV_2D (STFT) and FULLY_CONNECTED
    # (mel projection) can stay float alongside their integer classifier counterparts.
    float_nodes = [
        tensors[index]["name"] for op in ops if op["index"] not in targets for index in op["outputs"]
    ]

    print(f"Calibrating {len(windows)} windows from {CALIBRATION}")
    calibrated = calibrator.Calibrator(calibrator.add_intermediate_tensors(fp32)).calibrate(
        lambda: ([window[None, :]] for window in windows)
    )
    quantized = convert.mlir_quantize(calibrated, denylisted_nodes=float_nodes)
    candidate = restore_float_max_pools(quantized)
    verify_quantization(ops, tensors, targets, candidate)
    output.write_bytes(candidate)
    print(f"{FP32.name}: {len(fp32):,} bytes")
    print(f"{output}: {len(candidate):,} bytes ({1 - len(candidate) / len(fp32):.2%} smaller)")


if __name__ == "__main__":
    main()
