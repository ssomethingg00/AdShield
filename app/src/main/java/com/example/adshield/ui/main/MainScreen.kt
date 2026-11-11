package com.example.adshield.ui.main

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.adshield.R
import com.example.adshield.update.UpdateInfo
import com.example.adshield.update.UpdateManager
import kotlinx.coroutines.launch

private const val CURRENT_APP_VERSION = "v1.0.2"

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onItemClick: ((NavKey) -> Unit)? = null,
    viewModel: MainScreenViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val isVpnActive by viewModel.isVpnActive.collectAsStateWithLifecycle()
    val blockedCount by viewModel.blockedCount.collectAsStateWithLifecycle()
    val rulesCount by viewModel.rulesCount.collectAsStateWithLifecycle()
    val isPrivateDnsActive by viewModel.isPrivateDnsActive.collectAsStateWithLifecycle()

    // 3-dot Menu state
    var showMenu by remember { mutableStateOf(false) }

    // Update check states
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateInfo?>(null) }
    var showUpToDateDialog by remember { mutableStateOf(false) }
    var updateErrorMessage by remember { mutableStateOf<String?>(null) }
    var showAboutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.checkPrivateDns(context)
    }

    // VPN permission launcher
    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startVpn(context)
        }
    }

    // Notification permission launcher (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            vpnLauncher.launch(prepareIntent)
        } else {
            viewModel.startVpn(context)
        }
    }

    fun toggleVpn() {
        if (!isVpnActive) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    vpnLauncher.launch(prepareIntent)
                } else {
                    viewModel.startVpn(context)
                }
            }
        } else {
            viewModel.stopVpn(context)
        }
    }

    fun triggerUpdateCheck() {
        isCheckingUpdate = true
        coroutineScope.launch {
            val result = UpdateManager.checkForUpdates(CURRENT_APP_VERSION)
            isCheckingUpdate = false
            result.onSuccess { info ->
                if (info.hasUpdate) {
                    updateResult = info
                } else {
                    showUpToDateDialog = true
                }
            }.onFailure { error ->
                updateErrorMessage = error.localizedMessage ?: "Failed to check GitHub releases"
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // App Header with 3-dot Menu
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                // 3-dot Menu Icon Button
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_more_vert),
                            contentDescription = "Menu",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("🔄 Check for Updates") },
                            onClick = {
                                showMenu = false
                                triggerUpdateCheck()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🌐 GitHub Repository") },
                            onClick = {
                                showMenu = false
                                UpdateManager.openInBrowser(context, UpdateManager.GITHUB_REPO_URL)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("ℹ️ About AdShield") },
                            onClick = {
                                showMenu = false
                                showAboutDialog = true
                            }
                        )
                    }
                }
            }

            if (isPrivateDnsActive) {
                Spacer(Modifier.height(12.dp))
                PrivateDnsWarningCard(context)
            }
        }

        // Center: Shield Icon + Status
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val shieldColor = if (isVpnActive) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
            val glowColor = if (isVpnActive) Color(0x334CAF50) else Color(0x1F9E9E9E)

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(170.dp)
                    .clip(CircleShape)
                    .background(glowColor)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_shield),
                    contentDescription = "Shield Status",
                    modifier = Modifier.size(96.dp),
                    tint = shieldColor
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(if (isVpnActive) R.string.status_protected else R.string.status_unprotected),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = shieldColor
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(if (isVpnActive) R.string.subtitle_protected else R.string.subtitle_unprotected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // Stats Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatCard(
                title = stringResource(R.string.stat_ads_blocked),
                value = blockedCount.toString(),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = stringResource(R.string.stat_rules_loaded),
                value = if (rulesCount > 0) rulesCount.toString() else "60+",
                modifier = Modifier.weight(1f)
            )
        }

        // Bottom: Big Toggle Button
        Button(
            onClick = { toggleVpn() },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isVpnActive) Color(0xFFD32F2F) else Color(0xFF2E7D32)
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(bottom = 8.dp)
        ) {
            Text(
                text = stringResource(if (isVpnActive) R.string.stop_protection else R.string.enable_protection),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Dialogs: Update & About
    // ─────────────────────────────────────────────────────────────

    // 1. Checking for Update Progress
    if (isCheckingUpdate) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Checking for Updates") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text("Connecting to GitHub...")
                }
            },
            confirmButton = { }
        )
    }

    // 2. Update Available Dialog
    updateResult?.let { info ->
        AlertDialog(
            onDismissRequest = { updateResult = null },
            title = { Text("🚀 Update Available: ${info.latestVersion}") },
            text = {
                Column {
                    Text(
                        text = "A new version of AdShield is ready on GitHub!",
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = info.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val url = info.downloadUrl.ifEmpty { info.releasePageUrl }
                        UpdateManager.openInBrowser(context, url)
                        updateResult = null
                    }
                ) {
                    Text("Download Update")
                }
            },
            dismissButton = {
                TextButton(onClick = { updateResult = null }) {
                    Text("Later")
                }
            }
        )
    }

    // 3. Up to date Dialog
    if (showUpToDateDialog) {
        AlertDialog(
            onDismissRequest = { showUpToDateDialog = false },
            title = { Text("✅ Up to Date") },
            text = { Text("You are already using the latest version of AdShield ($CURRENT_APP_VERSION).") },
            confirmButton = {
                Button(onClick = { showUpToDateDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    // 4. Update Error Dialog
    updateErrorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { updateErrorMessage = null },
            title = { Text("Update Check") },
            text = {
                Text("Could not reach GitHub Releases:\n$msg\n\nWould you like to open the GitHub page directly?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        UpdateManager.openInBrowser(context, UpdateManager.GITHUB_REPO_URL + "/releases")
                        updateErrorMessage = null
                    }
                ) {
                    Text("Open GitHub")
                }
            },
            dismissButton = {
                TextButton(onClick = { updateErrorMessage = null }) {
                    Text("Dismiss")
                }
            }
        )
    }

    // 5. About Dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("🛡️ AdShield $CURRENT_APP_VERSION") },
            text = {
                Column {
                    Text(
                        text = "A fast, no-root DNS ad blocker for short video and drama apps (DramaBox, ReelShort, NetShort, StoryTV, etc.).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "• No Root Required\n• Local DNS Sinkhole\n• Safe Split-Tunnel Routing\n• In-App GitHub Updates",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        UpdateManager.openInBrowser(context, UpdateManager.GITHUB_REPO_URL)
                        showAboutDialog = false
                    }
                ) {
                    Text("View GitHub")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PrivateDnsWarningCard(context: Context) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFF3E0)
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.private_dns_warning_title),
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE65100),
                fontSize = 14.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.private_dns_warning_message),
                fontSize = 12.sp,
                color = Color(0xFF424242)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                    } catch (_: Exception) {}
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Open Settings", fontSize = 12.sp)
            }
        }
    }
}
