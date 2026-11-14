package app.hushtype.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.hushtype.HushtypeApp
import app.hushtype.service.HushtypeAccessibilityService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as HushtypeApp
        setContent {
            HushtypeTheme {
                var setup by remember { mutableStateOf(SetupState.read(this)) }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { setup = SetupState.read(this@MainActivity) }
                var onboarding by remember { mutableStateOf(!app.settings.onboardingDone) }
                AnimatedContent(onboarding, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "root") { showOnboarding ->
                    if (showOnboarding) {
                        Onboarding(app, setup, refresh = { setup = SetupState.read(this@MainActivity) }) {
                            app.settings.onboardingDone = true
                            onboarding = false
                        }
                    } else {
                        MainScreen(app, setup)
                    }
                }
            }
        }
    }
}

/** What Hushtype still needs from Android before the bubble works. */
data class SetupState(val microphone: Boolean, val accessibility: Boolean, val notifications: Boolean) {
    val ready: Boolean get() = microphone && accessibility

    companion object {
        fun read(context: Context) = SetupState(
            microphone = granted(context, Manifest.permission.RECORD_AUDIO),
            accessibility = accessibilityEnabled(context),
            notifications = granted(context, Manifest.permission.POST_NOTIFICATIONS),
        )

        private fun granted(context: Context, permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        fun accessibilityEnabled(context: Context): Boolean {
            if (HushtypeAccessibilityService.isRunning.value) return true
            val enabled = AndroidSettings.Secure.getString(context.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(context, HushtypeAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        fun openAccessibilitySettings(context: Context) {
            val me = ComponentName(context, HushtypeAccessibilityService::class.java).flattenToString()
            // Android 13+ can open Hushtype's own page directly; older versions show the list.
            val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, me)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val list = Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(detail) }.onFailure { context.startActivity(list) }
        }

        fun openAppSettings(context: Context) {
            context.startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
