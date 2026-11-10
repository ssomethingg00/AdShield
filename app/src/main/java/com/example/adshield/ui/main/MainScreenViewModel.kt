package com.example.adshield.ui.main

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.adshield.vpn.AdBlockVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

class MainScreenViewModel : ViewModel() {

    val isVpnActive: StateFlow<Boolean> = AdBlockVpnService.isVpnRunning
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val blockedCount: StateFlow<Int> = AdBlockVpnService.blockedDomainsCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val rulesCount: StateFlow<Int> = AdBlockVpnService.loadedRulesCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _isPrivateDnsActive = MutableStateFlow(false)
    val isPrivateDnsActive: StateFlow<Boolean> = _isPrivateDnsActive.asStateFlow()

    fun checkPrivateDns(context: Context) {
        try {
            val mode = Settings.Global.getString(context.contentResolver, "private_dns_mode")
            _isPrivateDnsActive.value = (mode == "hostname" || mode == "opportunistic")
        } catch (_: Exception) {
            _isPrivateDnsActive.value = false
        }
    }

    fun startVpn(context: Context) {
        AdBlockVpnService.start(context)
    }

    fun stopVpn(context: Context) {
        AdBlockVpnService.stop(context)
    }
}
