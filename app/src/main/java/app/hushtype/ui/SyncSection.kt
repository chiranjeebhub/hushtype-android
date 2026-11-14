package app.hushtype.ui

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.hushtype.HushtypeApp
import app.hushtype.core.SyncException
import app.hushtype.data.DeviceSync
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch

/** Settings → Sync: pair this phone with your Mac and iPhone by QR code. No accounts. */
@Composable
fun SyncSection(app: HushtypeApp) {
    val sync = app.sync
    val status by sync.status.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showCode by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var joining by remember { mutableStateOf(false) }

    fun join(code: String) {
        joining = true
        error = null
        scope.launch {
            try {
                sync.join(code)
                typing = false
            } catch (e: SyncException) {
                error = e.message
            } finally {
                joining = false
            }
        }
    }

    fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let(::join) }
            .addOnFailureListener { error = "The scanner couldn't start. Type the code instead." }
    }

    SectionLabel("Sync")
    HushCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
        if (status == DeviceSync.Status.Off) {
            SettingRow("Sync with your other devices", "Your dictionary and snippets, end-to-end encrypted. No account.", Icons.Rounded.Devices)
            Divider()
            SettingRow("Scan a code", "From Hushtype on your Mac or another phone", Icons.Rounded.QrCodeScanner, onClick = { scan() })
            Divider()
            SettingRow("Type a code", "It starts with HT1", Icons.Rounded.Keyboard, onClick = { error = null; typing = true })
            Divider()
            SettingRow("Start syncing from this phone", "Then scan its code on your other devices", Icons.Rounded.Sync, onClick = {
                scope.launch { sync.startNewGroup(); showCode = true }
            })
        } else {
            val (color, line) = when (val s = status) {
                DeviceSync.Status.Syncing -> Hush.amber to "Syncing…"
                is DeviceSync.Status.Synced -> Hush.mint to if (s.at == 0L) "On" else "Synced ${DateUtils.getRelativeTimeSpanString(s.at)}"
                is DeviceSync.Status.Failed -> Hush.red to s.message
                DeviceSync.Status.Off -> Hush.amber to "Off"
            }
            SettingRow("Sync is on", "Dictionary and snippets", Icons.Rounded.Devices, onClick = { scope.launch { sync.syncNow() } }) {
                StatusDot(color, line.take(28))
            }
            if (status is DeviceSync.Status.Failed) {
                Text((status as DeviceSync.Status.Failed).message, style = HushType.subhead, color = Hush.red, modifier = Modifier.padding(start = 48.dp, bottom = 6.dp))
            }
            Divider()
            SettingRow("Show my code", "Scan it on another device to add it", Icons.Rounded.QrCode2, onClick = { showCode = true })
            Divider()
            SettingRow("Turn off sync", "This phone keeps its words; other devices aren't changed", Icons.Rounded.LinkOff, onClick = { sync.turnOff() })
        }
        if (error != null && !typing) {
            Text(error!!, style = HushType.subhead, color = Hush.red, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
        }
        if (joining && !typing) Text("Joining…", style = HushType.subhead, color = HushTheme.colors.textSecondary, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
    }

    if (showCode) {
        sync.pairingCode?.let { code -> CodeDialog(code) { showCode = false } }
    }
    if (typing) {
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { typing = false },
            containerColor = HushTheme.colors.surface,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Type a code", style = HushType.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = code, onValueChange = { code = it; error = null }, minLines = 2,
                        placeholder = { Text("HT1-…") }, shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hush.violet, cursorColor = Hush.violet),
                        textStyle = HushType.body.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { Text(it, style = HushType.subhead, color = Hush.red) }
                }
            },
            confirmButton = {
                TextButton(enabled = code.isNotBlank() && !joining, onClick = { join(code) }) {
                    Text(if (joining) "Joining…" else "Join", color = Hush.violet, style = HushType.body.copy(fontWeight = FontWeight.SemiBold))
                }
            },
            dismissButton = { TextButton(onClick = { typing = false }) { Text("Cancel", color = HushTheme.colors.textSecondary) } },
        )
    }
}

@Composable
private fun CodeDialog(code: String, dismiss: () -> Unit) {
    val context = LocalContext.current
    val qr = remember(code) { qrBitmap(code, 640) }
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = HushTheme.colors.surface,
        shape = RoundedCornerShape(26.dp),
        title = { Text("Add a device", style = HushType.title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "On your Mac, open Settings → Your devices. On another phone, open Settings → Sync → Scan a code.",
                    style = HushType.subhead, color = HushTheme.colors.textSecondary,
                )
                Box(Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White).padding(12.dp)) {
                    Image(qr.asImageBitmap(), contentDescription = "Pairing code", modifier = Modifier.size(220.dp))
                }
                Text(code, style = HushType.subhead.copy(fontFamily = FontFamily.Monospace), color = HushTheme.colors.text, textAlign = TextAlign.Center)
                Text("Anyone with this code can read your dictionary. Share it only with your own devices.", style = HushType.subhead, color = HushTheme.colors.textTertiary, textAlign = TextAlign.Center)
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Done", color = Hush.violet) } },
        dismissButton = { TextButton(onClick = { copy(context, code) }) { Text("Copy code", color = HushTheme.colors.textSecondary) } },
    )
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0xFF15131C.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
