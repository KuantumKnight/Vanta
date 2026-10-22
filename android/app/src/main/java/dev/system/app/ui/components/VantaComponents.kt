package dev.system.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import dev.system.app.ui.theme.LocalVantaColors
import dev.system.app.ui.theme.VantaElevation
import dev.system.app.ui.theme.VantaLayout
import dev.system.app.ui.theme.VantaSpacing

internal enum class VantaCardTone {
    Standard,
    Subtle,
    Raised,
}

/** A quiet, shared card surface. Keep content hierarchy in the type and spacing, not shadow. */
@Composable
internal fun VantaCard(
    modifier: Modifier = Modifier,
    tone: VantaCardTone = VantaCardTone.Standard,
    content: @Composable ColumnScope.() -> Unit,
) {
    val containerColor = when (tone) {
        VantaCardTone.Standard, VantaCardTone.Raised -> MaterialTheme.colorScheme.surface
        VantaCardTone.Subtle -> LocalVantaColors.current.surfaceSubtle
    }
    val elevation = when (tone) {
        VantaCardTone.Standard, VantaCardTone.Subtle -> VantaElevation.flat
        VantaCardTone.Raised -> VantaElevation.card
    }
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        content = content,
    )
}

/** Section title and optional supporting count share one baseline and text hierarchy. */
@Composable
internal fun VantaSectionHeader(
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = VantaSpacing.md, bottom = VantaSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Non-interactive semantic label for schedule, state, or category distinctions. */
@Composable
internal fun VantaChip(
    label: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(
        modifier = modifier,
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = VantaSpacing.xs, vertical = VantaSpacing.xxs),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Reusable empty state that stays readable on expanded widths and uses semantic surfaces. */
@Composable
internal fun VantaEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = VantaSpacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        VantaCard(
            modifier = Modifier.widthIn(max = VantaLayout.contentMaxWidth).fillMaxWidth(),
            tone = VantaCardTone.Subtle,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(VantaSpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(VantaSpacing.xs),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction) { Text(actionLabel) }
                }
            }
        }
    }
}
