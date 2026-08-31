from __future__ import annotations

import argparse
import ctypes
import json
import os
import sys
from pathlib import Path
from typing import Any


_DLL_DIRECTORY_HANDLES: list[Any] = []


def parse_device_index(value: str | int | None, default: int = 0) -> int:
    raw = str(default if value is None or str(value).strip() == "" else value).strip()
    try:
        index = int(raw)
    except ValueError as exception:
        raise ValueError(f"GPU device index must be an integer, got {raw!r}") from exception
    if index < 0:
        raise ValueError("GPU device index must be zero or greater")
    return index


def configure_cuda_dll_search_paths() -> list[str]:
    """Make CUDA libraries installed by either pip or NVIDIA visible on Windows."""
    if os.name != "nt":
        return []

    candidates: list[Path] = []
    project_root = Path(__file__).resolve().parents[2]
    for raw_path in os.getenv("CUDA_DLL_PATHS", "").split(os.pathsep):
        raw_path = raw_path.strip()
        if raw_path:
            custom_path = Path(raw_path)
            candidates.append(custom_path if custom_path.is_absolute() else project_root / custom_path)
    for prefix in {Path(sys.prefix), Path(sys.base_prefix)}:
        site_packages = prefix / "Lib" / "site-packages"
        candidates.extend(
            [
                site_packages / "nvidia" / "cublas" / "bin",
                site_packages / "nvidia" / "cudnn" / "bin",
                site_packages / "torch" / "lib",
            ]
        )

    for variable in ("CUDA_PATH", "CUDNN_PATH"):
        value = os.getenv(variable, "").strip()
        if value:
            candidates.extend([Path(value) / "bin", Path(value)])

    program_files = Path(os.getenv("ProgramFiles", r"C:\Program Files"))
    candidates.extend(
        sorted(
            (program_files / "NVIDIA GPU Computing Toolkit" / "CUDA").glob("v*/bin"),
            reverse=True,
        )
    )
    candidates.extend(
        sorted((program_files / "NVIDIA" / "CUDNN").glob("v*/bin"), reverse=True)
    )

    registered: list[str] = []
    seen: set[str] = set()
    for candidate in candidates:
        if not candidate.is_dir():
            continue
        resolved = str(candidate.resolve())
        key = resolved.casefold()
        if key in seen:
            continue
        seen.add(key)
        registered.append(resolved)
        if hasattr(os, "add_dll_directory"):
            _DLL_DIRECTORY_HANDLES.append(os.add_dll_directory(resolved))

    if registered:
        current_path = os.environ.get("PATH", "")
        os.environ["PATH"] = os.pathsep.join([*registered, current_path])
    return registered


def _load_cuda_libraries() -> list[str]:
    if os.name == "nt":
        names = ("cublas64_12.dll", "cudnn64_9.dll", "cudnn_ops64_9.dll")
        loader = ctypes.WinDLL
    else:
        names = ("libcublas.so.12", "libcudnn.so.9")
        loader = ctypes.CDLL

    loaded: list[str] = []
    for name in names:
        try:
            loader(name)
        except OSError as exception:
            raise RuntimeError(f"required CUDA library is unavailable: {name}: {exception}") from exception
        loaded.append(name)
    return loaded


def probe_whisper_cuda(device_index: int, compute_type: str) -> dict[str, Any]:
    paths = configure_cuda_dll_search_paths()
    import ctranslate2

    count = ctranslate2.get_cuda_device_count()
    if count <= device_index:
        raise RuntimeError(
            f"Whisper requested GPU {device_index}, but CTranslate2 detected {count} CUDA device(s)"
        )
    supported = sorted(ctranslate2.get_supported_compute_types("cuda"))
    if compute_type not in supported:
        raise RuntimeError(
            f"Whisper compute type {compute_type!r} is not supported; available: {', '.join(supported)}"
        )
    libraries = _load_cuda_libraries()
    return {
        "component": "whisper",
        "status": "UP",
        "device_index": device_index,
        "device_count": count,
        "compute_type": compute_type,
        "ctranslate2": ctranslate2.__version__,
        "libraries": libraries,
        "dll_paths": paths,
    }


def probe_torch_cuda(device_index: int) -> dict[str, Any]:
    configure_cuda_dll_search_paths()
    import torch

    if not torch.cuda.is_available():
        raise RuntimeError(
            f"PyTorch {torch.__version__} does not provide an available CUDA runtime"
        )
    count = torch.cuda.device_count()
    if count <= device_index:
        raise RuntimeError(
            f"Embedding requested GPU {device_index}, but PyTorch detected {count} CUDA device(s)"
        )

    device = torch.device(f"cuda:{device_index}")
    with torch.inference_mode():
        matrix = torch.ones((8, 8), device=device)
        _ = matrix @ matrix
        convolution = torch.nn.Conv1d(1, 1, 3).to(device)
        _ = convolution(torch.ones((1, 1, 16), device=device))
        torch.cuda.synchronize(device)

    return {
        "component": "embedding",
        "status": "UP",
        "device_index": device_index,
        "device_count": count,
        "device_name": torch.cuda.get_device_name(device_index),
        "torch": torch.__version__,
        "torch_cuda": torch.version.cuda,
        "cudnn": torch.backends.cudnn.version(),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate local GPU runtime dependencies")
    parser.add_argument("component", choices=("whisper", "embedding"))
    parser.add_argument("--device-index", default="0")
    parser.add_argument("--compute-type", default="float16")
    args = parser.parse_args()
    try:
        index = parse_device_index(args.device_index)
        if args.component == "whisper":
            result = probe_whisper_cuda(index, args.compute_type)
        else:
            result = probe_torch_cuda(index)
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except Exception as exception:  # noqa: BLE001 - this is a startup boundary
        print(
            json.dumps(
                {
                    "component": args.component,
                    "status": "DOWN",
                    "error": str(exception),
                },
                ensure_ascii=False,
            ),
            file=sys.stderr,
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
