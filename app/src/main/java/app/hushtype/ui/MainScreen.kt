package app.hushtype.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.hushtype.HushtypeApp

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    DICTIONARY("Dictionary", Icons.AutoMirrored.Rounded.MenuBook),
    SNIPPETS("Snippets", Icons.AutoMirrored.Rounded.ShortText),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

@Composable
fun MainScreen(app: HushtypeApp, setup: SetupState) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val c = HushTheme.colors
    BackHandler(showHistory) { showHistory = false }
    Scaffold(
        containerColor = c.background,
        bottomBar = {
            if (!showHistory) {
                NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Hush.violetDeep.takeUnless { c.isDark } ?: Hush.violet,
                                selectedTextColor = c.text,
                                indicatorColor = c.accentSoft,
                                unselectedIconColor = c.textTertiary,
                                unselectedTextColor = c.textTertiary,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().background(c.background).padding(padding)) {
            AnimatedContent(if (showHistory) null else tab, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "tab") { t ->
                Column(Modifier.fillMaxSize()) {
                    when (t) {
                        null -> HistoryScreen(app) { showHistory = false }
                        Tab.HOME -> HomeScreen(app, setup) { showHistory = true }
                        Tab.DICTIONARY -> DictionaryScreen(app)
                        Tab.SNIPPETS -> SnippetsScreen(app)
                        Tab.SETTINGS -> SettingsScreen(app, setup)
                    }
                }
            }
        }
    }
}
