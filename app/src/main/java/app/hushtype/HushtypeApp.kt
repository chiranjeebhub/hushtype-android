package app.hushtype

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.hushtype.data.DeviceSync
import app.hushtype.data.FileVocabularyRepository
import app.hushtype.data.HistoryStore
import app.hushtype.data.SecretStore
import app.hushtype.data.Settings
import app.hushtype.data.VocabularyRepository
import app.hushtype.polish.OnDevicePolisher
import app.hushtype.service.DictationController
import java.io.File

/** Holds the app's long-lived pieces. */
class HushtypeApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var secrets: SecretStore
        private set
    lateinit var vocabulary: VocabularyRepository
        private set
    lateinit var history: HistoryStore
        private set
    lateinit var sync: DeviceSync
        private set
    val onDevice by lazy { OnDevicePolisher() }
    val dictation by lazy { DictationController(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        secrets = SecretStore(this)
        vocabulary = FileVocabularyRepository(File(filesDir, "vocabulary.json"))
        history = HistoryStore(File(filesDir, "history.json"))
        sync = DeviceSync(vocabulary, secrets).also { it.startWatching() }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_LISTENING, getString(R.string.channel_listening), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_listening_description)
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_LISTENING = "listening"
        lateinit var instance: HushtypeApp
            private set
    }
}
