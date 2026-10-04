# Encoder formula resources

The app displays checked-in Android VectorDrawable equations. The editable
LaTeX fragments in `src/` are the source for those visual resources; keep them
with the generated XML files when modifying a formula. Normal app builds do not
run this generator and need no TeX installation.

## Optional regeneration

Requirements: Python 3, a LaTeX distribution with `standalone`, `fontenc`,
`amsmath` and `amssymb`, `latex` and `dvisvgm` on PATH, JDK21 and the project's
Android SDK/Gradle wrapper.

From the repository root:

```bash
python tools/encoder-formulas/generate.py
```

The pipeline converts TeX → DVI → path-only SVG → Android VectorDrawable using
AGP's SVG converter. Outputs are `app/src/main/res/drawable/encoder_formula_*.xml`.
No WebView, font engine or mathematics renderer is included at runtime.

After regeneration, review the TeX/XML diff and run:

```bash
./gradlew :app:assembleDebug :app:lintDebug
git diff --check
```

If changing an equation, also update the descriptions and accessible spoken
semantics in `EncoderFormulaCatalog.kt`. Keep the visual equation consistent
with the implemented calculation. Use compact variants for narrow displays;
verify light/dark themes and large text without adding horizontal scrolling.
