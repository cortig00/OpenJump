#!/usr/bin/env python3
"""Generate Android VectorDrawable equations from the checked-in TeX sources."""

from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE_DIR = Path(__file__).resolve().parent / "src"
OUTPUT_DIR = ROOT / "app" / "src" / "main" / "res" / "drawable"
SVG2VECTOR_INIT = Path(__file__).resolve().parent / "svg2vector.init.gradle"

DOCUMENT = r"""\documentclass[border=1pt]{standalone}
\usepackage[T1]{fontenc}
\usepackage{amsmath,amssymb}
\begin{document}
\(\displaystyle
%s
\)
\end{document}
"""


def require_tool(name: str) -> str:
    executable = shutil.which(name)
    if executable is None:
        raise SystemExit(f"Required tool not found: {name}")
    return executable


def run(command: list[str], cwd: Path) -> None:
    subprocess.run(command, cwd=cwd, check=True)


def main() -> int:
    latex = require_tool("latex")
    dvisvgm = require_tool("dvisvgm")
    gradle = ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    if not gradle.exists():
        raise SystemExit(f"Gradle wrapper not found: {gradle}")

    sources = sorted(SOURCE_DIR.glob("encoder_formula_*.tex"))
    if not sources:
        raise SystemExit(f"No TeX sources found in {SOURCE_DIR}")

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="openjump-formulas-") as temporary:
        temp = Path(temporary)
        svg_dir = temp / "svg"
        svg_dir.mkdir()
        for source in sources:
            name = source.stem
            tex_file = temp / f"{name}.tex"
            tex_file.write_text(DOCUMENT % source.read_text(encoding="utf-8").strip(), encoding="utf-8")
            run([latex, "-interaction=nonstopmode", "-halt-on-error", tex_file.name], temp)
            run(
                [
                    dvisvgm,
                    "--no-fonts",
                    "--exact",
                    "--bbox=min",
                    f"--output={svg_dir / (name + '.svg')}",
                    f"{name}.dvi",
                ],
                temp,
            )

        run(
            [
                str(gradle),
                "-I",
                str(SVG2VECTOR_INIT),
                ":app:generateEncoderFormulaVectors",
                f"-PformulaSvgDir={svg_dir}",
                f"-PformulaVectorDir={OUTPUT_DIR}",
            ],
            ROOT,
        )

    print(f"Generated {len(sources)} formula vectors in {OUTPUT_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
