package app.hushtype.data

import android.util.Base64
import android.util.Log
import app.hushtype.core.PairingCode
import app.hushtype.core.SyncClient
import app.hushtype.core.SyncException
import app.hushtype.core.SyncKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps the dictionary and snippets in step with the user's Mac, iPhone and other phones through
 * the encrypted sync server. Syncs on launch, a couple of seconds after an edit, and every few
 * minutes while Hushtype runs.
 */
class DeviceSync(private val vocabulary: VocabularyRepository, private val secrets: SecretStore) {
    sealed interface Status {
        data object Off : Status
        data object Syncing : Status
        data class Synced(val at: Long) : Status
        data class Failed(val message: String) : Status
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _status = MutableStateFlow<Status>(if (keys() == null) Status.Off else Status.Synced(0))
    val status: StateFlow<Status> = _status.asStateFlow()
    private var periodic: Job? = null

    val isOn: Boolean get() = keys() != null
    val pairingCode: String? get() = keys()?.pairingCode

    @OptIn(FlowPreview::class)
    fun startWatching() {
        // An edit syncs a couple of seconds later, once typing has settled.
        scope.launch { vocabulary.state.drop(1).debounce(2_000).collect { if (isOn) syncNow() } }
        schedule()
    }

    /** This is the first device: make a new group and upload what's here. */
    suspend fun startNewGroup() {
        save(SyncKeys.random())
        syncNow()
    }

    /** Joins the group behind a scanned or typed code. Throws [SyncException] with a message for the user. */
    suspend fun join(code: String) {
        val secret = PairingCode.decode(code) ?: throw SyncException("That isn't a Hushtype code. It starts with HT1.")
        val keys = SyncKeys(secret)
        mutex.withLock {
            _status.value = Status.Syncing
            try {
                val merged = withContext(Dispatchers.IO) { SyncClient(keys).sync(vocabulary.state.value, mustExist = true) }
                save(keys)
                vocabulary.merge(merged)
                _status.value = Status.Synced(System.currentTimeMillis())
            } catch (e: SyncException) {
                _status.value = if (isOn) Status.Failed(e.message ?: "Sync failed.") else Status.Off
                throw e
            }
        }
    }

    fun turnOff() {
        secrets.syncSecret = ""
        periodic?.cancel()
        _status.value = Status.Off
    }

    suspend fun syncNow() {
        val keys = keys() ?: return
        mutex.withLock {
            _status.value = Status.Syncing
            try {
                val merged = withContext(Dispatchers.IO) { SyncClient(keys).sync(vocabulary.state.value) }
                vocabulary.merge(merged)
                _status.value = Status.Synced(System.currentTimeMillis())
            } catch (e: SyncException) {
                Log.w(TAG, "Sync failed", e)
                _status.value = Status.Failed(e.message ?: "Sync failed.")
            }
        }
    }

    private fun schedule() {
        periodic?.cancel()
        periodic = scope.launch {
            while (true) {
                if (isOn) syncNow()
                delay(5 * 60_000L)
            }
        }
    }

    private fun save(keys: SyncKeys) {
        secrets.syncSecret = Base64.encodeToString(keys.secret, Base64.NO_WRAP)
        if (periodic?.isActive != true) schedule()
    }

    private fun keys(): SyncKeys? = secrets.syncSecret.takeIf { it.isNotEmpty() }
        ?.let { runCatching { SyncKeys(Base64.decode(it, Base64.NO_WRAP)) }.getOrNull() }

    private companion object {
        const val TAG = "HushtypeSync"
    }
}
