package com.openjump.app.ui.fvp

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openjump.app.R
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.Spacing

/**
 * Paginas LOCALES del paso de entrada del flujo F–V–P.
 *
 * No son destinos de navegacion: no tienen ruta ni dueño propio. El dueño del estado recuperable
 * sigue siendo la pantalla de setup (`FvpSetupScreen`), que conserva TODOS sus `rememberSaveable`,
 * `Saver` y la re-base de unidades al cambiar de pagina. [INTRO] NO es un paso completado del
 * flujo: es una explicacion breve de primer uso, saltable y recuperable despues; no altera ningun
 * dato ni la elegibilidad.
 */
internal enum class FvpSetupPage {
    INTRO,
    DATA,
    MEASUREMENT,
}

/**
 * Preferencia de UI (NO evidencia cientifica, NO dato del atleta y NO parte del borrador): si el
 * usuario ya ha visto la introduccion del flujo.
 *
 * Se guarda en su PROPIO fichero de `SharedPreferences` para que no se mezcle con ajustes de la app
 * ni pueda confundirse con un dato de medicion. Es una interfaz para poder montar la pantalla en
 * tests sin tocar el disco.
 */
interface FvpIntroductionStore {
    fun hasSeenIntroduction(): Boolean

    fun markIntroductionSeen()
}

/**
 * Implementacion persistente de [FvpIntroductionStore] en un fichero PRIVADO propio.
 *
 * - Nunca se guarda en el borrador, en Room ni en el backup de datos: es solo una preferencia de
 *   presentacion. Perderla solo significa volver a ver la introduccion, jamas perder una medicion.
 * - `apply()` es suficiente: no hay ninguna garantia que dependa de que el valor este en disco
 *   antes de continuar; si el proceso muere justo despues, la introduccion vuelve a mostrarse.
 */
class FvpIntroductionPreferences(context: Context) : FvpIntroductionStore {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun hasSeenIntroduction(): Boolean = preferences.getBoolean(KEY_SEEN, false)

    override fun markIntroductionSeen() {
        preferences.edit().putBoolean(KEY_SEEN, true).apply()
    }

    internal companion object {
        /** Fichero PRIVADO propio: no comparte clave ni espacio con los ajustes de la app. */
        const val PREFERENCES = "openjump_fvp_ui"

        const val KEY_SEEN = "introduction_seen"
    }
}

/**
 * Introduccion breve del flujo: tres ideas y dos acciones (preparar o saltar).
 *
 * `revisit = true` es la ayuda reabierta desde una pagina posterior: en ese caso la unica accion
 * es VOLVER a la pagina desde la que se abrio, de modo que reabrir la introduccion nunca reinicia
 * el flujo ni cambia el estado de preferencia.
 *
 * Decisiones vinculantes:
 * - Tres ideas cortas, no un muro de texto: el detalle de cada campo vive junto a su campo.
 * - "Saltar" es una accion de primera clase, no un enlace escondido.
 * - Ningun texto afirma que algo se calcule o se rellene solo (la introduccion no promete nada que
 *   el flujo no haga: el usuario elige y declara).
 */
@Composable
internal fun FvpIntroductionPage(
    revisit: Boolean,
    onPrepare: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenHorizontal)
            .padding(top = Spacing.lg, bottom = Spacing.xl)
            .testTag("fvp-intro"),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Text(
            text = stringResource(R.string.fvp_intro_title),
            style = OpenJumpTypes.ScreenTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FvpIntroductionPoint(R.string.fvp_intro_point_data, "fvp-intro-point-data")
            FvpIntroductionPoint(R.string.fvp_intro_point_measurement, "fvp-intro-point-measurement")
            FvpIntroductionPoint(R.string.fvp_intro_point_conditions, "fvp-intro-point-conditions")
        }
        // El detalle cientifico sigue consultable, sin entrar en el formulario ni duplicar
        // el aviso breve que ya forma parte de las tres ideas de esta introduccion.
        FvpCaptureHeader(showWarning = false)
        Spacer(Modifier.fillMaxWidth())
        if (revisit) {
            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .testTag("fvp-intro-back"),
            ) {
                Text(stringResource(R.string.fvp_intro_back))
            }
        } else {
            Button(
                onClick = onPrepare,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .testTag("fvp-intro-prepare"),
            ) {
                Text(stringResource(R.string.fvp_intro_prepare))
            }
            TextButton(
                onClick = onSkip,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("fvp-intro-skip"),
            ) {
                Text(stringResource(R.string.fvp_intro_skip))
            }
        }
    }
}

/** Una idea de la introduccion: vineta textual + frase corta. */
@Composable
private fun FvpIntroductionPoint(textRes: Int, testTag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(testTag),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = "\u2022",
            style = OpenJumpTypes.Body,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(textRes),
            style = OpenJumpTypes.Body,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
