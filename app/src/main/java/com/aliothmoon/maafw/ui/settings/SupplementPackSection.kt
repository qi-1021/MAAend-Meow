package com.aliothmoon.maafw.ui.settings

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.i18n.asString
import com.aliothmoon.maafw.settings.SettingsIntent
import com.aliothmoon.maafw.supplement.SupplementPack
import com.aliothmoon.maafw.supplement.SupplementPackText
import com.aliothmoon.maafw.supplement.SupplementPackInstaller
import com.aliothmoon.maafw.supplement.SupplementVersion
import com.aliothmoon.maafw.theme.MaaDesignTokens
import com.aliothmoon.maafw.theme.MaaTheme
import com.aliothmoon.maafw.ui.components.MaaButton
import com.aliothmoon.maafw.ui.components.MaaCard
import com.aliothmoon.maafw.ui.components.MaaDescriptionPanel
import com.aliothmoon.maafw.ui.components.MaaInfoRow
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import com.aliothmoon.maafw.ui.components.MaaSemanticOutlinedButton
import com.aliothmoon.maafw.ui.components.MaaToneBadge

/**
 * 设置页「补充包」区块：按需下载的高级能力（地图定位 / 寻路 / 战斗识别）。
 *
 * 进入组合时自动刷新一次本地状态；下载、取消、删除都通过 [SettingsIntent]
 * 交给 [com.aliothmoon.maafw.settings.SettingsViewModel] 转发给 [SupplementPackInstaller]。
 */
@Composable
fun SupplementPackSection(
    state: SupplementPackInstaller.UiState,
    onIntent: (SettingsIntent) -> Unit,
) {
    LaunchedEffect(Unit) { onIntent(SettingsIntent.RefreshSupplementPacks) }

    MaaCard(
        title = stringResource(R.string.supplement_section_title),
        collapsible = true,
    ) {
        MaaDescriptionPanel {
            Text(
                text = stringResource(R.string.supplement_section_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        MaaInfoRow(
            label = stringResource(R.string.supplement_installed_total),
            value = formatShortFileSize(state.totalInstalledBytes),
        )

        if (state.packs.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = MaaDesignTokens.Spacing.sm),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            val packNames = rememberPackNames(state.packs.map { it.pack })
            Column(verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md)) {
                state.packs.forEachIndexed { index, packState ->
                    SupplementPackRow(
                        packState = packState,
                        allPacks = state.packs,
                        packNames = packNames,
                        onIntent = onIntent,
                    )
                    if (index < state.packs.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SupplementPackRow(
    packState: SupplementPackInstaller.PackState,
    allPacks: List<SupplementPackInstaller.PackState>,
    packNames: Map<String, String>,
    onIntent: (SettingsIntent) -> Unit,
) {
    val pack = packState.pack
    val name = packNames[pack.id] ?: pack.id
    val sizeText = formatShortFileSize(pack.totalBytes)
    val installedIds = remember(allPacks) {
        allPacks.filter { it.state == SupplementPack.State.INSTALLED }.map { it.pack.id }.toSet()
    }
    val unmetDependencies = remember(pack.requires, installedIds) {
        pack.requires.filterNot { it in installedIds }
    }
    val requiresHint = if (packState.state == SupplementPack.State.NOT_INSTALLED && unmetDependencies.isNotEmpty()) {
        val depNames = unmetDependencies.mapNotNull { packNames[it] }
        if (depNames.isNotEmpty()) {
            stringResource(R.string.supplement_requires_format, depNames.joinToString(", "))
        } else {
            null
        }
    } else {
        null
    }

    // 已装版本：让人一眼看出装的是哪一份（镜像回退拿到的内容是不是对的，靠它核对）。
    val installedVersionText = if (packState.state == SupplementPack.State.INSTALLED) {
        val version = packState.installedVersion
        if (version.isNullOrBlank()) {
            stringResource(R.string.supplement_pack_version_unknown)
        } else {
            stringResource(R.string.supplement_pack_version_format, version)
        }
    } else {
        null
    }
    // 低于 App 要求的最低版本 → 明确提示（不阻止使用，只建议重下）。
    val outdatedText = if (SupplementVersion.isOutdated(pack.id, packState.installedVersion)) {
        stringResource(
            R.string.supplement_pack_outdated_format,
            packState.installedVersion.orEmpty(),
            SupplementVersion.requiredFor(pack.id),
        )
    } else {
        null
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xxs),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = packSummary(pack),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PackActionButton(
                packState = packState,
                dependenciesMet = unmetDependencies.isEmpty(),
                onIntent = onIntent,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MaaToneBadge(
                text = stateLabel(packState.state),
                tone = stateTone(packState.state),
            )
            Text(
                text = sizeText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        installedVersionText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        outdatedText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        requiresHint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        AnimatedVisibility(
            visible = packState.progress != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            packState.progress?.let { progress ->
                PackProgress(progress = progress)
            }
        }

        AnimatedVisibility(
            visible = packState.error != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            packState.error?.let { error ->
                Text(
                    text = error.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PackActionButton(
    packState: SupplementPackInstaller.PackState,
    dependenciesMet: Boolean,
    onIntent: (SettingsIntent) -> Unit,
) {
    val packId = packState.pack.id
    when {
        packState.progress != null -> {
            MaaOutlinedButton(
                onClick = { onIntent(SettingsIntent.CancelSupplementPackDownload) },
            ) {
                Text(stringResource(R.string.supplement_action_cancel))
            }
        }

        packState.state == SupplementPack.State.INSTALLED -> {
            RemovePackButton(packState = packState, onIntent = onIntent)
        }

        packState.state == SupplementPack.State.PARTIAL || packState.error != null -> {
            MaaButton(
                onClick = { onIntent(SettingsIntent.InstallSupplementPack(packId)) },
            ) {
                Text(stringResource(R.string.supplement_action_retry))
            }
        }

        else -> {
            MaaButton(
                onClick = { onIntent(SettingsIntent.InstallSupplementPack(packId)) },
                enabled = dependenciesMet,
            ) {
                Text(stringResource(R.string.supplement_action_download))
            }
        }
    }
}

@Composable
private fun RemovePackButton(
    packState: SupplementPackInstaller.PackState,
    onIntent: (SettingsIntent) -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }
    MaaSemanticOutlinedButton(
        onClick = { showConfirm = true },
        semantic = MaterialTheme.colorScheme.error,
    ) {
        Text(stringResource(R.string.supplement_action_remove))
    }
    if (showConfirm) {
        RemoveConfirmDialog(
            packState = packState,
            onDismiss = { showConfirm = false },
            onConfirm = {
                showConfirm = false
                onIntent(SettingsIntent.RemoveSupplementPack(packState.pack.id))
            },
        )
    }
}

@Composable
private fun RemoveConfirmDialog(
    packState: SupplementPackInstaller.PackState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val name = rememberPackName(packState.pack)
    val sizeText = formatShortFileSize(packState.pack.totalBytes)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.supplement_remove_confirm_title)) },
        text = {
            Text(
                stringResource(
                    R.string.supplement_remove_confirm_message,
                    name,
                    sizeText,
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.common_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

@Composable
private fun PackProgress(progress: SupplementPackInstaller.Progress) {
    val context = LocalContext.current
    val percent = if (progress.totalBytes > 0) {
        (progress.downloadedBytes * 100 / progress.totalBytes).toInt()
    } else {
        0
    }
    val phaseLabel = when (progress.phase) {
        SupplementPackInstaller.Progress.Phase.DOWNLOADING -> stringResource(
            R.string.supplement_progress_downloading,
            "${percent}%",
        )
        SupplementPackInstaller.Progress.Phase.VERIFYING -> stringResource(R.string.supplement_progress_verifying)
    }
    val bytesLabel = stringResource(
        R.string.supplement_size_of_total,
        Formatter.formatShortFileSize(context, progress.downloadedBytes),
        Formatter.formatShortFileSize(context, progress.totalBytes),
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.xs),
    ) {
        LinearProgressIndicator(
            progress = { if (progress.totalBytes > 0) progress.downloadedBytes.toFloat() / progress.totalBytes else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = phaseLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = bytesLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 包名/说明走 [SupplementPackText] 的静态映射，**不用** `getIdentifier` 按名字动态查：
 * release 构建的资源压缩看不到动态引用的字符串，会把 `supplement_pack_*` 裁掉，
 * 编译期毫无提示，装到机器上才发现包名显示成了 `map-locate` 这种 id。
 */
@Composable
private fun rememberPackName(pack: SupplementPack.Pack): String {
    val resId = SupplementPackText.nameRes(pack.id)
    return if (resId != null) stringResource(resId) else pack.id
}

@Composable
private fun rememberPackNames(packs: List<SupplementPack.Pack>): Map<String, String> =
    packs.associate { it.id to rememberPackName(it) }

@Composable
private fun packSummary(pack: SupplementPack.Pack): String {
    val resId = SupplementPackText.summaryRes(pack.id)
    return if (resId != null) stringResource(resId) else ""
}

@Composable
private fun stateLabel(state: SupplementPack.State): String = when (state) {
    SupplementPack.State.NOT_INSTALLED -> stringResource(R.string.supplement_state_not_installed)
    SupplementPack.State.PARTIAL -> stringResource(R.string.supplement_state_partial)
    SupplementPack.State.INSTALLED -> stringResource(R.string.supplement_state_installed)
}

@Composable
private fun stateTone(state: SupplementPack.State) = when (state) {
    SupplementPack.State.NOT_INSTALLED -> MaaTheme.palette.neutral
    SupplementPack.State.PARTIAL -> MaaTheme.palette.warning
    SupplementPack.State.INSTALLED -> MaaTheme.palette.success
}

@Composable
private fun formatShortFileSize(bytes: Long): String {
    return Formatter.formatShortFileSize(LocalContext.current, bytes)
}
