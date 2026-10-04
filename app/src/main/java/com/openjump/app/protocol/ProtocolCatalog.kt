package com.openjump.app.protocol

object ProtocolCatalog {
    val all: List<ProtocolDefinition> = listOf(
        ProtocolDefinition(
            id = ProtocolId.CMJ,
            title = "Countermovement Jump",
            shortName = "CMJ",
            description = "Salto vertical con contramovimiento y manos en la cintura.",
            category = ProtocolCategory.VERTICAL_POWER,
            availability = ProtocolAvailability.AVAILABLE,
            requiredSetup = emptySet(),
            requiredEvents = listOf(EventType.MOVEMENT_START, EventType.TAKEOFF, EventType.LANDING),
            primaryMetric = MetricKey.HEIGHT_CM,
            includesRsiMod = true,
        ),
        ProtocolDefinition(
            id = ProtocolId.SJ,
            title = "Squat Jump",
            shortName = "SJ",
            description = "Salto desde posición estática de sentadilla, sin contramovimiento.",
            category = ProtocolCategory.VERTICAL_POWER,
            availability = ProtocolAvailability.AVAILABLE,
            requiredSetup = emptySet(),
            requiredEvents = listOf(EventType.TAKEOFF, EventType.LANDING),
            primaryMetric = MetricKey.HEIGHT_CM,
        ),
        ProtocolDefinition(
            id = ProtocolId.ABALAKOV,
            title = "Abalakov",
            shortName = "ABK",
            description = "CMJ con balanceo libre de brazos.",
            category = ProtocolCategory.VERTICAL_POWER,
            availability = ProtocolAvailability.AVAILABLE,
            requiredSetup = emptySet(),
            requiredEvents = listOf(EventType.MOVEMENT_START, EventType.TAKEOFF, EventType.LANDING),
            primaryMetric = MetricKey.HEIGHT_CM,
            includesRsiMod = true,
        ),
        ProtocolDefinition(
            id = ProtocolId.UNILATERAL,
            title = "Salto unilateral",
            shortName = "UNI",
            description = "CMJ a una pierna para registrar cada lado por separado.",
            category = ProtocolCategory.VERTICAL_POWER,
            availability = ProtocolAvailability.AVAILABLE,
            requiredSetup = setOf(SetupField.SIDE),
            requiredEvents = listOf(EventType.MOVEMENT_START, EventType.TAKEOFF, EventType.LANDING),
            primaryMetric = MetricKey.HEIGHT_CM,
            includesRsiMod = true,
        ),
        ProtocolDefinition(
            id = ProtocolId.DROP_JUMP,
            title = "Drop Jump",
            shortName = "DJ",
            description = "Evalúa la reactividad tras caer desde un cajón.",
            category = ProtocolCategory.REACTIVITY,
            availability = ProtocolAvailability.AVAILABLE,
            requiredSetup = setOf(SetupField.DROP_HEIGHT_CM),
            requiredEvents = listOf(EventType.INITIAL_CONTACT, EventType.TAKEOFF, EventType.LANDING),
            primaryMetric = MetricKey.RSI,
        ),
        ProtocolDefinition(
            id = ProtocolId.REPEATED_10_5,
            title = "10/5 repeated jumps",
            shortName = "10/5",
            description = "Diez rebotes y media de los cinco mejores RSI.",
            category = ProtocolCategory.REACTIVITY,
            availability = ProtocolAvailability.COMING_SOON,
            requiredSetup = emptySet(),
            requiredEvents = emptyList(),
            primaryMetric = MetricKey.BEST_FIVE_RSI_MEAN,
        ),
        ProtocolDefinition(
            id = ProtocolId.ASYMMETRY,
            title = "Asimetría",
            shortName = "ASY",
            description = "Comparación agrupada de intentos izquierdo y derecho.",
            category = ProtocolCategory.COMPARISON_DISTANCE,
            availability = ProtocolAvailability.AVAILABLE,
            requiredSetup = emptySet(),
            requiredEvents = emptyList(),
            primaryMetric = MetricKey.ASYMMETRY_PERCENT,
        ),
        ProtocolDefinition(
            id = ProtocolId.HORIZONTAL,
            title = "Salto horizontal",
            shortName = "DIST",
            description = "Distancia manual calibrada desde la salida hasta el talón más retrasado.",
            category = ProtocolCategory.COMPARISON_DISTANCE,
            availability = ProtocolAvailability.AVAILABLE,
            requiredSetup = emptySet(),
            requiredEvents = emptyList(),
            primaryMetric = MetricKey.DISTANCE_M,
        ),
    )

    val available: List<ProtocolDefinition> = all.filter { it.availability == ProtocolAvailability.AVAILABLE }

    fun find(id: ProtocolId): ProtocolDefinition = all.first { it.id == id }

    fun find(storageKey: String?): ProtocolDefinition? =
        ProtocolId.fromStorageKey(storageKey)?.let(::find)

    fun validateSetup(definition: ProtocolDefinition, setup: ProtocolSetup): String? {
        if (definition.availability != ProtocolAvailability.AVAILABLE) {
            return "Este protocolo todavía no está disponible."
        }
        if (SetupField.SIDE in definition.requiredSetup && setup.side == null) {
            return "Selecciona el lado izquierdo o derecho."
        }
        if (SetupField.DROP_HEIGHT_CM in definition.requiredSetup) {
            val height = setup.dropHeightCm
            if (height == null || !height.isFinite() || height <= 0.0) {
                return "Introduce una altura de cajón mayor que cero."
            }
        }
        if (SetupField.DISTANCE_CM in definition.requiredSetup) {
            val distance = setup.distanceCm
            if (distance == null || !distance.isFinite() || distance <= 0.0) {
                return "Introduce una distancia mayor que cero."
            }
        }
        return null
    }
}
