package com.openjump.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openjump.app.R
import com.openjump.app.protocol.ProtocolAvailability
import com.openjump.app.protocol.ProtocolDefinition
import com.openjump.app.ui.theme.LightPrimary
import com.openjump.app.ui.theme.OpenJumpTypes
import com.openjump.app.ui.theme.ShapeTokens
import com.openjump.app.ui.theme.Spacing

@Composable
fun OpenJumpIconBadge(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    iconSize: Dp = 20.dp,
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Surface(
        modifier = modifier.size(size),
        shape = ShapeTokens.small,
        color = if (isDark) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (isDark) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
            },
        ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = if (isDark) {
                    MaterialTheme.colorScheme.primary
                } else {
                    LightPrimary
                },
            )
        }
    }
}

@Composable
fun OpenJumpBrandBadge(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    iconSize: Dp = 20.dp,
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Surface(
        modifier = modifier.size(size),
        shape = ShapeTokens.small,
        color = if (isDark) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (isDark) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            },
        ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_openjump_logo),
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                colorFilter = if (isDark) {
                    ColorFilter.tint(MaterialTheme.colorScheme.primary)
                } else {
                    ColorFilter.tint(LightPrimary)
                },
            )
        }
    }
}

@Composable
fun OpenJumpLogoMark(
    modifier: Modifier = Modifier,
) {
    OpenJumpBrandBadge(modifier = modifier, size = 32.dp, iconSize = 20.dp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenJumpTopAppBar(
    title: String,
    onNavigationClick: (() -> Unit)? = null,
    navigationContentDescription: String? = null,
    showBrandLogo: Boolean = false,
    isHome: Boolean = false,
    iconBadge: Int? = null,
    titleLeadingContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    subtitle: String? = null,
) {
    val leading = titleLeadingContent ?: when {
        showBrandLogo -> {
            {
                if (isHome) {
                    OpenJumpBrandBadge(size = 36.dp, iconSize = 23.dp)
                } else {
                    OpenJumpBrandBadge(size = 32.dp, iconSize = 20.dp)
                }
            }
        }
        iconBadge != null -> {
            {
                OpenJumpIconBadge(icon = iconBadge, size = 30.dp, iconSize = 18.dp)
            }
        }
        else -> null
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        TopAppBar(
            title = {
                if (leading != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (isHome) 10.dp else Spacing.sm),
                    ) {
                        leading()
                        Column {
                            Text(
                                text = title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = if (isHome) {
                                    OpenJumpTypes.ScreenTitle.copy(
                                        fontSize = 23.sp,
                                        lineHeight = 28.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = (-0.6).sp,
                                    )
                                } else {
                                    OpenJumpTypes.ScreenTitle.copy(
                                        fontSize = 20.sp,
                                        lineHeight = 26.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-0.3).sp,
                                    )
                                },
                            )
                            subtitle?.let {
                                Text(
                                    text = it,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    Column {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = OpenJumpTypes.ScreenTitle.copy(letterSpacing = (-0.3).sp),
                        )
                        subtitle?.let {
                            Text(
                                text = it,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                if (onNavigationClick != null) {
                    IconButton(onClick = onNavigationClick) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = navigationContentDescription,
                        )
                    }
                }
            },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f),
            thickness = 0.75.dp,
        )
    }
}

@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(
        modifier = modifier,
        shape = ShapeTokens.full,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun ProtocolCard(
    definition: ProtocolDefinition,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val available = definition.availability == ProtocolAvailability.AVAILABLE
    val cardModifier = modifier.fillMaxWidth()
    if (available) {
        ElevatedCard(
            onClick = onClick,
            modifier = cardModifier,
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            ProtocolCardContent(definition, available = true)
        }
    } else {
        ElevatedCard(
            modifier = cardModifier,
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            ProtocolCardContent(definition, available = false)
        }
    }
}

@Composable
private fun ProtocolCardContent(definition: ProtocolDefinition, available: Boolean) {
    Row(
        modifier = Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(56.dp).clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                color = if (available) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ) {}
            Text(
                definition.shortName,
                color = if (available) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    definition.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (!available) StatusPill(stringResource(R.string.common_coming_soon))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                definition.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    Surface(
        modifier = modifier,
        shape = ShapeTokens.large,
        color = if (primary) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = if (primary) 0.dp else 2.dp,
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Text(
                label.uppercase(),
                style = OpenJumpTypes.MetricLabel,
                color = if (primary) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(Spacing.xs + Spacing.xs))
            Text(
                value,
                style = if (primary) OpenJumpTypes.MetricHero else OpenJumpTypes.MetricValue,
                color = if (primary) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun InfoBanner(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    warning: Boolean = false,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = ShapeTokens.medium,
        color = if (warning) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
