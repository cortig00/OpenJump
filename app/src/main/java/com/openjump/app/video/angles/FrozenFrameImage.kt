package com.openjump.app.video.angles

import android.graphics.Bitmap
import android.graphics.Matrix

/**
 * Resultado de una congelación: o hay una imagen inmutable con procedencia verificada, o hay un
 * bloqueo tipado. No existe un tercer estado "imagen aproximada".
 */
sealed interface FrameFreezeResult {

    /** Imagen congelada, copiada y orientada, lista para mostrar antes de colocar puntos. */
    data class Frozen(val image: FrozenFrameImage) : FrameFreezeResult

    /** No puede etiquetarse ninguna imagen como el frame solicitado. */
    data class Blocked(
        val reason: FrameFreezeRejectionReason,
        val detail: String,
    ) : FrameFreezeResult
}

/**
 * Imagen congelada inmutable y su procedencia. El bitmap se copia y se orienta **una sola vez**; la
 * capa de presentación y la de anotación deben usar exactamente [bitmap], de modo que lo que el
 * usuario ve es lo que se mide. [close] libera la copia; tras cerrarla, [bitmap] falla en lugar de
 * devolver píxeles liberados.
 *
 * La inmutabilidad es de contrato: el bitmap se crea con `isMutable = false` y esta clase no expone
 * operaciones de escritura. El digest de [provenance] permite comprobar más adelante que la imagen
 * anotada sigue siendo la misma.
 *
 * ## Alcance del presupuesto
 *
 * Una imagen entregada **ya no está contabilizada** por [FrameCaptureReservations]: esa reserva de
 * proceso se libera al terminar la captura y solo cubre el trabajo en vuelo (matriz ARGB, bitmap
 * decodificado y copias congeladas del peor caso), no las imágenes retenidas. Cuántas imágenes guarda
 * la UI, cuándo las cierra y cuánta memoria suman es responsabilidad de la integración y sigue siendo
 * una barrera abierta; [close] solo garantiza liberar el bitmap de **esta** imagen, de forma
 * idempotente.
 */
class FrozenFrameImage internal constructor(
    private val frozenBitmap: Bitmap,
    val provenance: FrameCaptureProvenance,
) : AutoCloseable {

    val bitmap: Bitmap
        get() {
            check(!frozenBitmap.isRecycled) { "La imagen congelada ya se liberó." }
            return frozenBitmap
        }

    val width: Int get() = provenance.frozenWidth
    val height: Int get() = provenance.frozenHeight

    private var released = false

    override fun close() {
        if (!released) {
            released = true
            frozenBitmap.recycle()
        }
    }
}

/**
 * Convierte un bitmap decodificado en una [FrozenFrameImage] solo si la política acepta toda la
 * evidencia. Es la única fábrica pública del tipo congelado: la copia, la orientación, el digest y la
 * etiqueta temporal se producen juntos o no se produce nada.
 *
 * Contrato de entrada: [decodedBitmap] pertenece al llamador (normalmente el decodificador); esta
 * función no lo recicla y no depende de que siga siendo mutable. La copia resultante es independiente.
 */
object FrozenFrameFreezer {

    fun freeze(
        decodedBitmap: Bitmap,
        request: FrameFreezeRequest,
        temporal: FrameTemporalEvidence,
        policy: FrameFreezePolicy = FrameFreezePolicy(),
        orientationEvidence: FrameOrientationEvidence? = null,
    ): FrameFreezeResult {
        if (decodedBitmap.isRecycled || decodedBitmap.width < 1 || decodedBitmap.height < 1) {
            return FrameFreezeResult.Blocked(
                FrameFreezeRejectionReason.DECODED_BITMAP_UNAVAILABLE,
                "El bitmap decodificado no está disponible.",
            )
        }
        // La solicitud puede describir una fuente real fuera de la envolvente (p. ej. 8K): se rechaza
        // con motivo tipado antes de planificar orientación, presupuestar o asignar copias. El
        // planificador lanzaría para áreas fuera de límite, así que esta es la única puerta segura.
        if (!FrameFreezeBudget.isWithinFrameEnvelope(request.encodedWidth, request.encodedHeight) ||
            !FrameFreezeBudget.isWithinFrameEnvelope(decodedBitmap.width, decodedBitmap.height)
        ) {
            return FrameFreezeResult.Blocked(
                FrameFreezeRejectionReason.MEMORY_BUDGET_EXCEEDED,
                "Las dimensiones declaradas o decodificadas superan la envolvente de proceso.",
            )
        }

        val orientation = FrameOrientationPlanner.plan(
            encodedWidth = request.encodedWidth,
            encodedHeight = request.encodedHeight,
            declaredRotationDegrees = request.declaredRotationDegrees,
            decodedWidth = decodedBitmap.width,
            decodedHeight = decodedBitmap.height,
            evidence = orientationEvidence,
        )
        if (orientation is FrameOrientationPlan.Undetermined) {
            // Rechazo antes de asignar la copia: no se muestra una orientación no demostrada.
            return FrameFreezeResult.Blocked(
                FrameFreezeRejectionReason.ORIENTATION_UNDETERMINED,
                orientation.reason.name,
            )
        }
        require(orientation is FrameOrientationPlan.Determined)

        // Presupuesto mínimo (una copia). Si la ruta necesita más copias simultáneas, la copia lo
        // vuelve a comprobar antes de asignarlas.
        if (!FrameFreezeBudget.isWithinBudget(
                decodedBitmap.allocationByteCount.toLong(),
                orientation.orientedWidth,
                orientation.orientedHeight,
                simultaneousFrozenCopies = 1,
            )
        ) {
            return FrameFreezeResult.Blocked(
                FrameFreezeRejectionReason.MEMORY_BUDGET_EXCEEDED,
                "La copia congelada supera el presupuesto de trabajo.",
            )
        }

        val frozenBitmap = try {
            orientedImmutableCopy(
                source = decodedBitmap,
                rotationToApplyDegrees = orientation.rotationToApplyDegrees,
                decodedAllocationBytes = decodedBitmap.allocationByteCount.toLong(),
                orientedWidth = orientation.orientedWidth,
                orientedHeight = orientation.orientedHeight,
            )
        } catch (_: OutOfMemoryError) {
            null
        } ?: return FrameFreezeResult.Blocked(
            FrameFreezeRejectionReason.MEMORY_BUDGET_EXCEEDED,
            "No se pudo asignar la copia inmutable del frame.",
        )

        if (frozenBitmap.width != orientation.orientedWidth || frozenBitmap.height != orientation.orientedHeight) {
            frozenBitmap.recycle()
            return FrameFreezeResult.Blocked(
                FrameFreezeRejectionReason.ORIENTATION_UNDETERMINED,
                "La copia orientada no coincide con las dimensiones presentadas.",
            )
        }

        val digest = try {
            val digest = FrameVisualIdentity.digest(frozenBitmap.width, frozenBitmap.height) { y, row ->
                frozenBitmap.getPixels(row, 0, frozenBitmap.width, 0, y, frozenBitmap.width, 1)
            }
            val decision = policy.evaluate(
                FrameFreezeObservation(
                    request = request,
                    temporal = temporal,
                    orientation = orientation,
                    orientationEvidence = orientationEvidence,
                    decodedWidth = decodedBitmap.width,
                    decodedHeight = decodedBitmap.height,
                    decodedAllocationBytes = decodedBitmap.allocationByteCount.toLong(),
                    contentDigest = digest,
                ),
            )
            when (decision) {
                is FrameFreezeDecision.Accepted -> FrameFreezeResult.Frozen(
                    FrozenFrameImage(frozenBitmap, decision.provenance),
                )
                is FrameFreezeDecision.Rejected -> {
                    frozenBitmap.recycle()
                    FrameFreezeResult.Blocked(decision.reason, decision.detail)
                }
            }
        } catch (error: OutOfMemoryError) {
            frozenBitmap.recycle()
            FrameFreezeResult.Blocked(
                FrameFreezeRejectionReason.MEMORY_BUDGET_EXCEEDED,
                "No se pudo completar la imagen congelada (${error.javaClass.simpleName}).",
            )
        } catch (error: Throwable) {
            frozenBitmap.recycle()
            throw error
        }
        return digest
    }

    /**
     * Copia ARGB_8888 **inmutable** y de propiedad exclusiva de la imagen congelada, aplicando como
     * máximo la rotación declarada y ya resuelta por [FrameOrientationPlanner]. No escala ni recorta:
     * cambiar dimensiones sería otra decisión de producto y otra fuente de ambigüedad.
     *
     * `Bitmap.createBitmap(..., matrix, true)` documenta un resultado inmutable, pero no lo garantiza
     * en todas las versiones: si el resultado es mutable se materializa una instantánea inmutable por
     * píxeles. Tampoco se devuelve nunca el propio bitmap del llamador (`copy` puede devolver `this`),
     * porque [FrozenFrameImage.close] lo reciclaría. Cada copia simultánea se presupuesta antes de
     * asignarla; si no cabe, se bloquea en lugar de arriesgar OOM.
     */
    private fun orientedImmutableCopy(
        source: Bitmap,
        rotationToApplyDegrees: Int,
        decodedAllocationBytes: Long,
        orientedWidth: Int,
        orientedHeight: Int,
    ): Bitmap? {
        if (rotationToApplyDegrees == 0) {
            // Un bitmap inmutable ARGB_8888 permite una única copia fresca. Cualquier otra
            // configuración va directa a la instantánea por píxeles: no se crea una copia
            // intermedia que luego habría que volver a copiar (menos copias vivas).
            if (source.config == Bitmap.Config.ARGB_8888 && !source.isMutable) {
                val copyBytes = FrameFreezeBudget.argbArrayBytes(orientedWidth, orientedHeight) ?: return null
                return when (val allocated = guardedAllocation(copyBytes) {
                    source.copy(Bitmap.Config.ARGB_8888, false)
                }) {
                    is FrameAllocation.Allocated -> allocated.value.takeIf { it !== source }
                    is FrameAllocation.Unavailable -> null
                }
            }
            // Instantánea: array ARGB + bitmap inmutable conviven con el bitmap decodificado.
            if (!FrameFreezeBudget.isWithinBudget(
                    decodedAllocationBytes,
                    orientedWidth,
                    orientedHeight,
                    simultaneousFrozenCopies = 2,
                )
            ) {
                return null
            }
            return pixelSnapshot(source)
        }
        val matrix = Matrix().apply { postRotate(rotationToApplyDegrees.toFloat()) }
        val oriented = try {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } catch (_: OutOfMemoryError) {
            null
        } ?: return null
        if (oriented.width != orientedWidth || oriented.height != orientedHeight) {
            oriented.recycle()
            return null
        }
        if (oriented.config == Bitmap.Config.ARGB_8888 && !oriented.isMutable) return oriented
        // Rotada + array de píxeles + instantánea inmutable viven a la vez: tres copias.
        if (!FrameFreezeBudget.isWithinBudget(
                decodedAllocationBytes,
                orientedWidth,
                orientedHeight,
                simultaneousFrozenCopies = 3,
            )
        ) {
            oriented.recycle()
            return null
        }
        val immutable = pixelSnapshot(oriented)
        oriented.recycle()
        return immutable
    }

    /** Instantánea ARGB_8888 inmutable y siempre recién asignada (nunca el bitmap de entrada). */
    private fun pixelSnapshot(source: Bitmap): Bitmap? {
        if (source.isRecycled) return null
        val width = source.width
        val height = source.height
        val arrayBytes = FrameFreezeBudget.argbArrayBytes(width, height) ?: return null
        return when (val pixelsAllocation = guardedAllocation(arrayBytes) {
            val pixels = IntArray(Math.multiplyExact(width, height))
            source.getPixels(pixels, 0, width, 0, 0, width, height)
            pixels
        }) {
            is FrameAllocation.Unavailable -> null
            is FrameAllocation.Allocated -> {
                val pixels = pixelsAllocation.value
                when (val bitmapAllocation = guardedAllocation(arrayBytes) {
                    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                }) {
                    is FrameAllocation.Unavailable -> null
                    is FrameAllocation.Allocated -> bitmapAllocation.value
                }
            }
        }
    }
}
