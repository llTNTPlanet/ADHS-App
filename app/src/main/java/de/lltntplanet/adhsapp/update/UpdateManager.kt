package de.lltntplanet.adhsapp.update

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import de.lltntplanet.adhsapp.BuildConfig
import de.lltntplanet.adhsapp.data.settings.SecretStore
import de.lltntplanet.adhsapp.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    /** Einmalig muss "Unbekannte Apps installieren" für ADHS App erlaubt werden. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    /** An Android übergeben – je nach Gerät fragt Android noch einmal nach. */
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo?) : UpdateState
}

/**
 * Sucht auf GitHub nach neuen Versionen und installiert sie – nach Bestätigung oder automatisch.
 * Wird von der Oberfläche (Dialog, Einstellungen), dem Hintergrund-Job und dem Install-Receiver genutzt.
 */
class UpdateManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val client: GitHubReleaseClient,
    private val installer: ApkInstaller,
    private val notifier: UpdateNotifier,
    private val scope: CoroutineScope,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _dialogVisible = MutableStateFlow(false)
    /** Soll der Update-Dialog in der App angezeigt werden? */
    val dialogVisible: StateFlow<Boolean> = _dialogVisible.asStateFlow()

    private val installMutex = Mutex()

    private val pendingInfo: UpdateInfo?
        get() = when (val s = _state.value) {
            is UpdateState.Available -> s.info
            is UpdateState.NeedsPermission -> s.info
            is UpdateState.Downloading -> s.info
            is UpdateState.Installing -> s.info
            is UpdateState.Failed -> s.info
            else -> null
        }

    /** Fragt GitHub nach der neuesten Version. */
    suspend fun check(userInitiated: Boolean): UpdateInfo? {
        if (userInitiated || pendingInfo == null) _state.value = UpdateState.Checking
        val result = runCatching {
            client.latestRelease(BuildConfig.UPDATE_REPO_OWNER, BuildConfig.UPDATE_REPO_NAME, secrets.githubToken.value)
        }
        settings.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
        return result.fold(
            onSuccess = { release ->
                val info = GitHubReleaseClient.toUpdate(release, currentVersionCode)
                _state.value = if (info == null) UpdateState.UpToDate else UpdateState.Available(info)
                info
            },
            onFailure = { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "Update-Prüfung fehlgeschlagen", e)
                _state.value = if (userInitiated) {
                    UpdateState.Failed(e.message ?: "Update-Prüfung fehlgeschlagen.", null)
                } else {
                    UpdateState.Idle
                }
                null
            },
        )
    }

    /** Beim Öffnen der App: höchstens stündlich prüfen und bei neuer Version sofort fragen. */
    fun onAppResumed() {
        scope.launch {
            // Zurück aus den Einstellungen "Unbekannte Apps installieren" → direkt weitermachen.
            val waiting = _state.value
            if (waiting is UpdateState.NeedsPermission && installer.canInstall()) {
                startUpdate()
                return@launch
            }
            val s = settings.current()
            val now = System.currentTimeMillis()
            val info = if (now - s.lastUpdateCheck >= CHECK_INTERVAL_FOREGROUND) check(userInitiated = false) else pendingInfo
            if (info != null && !(s.dismissedUpdateCode == info.versionCode && now - s.dismissedUpdateAt < SNOOZE)) {
                _dialogVisible.value = true
            }
        }
    }

    /** "Jetzt nach Updates suchen" in den Einstellungen. */
    fun checkNow() {
        _dialogVisible.value = true
        scope.launch { check(userInitiated = true) }
    }

    /** Aus der Benachrichtigung "Update verfügbar" geöffnet. */
    fun showDialog() {
        scope.launch {
            if (pendingInfo == null) check(userInitiated = true)
            _dialogVisible.value = true
        }
    }

    /** "Später" bzw. Dialog schließen. */
    fun dismiss() {
        _dialogVisible.value = false
        val info = pendingInfo
        when (_state.value) {
            is UpdateState.Available, is UpdateState.NeedsPermission -> if (info != null) {
                scope.launch {
                    settings.update { it.copy(dismissedUpdateCode = info.versionCode, dismissedUpdateAt = System.currentTimeMillis()) }
                }
            }
            is UpdateState.Failed -> _state.value = info?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            is UpdateState.UpToDate, is UpdateState.Checking -> _state.value = UpdateState.Idle
            else -> Unit
        }
    }

    /** "Jetzt aktualisieren". */
    fun startUpdate() {
        val info = pendingInfo ?: return
        scope.launch {
            installMutex.withLock {
                if (!installer.canInstall()) {
                    _state.value = UpdateState.NeedsPermission(info)
                    _dialogVisible.value = true
                    return@withLock
                }
                downloadAndInstall(info)
            }
        }
    }

    fun openInstallPermissionSettings() {
        runCatching { context.startActivity(installer.installPermissionSettings()) }
    }

    /** Vom Hintergrund-Job: automatisch installieren oder Bescheid geben. */
    suspend fun backgroundCheck() {
        val info = check(userInitiated = false) ?: return
        val s = settings.current()
        if (isAppVisible()) {
            _dialogVisible.value = true
            return
        }
        if (s.autoInstallUpdates && installer.canInstall()) {
            val handedOver = installMutex.withLock { downloadAndInstall(info) }
            if (!handedOver) notifier.notifyAvailable(info, (_state.value as? UpdateState.Failed)?.message)
        } else {
            notifier.notifyAvailable(info, null)
        }
    }

    private suspend fun downloadAndInstall(info: UpdateInfo): Boolean {
        _state.value = UpdateState.Downloading(info, 0f)
        return try {
            val file = client.download(info, secrets.githubToken.value, ApkInstaller.apkFile(context, info.versionCode)) { p ->
                _state.value = UpdateState.Downloading(info, p)
            }
            installer.verify(file)?.let { problem ->
                file.delete()
                _state.value = UpdateState.Failed(problem, info)
                return false
            }
            _state.value = UpdateState.Installing(info)
            installer.install(file, info.versionName)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update fehlgeschlagen", e)
            _state.value = UpdateState.Failed(e.message ?: "Update fehlgeschlagen.", info)
            false
        }
    }

    /** Android meldet einen Fehler bei der Installation. */
    fun onInstallFailed(message: String) {
        _state.value = UpdateState.Failed(message, pendingInfo)
        if (isAppVisible()) _dialogVisible.value = true else notifier.notifyFailed(message)
    }

    fun isAppVisible(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    companion object {
        private const val TAG = "UpdateManager"
        private const val CHECK_INTERVAL_FOREGROUND = 60 * 60 * 1000L
        /** Nach "Später" fragt die App frühestens nach 12 Stunden wieder nach derselben Version. */
        private const val SNOOZE = 12 * 60 * 60 * 1000L
    }
}
