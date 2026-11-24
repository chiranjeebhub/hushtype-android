package app.hushtype.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hushtype.HushtypeApp
import app.hushtype.R
import app.hushtype.data.HistoryEntry

@Composable
fun HomeScreen(app: HushtypeApp, setup: SetupState, openHistory: () -> Unit) {
    val c = HushTheme.colors
    val history by app.history.entries.collectAsState()
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp)) {
        item {
            Row(Modifier.padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.hushtype_logo), null, Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)))
                Spacer(Modifier.width(12.dp))
                Text("Hushtype", style = HushType.title, color = c.text, modifier = Modifier.weight(1f))
            }
        }
        item {
            when {
                setup.ready -> ReadyCard()
                setup.microphone && setup.bubbleStopped -> StoppedCard()
                else -> SetupCard(setup)
            }
        }
        if (setup.accessibility && !setup.unrestricted) {
            item {
                Spacer(Modifier.height(12.dp))
                KeepRunningCard()
            }
        }
        item {
            SectionLabel("Try it")
            TryField()
        }
        if (history.isNotEmpty()) {
            item {
                SectionLabel("This week")
                Stats(history)
            }
            item {
                Row(Modifier.padding(top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("RECENT", style = HushType.caption, color = c.textTertiary, modifier = Modifier.padding(start = 6.dp).weight(1f))
                    Text(
                        "See all",
                        color = Hush.violet,
                        style = HushType.subhead.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = openHistory).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            items(history.take(4), key = { it.id }) { entry ->
                HistoryRow(entry, onCopy = { copy(context, entry.text) }, onDelete = null)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ReadyCard() {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Hush.brand).padding(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Hush.mint))
                    Spacer(Modifier.width(8.dp))
                    Text("Ready in every app", style = HushType.subhead.copy(fontWeight = FontWeight.SemiBold), color = Hush.mint)
                }
                Spacer(Modifier.height(8.dp))
                Text("Tap the bubble beside your keyboard and talk.", style = HushType.headline, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text("Tap again to type it. Hold to talk, drag to move, drop on ✕ to hide it for an hour.", style = HushType.subhead, color = Color.White.copy(alpha = 0.75f))
            }
            Spacer(Modifier.width(14.dp))
            BubbleGlyph(60.dp)
        }
    }
}

/** The switch is on, but Android stopped the bubble and won't bring it back by itself. */
@Composable
private fun StoppedCard() {
    val context = LocalContext.current
    val c = HushTheme.colors
    HushCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Hush.amber))
            Spacer(Modifier.width(8.dp))
            Text("Bubble stopped", style = HushType.subhead.copy(fontWeight = FontWeight.SemiBold), color = Hush.amber)
        }
        Spacer(Modifier.height(8.dp))
        Text("Android stopped the bubble in the background", style = HushType.headline, color = c.text)
        Spacer(Modifier.height(4.dp))
        Text(
            "Turn it back on: in Accessibility, open “Downloaded apps”, tap Hushtype bubble, switch it off and on again.",
            style = HushType.subhead, color = c.textSecondary,
        )
        Spacer(Modifier.height(14.dp))
        PrimaryButton("Open accessibility settings") { SetupState.openAccessibilitySettings(context) }
    }
}

/** Phones that save battery aggressively stop the bubble unless they're told not to. */
@Composable
private fun KeepRunningCard() {
    val context = LocalContext.current
    val c = HushTheme.colors
    HushCard {
        Text("Keep the bubble running", style = HushType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        Spacer(Modifier.height(4.dp))
        Text(
            if (SetupState.isXiaomi) "Your phone stops apps it thinks are idle. Allow Hushtype to run without battery limits, and turn on Autostart for it."
            else "Your phone may stop Hushtype to save battery. Let it run without battery limits.",
            style = HushType.subhead, color = c.textSecondary,
        )
        Row {
            TextButton(onClick = { SetupState.requestUnrestricted(context) }, contentPadding = PaddingValues(0.dp)) {
                Text("Allow", color = Hush.violet, style = HushType.body.copy(fontWeight = FontWeight.SemiBold))
            }
            if (SetupState.isXiaomi) {
                Spacer(Modifier.width(20.dp))
                TextButton(onClick = { SetupState.openAutostart(context) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Autostart", color = Hush.violet, style = HushType.body.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

@Composable
private fun SetupCard(setup: SetupState) {
    val context = LocalContext.current
    HushCard {
        Text("Finish setting up", style = HushType.headline, color = HushTheme.colors.text)
        Spacer(Modifier.height(4.dp))
        Text("Two things Android needs before the bubble can work.", style = HushType.subhead, color = HushTheme.colors.textSecondary)
        Spacer(Modifier.height(8.dp))
        SettingRow("Microphone", if (setup.microphone) "Allowed" else "Needed to hear you", Icons.Rounded.Mic, onClick = { SetupState.openAppSettings(context) }) {
            StatusDot(if (setup.microphone) Hush.mint else Hush.amber, if (setup.microphone) "On" else "Off")
        }
        Divider()
        SettingRow("Bubble", if (setup.bubbleStopped) "Stopped. Switch it off and on in Accessibility" else if (setup.accessibility) "On" else "Turn on “Hushtype bubble” in Accessibility", Icons.Rounded.Accessibility, onClick = { SetupState.openAccessibilitySettings(context) }) {
            StatusDot(if (setup.ready || (setup.accessibility && setup.bubbleRunning)) Hush.mint else Hush.amber, if (setup.bubbleRunning) "On" else if (setup.bubbleStopped) "Stopped" else "Off")
        }
    }
}

@Composable
private fun TryField() {
    var text by remember { mutableStateOf("") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text("Tap here, then tap the bubble and speak") },
        minLines = 3,
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Hush.violet, cursorColor = Hush.violet,
            unfocusedContainerColor = HushTheme.colors.surface, focusedContainerColor = HushTheme.colors.surface,
            unfocusedBorderColor = HushTheme.colors.hairline,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Stats(history: List<HistoryEntry>) {
    val weekAgo = System.currentTimeMillis() - 7 * DateUtils.DAY_IN_MILLIS
    val week = history.filter { it.date >= weekAgo && it.note != "Cancelled" }
    val words = week.sumOf { it.text.split(Regex("\\s+")).count { w -> w.isNotBlank() } }
    val times = week.map { it.seconds }.filter { it > 0 }.sorted()
    val median = times.getOrNull(times.size / 2)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile("Words", "%,d".format(words), Modifier.weight(1f))
        StatTile("Dictations", "${week.size}", Modifier.weight(1f))
        StatTile("Typical wait", median?.let { "%.1f s".format(it) } ?: "—", Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    HushCard(modifier, padding = PaddingValues(14.dp)) {
        Text(value, style = HushType.title, color = HushTheme.colors.text, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(label, style = HushType.subhead, color = HushTheme.colors.textSecondary, maxLines = 1)
    }
}

@Composable
fun HistoryRow(entry: HistoryEntry, onCopy: () -> Unit, onDelete: (() -> Unit)?) {
    val c = HushTheme.colors
    HushCard(padding = PaddingValues(start = 16.dp, top = 14.dp, bottom = 12.dp, end = 6.dp), onClick = onCopy) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(entry.text, style = HushType.body, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                val meta = listOfNotNull(
                    entry.appName,
                    DateUtils.getRelativeTimeSpanString(entry.date).toString(),
                    entry.polishedBy?.let { "polished by $it" },
                ).joinToString(" · ")
                Text(meta, style = HushType.subhead, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.notInserted || entry.note != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        entry.note ?: "Not typed anywhere. Tap to copy.",
                        style = HushType.subhead, color = Hush.amber, maxLines = 2,
                    )
                }
            }
            IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, "Copy", tint = c.textSecondary, modifier = Modifier.size(20.dp)) }
            if (onDelete != null) {
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "Delete", tint = c.textSecondary, modifier = Modifier.size(20.dp)) }
            }
        }
    }
}

@Composable
fun HistoryScreen(app: HushtypeApp, back: () -> Unit) {
    val c = HushTheme.colors
    val history by app.history.entries.collectAsState()
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 20.dp, top = 8.dp, bottom = 28.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = c.text) }
                Text("History", style = HushType.title, color = c.text, modifier = Modifier.weight(1f))
                if (history.isNotEmpty()) TextButton(onClick = { app.history.clear() }) { Text("Clear", color = Hush.red) }
            }
        }
        if (history.isEmpty()) {
            item {
                Text("Your dictations show up here, so nothing you say is ever lost.", style = HushType.body, color = c.textSecondary, modifier = Modifier.padding(start = 12.dp, top = 12.dp))
            }
        }
        items(history, key = { it.id }) { entry ->
            Box(Modifier.padding(start = 8.dp, bottom = 8.dp)) {
                HistoryRow(entry, onCopy = { copy(context, entry.text) }, onDelete = { app.history.remove(entry.id) })
            }
        }
    }
}

fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Hushtype", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

