package app.hushtype.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
                // The bubble's service can start or stop while the app is open.
                LaunchedEffect(Unit) {
                    HushtypeAccessibilityService.isRunning.collect { setup = SetupState.read(this@MainActivity) }
                }
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
data class SetupState(
    val microphone: Boolean,
    /** The bubble's accessibility switch is on in Settings. */
    val accessibility: Boolean,
    val notifications: Boolean,
    /** The bubble's service is actually connected. It can be switched on yet stopped: when Android
     *  kills the app while the service runs, it marks the service crashed and won't start it again. */
    val bubbleRunning: Boolean = accessibility,
    /** Battery optimisation is off for Hushtype, so the system is less likely to stop it. */
    val unrestricted: Boolean = true,
) {
    val ready: Boolean get() = microphone && accessibility && bubbleRunning
    /** Switched on in Settings, but Android isn't running it. */
    val bubbleStopped: Boolean get() = accessibility && !bubbleRunning

    companion object {
        fun read(context: Context) = SetupState(
            microphone = granted(context, Manifest.permission.RECORD_AUDIO),
            accessibility = accessibilitySwitchOn(context),
            notifications = granted(context, Manifest.permission.POST_NOTIFICATIONS),
            bubbleRunning = HushtypeAccessibilityService.isRunning.value,
            unrestricted = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) != false,
        )

        private fun granted(context: Context, permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        fun accessibilitySwitchOn(context: Context): Boolean {
            if (HushtypeAccessibilityService.isRunning.value) return true
            val enabled = AndroidSettings.Secure.getString(context.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(context, HushtypeAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        fun openAccessibilitySettings(context: Context) {
            // The per-service page (ACCESSIBILITY_DETAILS_SETTINGS) needs a signature permission, so open
            // the list and ask Settings to scroll to and highlight Hushtype's row where it supports that.
            val me = ComponentName(context, HushtypeAccessibilityService::class.java).flattenToString()
            val args = android.os.Bundle().apply { putString(":settings:fragment_args_key", me) }
            context.startActivity(
                Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
                    .putExtra(":settings:fragment_args_key", me)
                    .putExtra(":settings:show_fragment_args", args)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        /** Asks Android to stop optimising Hushtype's battery use, so it isn't stopped in the background. */
        @SuppressLint("BatteryLife")
        fun requestUnrestricted(context: Context) {
            val ask = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(ask) }.onFailure { openAppSettings(context) }
        }

        /** Xiaomi phones also stop apps that aren't allowed to autostart. */
        val isXiaomi: Boolean get() = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
            Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

        fun openAutostart(context: Context) {
            val autostart = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(autostart) }.onFailure { openAppSettings(context) }
        }

        fun openAppSettings(context: Context) {
            context.startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
