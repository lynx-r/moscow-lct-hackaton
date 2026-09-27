"""Build the C++ kernel in place:  python -m heatrouting.core.build

Produces heatrouting/core/_core<ext-suffix>.so with g++ and pybind11 (no CMake needed for the prototype).
"""

from __future__ import annotations

import subprocess
import sys
import sysconfig
from pathlib import Path


def build(verbose: bool = True) -> Path:
    import pybind11

    here = Path(__file__).parent
    suffix = sysconfig.get_config_var("EXT_SUFFIX") or ".so"
    out = here / f"_core{suffix}"
    cmd = [
        "g++",
        "-O3",
        "-std=c++17",
        "-shared",
        "-fPIC",
        "-ffp-contract=off",
        "-DNDEBUG",
        f"-I{pybind11.get_include()}",
        f"-I{sysconfig.get_paths()['include']}",
        str(here / "core.cpp"),
        "-o",
        str(out) + ".tmp",
    ]
    if verbose:
        print(" ".join(cmd))
    subprocess.run(cmd, check=True)
    import os

    os.replace(
        str(out) + ".tmp", out
    )  # atomic: a running process keeps its old mapping
    return out


if __name__ == "__main__":
    print("built", build())
