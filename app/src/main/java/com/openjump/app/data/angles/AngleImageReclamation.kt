package com.openjump.app.data.angles

import java.io.File

/**
 * Resultado tipado de [PrivateAngleImageStore.reclaimVerifiedOwnedImage].
 *
 * La operación **no es un GC**: describe una única clave y una única integridad esperada, no
 * enumera el almacén, no busca huérfanos, no toca otras claves, el vídeo, las copias de compartir
 * ni el legado plano. Cada caso existe para que el llamante pueda informar la verdad al usuario:
 *
 * - [Removed]: el `image.bin` exacto se verificó (tamaño y SHA-256) y se retiró; se informa aparte
 *   si el directorio de la clave quedó retenido y si los nombres se pudieron sincronizar. Un
 *   `directoryRetained = true` o un `fsync` fallido **no** se convierten en "borrado durable".
 * - [Absent]: no hay nada en el objetivo exacto (idempotente). No se crea, mueve ni retira nada.
 * - [Retained]: se rechazó **antes** de mutar; consta que nada se ha borrado y el motivo es tipado.
 * - [Unconfirmed]: se intentó el `unlink` y su efecto **no** se pudo confirmar del todo. Puede que
 *   el fichero ya no exista: **no** se puede informar "conservado". [Unconfirmed.observed] dice qué
 *   se pudo observar del fichero exacto después del intento.
 *
 * Ni [Removed] ni [Unconfirmed] afirman nada sobre Room: que una fila dejó de referenciar la clave
 * lo garantiza —o no— el llamante, no el almacén (ver el contrato del método).
 */
internal sealed interface AngleImageReclaimResult {

    /**
     * Imagen publicada verificada y retirada con un `unlink` **exacto** del fichero final.
     *
     * [image] es la integridad realmente comprobada en disco (no la esperada) y la clave retirada.
     * [directoryRetained] indica que el directorio de la clave no se pudo retirar con `rmdir` (sigue
     * existiendo, posiblemente vacío): no se borra de forma recursiva ni se reintenta desde aquí.
     * [durability] informa de los `fsync` de los nombres que la retirada sí cambió; un
     * `UNSUPPORTED`/`FAILED` (o un nombre retenido sin confirmar) deja
     * [AngleImageReclaimDurability.removalEntriesSynced] en `false`, y entonces **no** se declara que
     * la retirada sobreviva a un corte.
     */
    data class Removed(
        val image: StoredAngleImage,
        val directoryRetained: Boolean,
        val durability: AngleImageReclaimDurability,
    ) : AngleImageReclaimResult

    /**
     * No hay imagen en el objetivo exacto: el directorio del almacén, el de la clave o el propio
     * `image.bin` no existen. Es un resultado **idempotente**: no se crea, mueve, retira ni
     * "repara" nada; un directorio de clave vacío se deja tal cual (la recuperación de restos no es
     * de esta pieza).
     */
    data class Absent(val imageKey: String) : AngleImageReclaimResult

    /**
     * Rechazo **antes** de cualquier mutación: se sabe que no se ha borrado nada y [reason] explica
     * por qué. No implica que la imagen se conserve en el futuro (otro proceso con acceso al
     * sandbox podría cambiarla), sólo que esta operación no tocó el fichero.
     */
    data class Retained(
        val imageKey: String,
        val reason: AngleImageReclaimRefusal,
    ) : AngleImageReclaimResult

    /**
     * El `unlink` del fichero exacto se intentó y su resultado **no** está confirmado: el backend
     * lanzó, o el nombre de la clave dejó de ser un directorio real. Puede que el fichero ya no
     * exista, así que **no** se informa como conservado; tampoco se informa como retirado, porque el
     * efecto no se pudo confirmar. [reason] es el fallo tipado y [observed] lo que se pudo observar
     * del fichero exacto después del intento.
     */
    data class Unconfirmed(
        val imageKey: String,
        val reason: AngleImageStoreError,
        val observed: AngleImageReclaimObservation,
    ) : AngleImageReclaimResult
}

/**
 * Qué se pudo observar del fichero exacto (`angle-images/<clave>/image.bin`) **después** de que el
 * `unlink` lanzara o de que el layout se volviera inesperado.
 *
 * Sirve para no mentir en ningún sentido: una excepción no demuestra que el fichero siga ahí (el
 * backend pudo borrarlo y fallar después), ni que se haya borrado (pudo fallar antes).
 */
internal enum class AngleImageReclaimObservation {
    /** La ruta exacta ya no existe: el borrado pudo aplicarse pese a la excepción. */
    ABSENT,

    /** La ruta exacta sigue existiendo: esta operación no la retiró. */
    PRESENT,

    /** No se pudo observar: ni presencia ni ausencia confirmadas. */
    UNKNOWN,
}

/**
 * Motivo tipado de [AngleImageReclaimResult.Retained].
 *
 * La mayor parte de los rechazos son errores ya tipados del almacén ([StoreError]): clave no
 * canónica, integridad esperada inutilizable, raíz no utilizable, ruta insegura, contenido que no
 * coincide y fallo de E/S con su etapa. [UnexpectedKeyDirectoryContent] es el único caso propio de
 * la reclamación: el layout exacto no se reconoce y se conserva **sin tocar nada**.
 */
internal sealed interface AngleImageReclaimRefusal {

    /** Rechazo ya tipado por el almacén; la operación no mutó el fichero. */
    data class StoreError(val error: AngleImageStoreError) : AngleImageReclaimRefusal

    /**
     * El directorio de la clave no contiene **exactamente** `image.bin`: hay temporales, ficheros
     * ajenos, subdirectorios o enlaces.
     *
     * [unexpectedEntryNames] son los nombres observados (sin contar `image.bin`), ordenados. No se
     * borra nada: un temporal abandonado o un extra pueden ser datos de otra operación y esta pieza
     * no decide sobre ellos (no hay borrado por prefijo, fecha ni enumeración).
     */
    data class UnexpectedKeyDirectoryContent(
        val imageKey: String,
        val unexpectedEntryNames: List<String>,
    ) : AngleImageReclaimRefusal
}

/**
 * Durabilidad observada de la retirada de una imagen.
 *
 * `fsync` de un directorio confirma los **nombres** que contiene; por eso sólo se sincronizan los
 * directorios cuyos nombres cambió esta operación, de más profundo a más superficial:
 *
 * - [keyDirectory]: el directorio de la clave, para confirmar la retirada del nombre `image.bin`.
 *   Es `null` cuando el directorio de la clave se retiró (no queda nombre propio dentro que
 *   confirmar) o cuando ya no existía como directorio real.
 * - [imagesDirectory]: `angle-images`, para confirmar la retirada del **nombre de la clave**. Es
 *   `null` cuando el directorio de la clave se conservó (el contenido de `angle-images` no cambió).
 *
 * El directorio raíz no se sincroniza: su contenido (`angle-images`) no cambia en una retirada y
 * su propia entrada queda fuera del alcance del almacén. `null` no es un fallo: significa que no
 * había ningún nombre que confirmar en ese directorio. Un valor `UNSUPPORTED` o `FAILED` sí lo es.
 */
internal data class AngleImageReclaimDurability(
    val keyDirectory: AngleImageDirectorySync?,
    val imagesDirectory: AngleImageDirectorySync?,
) {
    /**
     * `true` sólo si **todos** los nombres que la retirada cambió y que seguían existiendo se
     * confirmaron con [AngleImageDirectorySync.SYNCED]. En caso contrario la retirada está aplicada
     * en proceso pero su persistencia frente a un corte **no** está confirmada.
     */
    val removalEntriesSynced: Boolean
        get() = keyDirectory.wasSyncedOrNotNeeded() && imagesDirectory.wasSyncedOrNotNeeded()
}

/** `null` = no había nombre que confirmar en ese directorio; `SYNCED` = confirmado. */
private fun AngleImageDirectorySync?.wasSyncedOrNotNeeded(): Boolean =
    this == null || this == AngleImageDirectorySync.SYNCED

/**
 * Objetivo **ya validado** de una reclamación exacta: ruta del directorio de la clave, presupuesto
 * del directorio real e integridad realmente comprobada en disco.
 *
 * Sólo lo construye el preflight del almacén, cuando ya no queda ningún rechazo posible por
 * layout, límites ni contenido y todavía no se ha mutado nada.
 */
internal class ReclaimTarget(
    val keyDirectory: File,
    val capacity: DirectoryCapacity,
    val image: StoredAngleImage,
)

/** Resultado del preflight de reclamación: objetivo listo o resultado terminal sin mutación. */
internal sealed interface ReclaimPreflight {

    /** Todo verificado: el llamante puede intentar la retirada exacta. */
    class Ready(val target: ReclaimTarget) : ReclaimPreflight

    /** Resultado terminal **sin** mutación: rechazo tipado o ausencia idempotente. */
    class Settled(val result: AngleImageReclaimResult) : ReclaimPreflight
}
