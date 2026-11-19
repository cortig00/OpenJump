package com.openjump.app.ui.angles

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.room.Room
import com.openjump.app.OpenJumpApp
import com.openjump.app.data.JumpDatabase
import com.openjump.app.video.PersistedVideoGrantStore
import java.io.File
import java.util.UUID

/**
 * Banco de pruebas de aislamiento para navegar por Ángulos sin tocar el estado del usuario.
 *
 * Todo lo que una navegación puede escribir deriva de [uuid]: raíz de directorios ([ownedRoot]),
 * nombre de la base Room ([databaseName]), prefijo del fichero de preferencias y raíz de fotogramas
 * compartidos ([shareCacheRoot]). `openjump.db` no se abre, no se migra y no se modifica, y el
 * almacén privado de imágenes y el staging de backup quedan fuera porque `filesDir` de la app
 * aislada es [ownedFilesDir].
 *
 * `FileProvider` no se puede aislar: el proveedor real ya declara
 * `cache-path/shared-angle-frames` contra la `cacheDir` real, así que [shareCacheRoot] es una
 * subcarpeta propia dentro de esa raíz; es la única forma de obtener una URI real aceptada sin
 * publicar fixtures ni snapshots del test.
 *
 * La base no se simula: [newApp] construye una Room real y persistente sobre [databaseName], de modo
 * que una segunda llamada la reabre con los mismos directorios y sin pasar por `JumpDatabase.get`.
 *
 * La clase es `internal` a propósito: expone contextos y dueños de ViewModels internos del arnés
 * ([SandboxUiContext], [SandboxViewModelOwner]) y no forma parte de ninguna superficie de producto.
 */
internal class AngleNavigationSandbox(val targetContext: Context) {

    /** Identificador único del banco; aparece en todas las rutas y nombres que produce. */
    val uuid: String = UUID.randomUUID().toString()

    /** Raíz propia del banco; los directorios de la app aislada cuelgan de aquí. */
    val ownedRoot: File = File(targetContext.filesDir, "angle-nav-$uuid")

    /** `filesDir` de la app aislada (nunca la `filesDir` real). */
    val ownedFilesDir: File = File(ownedRoot, "files")

    /** `cacheDir` de la app aislada (nunca la `cacheDir` real). */
    val ownedCacheDir: File = File(ownedRoot, "cache")

    /** Directorio donde el envoltorio resuelve los nombres de base que no son propios. */
    internal val ownedDatabasesDir: File = File(ownedRoot, "databases")

    /** Nombre de la base Room propia. Nunca `openjump.db`. */
    val databaseName: String = "angle-nav-$uuid.db"

    /**
     * Raíz de compartición de fotogramas aceptada por el `FileProvider` real.
     *
     * La raíz declarada en `@xml/file_paths` es `cacheDir/shared-angle-frames`; esta subcarpeta por
     * banco queda dentro y no expone ni el almacén privado ni el resto de la caché.
     */
    val shareCacheRoot: File = File(targetContext.cacheDir, "shared-angle-frames/nav-$uuid")

    private val openDatabases = mutableListOf<JumpDatabase>()

    init {
        ownedFilesDir.mkdirs()
        ownedCacheDir.mkdirs()
        ownedDatabasesDir.mkdirs()
    }

    /**
     * Crea y arranca una app aislada (envoltorio + `onCreate`) con el mismo [databaseName] y los
     * mismos directorios; llamarla dos veces reabre la misma base Room.
     */
    fun newApp(): SandboxedApp = SandboxedApp(this).also { it.attach() }

    /** Registra una base construida por una [SandboxedApp] para poder cerrarla aquí. */
    internal fun registerDatabase(database: JumpDatabase) {
        openDatabases += database
    }

    /**
     * Cierra **sólo** las bases construidas por este banco. No borra ningún fichero: los artefactos
     * del banco quedan para inspección y ningún dato del usuario se toca.
     */
    fun closeDatabases() {
        openDatabases.forEach { database ->
            if (database.isOpen) database.close()
        }
        openDatabases.clear()
    }

    /** Nombre de preferencias aislado: nunca coincide con el fichero real. */
    internal fun prefixedPreferencesName(name: String): String = "angle-nav-$uuid.$name"

    /** Sólo el nombre de base propio puede abrirse; el resto (incluido `openjump.db`) se rechaza. */
    internal fun isOwnedDatabaseName(name: String): Boolean = name == databaseName

    /** Ruta aislada para un nombre de base ajeno; queda dentro de [ownedRoot] y no se crea. */
    internal fun isolatedDatabaseFile(name: String): File = File(ownedDatabasesDir, "rejected/$name")

    /**
     * URI real del `FileProvider` para un fichero del banco, que debe estar bajo [shareCacheRoot].
     *
     * El proveedor se consulta con [targetContext] —el contexto REAL— porque resuelve las raíces de
     * `@xml/file_paths` con el `Context` que recibe: la app aislada redirige `cacheDir`, así que
     * resolverlo con ella apuntaría la raíz `cache-path` a la caché del banco y rechazaría la ruta
     * real. La ruta canónica se comprueba antes para no publicar nada fuera de la raíz propia.
     */
    fun shareUri(app: OpenJumpApp, file: File): Uri {
        val root = shareCacheRoot.canonicalPath
        val canonical = file.canonicalPath
        check(canonical == root || canonical.startsWith("$root${File.separator}")) {
            "El banco sólo publica ficheros bajo su raíz de compartición: $canonical"
        }
        return FileProvider.getUriForFile(
            targetContext,
            "${app.packageName}.fileprovider",
            file,
        )
    }

    /**
     * `OpenJumpApp` aislada del banco.
     *
     * Usa las tres costuras de producción (`createDatabase`, `createVideoGrantStore` y
     * `angleFrameShareCacheRoot`) sin alterar el comportamiento por defecto:
     * - base Room real y persistente sobre [databaseName] (nunca `JumpDatabase.get`);
     * - almacén de permisos de vídeo en memoria (no llama a `ContentResolver`);
     * - raíz de compartición propia ([shareCacheRoot]) aceptada por el `FileProvider` real.
     */
    class SandboxedApp internal constructor(
        private val sandbox: AngleNavigationSandbox,
    ) : OpenJumpApp() {

        private val inMemoryGrantStore = SandboxVideoGrantStore()
        private var appContext: SandboxAppContext? = null

        /** Almacén de permisos en memoria realmente usado por esta app. */
        internal val videoGrantStore: SandboxVideoGrantStore get() = inMemoryGrantStore

        /** Enlaza el contexto aislado y ejecuta `onCreate` una sola vez. */
        fun attach(): SandboxedApp {
            check(appContext == null) { "La app aislada ya está enlazada a su contexto del banco." }
            val wrapper = SandboxAppContext(sandbox, this)
            attachBaseContext(wrapper)
            appContext = wrapper
            onCreate()
            return this
        }

        /** Room real sobre el nombre UUID; registrada en el banco para cerrarla al terminar. */
        protected override fun createDatabase(): JumpDatabase =
            Room.databaseBuilder(sandbox.targetContext, JumpDatabase::class.java, sandbox.databaseName)
                .build()
                .also(sandbox::registerDatabase)

        /** Sin `ContentResolver`: el banco nunca concede, revoca ni consulta permisos reales. */
        internal override fun createVideoGrantStore(): PersistedVideoGrantStore = inMemoryGrantStore

        /** `true` sólo para el nombre de base UUID de este banco. */
        internal fun ownsDatabaseName(name: String): Boolean = sandbox.isOwnedDatabaseName(name)

        internal override fun angleFrameShareCacheRoot(): File = sandbox.shareCacheRoot

        /**
         * URI del proveedor REAL para la copia del banco: se delega en [AngleNavigationSandbox.shareUri]
         * para resolver las raíces declaradas con el contexto real y acotar la ruta a la raíz propia.
         */
        internal override fun angleFrameShareUri(file: File): Uri = sandbox.shareUri(this, file)

        /** `Context` de UI con la app aislada y `startActivity` limitado a capturar el chooser. */
        fun newUiContext(activity: ComponentActivity): SandboxUiContext {
            check(appContext != null) {
                "Enlaza la app aislada antes de construir el contexto de UI."
            }
            return SandboxUiContext(activity, this)
        }

        /**
         * Dueño de ViewModels que conserva el almacén de la actividad y el `SavedStateRegistryOwner`
         * reales, pero entrega `APPLICATION_KEY` = esta app aislada. Es lo que hace que el
         * `rootFvpVM` (y cualquier fábrica que lea `APPLICATION_KEY`) use la base UUID.
         */
        fun newDefaultViewModelOwner(activity: ComponentActivity): SandboxViewModelOwner =
            SandboxViewModelOwner(activity, this)
    }
}

/**
 * `Context` que la app aislada recibe en `attachBaseContext`.
 *
 * Delega lo de sólo lectura (recursos, `packageName`, `ContentResolver`, `PackageManager`) en el
 * contexto real, pero redirige directorios y preferencias al banco. `getApplicationContext`
 * devuelve la app aislada, así que cualquier `activity.applicationContext` que llegue por
 * composición trabaja sobre ella.
 */
internal class SandboxAppContext(
    private val sandbox: AngleNavigationSandbox,
    private val app: OpenJumpApp,
) : ContextWrapper(sandbox.targetContext) {

    override fun getApplicationContext(): Context = app

    override fun getFilesDir(): File = sandbox.ownedFilesDir

    override fun getCacheDir(): File = sandbox.ownedCacheDir

    override fun getDir(name: String, mode: Int): File =
        File(getFilesDir(), name).also { it.mkdirs() }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        super.getSharedPreferences(sandbox.prefixedPreferencesName(name), mode)

    override fun deleteSharedPreferences(name: String): Boolean =
        super.deleteSharedPreferences(sandbox.prefixedPreferencesName(name))

    /**
     * El nombre propio se resuelve igual que Room lo va a abrir: el mismo fichero del directorio
     * privado de bases de la app real, pero con nombre UUID. Cualquier otro nombre —incluido
     * `openjump.db`, que `OnboardingPreferencesStore` sondea al arrancar para detectar una
     * instalación previa— se redirige a una ruta propia que nunca se crea: el sondeo ve "instalación
     * nueva" y nunca se expone el estado del usuario.
     */
    override fun getDatabasePath(name: String): File = when {
        sandbox.isOwnedDatabaseName(name) -> super.getDatabasePath(name)
        else -> sandbox.isolatedDatabaseFile(name)
    }

    /** Abrir o crear una base ajena es un error: el banco no escribe fuera de su base UUID. */
    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
    ): SQLiteDatabase {
        requireOwnedDatabase(name)
        return super.openOrCreateDatabase(name, mode, factory)
    }

    /**
     * La sobrecarga con `errorHandler` aplica **la misma** guarda y reenvía al `super` de cuatro
     * argumentos: sin este override, `Context.openOrCreateDatabase(name, mode, factory, handler)`
     * caería en la implementación base sin pasar por la guarda de la base propia.
     */
    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?,
    ): SQLiteDatabase {
        requireOwnedDatabase(name)
        return super.openOrCreateDatabase(name, mode, factory, errorHandler)
    }

    private fun requireOwnedDatabase(name: String) {
        check(sandbox.isOwnedDatabaseName(name)) {
            "El banco de Ángulos no abre bases ajenas: '$name' (sólo '${sandbox.databaseName}')."
        }
    }
}

/** Almacén de permisos de vídeo en memoria: no toca `ContentResolver` ni el estado del usuario. */
internal class SandboxVideoGrantStore : PersistedVideoGrantStore {
    private val readUris = linkedSetOf<String>()

    override fun persistedReadUris(): Set<String> = readUris.toSet()

    override fun takeReadGrant(uri: String) {
        readUris += uri
    }

    override fun releaseReadGrant(uri: String) {
        readUris -= uri
    }
}

/**
 * `Context` de UI del banco: `applicationContext`, directorios, preferencias y bases son los de la
 * app aislada. `startActivity` **no** lanza otras pantallas: sólo captura los `ACTION_CHOOSER` para
 * poder inspeccionar qué se compartió; cualquier otro intent es un error de test.
 */
internal class SandboxUiContext(
    activity: ComponentActivity,
    private val app: AngleNavigationSandbox.SandboxedApp,
) : ContextWrapper(activity) {

    private val captured = mutableListOf<Intent>()

    /** Intents `ACTION_CHOOSER` capturados, en orden. */
    val chooserIntents: List<Intent> get() = captured.toList()

    override fun getApplicationContext(): Context = app

    override fun getFilesDir(): File = app.filesDir

    override fun getCacheDir(): File = app.cacheDir

    override fun getDatabasePath(name: String): File = app.getDatabasePath(name)

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        app.getSharedPreferences(name, mode)

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
    ): SQLiteDatabase = app.openOrCreateDatabase(name, mode, factory)

    /** Misma guarda que el contexto de la app: el `errorHandler` no abre una vía alternativa. */
    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?,
    ): SQLiteDatabase {
        check(app.ownsDatabaseName(name)) {
            "El banco de Ángulos no abre bases ajenas: '$name'."
        }
        return app.openOrCreateDatabase(name, mode, factory, errorHandler)
    }

    override fun startActivity(intent: Intent) = captureChooser(intent)

    override fun startActivity(intent: Intent, options: Bundle?) = captureChooser(intent)

    private fun captureChooser(intent: Intent) {
        check(intent.action == Intent.ACTION_CHOOSER) {
            "El banco de Ángulos sólo captura el chooser de compartir; llegó '${intent.action}'."
        }
        captured += Intent(intent)
    }
}

/**
 * Dueño de ViewModels con el almacén y el `SavedStateRegistryOwner` de la actividad reales, pero
 * `APPLICATION_KEY` = la app aislada: así el `rootFvpVM` usa la base UUID.
 */
internal class SandboxViewModelOwner(
    private val activity: ComponentActivity,
    private val app: OpenJumpApp,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {

    override val viewModelStore: ViewModelStore get() = activity.viewModelStore

    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras(activity.defaultViewModelCreationExtras).apply {
            set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, app)
        }

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                activity.defaultViewModelProviderFactory.create(
                    modelClass,
                    MutableCreationExtras(extras).apply {
                        set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, app)
                    },
                )
        }
}

/** Desenvuelve la actividad real a partir de un `Context` de composición. */
fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
