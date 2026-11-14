package app.hushtype.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Hushtype's palette: one violet accent, with the logo's indigo and mint for brand moments. */
object Hush {
    val violet = Color(0xFF8C78FF)
    val violetDeep = Color(0xFF6B5CFF)
    val indigo = Color(0xFF3B2EA8)
    val mint = Color(0xFF7DF0C4)
    val mintDeep = Color(0xFF1FA77A)
    val amber = Color(0xFFFFB547)
    val red = Color(0xFFE5484D)

    val brand = Brush.linearGradient(listOf(Color(0xFF6D5BFF), Color(0xFF2A1E8C)))
    val button = Brush.linearGradient(listOf(Color(0xFF7A68FF), Color(0xFF4A3BD0)))
}

@Immutable
data class HushColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val hairline: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accentSoft: Color,
    val isDark: Boolean,
)

private val Light = HushColors(
    background = Color(0xFFF7F6FB),
    surface = Color.White,
    surfaceRaised = Color(0xFFF1EFF8),
    hairline = Color(0x14000000),
    text = Color(0xFF15131C),
    textSecondary = Color(0xFF6B6878),
    textTertiary = Color(0xFF9C99A8),
    accentSoft = Color(0xFFEDEAFF),
    isDark = false,
)

private val Dark = HushColors(
    background = Color(0xFF100F15),
    surface = Color(0xFF1A1823),
    surfaceRaised = Color(0xFF24212F),
    hairline = Color(0x1AFFFFFF),
    text = Color(0xFFF4F2FA),
    textSecondary = Color(0xFFA19DB0),
    textTertiary = Color(0xFF6E6A7D),
    accentSoft = Color(0xFF2A2547),
    isDark = true,
)

val LocalHush = staticCompositionLocalOf { Light }

object HushTheme {
    val colors: HushColors @Composable get() = LocalHush.current
}

@Composable
fun HushtypeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(primary = Hush.violet, onPrimary = Color.White, background = colors.background, surface = colors.surface,
            onBackground = colors.text, onSurface = colors.text, surfaceVariant = colors.surfaceRaised, onSurfaceVariant = colors.textSecondary,
            outline = colors.hairline, secondary = Hush.mint)
    } else {
        lightColorScheme(primary = Hush.violetDeep, onPrimary = Color.White, background = colors.background, surface = colors.surface,
            onBackground = colors.text, onSurface = colors.text, surfaceVariant = colors.surfaceRaised, onSurfaceVariant = colors.textSecondary,
            outline = colors.hairline, secondary = Hush.mintDeep)
    }
    CompositionLocalProvider(LocalHush provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

object HushType {
    val largeTitle = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp, lineHeight = 38.sp)
    val title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, lineHeight = 28.sp)
    val headline = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp, lineHeight = 22.sp)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    val subhead = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
}

/** A rounded card with a hairline edge. */
@Composable
fun HushCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = HushTheme.colors
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(BorderStroke(1.dp, c.hairline), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = HushType.caption,
        color = HushTheme.colors.textTertiary,
        modifier = modifier.padding(start = 6.dp, top = 22.dp, bottom = 8.dp),
    )
}

/** A small monochrome icon in a soft rounded square. */
@Composable
fun IconTile(icon: ImageVector, tint: Color = Hush.violet, background: Color = HushTheme.colors.accentSoft, size: Int = 34) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3).dp)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.56).dp))
    }
}

/** One line in a settings card: icon, title, optional subtitle, and something on the right. */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconTile(icon)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = HushType.body.copy(fontWeight = FontWeight.Medium), color = HushTheme.colors.text)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = HushType.subhead, color = HushTheme.colors.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, icon: ImageVector?, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle, icon, onClick = { onChange(!checked) }) {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Hush.violetDeep,
                checkedThumbColor = Color.White,
                uncheckedBorderColor = HushTheme.colors.hairline,
            ),
        )
    }
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().padding(start = 48.dp).height(1.dp).background(HushTheme.colors.hairline))
}

/** The main call to action: a violet gradient pill. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(shape)
            .background(if (enabled) Hush.button else Brush.linearGradient(listOf(HushTheme.colors.surfaceRaised, HushTheme.colors.surfaceRaised)))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = HushType.headline, color = if (enabled) Color.White else HushTheme.colors.textTertiary)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(shape)
            .background(HushTheme.colors.surfaceRaised)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = HushType.headline.copy(fontWeight = FontWeight.Medium), color = HushTheme.colors.text)
    }
}

/** A dot and a short status, like "Ready in every app". */
@Composable
fun StatusDot(color: Color, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, style = HushType.subhead.copy(fontWeight = FontWeight.Medium), color = HushTheme.colors.textSecondary)
    }
}
