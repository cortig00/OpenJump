package com.openjump.app.ui.angles

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openjump.app.settings.OnboardingCompletion
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prueba de aislamiento del banco de Ángulos.
 *
 * No navega: comprueba que el estado que una navegación puede escribir (directorios, preferencias,
 * base Room, raíz de compartición y permisos de vídeo) es propio del banco, que la base real del
 * usuario no se abre ni se crea, y que la única publicación hacia fuera —la copia de un fotograma—
 * sigue pasando por el `FileProvider` real.
 */
@RunWith(AndroidJUnit4::class)
class AngleNavigationSandboxInstrumentedTest {

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private var sandbox: AngleNavigationSandbox? = null

    @After
    fun tearDown() {
        sandbox?.closeDatabases()
    }

    @Test
    fun sandboxIsolatesPathsPreferencesDatabaseAndShareRoot() {
        val userDatabase = target.getDatabasePath(DEFAULT_DATABASE_NAME)
        val userDatabaseExistedBefore = userDatabase.exists()

        val sandbox = AngleNavigationSandbox(target)
        this.sandbox = sandbox
        val app = sandbox.newApp()

        // 1. Directorios propios bajo la raíz del banco; nunca los reales.
        assertTrue(app.filesDir.canonicalPath.startsWith(sandbox.ownedRoot.canonicalPath + File.separator))
        assertTrue(app.cacheDir.canonicalPath.startsWith(sandbox.ownedRoot.canonicalPath + File.separator))
        assertNotEquals(target.filesDir.canonicalPath, app.filesDir.canonicalPath)
        assertNotEquals(target.cacheDir.canonicalPath, app.cacheDir.canonicalPath)

        // 2. Preferencias aisladas: onboarding/modo/selección/display no comparten fichero real.
        val sandboxPreferences = app.getSharedPreferences(SETTINGS_PREFERENCES, Context.MODE_PRIVATE)
        assertTrue(sandboxPreferences.edit().putString(PROBE_KEY, PROBE_VALUE).commit())
        assertEquals(
            PROBE_VALUE,
            app.getSharedPreferences(SETTINGS_PREFERENCES, Context.MODE_PRIVATE)
                .getString(PROBE_KEY, null),
        )
        val userPreferences = target.getSharedPreferences(SETTINGS_PREFERENCES, Context.MODE_PRIVATE)
        assertFalse(userPreferences.contains(PROBE_KEY))
        assertEquals(OnboardingCompletion.PENDING, app.onboardingPreferencesStore.completion.value)
        app.onboardingPreferencesStore.completeOnboarding()
        assertEquals(OnboardingCompletion.COMPLETED, app.onboardingPreferencesStore.completion.value)

        // 3. Room real sobre el nombre UUID: `app.database` usa el override, no `JumpDatabase.get`.
        assertEquals(SCHEMA_VERSION, app.database.openHelper.writableDatabase.version)
        val sandboxDatabaseFile = target.getDatabasePath(sandbox.databaseName)
        assertEquals(sandbox.databaseName, sandboxDatabaseFile.name)
        assertTrue("La base del banco debe existir en su propio fichero.", sandboxDatabaseFile.exists())
        assertNotEquals(userDatabase.absolutePath, sandboxDatabaseFile.absolutePath)

        // 4. `openjump.db` se rechaza: ni resuelve al fichero real ni se puede abrir.
        val reservedPath = app.getDatabasePath(DEFAULT_DATABASE_NAME)
        assertNotEquals(userDatabase.absolutePath, reservedPath.absolutePath)
        assertFalse(reservedPath.exists())
        try {
            app.openOrCreateDatabase(DEFAULT_DATABASE_NAME, Context.MODE_PRIVATE, null)
            fail("El banco de Ángulos no debe abrir la base de producción.")
        } catch (expected: IllegalStateException) {
            // Rechazo intencional del nombre reservado, no un fallo silencioso.
        }

        // 5. Permisos de vídeo en memoria: ninguna llamada a `ContentResolver`.
        val grants = app.videoGrantStore
        assertTrue(grants.persistedReadUris().isEmpty())
        grants.takeReadGrant(SANDBOX_VIDEO_URI)
        assertEquals(setOf(SANDBOX_VIDEO_URI), grants.persistedReadUris())
        grants.releaseReadGrant(SANDBOX_VIDEO_URI)
        assertTrue(grants.persistedReadUris().isEmpty())

        // 6. Reapertura: otra app, mismo nombre y mismos directorios.
        val reopened = sandbox.newApp()
        assertNotSame(app.database, reopened.database)
        assertEquals(SCHEMA_VERSION, reopened.database.openHelper.writableDatabase.version)

        // 7. Publicación: URI real del FileProvider bajo la raíz del banco, legible y acotada.
        val sharedFrame = File(sandbox.shareCacheRoot, "sandbox-frame.png").apply {
            parentFile?.mkdirs()
            writeBytes(PNG_SIGNATURE)
        }
        assertTrue(sharedFrame.canonicalPath.startsWith(sandbox.shareCacheRoot.canonicalPath))
        assertFalse(sharedFrame.canonicalPath.startsWith(app.filesDir.canonicalPath))
        val uri = sandbox.shareUri(app, sharedFrame)
        assertEquals("content", uri.scheme)
        assertEquals("${target.packageName}.fileprovider", uri.authority)
        val readBytes = target.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: ByteArray(0)
        assertArrayEquals(PNG_SIGNATURE, readBytes)
        sharedFrame.delete()

        sandbox.closeDatabases()
        assertFalse(app.database.isOpen)
        assertFalse(reopened.database.isOpen)

        // 8. La base del usuario no se ha creado ni borrado por efecto del banco.
        assertEquals(userDatabaseExistedBefore, userDatabase.exists())
    }

    private companion object {
        private const val DEFAULT_DATABASE_NAME = "openjump.db"
        private const val SETTINGS_PREFERENCES = "openjump_settings"
        private const val PROBE_KEY = "angle-nav-sandbox-probe"
        private const val PROBE_VALUE = "isolated"
        private const val SANDBOX_VIDEO_URI = "content://sandbox.video/isolated"
        private const val SCHEMA_VERSION = 14

        /** Firma PNG (8 bytes): basta para que el fichero compartido sea un PNG identificable. */
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A,
        )
    }
}
