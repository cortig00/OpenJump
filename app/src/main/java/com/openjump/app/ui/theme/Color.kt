package com.openjump.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── Light scheme ───────────────────────────────────────────────────────────
internal val LightPrimary = Color(0xFF006B5A)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFF8CF8D8)
internal val LightOnPrimaryContainer = Color(0xFF002019)
internal val LightSecondary = Color(0xFF3F5F90)
internal val LightOnSecondary = Color(0xFFFFFFFF)
internal val LightOnSecondaryContainer = Color(0xFF0B1B32)
internal val LightSecondaryContainer = Color(0xFFD7E3FF)
internal val LightTertiary = Color(0xFF8A4F00)
internal val LightOnTertiary = Color(0xFFFFFFFF)
internal val LightTertiaryContainer = Color(0xFFFFDDB8)
internal val LightOnTertiaryContainer = Color(0xFF2C1600)
internal val LightError = Color(0xFFBA1A1A)
internal val LightOnError = Color(0xFFFFFFFF)
internal val LightErrorContainer = Color(0xFFFFDAD6)
internal val LightOnErrorContainer = Color(0xFF410002)
internal val LightBackground = Color(0xFFF4F8F6)
internal val LightOnBackground = Color(0xFF171D1A)
internal val LightSurface = Color(0xFFFBFDFB)
internal val LightOnSurface = Color(0xFF171D1A)
internal val LightSurfaceVariant = Color(0xFFDCE5E0)
internal val LightOnSurfaceVariant = Color(0xFF3F4944)

// Surface container helpers give neutral depth (see skill: avoid elevation-only cards).
internal val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
internal val LightSurfaceContainerLow = Color(0xFFEDF2EF)
internal val LightSurfaceContainer = Color(0xFFE6ECE9)
internal val LightSurfaceContainerHigh = Color(0xFFE0E8E4)
internal val LightSurfaceContainerHighest = Color(0xFFDAE3DE)
// Neutral mint backing for transparent onboarding art with dark outlines.
internal val LightOnboardingIllustrationSurface = Color(0xFFE8F1EB)
internal val LightOutline = Color(0xFF6F7975)
internal val LightOutlineVariant = Color(0xFFBFC9C4)
internal val LightInverseSurface = Color(0xFF2C322E)
internal val LightInverseOnSurface = Color(0xFFECF2EE)
internal val LightInversePrimary = Color(0xFF67D1B8)
internal val LightScrim = Color(0xFF000000)

// Product-specific semantic colors (never the only information carrier).
internal val LightPositive = Color(0xFF1B7A43)     // valid / performance / improvement
internal val LightPositiveContainer = Color(0xFFBBEDCC)
internal val LightWarning = Color(0xFF8A4F00)      // amber family, uncertainty
internal val LightWarningContainer = Color(0xFFFFDDB8)

// Semantic roles: navigation + stepper use the brand-green family so the
// lavender/blue data role stays reserved for data visualization only.
internal val LightDataBlue = LightSecondary
internal val LightDataBlueContainer = LightSecondaryContainer
internal val LightNavigationSelectedContainer = LightPrimaryContainer
internal val LightNavigationSelectedContent = LightOnPrimaryContainer
internal val LightProgressCompletedContainer = LightPrimaryContainer
internal val LightProgressCompletedContent = LightOnPrimaryContainer

// ── Dark scheme ────────────────────────────────────────────────────────────
internal val DarkPrimary = Color(0xFF67D1B8)
internal val DarkOnPrimary = Color(0xFF00382D)
internal val DarkPrimaryContainer = Color(0xFF0F4D3D)
internal val DarkOnPrimaryContainer = Color(0xFFA4F3DC)

// Secondary is neutral-green chrome; blue is reserved for data visualization.
internal val DarkSecondary = Color(0xFFAFC7BF)
internal val DarkOnSecondary = Color(0xFF17382F)
internal val DarkSecondaryContainer = Color(0xFF29433A)
internal val DarkOnSecondaryContainer = Color(0xFFCEEAE0)
internal val DarkDataBlue = Color(0xFFA9C7FF)
internal val DarkDataBlueContainer = Color(0xFF294775)
internal val DarkNavigationSelectedContainer = DarkPrimaryContainer
internal val DarkNavigationSelectedContent = DarkOnPrimaryContainer
internal val DarkProgressCompletedContainer = DarkPrimaryContainer
internal val DarkProgressCompletedContent = DarkOnPrimaryContainer

internal val DarkTertiary = Color(0xFFF2C76E)
internal val DarkOnTertiary = Color(0xFF462A00)
internal val DarkTertiaryContainer = Color(0xFF663C00)
internal val DarkOnTertiaryContainer = Color(0xFFFFDDB8)
internal val DarkError = Color(0xFFFFB4AB)
internal val DarkOnError = Color(0xFF690005)
internal val DarkErrorContainer = Color(0xFF93000A)
internal val DarkOnErrorContainer = Color(0xFFFFDAD6)
internal val DarkBackground = Color(0xFF0C120F)
internal val DarkSurface = Color(0xFF0E1411)
internal val DarkSurfaceVariant = Color(0xFF28352F)
internal val DarkOnBackground = Color(0xFFE3ECE7)
internal val DarkOnSurface = Color(0xFFE3ECE7)
internal val DarkOnSurfaceVariant = Color(0xFFB4C4BC)

// Dark surface container helpers with clear OLED-friendly tonal separation.
internal val DarkSurfaceContainerLowest = Color(0xFF080C0A)
internal val DarkSurfaceContainerLow = Color(0xFF151E1A)
internal val DarkSurfaceContainer = Color(0xFF1A2420)
internal val DarkSurfaceContainerHigh = Color(0xFF212D28)
internal val DarkSurfaceContainerHighest = Color(0xFF293731)
// Deliberately lighter than dark chrome so the supplied black line art remains legible.
internal val DarkOnboardingIllustrationSurface = Color(0xFF6B8E82)
internal val DarkOutline = Color(0xFF81948C)
internal val DarkOutlineVariant = Color(0xFF33443C)
internal val DarkInverseSurface = Color(0xFFE1E9E5)
internal val DarkInverseOnSurface = Color(0xFF252B28)
internal val DarkInversePrimary = Color(0xFF006B5A)
internal val DarkScrim = Color(0xFF000000)

// Product semantic colors (dark).
internal val DarkPositive = Color(0xFF79D991)
internal val DarkPositiveContainer = Color(0xFF00512F)
internal val DarkWarning = DarkTertiary
internal val DarkWarningContainer = Color(0xFF663C00)
