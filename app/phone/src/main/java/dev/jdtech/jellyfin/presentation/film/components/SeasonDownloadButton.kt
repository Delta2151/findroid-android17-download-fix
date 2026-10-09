package dev.jdtech.jellyfin.presentation.film.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.core.presentation.downloader.SeasonDownloaderViewModel
import java.util.UUID

@Composable
fun SeasonDownloadButton(
    seasonId: UUID,
    enabled: Boolean,
    onQueued: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SeasonDownloaderViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedStorageIndex by remember(seasonId) { mutableIntStateOf(0) }
    var storageDialogOpen by remember(seasonId) { mutableStateOf(false) }
    var storageUnavailable by remember(seasonId) { mutableStateOf(false) }
    var storageLocations by
        remember(seasonId) {
            mutableStateOf(
                context.getExternalFilesDirs(null).withIndex().filter { it.value != null }
            )
        }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.downloadSeason(seasonId, selectedStorageIndex)
        }
    val startDownload = {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.downloadSeason(seasonId, selectedStorageIndex)
        }
    }

    LaunchedEffect(seasonId) { viewModel.observeSeason(seasonId) }
    LaunchedEffect(state.finished, state.progress.queued) {
        if (state.finished || state.progress.queued > 0) onQueued()
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            enabled = enabled && !state.isQueuing,
            onClick = {
                storageUnavailable = false
                storageLocations =
                    context.getExternalFilesDirs(null).withIndex().filter { it.value != null }
                when (storageLocations.size) {
                    0 -> storageUnavailable = true
                    1 -> {
                        selectedStorageIndex = storageLocations.single().index
                        startDownload()
                    }
                    else -> storageDialogOpen = true
                }
            },
        ) {
            Icon(painterResource(CoreR.drawable.ic_download), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(CoreR.string.download_season))
        }
        when {
            storageUnavailable -> Text(stringResource(CoreR.string.storage_unavailable))
            state.isQueuing ->
                Text(
                    if (state.progress.total == 0)
                        stringResource(CoreR.string.season_download_waiting)
                    else
                        stringResource(
                            CoreR.string.season_download_adding,
                            state.progress.processed,
                            state.progress.total,
                        )
                )
            state.error ->
                Text(
                    stringResource(CoreR.string.season_download_error),
                    color = MaterialTheme.colorScheme.error,
                )
            state.finished ->
                Text(
                    stringResource(
                        CoreR.string.season_download_summary,
                        state.progress.queued,
                        state.progress.skipped,
                        state.progress.failed,
                    )
                )
        }
    }
    if (storageDialogOpen) {
        StorageSelectionDialog(
            storageLocations =
                storageLocations.map { (_, dir) ->
                    val location =
                        context.getString(
                            if (Environment.isExternalStorageRemovable(dir!!)) CoreR.string.external
                            else CoreR.string.internal
                        )
                    context.getString(
                        CoreR.string.storage_name,
                        location,
                        StatFs(dir.path).availableBytes / 1000000,
                    )
                },
            onSelect = {
                selectedStorageIndex = storageLocations[it].index
                storageDialogOpen = false
                startDownload()
            },
            onDismiss = { storageDialogOpen = false },
        )
    }
}
