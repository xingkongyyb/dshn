package dev.dshn.app

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshn.app.ui.AcpPermissionDialog
import dev.dshn.app.ui.AppStrings
import dev.dshn.app.ui.ChatScreen
import dev.dshn.app.ui.DeviceBridgeConfirmDialog
import dev.dshn.app.ui.DeviceLabScreen
import dev.dshn.app.ui.DshnTheme
import dev.dshn.app.ui.LocalDshnColors
import dev.dshn.app.ui.MainTab
import dev.dshn.app.ui.MainViewModel
import dev.dshn.app.ui.SessionsScreen
import dev.dshn.app.ui.SettingsScreen
import dev.dshn.app.ui.TerminalScreen
import dev.dshn.app.ui.UserQuestionDialog
import dev.dshn.app.ui.resolveIsDarkTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by vm.settings.collectAsState()
            val selectedTab by vm.selectedTab.collectAsState()
            val banner by vm.statusBanner.collectAsState()
            val pendingPermission by vm.pendingPermission.collectAsState()
            val pendingQuestion by vm.pendingQuestion.collectAsState()
            val pendingBridgeConfirm by vm.pendingBridgeConfirm.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }
            val strings = AppStrings.resolve(settings.localeMode)
            val isDark = resolveIsDarkTheme(settings.themeMode)

            LaunchedEffect(isDark) {
                if (isDark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
                        navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    )
                } else {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.light(
                            AndroidColor.TRANSPARENT,
                            AndroidColor.TRANSPARENT
                        ),
                        navigationBarStyle = SystemBarStyle.light(
                            AndroidColor.TRANSPARENT,
                            AndroidColor.TRANSPARENT
                        )
                    )
                }
            }

            LaunchedEffect(banner) {
                val msg = banner
                if (!msg.isNullOrBlank()) {
                    snackbarHostState.showSnackbar(msg)
                    vm.dismissBanner()
                }
            }

            DshnTheme(themeMode = settings.themeMode) {
                val semantic = LocalDshnColors.current
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        Column {
                            HorizontalDivider(thickness = 1.dp, color = semantic.cardBorder)
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 0.dp
                            ) {
                                val navItems = listOf(
                                    Triple(MainTab.CHAT, Icons.AutoMirrored.Filled.Chat, strings.tabChat),
                                    Triple(MainTab.SESSIONS, Icons.Default.Forum, strings.tabSessions),
                                    Triple(MainTab.TERMINAL, Icons.Default.Terminal, strings.tabTerminal),
                                    Triple(MainTab.LAB, Icons.Default.DeveloperBoard, strings.tabLab),
                                    Triple(MainTab.SETTINGS, Icons.Default.Settings, strings.tabSettings)
                                )
                                for ((tab, icon, label) in navItems) {
                                    NavigationBarItem(
                                        selected = selectedTab == tab,
                                        onClick = { vm.selectTab(tab) },
                                        icon = { Icon(icon, contentDescription = label) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        label = {
                                            Text(
                                                text = label,
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (selectedTab) {
                            MainTab.CHAT -> ChatScreen(vm = vm, strings = strings)
                            MainTab.SESSIONS -> SessionsScreen(vm = vm, strings = strings)
                            MainTab.TERMINAL -> TerminalScreen(vm = vm, strings = strings)
                            MainTab.LAB -> DeviceLabScreen(vm = vm, strings = strings)
                            MainTab.SETTINGS -> SettingsScreen(vm = vm, strings = strings)
                        }
                    }

                    pendingPermission?.let { req ->
                        AcpPermissionDialog(
                            req = req,
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
                            onSubmit = { answers -> vm.replyUserQuestion(answers, cancelled = false) },
                            onCancel = { vm.replyUserQuestion(emptyList(), cancelled = true) }
                        )
                    }
                }
            }
        }
    }
}
