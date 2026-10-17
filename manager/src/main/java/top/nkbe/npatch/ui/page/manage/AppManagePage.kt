package top.nkbe.npatch.ui.page.manage

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardCapslock
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.nkbe.npatch.R
import top.nkbe.npatch.BuildConfig
import top.nkbe.npatch.config.ConfigManager
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.database.entity.LoadedModule
import top.nkbe.npatch.manager.ModuleScopeSyncStore
import top.nkbe.npatch.manager.DiagnosticLogExporter
import top.nkbe.npatch.share.Constants
import top.nkbe.npatch.share.LSPConfig

import top.nkbe.npatch.ui.component.AccessibleMenuItem
import top.nkbe.npatch.ui.component.AppItem
import top.nkbe.npatch.ui.component.NPatchPullToRefresh
import top.nkbe.npatch.ui.page.ACTION_APPLIST
import top.nkbe.npatch.ui.page.ACTION_STORAGE
import top.nkbe.npatch.ui.page.Navigator
import top.nkbe.npatch.ui.page.Route
import top.nkbe.npatch.ui.page.SelectAppsResult
import top.nkbe.npatch.ui.viewmodel.manage.AppManageViewModel
import top.nkbe.npatch.ui.viewmodel.manage.ModuleManageViewModel
import top.nkbe.npatch.ui.viewstate.ProcessingState
import nkbe.util.NeoPackageManager
import nkbe.util.ShizukuApi
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.FloatingActionButton
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.basic.PopupPositionProvider
import io.github.suqi8.coui.kmp.basic.ScrollBehavior
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.rememberPullToRefreshState
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.overlay.OverlayDialog
import io.github.suqi8.coui.kmp.overlay.OverlayListPopup
import io.github.suqi8.coui.kmp.overlay.OverlayLoadingDialog
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.overScrollVertical
import io.github.suqi8.coui.kmp.utils.scrollEndHaptic
import java.io.IOException

private const val TAG = "AppManagePage"

@Composable
fun AppManageBody(
    navigator: Navigator,
    searchQuery: String = "",
    contentPadding: PaddingValues = PaddingValues(0.dp),
    scrollBehavior: ScrollBehavior,
    hazeState: HazeState
) {
    val viewModel = viewModel<AppManageViewModel>()
    val moduleManageViewModel = viewModel<ModuleManageViewModel>()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pullToRefreshState = rememberPullToRefreshState()
    val hapticFeedback = LocalHapticFeedback.current

    val filteredList = remember(viewModel.appList, searchQuery) {
        if (searchQuery.isEmpty()) viewModel.appList
        else viewModel.appList.filter {
            it.first.label.contains(searchQuery, true) ||
                    it.first.app.packageName.contains(searchQuery, true)
        }
    }

    val uninstallSuccessfully = stringResource(R.string.manage_uninstall_successfully)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                Toast.makeText(context, uninstallSuccessfully, Toast.LENGTH_SHORT).show()
                viewModel.dispatch(AppManageViewModel.ViewAction.Refresh)
            }
        }
    }

    val isProcessing = viewModel.updateLoaderState is ProcessingState.Processing 
            || viewModel.optimizeState is ProcessingState.Processing
            || viewModel.forceStopState is ProcessingState.Processing
            || viewModel.forceRestartState is ProcessingState.Processing
    if (isProcessing) {
        val showLoading = remember { mutableStateOf(true) }
        OverlayLoadingDialog(
            text = stringResource(R.string.manage_loading),
            show = showLoading.value,
            onDismissRequest = { /* 阻断取消，等待处理完成 */ }
        )
    }

    when (viewModel.updateLoaderState) {
        is ProcessingState.Idle -> Unit
        is ProcessingState.Processing -> Unit
        is ProcessingState.Done -> {
            val it = viewModel.updateLoaderState as ProcessingState.Done
            val updateSuccessfully = stringResource(R.string.manage_update_loader_successfully)
            val updateFailed = stringResource(R.string.manage_update_loader_failed)
            LaunchedEffect(Unit) {
                it.result.onSuccess {
                    Toast.makeText(context, updateSuccessfully, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, updateFailed, Toast.LENGTH_SHORT).show()
                }
                viewModel.dispatch(AppManageViewModel.ViewAction.ClearUpdateLoaderResult)
            }
        }
    }

    when (viewModel.optimizeState) {
        is ProcessingState.Idle -> Unit
        is ProcessingState.Processing -> Unit
        is ProcessingState.Done -> {
            val it = viewModel.optimizeState as ProcessingState.Done
            val optimizeSucceed = stringResource(R.string.manage_optimize_successfully)
            val optimizeFailed = stringResource(R.string.manage_optimize_failed)
            LaunchedEffect(Unit) {
                Toast.makeText(context, if (it.result) optimizeSucceed else optimizeFailed, Toast.LENGTH_SHORT).show()
                viewModel.dispatch(AppManageViewModel.ViewAction.ClearOptimizeResult)
            }
        }
    }

    when (viewModel.forceStopState) {
        is ProcessingState.Idle -> Unit
        is ProcessingState.Processing -> Unit
        is ProcessingState.Done -> {
            val it = viewModel.forceStopState as ProcessingState.Done
            val forceStopSucceed = stringResource(R.string.manage_force_stop_successfully)
            val forceStopFailed = stringResource(R.string.manage_force_stop_failed)
            LaunchedEffect(Unit) {
                Toast.makeText(context, if (it.result) forceStopSucceed else forceStopFailed, Toast.LENGTH_SHORT).show()
                viewModel.dispatch(AppManageViewModel.ViewAction.ClearForceStopResult)
            }
        }
    }

    when (viewModel.forceRestartState) {
        is ProcessingState.Idle -> Unit
        is ProcessingState.Processing -> Unit
        is ProcessingState.Done -> {
            val it = viewModel.forceRestartState as ProcessingState.Done
            val forceRestartSucceed = stringResource(R.string.manage_force_restart_successfully)
            val forceRestartFailed = stringResource(R.string.manage_force_restart_failed)
            LaunchedEffect(Unit) {
                Toast.makeText(context, if (it.result) forceRestartSucceed else forceRestartFailed, Toast.LENGTH_SHORT).show()
                viewModel.dispatch(AppManageViewModel.ViewAction.ClearForceRestartResult)
            }
        }
    }

    NPatchPullToRefresh(
        isRefreshing = viewModel.isRefreshing,
        onRefresh = { viewModel.dispatch(AppManageViewModel.ViewAction.Refresh) },
        pullToRefreshState = pullToRefreshState,
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .scrollEndHaptic()
                .overScrollVertical()
                .hazeSource(state = hazeState),
            contentPadding = contentPadding,
            overscrollEffect = null
        ) {
            if (filteredList.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (NeoPackageManager.appList.isEmpty()) {
                                InfiniteProgressIndicator()
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = stringResource(R.string.manage_loading),
                                    style = COUITheme.textStyles.body1,
                                    color = COUITheme.colorScheme.onSurfaceVariantSummary
                                )
                            } else {
                                Text(
                                    text = if (searchQuery.isNotEmpty()) stringResource(R.string.manage_no_search_results) else stringResource(R.string.manage_no_apps),
                                    style = COUITheme.textStyles.body1,
                                    color = COUITheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                }
            } else {
                items(
                    items = filteredList,
                    key = { it.first.app.packageName }
                ) { (appInfo, patchConfig) ->

                    val isLocal = patchConfig.useManager
                    val currentVersion = patchConfig.lspConfig.VERSION_CODE
                    val managerVersion = LSPConfig.instance.VERSION_CODE

                    val showVersionNumber = if (isLocal) {
                        currentVersion < Constants.MIN_ROLLING_VERSION_CODE
                    } else {
                        currentVersion != managerVersion
                    }

                    val canUpdateLoader = if (isLocal) {
                        currentVersion < Constants.MIN_ROLLING_VERSION_CODE
                    } else {
                        (currentVersion != managerVersion) || (patchConfig.managerPackageName != BuildConfig.APPLICATION_ID)
                    }

                    val showDropdown = remember { mutableStateOf(false) }
                    val scopeUpdatedText = stringResource(R.string.manage_module_scope_updated)

                    Box(modifier = Modifier.fillMaxWidth()) {
                        AppItem(
                            icon = {
                                Image(
                                    bitmap = NeoPackageManager.getIcon(appInfo),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                                )
                            },
                            label = appInfo.label,
                            packageName = appInfo.app.packageName,
                            summaryRow = {
                                val patchColor = if (isLocal) COUITheme.colorScheme.primary else COUITheme.colorScheme.onSurfaceVariantSummary

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // 补丁来源标记（NPatch / LSPatch(原子) / FPA / 女娲石）
                                    // 快速检测失败时用深度检测（原子会把 factory 混淆成 a.b.C）
                                    val srcType = remember(appInfo.app.packageName) {
                                        val fast = appInfo.patchedType
                                        if (fast != NeoPackageManager.PatchedType.NONE) fast
                                        else runCatching { NeoPackageManager.detectPatchedTypeDeep(appInfo) }
                                            .getOrDefault(NeoPackageManager.PatchedType.NONE)
                                    }
                                    if (srcType != NeoPackageManager.PatchedType.NONE) {
                                        val srcLabel = when (srcType) {
                                            NeoPackageManager.PatchedType.NPATCH -> "女娲石/NPatch"
                                            NeoPackageManager.PatchedType.LSPATCH -> "原子/LSPatch"
                                            NeoPackageManager.PatchedType.FPA -> "FPA"
                                            NeoPackageManager.PatchedType.ATOM -> "原子"
                                            NeoPackageManager.PatchedType.EMBEDDED -> "内嵌"
                                            else -> ""
                                        }
                                        if (srcLabel.isNotEmpty()) {
                                            Text(
                                                text = "[$srcLabel]",
                                                color = COUITheme.colorScheme.primary,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                        }
                                    }
                                    val modeLabel = if (isLocal) {
                                        "${stringResource(R.string.patch_local)} ${stringResource(R.string.manage_rolling)}"
                                    } else {
                                        stringResource(R.string.patch_integrated)
                                    }

                                    Text(
                                        text = modeLabel,
                                        color = patchColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Serif
                                    )

                                    if (showVersionNumber) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = currentVersion.toString(),
                                            color = patchColor,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = FontFamily.Serif
                                        )
                                    }

                                    if (canUpdateLoader) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        with(LocalDensity.current) {
                                            val size = 16.sp * 1.2
                                            Icon(
                                                imageVector = Icons.Filled.KeyboardCapslock,
                                                contentDescription = null,
                                                modifier = Modifier.size(size.toDp()),
                                                tint = patchColor
                                            )
                                        }
                                    }
                                }
                            },
                            onClick = {
                                showDropdown.value = true
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
                            },
                            onLongPress = {
                                showDropdown.value = true
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
                            }
                        )

                        OverlayListPopup(
                            show = showDropdown.value,
                            alignment = PopupPositionProvider.Align.End,
                            onDismissRequest = { showDropdown.value = false }
                        ) {
                            val actions = mutableListOf<Pair<String, () -> Unit>>()

                            if (canUpdateLoader || BuildConfig.DEBUG) {
                                actions.add(stringResource(R.string.manage_update_loader) to {
                                    scope.launch { viewModel.dispatch(AppManageViewModel.ViewAction.UpdateLoader(appInfo, patchConfig)) }
                                })
                            }
                            if (isLocal) {
                                actions.add(stringResource(R.string.manage_module_scope) to {
                                    viewModel.viewModelScope.launch {
                                        val targetAppPkg = appInfo.app.packageName
                                        // 原子/LSPatch 打的包：作用域读自 Android/media/<pkg>/lspatch_scope.json
                                        // 女娲石/NPatch 打的包：读自己的数据库
                                        val isAtomPatched = withContext(Dispatchers.IO) {
                                            runCatching { NeoPackageManager.detectPatchedTypeDeep(appInfo) }
                                                .getOrDefault(NeoPackageManager.PatchedType.NONE) ==
                                                NeoPackageManager.PatchedType.ATOM
                                        }
                                        val activated = withContext(Dispatchers.IO) {
                                            if (isAtomPatched) readLspatchScope(targetAppPkg)
                                            else ConfigManager.getModulesForApp(targetAppPkg).map { it.pkgName }.toSet()
                                        }

                                        val initialSelected = NeoPackageManager.appList.mapNotNull {
                                            if (activated.contains(it.app.packageName)) it.app.packageName else null
                                        }
                                        val result = navigator.navigateForResult<SelectAppsResult>(
                                            Route.SelectApps(true, initialSelected)
                                        )
                                        if (result is SelectAppsResult.MultipleApps) {
                                            withContext(Dispatchers.IO) {
                                                val previousModules = ConfigManager.getModulesForApp(targetAppPkg)
                                                val affectedPackages = buildSet {
                                                    previousModules.forEach { add(it.pkgName) }
                                                    result.selected.forEach { add(it.app.packageName) }
                                                }
                                                if (isAtomPatched) {
                                                    // 原子包：直接写 lspatch_scope.json
                                                    writeLspatchScope(targetAppPkg, result.selected.map { it.app.packageName }.toSet())
                                                } else {
                                                    previousModules.forEach {
                                                        ConfigManager.deactivateModule(targetAppPkg, it)
                                                    }
                                                    result.selected.forEach {
                                                        Log.d(TAG, "Activate ${it.app.packageName} for $targetAppPkg")
                                                        ConfigManager.activateModule(targetAppPkg, LoadedModule(it.app.packageName, it.app.sourceDir))
                                                    }
                                                }
                                                if (ShizukuApi.isReady) {
                                                    // Notify both removed and newly added modules so they do not
                                                    // keep stale scope state after a scope edit.
                                                    ModuleScopeSyncStore.syncModuleScopes(affectedPackages)
                                                }
                                            }
                                            moduleManageViewModel.refreshScopedActivationState()
                                            Toast.makeText(context, scopeUpdatedText, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                })
                            }
                            // 分身作用域（系统分身 = 用户 999）：仅当该应用真的存在于分身空间时才显示
                            val cloneUserIdForApp = remember(appInfo.app.packageName) {
                                findCloneUserId(appInfo.app.packageName)
                            }
                            if (cloneUserIdForApp != null) {
                            actions.add("设置分身作用域（用户 $cloneUserIdForApp）" to {
                                viewModel.viewModelScope.launch {
                                    val scopeUserId = cloneUserIdForApp
                                    val atomScope = withContext(Dispatchers.IO) { readLspatchScope(appInfo.app.packageName, scopeUserId) }
                                    val sel = NeoPackageManager.appList.mapNotNull {
                                        if (atomScope.contains(it.app.packageName)) it.app.packageName else null
                                    }
                                    val r = navigator.navigateForResult<SelectAppsResult>(
                                        Route.SelectApps(true, sel)
                                    )
                                    if (r is SelectAppsResult.MultipleApps) {
                                        withContext(Dispatchers.IO) {
                                            writeLspatchScope(appInfo.app.packageName, r.selected.map { it.app.packageName }.toSet(), scopeUserId)
                                        }
                                        moduleManageViewModel.refreshScopedActivationState()
                                        Toast.makeText(context, "分身作用域已保存（用户 $scopeUserId），重启分身应用生效", Toast.LENGTH_LONG).show()
                                    }
                                }
                            })
                            }
                            actions.add(stringResource(R.string.manage_export_diagnostics) to {
                                scope.launch {
                                    runCatching {
                                        val result = DiagnosticLogExporter.export(
                                            context,
                                            appInfo.app.packageName,
                                        )
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            result.file,
                                        )
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "application/zip"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            clipData = ClipData.newUri(
                                                context.contentResolver,
                                                result.file.name,
                                                uri,
                                            )
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }.let { shareIntent ->
                                            context.startActivity(
                                                Intent.createChooser(
                                                    shareIntent,
                                                    context.getString(R.string.manage_export_diagnostics_chooser),
                                                ),
                                            )
                                        }
                                    }.onFailure {
                                        Log.e(TAG, "Failed to export diagnostics for ${appInfo.app.packageName}", it)
                                        Toast.makeText(
                                            context,
                                            R.string.manage_export_diagnostics_failed,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            })
                            actions.add(stringResource(R.string.manage_repatch) to {
                                navigator.navigate(
                                    Route.NewPatch(
                                        id = ACTION_APPLIST,
                                        data = appInfo.app.packageName,
                                    )
                                )
                            })
                            val shizukuUnavailable = stringResource(R.string.shizuku_unavailable)
                            actions.add(stringResource(R.string.manage_optimize) to {
                                scope.launch {
                                    if (!ShizukuApi.isReady) {
                                        Toast.makeText(context, shizukuUnavailable, Toast.LENGTH_SHORT).show()
                                    } else {
                                        viewModel.dispatch(AppManageViewModel.ViewAction.PerformOptimize(appInfo))
                                    }
                                }
                            })
                            actions.add(stringResource(R.string.manage_force_stop) to {
                                if (ShizukuApi.isReady) {
                                    scope.launch { viewModel.dispatch(AppManageViewModel.ViewAction.PerformForceStop(appInfo)) }
                                } else {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = "package:${appInfo.app.packageName}".toUri()
                                    }
                                    context.startActivity(intent)
                                }
                            })
                            actions.add(stringResource(R.string.manage_force_restart) to {
                                if (ShizukuApi.isReady) {
                                    scope.launch {
                                        viewModel.dispatch(AppManageViewModel.ViewAction.PerformForceRestart(appInfo))
                                    }
                                } else {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = "package:${appInfo.app.packageName}".toUri()
                                    }
                                    context.startActivity(intent)
                                }
                            })
                            actions.add("打开应用" to {
                                runCatching {
                                    val launch = context.packageManager.getLaunchIntentForPackage(appInfo.app.packageName)
                                    if (launch != null) {
                                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(launch)
                                    } else {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = "package:${appInfo.app.packageName}".toUri()
                                        }
                                        context.startActivity(intent)
                                    }
                                }.onFailure {
                                    Toast.makeText(context, "无法启动应用", Toast.LENGTH_SHORT).show()
                                }
                            })
                            actions.add(stringResource(R.string.manage_app_info) to {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = "package:${appInfo.app.packageName}".toUri()
                                }
                                context.startActivity(intent)
                            })
                            actions.add(stringResource(R.string.uninstall) to {
                                val intent = Intent(Intent.ACTION_DELETE).apply {
                                    data = "package:${appInfo.app.packageName}".toUri()
                                    putExtra(Intent.EXTRA_RETURN_RESULT, true)
                                }
                                launcher.launch(intent)
                            })

                            ListPopupColumn {
                                actions.forEachIndexed { index, (text, action) ->
                                    AccessibleMenuItem(
                                        text = text,
                                        onClick = {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                            showDropdown.value = false
                                            action()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppManageFab(
    navigator: Navigator,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shouldSelectDirectory = remember { mutableStateOf(false) }
    val showNewPatchDialog = remember { mutableStateOf(false) }

    val errorText = stringResource(R.string.patch_select_dir_error)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        try {
            if (it.resultCode == Activity.RESULT_CANCELED) return@rememberLauncherForActivityResult
            val uri = it.data?.data ?: throw IOException("No data")
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            Configs.storageDirectory = uri.toString()
            Log.i(TAG, "Storage directory: ${uri.path}")
            showNewPatchDialog.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Error when requesting saving directory", e)
            Toast.makeText(context, errorText, Toast.LENGTH_SHORT).show()
        }
    }

    OverlayDialog(
        title = stringResource(R.string.patch_select_dir_title),
        summary = stringResource(R.string.patch_select_dir_text),
        show = shouldSelectDirectory.value,
        onDismissRequest = { shouldSelectDirectory.value = false },
    ) {
        DialogButtonBar(
            negative = DialogButtonBarAction(
                text = stringResource(android.R.string.cancel),
                onClick = { shouldSelectDirectory.value = false },
            ),
            positive = DialogButtonBarAction(
                text = stringResource(android.R.string.ok),
                onClick = {
                    launcher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
                    shouldSelectDirectory.value = false
                },
            ),
        )
    }

    OverlayDialog(
        title = stringResource(R.string.screen_new_patch),
        show = showNewPatchDialog.value,
        onDismissRequest = { showNewPatchDialog.value = false },
    ) {
        DialogButtonBar(
            neutral = DialogButtonBarAction(
                text = stringResource(R.string.patch_from_storage),
                onClick = {
                    navigator.navigate(Route.NewPatch(id = ACTION_STORAGE))
                    showNewPatchDialog.value = false
                },
            ),
            positive = DialogButtonBarAction(
                text = stringResource(R.string.patch_from_applist),
                onClick = {
                    navigator.navigate(Route.NewPatch(id = ACTION_APPLIST))
                    showNewPatchDialog.value = false
                },
            ),
            negative = DialogButtonBarAction(
                text = stringResource(android.R.string.cancel),
                onClick = { showNewPatchDialog.value = false },
            ),
        )
    }

    val addLabel = stringResource(R.string.add)
    FloatingActionButton(
        modifier = modifier.semantics(mergeDescendants = true) {
            role = Role.Button
            this.contentDescription = addLabel
        },
        onClick = {
            val uri = Configs.storageDirectory?.toUri()
            if (uri == null) {
                shouldSelectDirectory.value = true
            } else {
                runCatching {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                    if (DocumentFile.fromTreeUri(context, uri)?.exists() == false) throw IOException("Storage directory was deleted")
                }.onSuccess {
                    showNewPatchDialog.value = true
                }.onFailure {
                    Log.w(TAG, "Failed to take persistable permission for saved uri", it)
                    Configs.storageDirectory = null
                    shouldSelectDirectory.value = true
                }
            }
        }
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = COUITheme.colorScheme.onPrimary
        )
    }
}

/**
 * 读取原子/LSPatch 系框架的作用域配置
 * 路径: /storage/emulated/0/Android/media/<包名>/lspatch_scope.json
 * 格式: {"modules": ["模块包名1", "模块包名2"]}
 */
private fun readLspatchScope(appPackageName: String, userId: Int = 0): Set<String> = runCatching {
    val f = lspatchScopeFile(appPackageName, userId)
    if (!f.isFile) return@runCatching emptySet<String>()
    val text = f.readText()
    val arr = org.json.JSONObject(text).optJSONArray("modules") ?: return@runCatching emptySet<String>()
    buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
}.getOrDefault(emptySet())

/**
 * 写入原子/LSPatch 系框架的作用域配置
 */
private fun writeLspatchScope(appPackageName: String, modules: Set<String>, userId: Int = 0): Boolean = runCatching {
    val dir = lspatchScopeFile(appPackageName, userId).parentFile!!
    if (!dir.exists()) dir.mkdirs()
    val f = java.io.File(dir, "lspatch_scope.json")
    val obj = org.json.JSONObject()
    val arr = org.json.JSONArray()
    modules.sorted().forEach { arr.put(it) }
    obj.put("modules", arr)
    f.writeText(obj.toString())
    true
}.getOrDefault(false)

/**
 * 构造 lspatch_scope.json 路径，支持系统分身（多用户）
 * userId=0 → /storage/emulated/0/Android/media/<pkg>/lspatch_scope.json
 * userId=999 → /storage/emulated/999/Android/media/<pkg>/lspatch_scope.json
 */
private fun lspatchScopeFile(appPackageName: String, userId: Int): java.io.File {
    val base = if (userId == 0) {
        android.os.Environment.getExternalStorageDirectory()
    } else {
        java.io.File("/storage/emulated/$userId")
    }
    return java.io.File(base, "Android/media/$appPackageName/lspatch_scope.json")
}

/**
 * 检测某个包名是否存在于系统分身（多用户）空间，返回 userId（如 999），不存在返回 null
 * 判断依据：/data/user/<uid>/<pkg> 或 /storage/emulated/<uid>/Android/media 无法直接读，
 * 这里用「作用域文件所在父目录可写/存在」或 PackageManager 多用户查询兜底。
 */
private fun findCloneUserId(packageName: String): Int? = runCatching {
    // 1) 尝试常见分身 userId（MIUI/ColorOS 等常见 999，其次 10、11）
    val candidates = listOf(999, 10, 11, 12)
    for (uid in candidates) {
        val mediaDir = java.io.File("/storage/emulated/$uid/Android/media/$packageName")
        if (mediaDir.exists()) return uid
        // 有些系统分身没有 media 目录，但已安装；用 pm 查（可能无权限）
        val ctx = lspApp
        @Suppress("DiscouragedPrivateApi")
        val pm = ctx.packageManager
        val info = runCatching {
            val m = pm.javaClass.getMethod("getPackageInfoAsUser", String::class.java, Int::class.java, Int::class.java)
            m.invoke(pm, packageName, 0, uid)
        }.getOrNull()
        if (info != null) return uid
    }
    null
}.getOrNull()
