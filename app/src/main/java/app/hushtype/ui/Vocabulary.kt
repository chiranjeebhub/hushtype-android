package app.hushtype.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hushtype.HushtypeApp
import app.hushtype.core.Prompts
import app.hushtype.core.Snippet

@Composable
private fun ScreenTitle(title: String, subtitle: String) {
    Column(Modifier.padding(top = 36.dp, bottom = 8.dp)) {
        Text(title, style = HushType.largeTitle, color = HushTheme.colors.text)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = HushType.body, color = HushTheme.colors.textSecondary)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Hush.violet, cursorColor = Hush.violet,
    unfocusedContainerColor = HushTheme.colors.surface, focusedContainerColor = HushTheme.colors.surface,
    unfocusedBorderColor = HushTheme.colors.hairline,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DictionaryScreen(app: HushtypeApp) {
    val c = HushTheme.colors
    val state by app.vocabulary.state.collectAsState()
    val learned = state.terms.filter { !it.deleted && it.learned }.map { it.text }.toSet()
    val terms = state.liveTerms
    var draft by remember { mutableStateOf("") }
    fun add() {
        val words = Prompts.vocabulary(draft)
        if (words.isNotEmpty()) app.vocabulary.addTerms(words)
        draft = ""
    }
    LazyColumn(Modifier.imePadding(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            ScreenTitle("Dictionary", "Names, brands and jargon Hushtype should always spell your way. Words you fix after dictating are learned here too.")
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Add a word or name") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(if (draft.isBlank()) c.surfaceRaised else Hush.violetDeep)
                        .clickable(enabled = draft.isNotBlank()) { add() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Add, "Add", tint = if (draft.isBlank()) c.textTertiary else Color.White) }
            }
            Spacer(Modifier.height(20.dp))
        }
        item {
            if (terms.isEmpty()) {
                Text("No words yet.", style = HushType.body, color = c.textTertiary)
            } else {
                FlowRow(Modifier.animateContentSize(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    terms.forEach { term -> TermChip(term, term in learned) { app.vocabulary.removeTerm(term) } }
                }
            }
        }
        if (learned.isNotEmpty()) {
            item {
                Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Hush.mintDeep, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Learned from your corrections", style = HushType.subhead, color = c.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun TermChip(term: String, learned: Boolean, onRemove: () -> Unit) {
    val c = HushTheme.colors
    Row(
        Modifier
            .clip(CircleShape)
            .background(c.surface)
            .border(1.dp, if (learned) Hush.mint.copy(alpha = 0.7f) else c.hairline, CircleShape)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (learned) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Hush.mintDeep, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(term, style = HushType.body.copy(fontWeight = FontWeight.Medium), color = c.text)
        Box(Modifier.padding(start = 4.dp).size(28.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Remove $term", tint = c.textTertiary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun SnippetsScreen(app: HushtypeApp) {
    val c = HushTheme.colors
    val state by app.vocabulary.state.collectAsState()
    val snippets = state.liveSnippets
    var editing by remember { mutableStateOf<Snippet?>(null) }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            ScreenTitle("Snippets", "Say a short phrase, get the full text. Say just the phrase to type it as is, or use it inside a sentence.")
            Spacer(Modifier.height(16.dp))
            PrimaryButton("New snippet") { editing = Snippet(trigger = "", expansion = "") }
            Spacer(Modifier.height(16.dp))
        }
        if (snippets.isEmpty()) {
            item {
                HushCard {
                    Text("For example", style = HushType.caption, color = c.textTertiary)
                    Spacer(Modifier.height(8.dp))
                    Text("“my calendar link”", style = HushType.headline, color = c.text)
                    Text("cal.com/you/30min", style = HushType.body, color = c.textSecondary)
                }
            }
        }
        items(snippets, key = { it.id }) { snippet ->
            HushCard(onClick = { editing = snippet }) {
                Text("“${snippet.trigger}”", style = HushType.headline, color = c.text)
                Spacer(Modifier.height(4.dp))
                Text(snippet.expansion, style = HushType.body, color = c.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
    editing?.let { snippet ->
        SnippetEditor(
            snippet,
            isNew = snippets.none { it.id == snippet.id },
            onSave = { app.vocabulary.upsertSnippet(it); editing = null },
            onDelete = { app.vocabulary.removeSnippet(snippet.id); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun SnippetEditor(snippet: Snippet, isNew: Boolean, onSave: (Snippet) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var trigger by remember { mutableStateOf(snippet.trigger) }
    var expansion by remember { mutableStateOf(snippet.expansion) }
    val valid = trigger.isNotBlank() && expansion.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = HushTheme.colors.surface,
        shape = RoundedCornerShape(26.dp),
        title = { Text(if (isNew) "New snippet" else "Edit snippet", style = HushType.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = trigger, onValueChange = { trigger = it }, singleLine = true,
                    label = { Text("When I say") }, placeholder = { Text("my calendar link") },
                    shape = RoundedCornerShape(14.dp), colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = expansion, onValueChange = { expansion = it }, minLines = 3,
                    label = { Text("Type") }, shape = RoundedCornerShape(14.dp), colors = fieldColors(), modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(snippet.copy(trigger = trigger.trim(), expansion = expansion)) }) {
                Text("Save", color = if (valid) Hush.violet else HushTheme.colors.textTertiary, style = HushType.body.copy(fontWeight = FontWeight.SemiBold))
            }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete", color = Hush.red) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = HushTheme.colors.textSecondary) }
            }
        },
    )
}
