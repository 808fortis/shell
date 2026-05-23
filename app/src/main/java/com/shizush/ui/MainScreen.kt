package com.shizush.ui

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.shizush.service.ShellService
import com.shizush.shizuku.ShizukuManager
import com.shizush.shizuku.ShellProvider
import com.shizush.shizuku.WirelessDebugInfo
import com.shizush.ui.components.TerminalLine
import com.shizush.ui.components.TerminalView
import com.shizush.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    shizukuManager: ShizukuManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showSettings by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf(ShellProvider.NONE) }
    var lines by remember { mutableStateOf(listOf<TerminalLine>()) }
    var inputValue by remember { mutableStateOf(TextFieldValue("")) }
    var isExecuting by remember { mutableStateOf(false) }
    var commandHistory by remember { mutableStateOf(listOf<String>()) }
    var historyIndex by remember { mutableIntStateOf(-1) }
    val wirelessInfo by shizukuManager.wirelessInfo.collectAsState()

    LaunchedEffect(Unit) {
        provider = shizukuManager.initialize()

        val welcome = buildString {
            appendLine("\u2554\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2557")
            appendLine("\u2551          Shell Terminal             \u2551")
            appendLine("\u2560\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2563")
            appendLine("\u2551  Mode: ${provider.name.padEnd(27)}\u2551")
            appendLine("\u255A\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u255D")
        }
        lines = listOf(TerminalLine(welcome))

        val intent = Intent(context, ShellService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Primary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Terminal,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Shell", fontWeight = FontWeight.Bold)
                            Text(
                                provider.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = provider.statusColor,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            shizukuManager.refreshWirelessInfo()
                            provider = shizukuManager.initialize()
                        }
                    }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { lines = emptyList() }) {
                        Icon(
                            Icons.Outlined.DeleteSweep,
                            contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            StatusCard(
                provider = provider,
                wirelessInfo = wirelessInfo,
                onProviderClick = { showSettings = true },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            TerminalView(
                lines = lines,
                inputValue = inputValue,
                onInputChange = { inputValue = it },
                onExecute = { command ->
                    val cmd = command.trim()
                    lines = lines + TerminalLine(cmd, isInput = true)
                    commandHistory = commandHistory + cmd
                    historyIndex = -1
                    inputValue = TextFieldValue("")
                    isExecuting = true

                    scope.launch {
                        try {
                            val result = shizukuManager.executeCommand(cmd)
                            lines = lines + TerminalLine(result.output)
                        } catch (e: Exception) {
                            lines = lines + TerminalLine(
                                "Error: ${e.message}",
                                isError = true
                            )
                        } finally {
                            isExecuting = false
                        }
                    }
                },
                onHistoryUp = {
                    if (commandHistory.isNotEmpty()) {
                        val newIndex = if (historyIndex == -1) {
                            commandHistory.size - 1
                        } else {
                            maxOf(0, historyIndex - 1)
                        }
                        historyIndex = newIndex
                        inputValue = TextFieldValue(commandHistory[newIndex])
                    }
                },
                onHistoryDown = {
                    if (historyIndex != -1) {
                        val newIndex = historyIndex + 1
                        if (newIndex >= commandHistory.size) {
                            historyIndex = -1
                            inputValue = TextFieldValue("")
                        } else {
                            historyIndex = newIndex
                            inputValue = TextFieldValue(commandHistory[newIndex])
                        }
                    }
                },
                isExecuting = isExecuting,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showSettings) {
        SettingsBottomSheet(
            sheetState = sheetState,
            provider = provider,
            wirelessInfo = wirelessInfo,
            onDismiss = { showSettings = false },
            onSelectProvider = { selected ->
                when (selected) {
                    ShellProvider.SHIZUKU -> {
                        if (!shizukuManager.isShizukuPermissionGranted()) {
                            shizukuManager.requestShizukuPermission()
                        }
                        scope.launch { provider = shizukuManager.initialize() }
                    }
                    ShellProvider.WIRELESS_DEBUGGING -> {
                        if (wirelessInfo.enabled) {
                            scope.launch { provider = ShellProvider.WIRELESS_DEBUGGING }
                        } else {
                            shizukuManager.openDeveloperSettings()
                        }
                    }
                    ShellProvider.DIRECT -> {
                        scope.launch { provider = ShellProvider.DIRECT }
                    }
                    else -> {}
                }
                showSettings = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusCard(
    provider: ShellProvider,
    wirelessInfo: WirelessDebugInfo,
    onProviderClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onProviderClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(provider.containerColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = provider.icon,
                    contentDescription = null,
                    tint = provider.statusColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = provider.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (provider == ShellProvider.WIRELESS_DEBUGGING && wirelessInfo.enabled) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${wirelessInfo.ipAddress}:${wirelessInfo.port}",
                        style = MaterialTheme.typography.labelMedium,
                        color = StatusConnected,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(provider.statusColor)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Tap to change",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsBottomSheet(
    sheetState: SheetState,
    provider: ShellProvider,
    wirelessInfo: WirelessDebugInfo,
    onDismiss: () -> Unit,
    onSelectProvider: (ShellProvider) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Shell Provider",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            SettingsProviderCard(
                icon = Icons.Filled.Shield,
                title = "Shizuku",
                subtitle = "Run commands with elevated privileges via Shizuku service",
                selected = provider == ShellProvider.SHIZUKU,
                onClick = { onSelectProvider(ShellProvider.SHIZUKU) },
            )
            Spacer(Modifier.height(8.dp))

            SettingsProviderCard(
                icon = Icons.Filled.DeveloperMode,
                title = "Wireless Debugging",
                subtitle = if (wirelessInfo.enabled)
                    "Connected via ADB over TCP/IP (${wirelessInfo.ipAddress}:${wirelessInfo.port})"
                else
                    "Connect via ADB over TCP/IP - enable in Developer Options",
                selected = provider == ShellProvider.WIRELESS_DEBUGGING,
                onClick = { onSelectProvider(ShellProvider.WIRELESS_DEBUGGING) },
                trailing = if (wirelessInfo.enabled) {
                    {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(StatusConnected)
                        )
                    }
                } else null,
            )
            Spacer(Modifier.height(8.dp))

            SettingsProviderCard(
                icon = Icons.Filled.Terminal,
                title = "Direct Shell",
                subtitle = "Basic shell execution with app-level permissions only",
                selected = provider == ShellProvider.DIRECT,
                onClick = { onSelectProvider(ShellProvider.DIRECT) },
            )
            Spacer(Modifier.height(24.dp))

            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { /* clear terminal action */ },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        Icons.Outlined.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Clear Terminal")
                }
                FilledTonalButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun SettingsProviderCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (selected)
            androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            )
        else null,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (trailing != null) {
                trailing()
                Spacer(Modifier.width(8.dp))
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

private val Primary = TerminalBlue

private val ShellProvider.displayName: String
    get() = when (this) {
        ShellProvider.SHIZUKU -> "Shizuku"
        ShellProvider.WIRELESS_DEBUGGING -> "Wireless Debugging"
        ShellProvider.DIRECT -> "Direct Shell"
        ShellProvider.NONE -> "Disconnected"
    }

private val ShellProvider.description: String
    get() = when (this) {
        ShellProvider.SHIZUKU -> "Elevated shell via Shizuku"
        ShellProvider.WIRELESS_DEBUGGING -> "Shell via ADB over TCP/IP"
        ShellProvider.DIRECT -> "Basic app-level shell"
        ShellProvider.NONE -> "No provider selected"
    }

private val ShellProvider.statusColor: Color
    get() = when (this) {
        ShellProvider.SHIZUKU -> StatusConnected
        ShellProvider.WIRELESS_DEBUGGING -> StatusConnected
        ShellProvider.DIRECT -> StatusWarning
        ShellProvider.NONE -> StatusDisconnected
    }

private val ShellProvider.containerColor: Color
    get() = when (this) {
        ShellProvider.SHIZUKU -> TerminalBlue.copy(alpha = 0.15f)
        ShellProvider.WIRELESS_DEBUGGING -> TerminalCyan.copy(alpha = 0.15f)
        ShellProvider.DIRECT -> StatusWarning.copy(alpha = 0.15f)
        ShellProvider.NONE -> StatusDisconnected.copy(alpha = 0.15f)
    }

private val ShellProvider.icon: ImageVector
    get() = when (this) {
        ShellProvider.SHIZUKU -> Icons.Filled.Shield
        ShellProvider.WIRELESS_DEBUGGING -> Icons.Filled.DeveloperMode
        ShellProvider.DIRECT -> Icons.Filled.Terminal
        ShellProvider.NONE -> Icons.Outlined.QuestionMark
    }
