package dev.dshn.app

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.dshn.app.data.BannerAction
import dev.dshn.app.navigation.DshnNavHost
import dev.dshn.core.ui.DshnTheme
import dev.dshn.core.ui.LocalIsZh
import dev.dshn.core.ui.resolveIsDarkTheme
import dev.dshn.feature.bridge.DeviceBridgeConfirmDialog
import dev.dshn.feature.chat.AcpPermissionDialog
import dev.dshn.feature.chat.ChatRoute
import dev.dshn.feature.chat.UserQuestionDialog
import dev.dshn.feature.settings.SettingsDiagnosticsRoute
import dev.dshn.feature.settings.SettingsEnvironmentRoute
import dev.dshn.feature.settings.SettingsModelRoute
import dev.dshn.feature.setup.SetupRoute

class MainActivity : ComponentActivity() {

    private val appContainer by lazy { (application as DshnApplication).appContainer }
    private val appVm: AppViewModel by viewModels { appContainer.appViewModelFactory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by appVm.settings.collectAsStateWithLifecycle()
            val banner by appVm.statusBanner.collectAsStateWithLifecycle()
            val pendingPermission by appVm.pendingPermission.collectAsStateWithLifecycle()
            val pendingQuestion by appVm.pendingQuestion.collectAsStateWithLifecycle()
            val pendingBridgeConfirm by appVm.pendingBridgeConfirm.collectAsStateWithLifecycle()

            val navController = rememberNavController()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val isChatDestination = navBackStackEntry?.destination?.hasRoute<ChatRoute>() == true
            val startDestination = remember {
                if (appContainer.resolveInitialSetupRequired()) SetupRoute(reopened = false) else ChatRoute
            }

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
                            BannerAction.RESTART_RUNTIME -> appVm.restartRuntime()
                            BannerAction.OPEN_LOGS -> navController.navigate(SettingsDiagnosticsRoute) { launchSingleTop = true }
                            BannerAction.OPEN_MODEL_SETTINGS -> navController.navigate(SettingsModelRoute) { launchSingleTop = true }
                            BannerAction.OPEN_ENVIRONMENT -> navController.navigate(SettingsEnvironmentRoute) { launchSingleTop = true }
                            null -> {}
                        }
                    }
                    appVm.dismissBanner(b.id)
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    DshnNavHost(
                        navController = navController,
                        appContainer = appContainer,
                        activityOwner = this@MainActivity,
                        startDestination = startDestination,
                        onComposerHeight = { composerHeight = it },
                        modifier = Modifier.fillMaxSize()
                    )

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(bottom = if (isChatDestination) composerHeight + 8.dp else 12.dp)
                    )
                }

                pendingPermission?.let { req ->
                    AcpPermissionDialog(
                        req = req,
                        sessionLabel = appVm.resolveSessionDisplayLabel(req.sessionId),
                        onSelect = { optId -> appVm.replyAcpPermission(optId, cancelled = false) },
                        onCancel = { appVm.replyAcpPermission(null, cancelled = true) }
                    )
                }

                pendingBridgeConfirm?.let { req ->
                    DeviceBridgeConfirmDialog(
                        req = req,
                        onDecision = { allow -> appVm.respondBridgeConfirmation(allow) }
                    )
                }

                pendingQuestion?.let { req ->
                    UserQuestionDialog(
                        req = req,
                        sessionLabel = appVm.resolveSessionDisplayLabel(req.sessionId),
                        onSubmit = { answers -> appVm.replyUserQuestion(answers, cancelled = false) },
                        onCancel = { appVm.replyUserQuestion(emptyList(), cancelled = true) }
                    )
                }
            }
        }
    }
}
