package com.example.adshield.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MainScreenViewModelTest {

    @Test
    fun initialState_vpnIsDisabled() {
        val viewModel = MainScreenViewModel()
        assertFalse(viewModel.isVpnActive.value)
        assertEquals(0, viewModel.blockedCount.value)
        assertEquals(0, viewModel.rulesCount.value)
    }

    @Test
    fun initialState_privateDnsIsFalse() {
        val viewModel = MainScreenViewModel()
        assertFalse(viewModel.isPrivateDnsActive.value)
    }
}
