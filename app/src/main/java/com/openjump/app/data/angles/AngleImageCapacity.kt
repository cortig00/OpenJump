package com.openjump.app.data.angles

/**
 * Presupuesto agregado de un **directorio real**, compartido por todas las instancias del proceso
 * que publican imágenes en él (ver [AngleImageCapacityRegistry]).
 *
 * **Por qué compartido.** El presupuesto no puede vivir en cada instancia de
 * [PrivateAngleImageStore]: dos instancias sobre el mismo directorio contarían por separado y la
 * suma de sus imágenes publicadas podría superar [AngleImageStoreLimits.maxTotalBytes]. El
 * presupuesto es, por tanto, una pieza más del directorio, no de la instancia.
 *
 * **Qué garantiza el cerrojo.** La medición del uso real, la reserva, la publicación y la
 * liberación de la reserva ocurren bajo el **mismo** cerrojo:
 *
 * - medir y reservar en el mismo paso atómico impide que una medida tomada antes de una
 *   publicación se use después de ella (medida obsoleta) y que "publicado + reservado" se cuente
 *   dos veces o se pierda;
 * - publicar y liberar en el mismo paso atómico impide que el hueco contable quede abierto entre
 *   el rename del nombre definitivo y la contabilidad, o al contrario.
 *
 * Las escrituras no se serializan entre sí: el cerrojo se mantiene lo que dura la medición del
 * directorio y la publicación (un rename interno, más el `mkdir` exclusivo de la clave), nunca lo
 * que dura el streaming. Una escritura que no cabe se rechaza con un resultado tipado en vez de
 * esperar.
 *
 * **Alcance.** La coordinación es **de proceso** (JVM de la app). Este almacén no puede coordinar
 * procesos no controlados que escriban en el mismo directorio: la comprobación de cuota se apoya
 * en la medición del directorio en el momento de reservar, no en un bloqueo entre procesos.
 *
 * **Aritmética.** La comprobación se hace por diferencias (`disponible = máx − medido − enVuelo`)
 * en vez de sumar `medido + enVuelo + solicitado`, de modo que ningún valor cercano a
 * [Long.MAX_VALUE] puede desbordar y convertir un rechazo en una aceptación. Una medición
 * negativa o inverosímil se rechaza (fail-closed), nunca se interpreta como "hay hueco".
 *
 * **Conservador, nunca permisivo.** La medición incluye los temporales propios de escrituras en
 * vuelo, así que mientras otra escritura está en curso puede rechazarse antes de tiempo una que sí
 * cabría; el error es siempre por exceso de celo y nunca permite superar el máximo.
 */
internal class DirectoryCapacity(val limits: AngleImageStoreLimits) {

    private val lock = Any()

    /** Bytes reservados por escrituras activas de este directorio y aún no publicados. */
    private var inFlightBytes = 0L

    /**
     * Mide el uso real con [measureUsageBytes] y reserva [requestedBytes] en el mismo paso atómico.
     *
     * [measureUsageBytes] se ejecuta bajo el cerrojo del presupuesto: no puede observar el
     * directorio antes de una publicación y devolver el valor después. Puede lanzar; en ese caso
     * no se reserva nada.
     */
    fun reserve(measureUsageBytes: () -> Long, requestedBytes: Long): CapacityReservationResult =
        synchronized(lock) {
            val measured = measureUsageBytes()
            if (measured < 0L || measured > limits.maxTotalBytes || requestedBytes < 1L) {
                // Medida imposible (E/S rota) o reserva sin sentido: no se acepta nada.
                return@synchronized CapacityReservationResult.Denied(measured)
            }
            val availableTotal = limits.maxTotalBytes - measured
            if (inFlightBytes > availableTotal || requestedBytes > availableTotal - inFlightBytes) {
                return@synchronized CapacityReservationResult.Denied(measured)
            }
            // Suma acotada: `requestedBytes <= availableTotal - inFlightBytes` ya se ha comprobado,
            // así que el resultado nunca supera `limits.maxTotalBytes` ni desborda.
            inFlightBytes += requestedBytes
            CapacityReservationResult.Granted(Reservation(requestedBytes))
        }

    /**
     * Ejecuta [mutation] con el **cerrojo de medición y publicación** del presupuesto tomado.
     *
     * Existe para una única pieza: la retirada exacta de una imagen ya verificada
     * ([PrivateAngleImageStore.reclaimVerifiedOwnedImage]). El `unlink` del fichero final y el
     * `rmdir` del directorio de clave cambian los bytes medidos, así que deben ocurrir dentro del
     * mismo cerrojo en el que [reserve] mide y reserva:
     *
     * - una medición no puede quedar "a medias" de la retirada. En particular, el recorrido de uso
     *   hace `lstat` y luego `length` sobre cada entrada; si un `unlink` ajeno se intercalara entre
     *   ambas consultas, una **escritura no relacionada** fallaría con un error de E/S espurio;
     * - una escritura concurrente no puede publicar contando bytes que la retirada está a punto de
     *   liberar, ni al contrario (la reserva en vuelo se mide contra el mismo cerrojo).
     *
     * No se simula ninguna reserva: la retirada sólo libera bytes reales, no reserva hueco. El
     * cerrojo es **corto** por contrato: quien lo usa no hace streaming, no abre flujos grandes ni
     * espera a Room, a la base de datos ni a otro proceso. Los `fsync` de directorio que confirman
     * los nombres retirados se hacen **después** de soltarlo, porque no cambian el uso medido.
     */
    fun <T> withMeasurementLock(mutation: () -> T): T = synchronized(lock) { mutation() }

    /**
     * Reserva en vuelo de una escritura concreta.
     *
     * [commit] publica y libera en el mismo paso atómico; si la publicación falla, la reserva se
     * libera igualmente. [release] es idempotente y sólo toca la contabilidad de esta reserva.
     */
    inner class Reservation internal constructor(private val bytes: Long) {

        private var released = false

        fun commit(publish: () -> Unit) = synchronized(lock) {
            try {
                publish()
            } finally {
                releaseLocked()
            }
        }

        fun release() = synchronized(lock) { releaseLocked() }

        private fun releaseLocked() {
            if (!released) {
                released = true
                inFlightBytes -= bytes
            }
        }
    }
}

/** Resultado de intentar reservar: concesión con su reserva o rechazo con la medida observada. */
internal sealed interface CapacityReservationResult {

    /** Reserva concedida; el llamante debe publicar con [Granted.reservation] o liberarla. */
    class Granted internal constructor(val reservation: DirectoryCapacity.Reservation) :
        CapacityReservationResult

    /**
     * Rechazo. [measuredUsageBytes] es el uso medido dentro del cerrojo (para diagnóstico y para
     * el error tipado); si es negativo, la medición no era fiable y el llamante debe tratarlo como
     * fallo de E/S, no como cuota.
     */
    class Denied(val measuredUsageBytes: Long) : CapacityReservationResult
}

/** Registro de presupuestos del proceso: uno por directorio **real** (ruta canónica). */
internal object AngleImageCapacityRegistry {

    private val lock = Any()

    /**
     * No se descartan entradas vacías a propósito: olvidar el presupuesto de un directorio
     * reabriría la puerta a que una segunda instancia con otros límites se registrase como si
     * fuese la primera. El coste es una entrada pequeña por directorio usado en el proceso.
     */
    private val byRealDirectory = HashMap<String, DirectoryCapacity>()

    /**
     * Devuelve el presupuesto de [realDirectoryPath] con [limits], creándolo si es el primero.
     *
     * Si ya existe uno con **otros** límites, devuelve [CapacityRegistration.ConflictingLimits]
     * (fail-closed): mezclar presupuestos distintos para el mismo directorio permitiría que la
     * instancia más permisiva sobrepasase el total que la otra considera máximo.
     */
    fun capacityFor(realDirectoryPath: String, limits: AngleImageStoreLimits): CapacityRegistration =
        synchronized(lock) {
            val existing = byRealDirectory[realDirectoryPath]
            when {
                existing == null -> {
                    val created = DirectoryCapacity(limits)
                    byRealDirectory[realDirectoryPath] = created
                    CapacityRegistration.Registered(created)
                }
                existing.limits == limits -> CapacityRegistration.Registered(existing)
                else -> CapacityRegistration.ConflictingLimits(existing.limits)
            }
        }
}

/** Registro de un directorio: presupuesto compartido o conflicto de límites ya registrados. */
internal sealed interface CapacityRegistration {

    /** Presupuesto vigente para el directorio real. */
    class Registered(val capacity: DirectoryCapacity) : CapacityRegistration

    /** Otra instancia del proceso ya registró [registeredLimits] para ese directorio. */
    class ConflictingLimits(val registeredLimits: AngleImageStoreLimits) : CapacityRegistration
}
