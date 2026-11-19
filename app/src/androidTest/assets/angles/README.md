# Fixtures sintéticos de Ángulos (`app/src/androidTest/assets/angles/`)

Estos archivos **no** son datos de usuario ni capturas reales: son vídeos generados para verificar la
correlación píxel↔frame de la congelación de fotogramas. No se descargan de Internet.

## `ordinal_cfr_bframes.mp4`

- 24 frames, 320×240, H.264 High con `has_b_frames=2`, `yuv420p`, 25 fps CFR.
- PTS de presentación: 0, 40 000, 80 000, …, 920 000 µs.
- Cada frame de presentación `n` lleva un patrón visual que codifica el ordinal `n` (12 bits) en la
  esquina superior izquierda, con fondo gris. Así la verificación lee el ordinal de los píxeles
  decodificados y **no** se apoya en el PTS asignado por el adaptador.
- Sin rotación declarada. La orientación 90/270/180 se cubre con pruebas JVM de
  `FrameOrientationPlanner`; falta un fixture rotado real para la experimentación en AVD (B2).

### Especificación del patrón (debe coincidir con `FrameVisualIdentity`)

- Frame mínimo 272×48; margen 16 px; celdas de 16×16 px, muestreadas en su centro.
- Fila superior izquierda a `y = 8`: 3 celdas de marcador (oscuro, claro, oscuro) y 12 celdas de
  datos en orden LSB primero (claro = 1, oscuro = 0).
- Fondo RGB(96,96,96); celdas oscuras RGB(0,0,0); celdas claras RGB(255,255,255). El decodificador de
  prueba usa luminancia > 140 para “claro”, de modo que sobrevive a la compresión 4:2:0 de rango
  limitado.

### Generación reproducible (ffmpeg local, sin descargas)

Los frames RGB24 se escriben en orden de presentación con la especificación anterior y se codifican:

```
python3 - <<'PY' > /tmp/ordinal_frames.rgb
import sys
W,H,CELL,MARGIN,DATA_BITS=320,240,16,16,12
def frame(ordinal):
    buf=bytearray([96,96,96])*(W*H)
    cells=[False,True,False]+[bool((ordinal>>b)&1) for b in range(DATA_BITS)]
    for cell,light in enumerate(cells):
        rgb=b'\xff\xff\xff' if light else b'\x00\x00\x00'
        x0=MARGIN+cell*CELL
        for y in range(MARGIN,MARGIN+CELL):
            base=(y*W+x0)*3
            for x in range(CELL):
                i=base+x*3
                buf[i:i+3]=rgb
    return bytes(buf)
out=sys.stdout.buffer
for i in range(24):
    out.write(frame(i))
PY
ffmpeg -y -f rawvideo -pixel_format rgb24 -video_size 320x240 -framerate 25 \
  -i /tmp/ordinal_frames.rgb -c:v libx264 -profile:v high -bf 3 -g 12 \
  -pix_fmt yuv420p -movflags +faststart ordinal_cfr_bframes.mp4
```

Comprobación local realizada al crear el fixture: `nb_read_frames=24`, `has_b_frames=2`, PTS CFR
0…0,92 s, y el ordinal leído tras decodificar con ffmpeg coincide con el índice de presentación en los
24 frames. La ruta real `MediaCodec` se ejecutó en el AVD `OpenJump_API35` el 2026-11-10 (frames 0, 12
y 23 con ordinal correcto); la ruta `MediaMetadataRetriever` también está caracterizada allí, sin
acreditar PTS exacto.

### Ejecución en AVD (un solo propietario, sin borrar datos)

```
ANDROID_SERIAL=emulator-5554 ./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.openjump.app.video.angles.FrozenFrameVisualIdentityInstrumentedTest
```

El test copia el asset a `cacheDir` del target y lo borra al terminar. Si el ordinal observado difiere
del solicitado, el mensaje identifica frame y tamaño: esa es la evidencia de que la ruta
`MediaMetadataRetriever` no sostiene el contrato de captura exacta y debe usarse una ruta MediaCodec
que exponga el PTS del buffer de salida.

## Estado del slice B2 (productor MediaCodec)

- Se añade `video/angles/CodecFrozenFrameProducer.kt`: productor real `MediaExtractor` + `MediaCodec`
  que lee los píxeles **del mismo buffer de salida** cuyo `presentationTimeUs` reporta como PTS.
- Solo entrega `FrozenFrameImage` si el PTS coincide exactamente con el indexado, la fuente y el frame
  de la evidencia coinciden con la solicitud, y la orientación es demostrable.
- Fail-closed: PTS pasado, presupuesto de decodificación agotado, layout/crop no convertible, error
  del códec o cancelación devuelven `FrameFreezeResult.Blocked`. No hay fallback a
  `MediaMetadataRetriever`.
- **Ejecutado en AVD API 35** (goldfish, 2026-11-10): frames 0/12/23 con ordinal correcto, PTS
  reportado por el buffer de salida, inmutabilidad y bloqueos de orientación/cancelación funcionando;
  fallaron la validación de PTS fuera del índice (F1) y el presupuesto 8K (F2), corregidos después en
  `CodecCapturePolicy`/`FrameFreezeBudget` sin re-ejecutar el emulador en este slice. Compilar no
  acredita compatibilidad con dispositivos: hardware físico, API 26–27 y fidelidad de color frente a
  un render de referencia siguen sin demostrarse (la conversión usa la matriz/rango declarados).

## Versionado del fixture

`app/src/androidTest/assets/` está ignorado salvo esta carpeta y este README: `.gitignore` re-incluye
**solo** `angles/README.md` y los seis MP4 de `angles/` (`ordinal_cfr_bframes.mp4`, `ordinal_rot90.mp4`,
`ordinal_rot180.mp4`, `ordinal_rot270.mp4`, `ordinal_square_rot90.mp4`, `ordinal_vfr.mp4`). Cualquier
otro asset o vídeo sigue ignorado. Los MP4 **sí se versionan**: son fixtures sintéticos pequeños, sin
datos de usuario, y deben estar en el repositorio para que el APK de `androidTest` los incluya en CI.
Se regeneran con las recetas de este README y deben conservar `nb_read_frames`, PTS, rotación de
contenedor y ordinal por frame antes de sustituirlos; cualquier otro asset sigue fuera del control de
versiones.

## Corrección de fuente y memoria (revisión B2 posterior)

- `FrozenFrameSourceSession` copia la fuente **una sola vez** a `cacheDir/angles_source_snapshots`
  (nombre opaco), sella longitud e identidad de sesión e indexa/captura con
  `MediaExtractor.setDataSource(fd, 0, sealedLength)`. Un `content://` reemplazado, un permiso revocado
  o un editor concurrente no cambian el contenido de la sesión; dos fuentes con la misma metadata/PTS
  no pueden atribuirse porque la solicitud debe llevar la identidad acuñada por la sesión
  (`SOURCE_SESSION_INVALID` si no). Límite por defecto de copia: 2 GiB configurable, reserva de 64 MiB
  y cancelación por bloque; snapshots huérfanos de más de 24 h se barren al abrir otra fuente.
- El presupuesto de memoria se evalúa **antes** de asignar la matriz ARGB y cuenta matriz + bitmap
  decodificado + copias congeladas del peor caso (rotación + instantánea inmutable). 1080p cabe;
  1440p/4K se rechazan con `MEMORY_BUDGET_EXCEEDED` (no se declaran soportados). `OutOfMemoryError`
  de asignaciones propias se convierte en el mismo rechazo. Este slice no ejecuta AVD: las pruebas
  instrumentadas nuevas (`FrozenFrameSourceInstrumentedTest`) están compiladas, no ejecutadas.

## Barrera de snapshots y orientación (revisión posterior, sin AVD)

### Barrido, reservas y cierre

- `SourceSnapshotCopier.sweepStale` ya no decide solo por `lastModified`. Cada copia toma una reserva
  de proceso (`SnapshotLease`) **antes** de crear su temporal y la conserva la sesión hasta cerrar; el
  barrido comprueba reserva + edad + borrado bajo un único cerrojo. Un reloj adelantado, una sesión
  viva de más de 24 h o una copia en curso ya no pueden perder su fichero.
- Sólo se borran ficheros regulares con prefijo propio cuyo directorio padre canónico es el directorio
  de snapshots: nunca subdirectorios, ficheros ajenos ni rutas fuera del directorio propio. Lo que sí
  se limpia son restos sin reserva (proceso interrumpido) más antiguos que el umbral.
- Cerrar durante una captura **difiere** el borrado a la última captura en vuelo y esa captura no
  entrega imagen tras el cierre. `request()` y la apertura del descriptor devuelven rechazos tipados
  (`SOURCE_SESSION_INVALID`): no escapan `IllegalStateException`/`IOException` de estado. Un fallo de
  apertura/escritura del temporal también se tipa (`STORAGE_UNAVAILABLE`/`COPY_FAILED`) y una caché
  purgada se reporta como `SOURCE_SESSION_INVALID` en vez de excepción.
- Pruebas JVM deterministas: `SnapshotSweepLeaseTest` (reloj adelantado sobre sesión viva, copia en
  curso, restos de proceso muerto, cierre/captura y limpieza al cerrar, caché purgada).

### Orientación 180 y cuadrado 90/270 (evidencia explícita, no dimensiones)

- Las dimensiones no distinguen 180 (mismo tamaño) ni 90/270 en un frame cuadrado. Se añade
  `FrameOrientationEvidence.BufferModeWithoutSurface`: el productor afirma que configuró `MediaCodec`
  **sin `Surface`** (`configure(format, null, null, 0)`, salida por `getOutputImage`/`getOutputBuffer`),
  así que la salida está en la orientación codificada y la congelación aplica la rotación declarada.
- **Qué es inferencia y qué está observado:** con esa evidencia, 180 y cuadrado 90/270 quedan
  determinados con la certeza `PRODUCER_BUFFER_MODE_NO_SURFACE_TRANSFORM`. La afirmación se apoya en
  el contrato documentado del framework (`MediaFormat.KEY_ROTATION` es una indicación para
  decodificadores con superficie) y en la configuración real del productor; **no** se ha observado en
  dispositivo en este slice y no hay fixture rotado nuevo, así que la verificación empírica de esos dos
  casos en hardware sigue **pendiente**. Sin la evidencia, ambos casos siguen bloqueados y la política
  rechaza incluso un plan fabricado (`FrameOrientationEvidenceTest`).
- Si una futura ejecución en dispositivo mostrara que el decodificador rota en modo buffer, el camino
  correcto es retirar la evidencia (volver a bloquear), no reinterpretar las dimensiones.

## Fixtures con rotación real de contenedor y VFR (evidencia visual, sin AVD)

Slice posterior: hacía falta evidencia de que la imagen congelada **se corresponde con el frame pedido**
y con la **rotación declarada**, no solo de que el PTS devuelto coincida. Cinco fixtures nuevos,
sintéticos y generados con el ffmpeg local (sin descargas). Todos: 12 frames, 25 fps de origen,
`libx264 -crf 10 -preset veryslow -g 4 -bf 0 -pix_fmt yuv420p -movflags +faststart`. `-g 4` deja
varios puntos de sincronización para las búsquedas alternas.

Cada frame codifica dos cosas legibles en píxeles:

- el **ordinal de presentación** (12 bits) con el patrón de `FrameVisualIdentity` en la esquina
  superior izquierda de la imagen codificada, y
- cuatro **esquinas asimétricas** de 16×16: TL rojo, TR verde, BL azul, BR amarillo (fuera del margen
  del patrón, que empieza en 16 px).

Manifest (lo que la prueba instrumentada `RotatedFixtureVisualIdentityInstrumentedTest` exige de forma
explícita, sin derivarlo del índice leído):

| Asset | Codificado | Rotación de contenedor | KEY_ROTATION esperada | PTS (µs) | Congelado | Esquinas TL/TR/BR/BL esperadas |
| --- | --- | --- | --- | --- | --- | --- |
| `ordinal_rot90.mp4` | 320×240 | matriz 90° (`[0,1,0,-1,0,0]`) | 90 | 0…440 000, Δ40 000 | 240×320 | azul/rojo/verde/amarillo |
| `ordinal_rot180.mp4` | 320×240 | matriz 180° (`[-1,0,0,0,-1,0]`) | 180 | 0…440 000, Δ40 000 | 320×240 | amarillo/azul/rojo/verde |
| `ordinal_rot270.mp4` | 320×240 | matriz 270° (`[0,-1,0,1,0,0]`) | 270 | 0…440 000, Δ40 000 | 240×320 | verde/amarillo/azul/rojo |
| `ordinal_square_rot90.mp4` | 320×320 | matriz 90° | 90 | 0…440 000, Δ40 000 | 320×320 | azul/rojo/verde/amarillo |
| `ordinal_vfr.mp4` | 320×240 | sin rotación | 0 | 0, 40 000, 80 000, 120 000, 200 000, 240 000, 280 000, 320 000, 480 000, 520 000, 560 000, 600 000 | 320×240 | rojo/verde/amarillo/azul |

El cuadrado 90 es el caso en que las dimensiones **no** pueden demostrar la rotación: solo la
permutación de esquinas (y que el patrón deja de ser legible en la orientación codificada) acredita
la transformación.

### Generación reproducible (ffmpeg local, sin descargas)

1. Frames RGB24 con esquinas + patrón de ordinal:

```
cat > /tmp/angles_gen_frames.py <<'PY'
import sys
W, H, COUNT = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])
CELL, MARGIN, DATA_BITS = 16, 16, 12
BG, DARK, LIGHT = bytes((96,96,96)), bytes((0,0,0)), bytes((255,255,255))
CORNERS = {"tl": (255,0,0), "tr": (0,255,0), "bl": (0,0,255), "br": (255,255,0)}  # TL rojo, TR verde, BL azul, BR amarillo
def fill(buf, x0, y0, w, h, rgb):
    rgb = bytes(rgb)
    for y in range(y0, y0 + h):
        base = (y * W + x0) * 3
        buf[base:base + w * 3] = rgb * w
def frame(ordinal):
    buf = bytearray(BG * (W * H))
    fill(buf, 0, 0, 16, 16, CORNERS["tl"]); fill(buf, W - 16, 0, 16, 16, CORNERS["tr"])
    fill(buf, 0, H - 16, 16, 16, CORNERS["bl"]); fill(buf, W - 16, H - 16, 16, 16, CORNERS["br"])
    cells = [False, True, False] + [bool((ordinal >> b) & 1) for b in range(DATA_BITS)]
    for cell, light in enumerate(cells):
        fill(buf, MARGIN + cell * CELL, MARGIN, CELL, CELL, LIGHT if light else DARK)
    return bytes(buf)
out = sys.stdout.buffer
for i in range(COUNT):
    out.write(frame(i))
PY
python3 /tmp/angles_gen_frames.py 320 240 12 > /tmp/angles_320x240.rgb
python3 /tmp/angles_gen_frames.py 320 320 12 > /tmp/angles_320x320.rgb
```

2. Codificación y **patcheo de la matriz de visualización** en `tkhd` (16.16). ffmpeg no escribe
   display matrix desde la CLI (`-display_rotation` es opción de entrada; `-metadata rotate=` ya no se
   traduce), así que se inyecta con este patcher determinista, que también ajusta las dimensiones de
   presentación 16.16 del propio `tkhd`:

```
cat > /tmp/angles_patch_rotation.py <<'PY'
import struct, sys
MATRICES = {
    0:   [0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000],
    90:  [0, 0x10000, 0, -0x10000, 0, 0, 0, 0, 0x40000000],
    180: [-0x10000, 0, 0, 0, -0x10000, 0, 0, 0, 0x40000000],
    270: [0, -0x10000, 0, 0x10000, 0, 0, 0, 0, 0x40000000],
}
def walk(buf, start, end, want):
    off = start
    while off + 8 <= end:
        size = struct.unpack('>I', buf[off:off + 4])[0]
        typ = buf[off + 4:off + 8].decode('latin1')
        if size < 8: raise SystemExit('caja inválida en %d' % off)
        if typ == want: yield off, size
        if typ in ('moov', 'trak', 'mdia', 'minf', 'stbl', 'edts', 'udta'):
            yield from walk(buf, off + 8, off + size, want)
        off += size
def main(path, rotation):
    data = bytearray(open(path, 'rb').read())
    found = list(walk(data, 0, len(data), 'tkhd'))
    if len(found) != 1: raise SystemExit('se esperaba un tkhd, hay %d' % len(found))
    off, size = found[0]
    moff = off + (48 if data[off + 8] == 0 else 60)  # tkhd v0/v1
    if moff + 44 > off + size: raise SystemExit('tkhd demasiado corto')
    data[moff:moff + 36] = struct.pack('>9i', *MATRICES[rotation])
    w = struct.unpack('>I', data[moff + 36:moff + 40])[0] >> 16
    h = struct.unpack('>I', data[moff + 40:moff + 44])[0] >> 16
    dw, dh = (h, w) if rotation in (90, 270) else (w, h)
    data[moff + 36:moff + 40] = struct.pack('>I', dw << 16)
    data[moff + 40:moff + 44] = struct.pack('>I', dh << 16)
    open(path, 'wb').write(data)
    print('%s: rotación=%d, tkhd %dx%d' % (path, rotation, dw, dh))
main(sys.argv[1], int(sys.argv[2]))
PY

for r in 90 180 270; do
  ffmpeg -y -f rawvideo -pixel_format rgb24 -video_size 320x240 -framerate 25 -i /tmp/angles_320x240.rgb \
    -c:v libx264 -crf 10 -preset veryslow -g 4 -bf 0 -pix_fmt yuv420p -movflags +faststart ordinal_rot${r}.mp4
  python3 /tmp/angles_patch_rotation.py ordinal_rot${r}.mp4 ${r}
done
ffmpeg -y -f rawvideo -pixel_format rgb24 -video_size 320x320 -framerate 25 -i /tmp/angles_320x320.rgb \
  -c:v libx264 -crf 10 -preset veryslow -g 4 -bf 0 -pix_fmt yuv420p -movflags +faststart ordinal_square_rot90.mp4
python3 /tmp/angles_patch_rotation.py ordinal_square_rot90.mp4 90

# VFR: deltas 40,40,40,80,40,40,40,160,40,40,40 ms (setpts no uniforme + -fps_mode vfr).
ffmpeg -y -f rawvideo -pixel_format rgb24 -video_size 320x240 -framerate 25 -i /tmp/angles_320x240.rgb \
  -vf "setpts='(N*0.04 + if(gte(N,4),0.04,0) + if(gte(N,8),0.12,0))/TB'" -fps_mode vfr \
  -c:v libx264 -crf 10 -preset veryslow -g 4 -bf 0 -pix_fmt yuv420p -movflags +faststart ordinal_vfr.mp4
```

3. Verificación **en host** antes de versionar (no sustituye a la ejecución en dispositivo, pero
   acredita que el contenedor y los píxeles codificados son los del manifest):

```
for f in ordinal_rot90 ordinal_rot180 ordinal_rot270 ordinal_square_rot90 ordinal_vfr; do
  ffprobe -v error -select_streams v:0 \
    -show_entries stream=width,height,nb_frames:stream_side_data=rotation -of default=noprint_wrappers=1 $f.mp4
  ffprobe -v error -select_streams v:0 -show_entries packet=pts_time -of csv=p=0 $f.mp4 | tr '\n' ' '; echo
done
```

ffprobe reporta la rotación del displaymatrix con **su** convención de signo: la matriz de 90° de
iOS/Android (la que hace que `KEY_ROTATION` valga 90) aparece como `rotation=-90`, 180 como `-180` y
270 como `90`. La expectativa en dispositivo es la de la tabla (`KEY_ROTATION` = 90/180/270); la
prueba instrumentada la exige literalmente y además comprueba la permutación de esquinas, así que un
mapeo distinto del extractor **fallaría de forma explícita**, no pasaría en silencio.

Comprobación visual de los píxeles codificados (ordinal y esquinas) frame a frame, decodificando en
la orientación codificada (`-noautorotate -fps_mode passthrough`): el script de verificación del slice
leyó las 12 imágenes de cada fixture y comprobó ordinal 0..11 y TL=rojo, TR=verde, BL=azul,
BR=amarillo en los cinco (5/5 OK). Reproducción compacta de esa comprobación:

```
cat > /tmp/angles_verify.py <<'PY'
import subprocess, sys
path, ew, eh, count = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
raw = subprocess.run(['ffmpeg','-v','error','-noautorotate','-i',path,'-fps_mode','passthrough',
                      '-f','rawvideo','-pix_fmt','rgb24','-'], check=True, capture_output=True).stdout
size = ew * eh * 3
assert len(raw) == size * count, (len(raw), size * count)
MARGIN, CELL = 16, 16
want = {'tl': (255,0,0), 'tr': (0,255,0), 'bl': (0,0,255), 'br': (255,255,0)}
pos = {'tl': (8,8), 'tr': (ew-8,8), 'bl': (8,eh-8), 'br': (ew-8,eh-8)}
def px(f, x, y):
    i = (y * ew + x) * 3
    return tuple(f[i:i+3])
def near(a, b, tol=40):
    return all(abs(x - y) <= tol for x, y in zip(a, b))
def ordinal(f):
    v, y = 0, MARGIN + CELL // 2
    for cell in range(15):
        light = sum(px(f, MARGIN + cell * CELL + CELL // 2, y)) / 3 > 140
        if cell < 3:
            if light != [False, True, False][cell]: return None
        elif light: v |= 1 << (cell - 3)
    return v
for n in range(count):
    f = raw[n*size:(n+1)*size]
    assert all(near(px(f, *pos[k]), want[k]) for k in want), (n, 'esquinas')
    assert ordinal(f) == n, (n, ordinal(f))
print('%s: %d frames, ordinal y esquinas OK' % (path, count))
PY
python3 /tmp/angles_verify.py ordinal_rot90.mp4 320 240 12
python3 /tmp/angles_verify.py ordinal_rot180.mp4 320 240 12
python3 /tmp/angles_verify.py ordinal_rot270.mp4 320 240 12
python3 /tmp/angles_verify.py ordinal_square_rot90.mp4 320 320 12
python3 /tmp/angles_verify.py ordinal_vfr.mp4 320 240 12
```

Checksums de la generación local (ffmpeg 8.0.1, x86_64), informativos:

```
87c1621e347acaa3b47be787969a7abbc421c23e29f5dfe8dd43dce41fcb01a1  ordinal_rot90.mp4
64be072254ec9368f183f2e1be63958809892f55391a472ba79dcced72c0f9d4  ordinal_rot180.mp4
09b140a3495c1d5a0bf30518fb3c4e7d8419cdb608edf6939301bcb77bcbbc38  ordinal_rot270.mp4
949d759e56b91cf65be38d09b6986b69345ba4930a50fc455bb50428eb77847e  ordinal_square_rot90.mp4
e714968f1f683ccc512e42c7a04af3a908e7581130e451948c9ab22a97704172  ordinal_vfr.mp4
```

Son informativos: pueden cambiar con otra versión de x264/ffmpeg; lo que debe conservarse es el
manifest (dimensiones, rotación, frames, PTS, ordinal y esquinas).

### Estado de la evidencia

- **Observado en host:** MP4 decodificables, `nb_frames=12`, dimensiones codificadas, matriz de
  rotación presente y valores de la tabla, PTS CFR/VFR exactos, y píxeles con el ordinal y las
  esquinas esperadas en los 12 frames de los 5 fixtures.
- **No observado:** ejecución en AVD/dispositivo de este slice. `RotatedFixtureVisualIdentityInstrumentedTest`
  usa la sesión sellada real y la ruta `MediaExtractor` + `MediaCodec`; exige `KEY_ROTATION` =
  90/180/270, dimensiones congeladas 240×320 (90/270), ordinal visual en la orientación presentada,
  permutación de esquinas y PTS del buffer de salida igual al del índice, además de seeks alternos
  (0, último, medio, 1, penúltimo). Compilarla no acredita que el emulador decodifique bien estos
  fixtures: falta ejecutarla.
- **PTS inicial distinto de 0: no demostrado, no versionado.** Se probó en host con
  `setpts='(…)+2/TB'` (`-output_ts_offset`): ffmpeg escribe las muestras desde 2 s pero añade un `elst`
  con una edición vacía de 2 000 ms. Sin dispositivo no se puede acreditar que `MediaExtractor`
  conserve ese desplazamiento en vez de normalizarlo, así que no se crea fixture ni se afirma soporte;
  queda como pendiente con evidencia insuficiente, no como caso aprobado.
- **VFR sí se versiona** porque los PTS variables son duraciones de muestra (`stts`), sin edición
  vacía: el contenedor no necesita normalización para conservarlos. Aun así, los deltas exactos los
  exige la prueba instrumentada en dispositivo; aquí solo están verificados en host.
