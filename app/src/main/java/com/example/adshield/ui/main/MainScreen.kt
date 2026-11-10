package com.example.adshield.ui.main

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onItemClick: ((NavKey) -> Unit)? = null,
    viewModel: MainScreenViewModel = viewModel()
) {
    val context = LocalContext.current
    val isVpnActive by viewModel.isVpnActive.collectAsStateWithLifecycle()
    val blockedCount by viewModel.blockedCount.collectAsStateWithLifecycle()
    val rulesCount by viewModel.rulesCount.collectAsStateWithLifecycle()
    val isPrivateDnsActive by viewModel.isPrivateDnsActive.collectAsStateWithLifecycle()

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
        // Proceed with VPN start after notification permission prompt
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // App header
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )

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
