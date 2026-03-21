package com.modemdoctor

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.modemdoctor.core.ModemMonitorService
import com.modemdoctor.ui.MainViewModel
import com.modemdoctor.ui.theme.ModemDoctorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ModemDoctorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val hasRoot by viewModel.hasRoot.collectAsState()
    val isMonitoring by viewModel.isMonitoring.collectAsState()
    val githubToken by viewModel.githubToken.collectAsState()
    val tokenValid by viewModel.githubTokenValid.collectAsState()
    val logProgress by viewModel.logCollectionProgress.collectAsState()
    val lastGistUrl by viewModel.lastGistUrl.collectAsState()
    val events by viewModel.events.collectAsState()
    val networkLossCount by viewModel.networkLossCount.collectAsState()
    val autoUploadStatus by viewModel.autoUploadStatus.collectAsState()
    val disable5GStatus by viewModel.disable5GStatus.collectAsState()
    val volteStatus by viewModel.volteStatus.collectAsState()
    val ultra3GStatus by viewModel.ultra3GStatus.collectAsState()
    val chargeLimitEnabled by viewModel.chargeLimitEnabled.collectAsState()
    val batteryMessage by viewModel.batteryMessage.collectAsState()
    
    var showTokenDialog by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf("") }
    
    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(context, "Some permissions denied - functionality may be limited", Toast.LENGTH_LONG).show()
        }
    }
    
    // Initialize
    LaunchedEffect(Unit) {
        viewModel.checkRootAccess()
        
        val missingPermissions = viewModel.checkPermissions(context)
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
    
    // Sync monitoring state
    LaunchedEffect(isMonitoring) {
        // Update local state from service
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CellTower,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Modem Doctor")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Root Status Card
            item {
                StatusCard(
                    title = "Root Access",
                    status = when (hasRoot) {
                        true -> "Granted ✓"
                        false -> "Not Available ✗"
                        null -> "Checking..."
                    },
                    color = when (hasRoot) {
                        true -> Color(0xFF4CAF50)
                        false -> Color(0xFFF44336)
                        null -> Color(0xFFFFC107)
                    }
                )
            }
            
            // Battery Protection Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (chargeLimitEnabled) 
                            Color(0xFFE8F5E9) 
                        else 
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.BatteryStd,
                                    contentDescription = null,
                                    tint = if (chargeLimitEnabled) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        "Защита батареи",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "Зарядка до 80%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            
                            Switch(
                                checked = chargeLimitEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.toggleChargeLimit(enabled)
                                },
                                enabled = hasRoot == true
                            )
                        }
                        
                        if (batteryMessage != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                batteryMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (batteryMessage!!.contains("✓") || batteryMessage!!.contains("включено")) 
                                    Color(0xFF4CAF50) 
                                else 
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Продлевает срок службы аккумулятора, ограничивая максимальный уровень заряда.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // GitHub Token Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "GitHub Integration",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = { showTokenDialog = true }) {
                                Icon(Icons.Default.Settings, "Configure token")
                            }
                        }
                        
                        when {
                            githubToken.isBlank() -> {
                                Text(
                                    "Configure GitHub token to auto-upload logs",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            tokenValid == true -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF4CAF50),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "Token configured",
                                        color = Color(0xFF4CAF50)
                                    )
                                }
                            }
                            tokenValid == false -> {
                                Text(
                                    "Token may be invalid",
                                    color = Color(0xFFF44336)
                                )
                            }
                            else -> {
                                Text("Validating token...")
                            }
                        }
                    }
                }
            }
            
            // Monitoring Controls
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Network Monitoring",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        
                        if (networkLossCount > 0) {
                            Text(
                                "Network losses detected: $networkLossCount",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFF44336)
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        
                        autoUploadStatus?.let { status ->
                            Text(
                                "Auto-upload: $status",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (status.contains("SUCCESS") || status.contains("Uploaded:")) 
                                    Color(0xFF4CAF50) 
                                else if (status.contains("Error") || status.contains("failed")) 
                                    Color(0xFFF44336) 
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.startMonitoring(context) },
                                enabled = hasRoot == true && !isMonitoring,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF4CAF50)
                                )
                            ) {
                                Icon(Icons.Default.PlayArrow, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Start")
                            }
                            
                            Button(
                                onClick = { viewModel.stopMonitoring(context) },
                                enabled = isMonitoring,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFF44336)
                                )
                            ) {
                                Icon(Icons.Default.Stop, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Stop")
                            }
                        }
                    }
                }
            }
            
            // Log Collection
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Diagnostics",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        
                        Button(
                            onClick = { viewModel.collectLogs(context) },
                            enabled = hasRoot == true && uiState !is MainViewModel.UiState.CollectingLogs,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (uiState is MainViewModel.UiState.CollectingLogs) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Collecting... ${(logProgress * 100).toInt()}%")
                            } else {
                                Icon(Icons.Default.BugReport, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Collect Logs Now")
                            }
                        }
                        
                        if (logProgress > 0 && logProgress < 1) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = logProgress,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        
                        lastGistUrl?.let { url ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Last log uploaded:",
                                style = MaterialTheme.typography.bodySmall
                            )
                            SelectionContainer {
                                Text(
                                    url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
            
            // Quick Actions
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Quick Actions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        
                        OutlinedButton(
                            onClick = { 
                                viewModel.triggerProblemLog(context, "Manual trigger - no signal")
                                Toast.makeText(context, "Collecting logs...", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Warning, null, tint = Color(0xFFFF9800))
                            Spacer(Modifier.width(8.dp))
                            Text("Problem: No Signal")
                        }
                        
                        Spacer(Modifier.height(4.dp))
                        
                        OutlinedButton(
                            onClick = { 
                                viewModel.triggerProblemLog(context, "Manual trigger - network stuck")
                                Toast.makeText(context, "Collecting logs...", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.SignalCellularAlt, null, tint = Color(0xFFFF9800))
                            Spacer(Modifier.width(8.dp))
                            Text("Problem: Network Stuck")
                        }
                    }
                }
            }
            
            // 5G Settings
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0x33E91E63)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.SignalCellularOff,
                                contentDescription = null,
                                tint = Color(0xFFE91E63),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "5G Settings",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        
                        Text(
                            "Disable 5G to prevent modem crashes on Pixel 6 series",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        
                        Button(
                            onClick = { 
                                viewModel.disable5G(context)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE91E63)
                            )
                        ) {
                            Icon(Icons.Default.Cancel, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Disable 5G Completely")
                        }
                        
                        disable5GStatus?.let { status ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (status.contains("success", ignoreCase = true)) 
                                    Color(0xFF4CAF50) 
                                else 
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            // ULTRA 3G DISABLE - САМЫЙ ЖЁСТКИЙ МЕТОД!
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0x33FF5722)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Block,
                                contentDescription = null,
                                tint = Color(0xFFFF5722),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "⚠️ ULTRA DISABLE 3G/WCDMA",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        
                        Text(
                            "САМЫЙ ЖЁСТКИЙ МЕТОД для Pixel 6 с Exynos модемом!",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFF5722),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "• Отключает CS Fallback (звонки не упадут в 3G)\n" +
                            "• Блокирует WCDMA scanning полностью\n" +
                            "• wcdma_supported = 0 (модем не будет искать 3G)\n" +
                            "• Форсирует IMS/VoLTE\n" +
                            "⚠️ ТРЕБУЕТСЯ ПЕРЕЗАГРУЗКА!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.ultraDisable3G(context) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF5722)
                                )
                            ) {
                                Icon(Icons.Default.Block, null)
                                Spacer(Modifier.width(4.dp))
                                Text("ULTRA\nDISABLE", maxLines = 2, fontSize = 12.sp)
                            }
                            
                            OutlinedButton(
                                onClick = { viewModel.check3GDisableStatus(context) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Info, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Check\nStatus", maxLines = 2, fontSize = 12.sp)
                            }
                        }
                        
                        ultra3GStatus?.let { status ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (status.contains("✓")) 
                                    Color(0xFF4CAF50) 
                                else if (status.contains("✗"))
                                    Color(0xFFF44336)
                                else 
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            // VoLTE Settings
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0x334CAF50)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.PhoneInTalk,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "VoLTE Settings",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        
                        Text(
                            "Enable VoLTE (Voice over LTE) for HD calls",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.enableVoLTE(context) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF4CAF50)
                                )
                            ) {
                                Icon(Icons.Default.Check, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Enable VoLTE")
                            }
                            
                            OutlinedButton(
                                onClick = { viewModel.checkVoLTEStatus(context) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Info, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Check Status")
                            }
                        }
                        
                        volteStatus?.let { status ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (status.contains("✓")) 
                                    Color(0xFF4CAF50) 
                                else if (status.contains("✗"))
                                    Color(0xFFF44336)
                                else 
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            // Recent Events
            if (events.isNotEmpty()) {
                item {
                    Text(
                        "Recent Events",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                items(events.take(10)) { event ->
                    EventItem(event)
                }
            }
            
            // Info Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "How it works",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            """
1. Grant root access when prompted
2. Set up GitHub token for auto-upload
3. Press START before traveling
4. App will AUTO-UPLOAD logs when network drops
5. Check Gist URL after problem occurs

Logs are compact (~30KB per upload).
Monitoring can run for days.
                            """.trimIndent(),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
    
    // Token Dialog
    if (showTokenDialog) {
        AlertDialog(
            onDismissRequest = { showTokenDialog = false },
            title = { Text("GitHub Token") },
            text = {
                Column {
                    Text(
                        "Enter your GitHub Personal Access Token with 'gist' scope.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Create token: github.com/settings/tokens/new",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = { Text("Token") },
                        placeholder = { Text("ghp_xxxx or github_pat_xxxx") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setGithubToken(tokenInput)
                    showTokenDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTokenDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun StatusCard(title: String, status: String, color: Color) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun EventItem(event: ModemMonitorService.MonitorEvent) {
    val backgroundColor = when (event.type) {
        ModemMonitorService.EventType.NETWORK_LOSS -> Color(0x33F44336)
        ModemMonitorService.EventType.SIGNAL_LOSS -> Color(0x33FF9800)
        ModemMonitorService.EventType.NETWORK_RESTORE -> Color(0x334CAF50)
        ModemMonitorService.EventType.MODEM_ERROR -> Color(0x33F44336)
        else -> Color.Transparent
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor.takeIf { it != Color.Transparent } 
                ?: MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    event.type.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = when (event.type) {
                        ModemMonitorService.EventType.NETWORK_LOSS -> Color(0xFFF44336)
                        ModemMonitorService.EventType.SIGNAL_LOSS -> Color(0xFFFF9800)
                        ModemMonitorService.EventType.NETWORK_RESTORE -> Color(0xFF4CAF50)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    event.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                event.description,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
