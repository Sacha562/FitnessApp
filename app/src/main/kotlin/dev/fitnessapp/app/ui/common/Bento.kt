package dev.fitnessapp.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fitnessapp.app.ui.theme.Fit

/*
 * The Bento components (D31, UX 4): tiles, pill buttons, chips, the ring timer, the floating navigation bar and the
 * sub-screen scaffold. Screens build on these and the Material 3 theme instead of styling stock components inline.
 */

/** A rounded surface that groups related content (UX 4.3-4.4). */
@Composable
fun Tile(
    modifier: Modifier = Modifier,
    color: Color = Fit.Tile,
    radius: Dp = 24.dp,
    padding: PaddingValues = PaddingValues(16.dp),
    spacing: Dp = 8.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier.fillMaxWidth().clip(shape).background(color).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** A 1 dp divider between rows inside a tile. */
@Composable
fun TileDivider(modifier: Modifier = Modifier) = HorizontalDivider(modifier, thickness = 1.dp, color = Fit.Line)

/** The one filled button of a screen (P1): an accent pill. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 58.dp, icon: ImageVector? = null) {
    Button(
        onClick = onClick, enabled = enabled, shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = Fit.Accent, contentColor = Fit.OnAccent, disabledContainerColor = Fit.Raised, disabledContentColor = Fit.Muted),
        contentPadding = PaddingValues(horizontal = 24.dp),
        modifier = modifier.heightIn(min = height),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold), textAlign = TextAlign.Center)
    }
}

/** Every other button (P1): a raised pill. With [onRepeat] holding it repeats (UX 6.1, [rememberHoldRepeat]). */
@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 52.dp,
    icon: ImageVector? = null,
    container: Color = Fit.Raised,
    content: Color = Fit.Ink,
    onRepeat: ((Int) -> Unit)? = null,
) {
    val hold = rememberHoldRepeat(enabled && onRepeat != null, onClick, onRepeat ?: {})
    Button(
        onClick = hold.onClick, enabled = enabled, shape = CircleShape, interactionSource = hold.interactionSource,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content, disabledContainerColor = container.copy(alpha = 0.5f), disabledContentColor = Fit.Muted.copy(alpha = 0.7f)),
        contentPadding = PaddingValues(horizontal = 20.dp),
        elevation = null,
        modifier = modifier.heightIn(min = height),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** A text action in the accent colour with a 48 dp target. */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Fit.Accent, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) color else Fit.Muted)
    }
}

/** A circular icon button (back, minimise, menus). */
@Composable
fun RoundIconButton(icon: ImageVector, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 48.dp, container: Color = Fit.Tile, tint: Color = Fit.Ink) {
    FilledIconButton(
        onClick = onClick, shape = CircleShape, modifier = modifier.size(size),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = tint),
    ) { Icon(icon, contentDescription) }
}

/** A small non-interactive label ("Set 2 of 2", "Skipped"). */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, container: Color = Fit.Raised, content: Color = Fit.Ink) {
    Box(modifier.clip(CircleShape).background(container).heightIn(min = 28.dp).padding(horizontal = 12.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}

/** A selectable pill chip, at least 48 dp tall (P4). [role] is RadioButton in a single choice, Checkbox for a toggle. */
@Composable
fun ChipButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, role: Role = Role.RadioButton, enabled: Boolean = true) {
    Box(
        modifier.heightIn(min = 48.dp).clip(CircleShape).background(if (selected) Fit.Accent else Fit.Raised)
            .selectable(selected = selected, enabled = enabled, role = role, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) Fit.OnAccent else if (enabled) Fit.Ink else Fit.Muted, textAlign = TextAlign.Center)
    }
}

/** A large square-ish tile button for one-tap logging (RIR and felt chips, UX 6.2). */
@Composable
fun LogChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 64.dp) {
    Box(
        modifier.height(height).clip(RoundedCornerShape(18.dp)).background(Fit.Raised).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.titleLarge, color = Fit.Ink, textAlign = TextAlign.Center) }
}

/** A segmented single choice in one raised pill (the five-state preference, UX 13.4). */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(CircleShape).background(Fit.Raised).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(CircleShape).background(if (on) Fit.Accent else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(o) }),
                contentAlignment = Alignment.Center,
            ) { Text(label(o), style = MaterialTheme.typography.labelMedium, color = if (on) Fit.OnAccent else Fit.Ink, maxLines = 1, softWrap = false) }
        }
    }
}

/** A rounded progress bar (session progress, onboarding steps). */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 8.dp, color: Color = Fit.Accent) {
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(Fit.Raised)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(CircleShape).background(color))
    }
}

/** The rest ring (UX 11.13.2): an accent arc on a raised track, depleting clockwise from the top. */
@Composable
fun RingTimer(fraction: Float?, modifier: Modifier = Modifier, stroke: Dp = 12.dp, color: Color = Fit.Accent, content: @Composable BoxScope.() -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = Size(size.width - w, size.height - w)
            drawArc(Fit.Raised, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
            if (fraction != null && fraction > 0f) {
                // Round caps overlap into a notch when the arc is nearly closed; a full ring is drawn flat.
                val f = fraction.coerceIn(0f, 1f)
                drawArc(color, -90f, 360f * f, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = if (f > 0.97f) StrokeCap.Butt else StrokeCap.Round))
            }
        }
        content()
    }
}

/** Role colour of a banner (UX 7.3, 4.1). */
enum class BannerKind(val color: Color, val container: Color, val icon: ImageVector, val iconLabel: String) {
    ADVISORY(Fit.Advisory, Fit.AdvisoryContainer, Icons.Rounded.Info, "Note"),
    SAFETY(Fit.Safety, Fit.SafetyContainer, Icons.Rounded.HealthAndSafety, "Safety"),
}

/** A banner with a leading role icon and a 1 dp outline at 40 % (UX 4.1): colour never carries meaning alone. */
@Composable
fun Banner(
    text: String,
    kind: BannerKind,
    modifier: Modifier = Modifier,
    icon: ImageVector = kind.icon,
    action: String? = null,
    onAction: () -> Unit = {},
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(kind.container)
            .border(BorderStroke(1.dp, kind.color.copy(alpha = 0.4f)), RoundedCornerShape(18.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, kind.iconLabel, tint = kind.color, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Fit.Ink, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
        if (action != null) LinkButton(action, onAction, color = kind.color)
    }
}

/** A section label inside or above a tile: sentence case, muted (UX 4.2). */
@Composable
fun TileLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Fit.Muted, modifier = modifier.semantics { heading() })
}

/** The text field of the theme: raised fill, 1 dp outline, accent when focused (UX 4.4). */
@Composable
fun FitTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    enabled: Boolean = true,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = modifier,
        label = label?.let { { Text(it) } }, placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine, minLines = minLines, enabled = enabled, isError = isError,
        keyboardOptions = keyboardOptions, visualTransformation = visualTransformation,
        leadingIcon = leadingIcon, trailingIcon = trailingIcon, supportingText = supportingText,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Fit.Raised, unfocusedContainerColor = Fit.Raised, disabledContainerColor = Fit.Raised, errorContainerColor = Fit.Raised,
            focusedBorderColor = Fit.Accent, unfocusedBorderColor = Fit.Outline, disabledBorderColor = Fit.Line, errorBorderColor = Fit.Safety,
            focusedLabelColor = Fit.Accent, unfocusedLabelColor = Fit.Muted, cursorColor = Fit.Accent,
            focusedTextColor = Fit.Ink, unfocusedTextColor = Fit.Ink,
        ),
    )
}

/** One destination of the floating navigation bar. */
data class NavItem(val label: String, val icon: ImageVector)

/** The floating navigation tile (UX 4.3): the active destination is a raised pill in the accent colour. */
@Composable
fun FitNavBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(24.dp)).background(Fit.Tile).padding(6.dp)) {
            items.forEachIndexed { i, item ->
                val on = i == selected
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(if (on) Fit.Raised else Color.Transparent)
                        .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) }),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    val tint = if (on) Fit.Accent else Fit.Muted
                    Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    Text(item.label, style = MaterialTheme.typography.labelSmall, color = tint)
                }
            }
        }
    }
}

/** The top bar of a pushed screen: a round back button and the title (UX 4.3). */
@Composable
fun FitTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 72.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
        // Long exercise names wrap to two lines in a smaller style rather than being cut (UX 4.8 rule 4).
        Text(
            title, style = if (title.length > 22) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            color = Fit.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp).semantics { heading() },
        )
        actions()
    }
}

/** A pushed screen: [FitTopBar], ground background, optional docked bottom bar and snackbar host. */
@Composable
fun FitScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = Fit.Ground,
        topBar = { FitTopBar(title, onBack, actions) },
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        content = content,
    )
}

/** A docked bottom area for the screen's primary button (P3), above the system navigation bar. */
@Composable
fun DockedBar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(Fit.Ground).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
