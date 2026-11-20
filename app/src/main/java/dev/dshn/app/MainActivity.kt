package dev.dshn.app

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.dshn.app.ui.AboutScreen
import dev.dshn.app.ui.AcpPermissionDialog
import dev.dshn.app.ui.AppRoute
import dev.dshn.app.ui.AppStrings
import dev.dshn.app.ui.BackgroundSettingsScreen
import dev.dshn.app.ui.BannerAction
import dev.dshn.app.ui.ChatScreen
import dev.dshn.app.ui.DeviceBridgeConfirmDialog
import dev.dshn.app.ui.DeviceBridgeScreen
import dev.dshn.app.ui.DeviceLabScreen
import dev.dshn.app.ui.DiagnosticsScreen
import dev.dshn.app.ui.DshnTheme
import dev.dshn.app.ui.EnvironmentSettingsScreen
import dev.dshn.app.ui.LocalIsZh
import dev.dshn.app.ui.MainViewModel
import dev.dshn.app.ui.ModelSettingsScreen
import dev.dshn.app.ui.PermissionSettingsScreen
import dev.dshn.app.ui.SecondaryScaffold
import dev.dshn.app.ui.SettingsHomeScreen
import dev.dshn.app.ui.SetupScreen
import dev.dshn.app.ui.TerminalScreen
import dev.dshn.app.ui.UserQuestionDialog
import dev.dshn.app.ui.resolveIsDarkTheme
import dev.dshn.app.ui.tr

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by vm.settings.collectAsState()
            val backStack by vm.backStack.collectAsState()
            val setupRequired by vm.setupRequired.collectAsState()
            val banner by vm.statusBanner.collectAsState()
            val pendingPermission by vm.pendingPermission.collectAsState()
            val pendingQuestion by vm.pendingQuestion.collectAsState()
            val pendingBridgeConfirm by vm.pendingBridgeConfirm.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }
            var composerHeight by remember { mutableStateOf(0.dp) }
            val isDark = resolveIsDarkTheme(settings.themeMode)

            LaunchedEffect(isDark) {
                if (isDark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    )
                } else {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    )
                }
            }

            DshnTheme(themeMode = settings.themeMode, localeMode = settings.localeMode) {
                val isZh = LocalIsZh.current
                val strings = AppStrings.resolve(settings.localeMode)
                val saveableHolder = rememberSaveableStateHolder()
                val route = backStack.last()

                LaunchedEffect(banner?.id) {
                    val b = banner ?: return@LaunchedEffect
                    val actionLabel = when (b.action) {
                        BannerAction.RESTART_RUNTIME -> if (isZh) "立即重启" else "Restart"
                        BannerAction.OPEN_LOGS -> if (isZh) "查看日志" else "Logs"
                        BannerAction.OPEN_MODEL_SETTINGS -> if (isZh) "去设置" else "Set up"
                        BannerAction.OPEN_ENVIRONMENT -> if (isZh) "查看" else "View"
                        null -> null
                    }
                    val result = snackbarHostState.showSnackbar(
                        message = b.text,
                        actionLabel = actionLabel,
                        duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        when (b.action) {
                            BannerAction.RESTART_RUNTIME -> vm.restartRuntime()
                            BannerAction.OPEN_LOGS -> vm.navigate(AppRoute.SETTINGS_DIAGNOSTICS)
                            BannerAction.OPEN_MODEL_SETTINGS -> vm.navigate(AppRoute.SETTINGS_MODEL)
                            BannerAction.OPEN_ENVIRONMENT -> vm.navigate(AppRoute.SETTINGS_ENVIRONMENT)
                            null -> {}
                        }
                    }
                    vm.dismissBanner(b.id)
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    if (setupRequired) {
                        SetupScreen(vm)
                    } else {
                        BackHandler(enabled = backStack.size > 1) { vm.navigateBack() }
                        AnimatedContent(
                            targetState = route to backStack.size,
                            transitionSpec = {
                                val forward = targetState.second >= initialState.second
                                (slideInHorizontally { w -> if (forward) w / 5 else -w / 5 } + fadeIn()) togetherWith
                                    (slideOutHorizontally { w -> if (forward) -w / 8 else w / 5 } + fadeOut())
                            },
                            contentKey = { it.first },
                            label = "route"
                        ) { (r, _) ->
                            saveableHolder.SaveableStateProvider(r.name) {
                                when (r) {
                                    AppRoute.CHAT -> ChatScreen(vm, onComposerHeight = { composerHeight = it })
                                    AppRoute.TERMINAL -> TerminalScreen(vm)
                                    AppRoute.DEVICE_BRIDGE -> DeviceBridgeScreen(vm)
                                    AppRoute.DEVICE_LAB -> SecondaryScaffold(
                                        title = tr("设备桥调试台", "Device bridge lab"),
                                        onBack = { vm.navigateBack() }
                                    ) { inner ->
                                        DeviceLabScreen(vm = vm, strings = strings, modifier = Modifier.padding(inner))
                                    }
                                    AppRoute.SETTINGS -> SettingsHomeScreen(vm)
                                    AppRoute.SETTINGS_MODEL -> ModelSettingsScreen(vm)
                                    AppRoute.SETTINGS_PERMISSION -> PermissionSettingsScreen(vm)
                                    AppRoute.SETTINGS_BACKGROUND -> BackgroundSettingsScreen(vm)
                                    AppRoute.SETTINGS_ENVIRONMENT -> EnvironmentSettingsScreen(vm)
                                    AppRoute.SETTINGS_DIAGNOSTICS -> DiagnosticsScreen(vm)
                                    AppRoute.ABOUT -> AboutScreen(vm)
                                }
                            }
                        }
                    }

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(bottom = if (!setupRequired && route == AppRoute.CHAT) composerHeight + 8.dp else 12.dp)
                    )
                }

                pendingPermission?.let { req ->
                    AcpPermissionDialog(
                        req = req,
                        sessionLabel = vm.resolveSessionDisplayLabel(req.sessionId),
                        onSelect = { optId -> vm.replyAcpPermission(optId, cancelled = false) },
                        onCancel = { vm.replyAcpPermission(null, cancelled = true) }
                    )
                }

                pendingBridgeConfirm?.let { req ->
                    DeviceBridgeConfirmDialog(
                        req = req,
                        onDecision = { allow -> vm.respondBridgeConfirmation(allow) }
                    )
                }

                pendingQuestion?.let { req ->
                    UserQuestionDialog(
                        req = req,
                        sessionLabel = vm.resolveSessionDisplayLabel(req.sessionId),
                        onSubmit = { answers -> vm.replyUserQuestion(answers, cancelled = false) },
                        onCancel = { vm.replyUserQuestion(emptyList(), cancelled = true) }
                    )
                }
            }
        }
    }
}
