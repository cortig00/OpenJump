# Fórmulas científicas del Encoder

Este directorio contiene el pipeline reproducible de ecuaciones que usa
`EncoderMethodologyScreen`. Las fórmulas se componen con **LaTeX real en build
time/diseño** y se versionan como `VectorDrawable` de paths. La app no incorpora
WebView, KaTeX ni un motor matemático en runtime.

## Mapa de archivos

```text
tools/encoder-formulas/
├── src/encoder_formula_*.tex   fuente visual de verdad
├── generate.py                 TeX -> DVI -> SVG -> VectorDrawable
├── svg2vector.init.gradle      tarea AGP Svg2Vector aislada
└── README.md

app/src/main/res/drawable/
└── encoder_formula_*.xml       artefactos generados y versionados

app/src/main/java/com/openjump/app/ui/encoder/
├── EncoderFormulaCatalog.kt    metadatos, recursos y semántica hablada
└── EncoderHelpUi.kt            presentación Compose responsive
```

Los fragmentos `.tex` son la fuente visual de verdad. Los XML son la frontera
generada que Android compila y **no deben editarse a mano**. Fuente y vector
generado siempre deben viajar en el mismo commit.

## Pipeline

```text
fragmento TeX
  -> documento standalone temporal
  -> latex (DVI)
  -> dvisvgm --no-fonts --exact (SVG con paths)
  -> Android Gradle Plugin Svg2Vector
  -> VectorDrawable path-only
```

`--no-fonts` convierte los glifos en paths: el dispositivo no necesita la fuente
LaTeX. Compose aplica el color del tema mediante `ColorFilter.tint`, por lo que
los XML generados conservan fill negro opaco.

## Requisitos

- Python 3;
- distribución LaTeX con `standalone`, `fontenc`, `amsmath` y `amssymb`;
- ejecutables `latex` y `dvisvgm` en `PATH`;
- JDK 17;
- Android SDK/Gradle Wrapper del proyecto y dependencias AGP resolubles.

El pipeline fue validado en Windows desde el repositorio. No añade plugins ni
dependencias a `app/build.gradle.kts`: el init script carga la clase Svg2Vector
solo durante la generación.

## Regenerar todas las ecuaciones

Desde la raíz:

```bash
python tools/encoder-formulas/generate.py
```

Salida esperada:

```text
Generated 14 formula vectors in .../app/src/main/res/drawable
```

Después revisar:

```bash
git diff -- tools/encoder-formulas/src app/src/main/res/drawable
git diff --check
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

La generación debe ser determinista: ejecutar dos veces sin cambiar fuentes no
debe alterar los XML.

## Añadir o modificar una fórmula

1. Crear/editar `src/encoder_formula_<id>.tex` como fragmento matemático, sin
   `\documentclass` ni delimitadores `\(...\)`.
2. Para una ecuación larga, crear también
   `encoder_formula_<id>_compact.tex`, normalmente con `aligned` y saltos de línea.
3. Ejecutar `generate.py`.
4. Registrar el drawable normal/compacto en `EncoderFormulaCatalog.kt`.
5. Añadir:
   - título y resumen legibles;
   - unidad SI;
   - explicación breve;
   - símbolos necesarios;
   - descripción hablada española de la ecuación;
   - `EncoderMetricKey` cubiertas, si corresponde.
6. Actualizar `EncoderFormulaCatalogTest.kt` para cualquier nuevo contrato.
7. Revisar visualmente 320/360/411 dp, tema claro/oscuro y escalas de fuente
   1.0/1.3/2.0.
8. Verificar la semántica en la jerarquía Android o con TalkBack.

## Responsive y accesibilidad

`EncoderFormulaSpec.drawableFor(widthDp, fontScale)` selecciona el recurso
compacto cuando:

```text
widthDp < 360  o  fontScale >= 1.3
```

Las cabeceras de fila también se apilan con ancho <340 dp o fuente ≥1.3. No se
permite scroll horizontal para leer una ecuación.

Los paths no contienen texto accesible. La semántica vive deliberadamente en
`EncoderFormulaCatalog.kt` mediante `spokenEquation` y se asigna como
`contentDescription` a la imagen. Cambiar una ecuación sin actualizar su lectura
hablada es una regresión de accesibilidad.

## Convenciones científicas

- variables escalares en cursiva matemática;
- nombres de métricas, funciones y unidades en redonda;
- unidades SI separadas de la ecuación visual cuando mejora la lectura;
- `+Y` representa ascenso;
- MCV es desplazamiento concéntrico dividido entre duración concéntrica;
- PV procede de la señal procesada;
- Velocity Loss solo usa repeticiones válidas;
- el ajuste cúbico representa la implementación real ponderada por confianza.

La ecuación visual, la explicación y el cálculo en `encoder/` deben describir la
misma operación. Si cambia el algoritmo, actualizar los tres en un único cambio.

## Decisiones descartadas

- `MathBuilder`/baseline manual: parecía pseudo-LaTeX y era frágil con reflow.
- Huarang/Orcex: exigían compileSdk 36 en el estudio realizado.
- AndroidMath: aumentaba significativamente el APK, añadía código nativo/JitPack
  y no resolvía la semántica.
- WebView/KaTeX: complicaba tema, altura, reflow, tests y TalkBack.

Reabrir estas opciones solo si cambian los requisitos; no añadir otra librería de
fórmulas para una ecuación aislada.
