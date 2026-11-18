package app.hushtype.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.hushtype.HushtypeApp
import app.hushtype.R

private enum class Step { WELCOME, MICROPHONE, BUBBLE, POLISH, TRY }

@Composable
fun Onboarding(app: HushtypeApp, setup: SetupState, refresh: () -> Unit, done: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val steps = Step.entries
    val current = steps[step]
    val c = HushTheme.colors

    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding()) {
        // Progress: one slim segment per step.
        if (current != Step.WELCOME) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 1 until steps.size) {
                    Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(if (i <= step) Hush.violet else c.hairline))
                }
            }
        }
        AnimatedContent(
            current,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(tween(320)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(320))) togetherWith
                    (slideOutHorizontally(tween(240)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(200)))
            },
            modifier = Modifier.weight(1f),
            label = "step",
        ) { s ->
            when (s) {
                Step.WELCOME -> Welcome { step++ }
                Step.MICROPHONE -> MicrophoneStep(setup, refresh) { step++ }
                Step.BUBBLE -> BubbleStep(setup) { step++ }
                Step.POLISH -> PolishStep(app) { step++ }
                Step.TRY -> TryStep(setup, done)
            }
        }
    }
}

@Composable
private fun Welcome(next: () -> Unit) {
    val c = HushTheme.colors
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(0.6f))
        Image(painterResource(R.drawable.hushtype_logo), contentDescription = null, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(10.dp))
        ListeningOrb(220.dp)
        Spacer(Modifier.height(8.dp))
        Text("Speak. It types.", style = HushType.largeTitle, color = c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            "Hushtype turns your voice into clean, polished text in any app. Tap the floating bubble, talk, and it lands where you're typing.",
            style = HushType.body, color = c.textSecondary, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        PrimaryButton("Get started", onClick = next)
    }
}

@Composable
private fun StepPage(
    icon: ImageVector,
    title: String,
    body: String,
    content: @Composable () -> Unit = {},
    actions: @Composable () -> Unit,
) {
    val c = HushTheme.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(28.dp))
            IconTile(icon, size = 56)
            Spacer(Modifier.height(22.dp))
            Text(title, style = HushType.largeTitle, color = c.text)
            Spacer(Modifier.height(10.dp))
            Text(body, style = HushType.body, color = c.textSecondary)
            Spacer(Modifier.height(24.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
        Column(Modifier.padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

@Composable
private fun SetupHint(number: Int, text: String) {
    val c = HushTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(22.dp).background(Hush.violet.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", style = HushType.subhead.copy(fontWeight = FontWeight.SemiBold), color = Hush.violet)
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = HushType.subhead, color = c.textSecondary)
    }
}

@Composable
private fun Granted(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Icon(Icons.Rounded.CheckCircle, null, tint = Hush.mintDeep, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = HushType.body.copy(fontWeight = FontWeight.Medium), color = HushTheme.colors.text)
    }
}

@Composable
private fun MicrophoneStep(setup: SetupState, refresh: () -> Unit, next: () -> Unit) {
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        refresh()
        if (result[Manifest.permission.RECORD_AUDIO] == true) next() else denied = true
    }
    StepPage(
        Icons.Rounded.Mic,
        "Your microphone",
        "Hushtype listens only while the bubble is lit up. Speech is turned into text by Android's recognizer on your phone.",
        content = {
            if (setup.microphone) Granted("Microphone allowed")
            if (denied) {
                Text("Android didn't allow it. You can turn it on in App info → Permissions.", style = HushType.subhead, color = Hush.red)
            }
        },
    ) {
        if (setup.microphone) {
            PrimaryButton("Continue", onClick = next)
        } else {
            PrimaryButton("Allow microphone") {
                if (denied) SetupState.openAppSettings(context)
                else launcher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            }
        }
    }
}

@Composable
private fun BubbleStep(setup: SetupState, next: () -> Unit) {
    val context = LocalContext.current
    val c = HushTheme.colors
    StepPage(
        Icons.Rounded.Accessibility,
        "Turn on the bubble",
        "Android lets an app float over others and type into them only as an accessibility service. Turn on “Hushtype bubble” in Accessibility settings.",
        content = {
            if (setup.accessibility) Granted("Bubble is on")
            else {
                HushCard {
                    Text("Two quick steps", style = HushType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    Spacer(Modifier.height(8.dp))
                    SetupHint(1, "Android blocks this switch for apps installed outside the Play Store. Open App info, tap ⋮ in the top corner and choose “Allow restricted settings”.")
                    TextButton(onClick = { SetupState.openAppSettings(context) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 30.dp)) {
                        Text("Open App info", color = Hush.violet, style = HushType.body.copy(fontWeight = FontWeight.SemiBold))
                    }
                    SetupHint(2, "Tap the button below. Scroll to the bottom of the Accessibility page, open “Downloaded apps” (or “Installed apps”), choose Hushtype bubble and turn it on.")
                }
                Spacer(Modifier.height(12.dp))
            }
            HushCard {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Shield, null, tint = Hush.violet, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("What Hushtype reads", style = HushType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Only the text field you're dictating into: the words around your cursor help the polish match your sentence and learn names you correct. Nothing is collected or sent anywhere except to the polish engine you pick.",
                            style = HushType.subhead, color = c.textSecondary,
                        )
                    }
                }
            }
        },
    ) {
        if (setup.accessibility) PrimaryButton("Continue", onClick = next)
        else {
            PrimaryButton("Open accessibility settings") { SetupState.openAccessibilitySettings(context) }
            TextButton(onClick = next, modifier = Modifier.fillMaxWidth()) { Text("Later", color = c.textSecondary) }
        }
    }
}

@Composable
private fun PolishStep(app: HushtypeApp, next: () -> Unit) {
    StepPage(
        Icons.Rounded.CheckCircle,
        "Polish",
        "Hushtype removes ums, fixes punctuation, applies “no, I meant…” corrections and spells your dictionary words right. Pick what does the polishing.",
        content = { PolishPicker(app) },
    ) {
        PrimaryButton("Continue", onClick = next)
    }
}

@Composable
private fun TryStep(setup: SetupState, done: () -> Unit) {
    var text by remember { mutableStateOf("") }
    StepPage(
        Icons.Rounded.TouchApp,
        "Try it",
        if (setup.accessibility) "Tap the box below. When the bubble appears beside the keyboard, tap it, say something, and tap it again. Hold it to talk instead, and drag it to move it."
        else "Turn on the bubble first, then come back here to try it. You can also do this later from Home.",
        content = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Tap here, then tap the bubble") },
                minLines = 4,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hush.violet, cursorColor = Hush.violet),
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        PrimaryButton("Done", onClick = done)
    }
}
