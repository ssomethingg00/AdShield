package com.example.adshield.blocklist

import android.content.Context
import android.content.res.AssetManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Unit tests for [BlocklistManager].
 * Uses MockK to mock Android's AssetManager so tests run on pure JVM.
 */
class BlocklistManagerTest {

    private lateinit var context: Context
    private lateinit var assetManager: AssetManager
    private lateinit var manager: BlocklistManager

    private val fakeBlocklist = """
        # Comment line — should be ignored
        ! Also a comment
        ||ads.applovin.com^
        ||*.mintegral.com^
        0.0.0.0 googleads.g.doubleclick.net
        127.0.0.1 pagead2.googlesyndication.com
        ||pangle.io^
        @@||allowed.pangle.io^
    """.trimIndent()

    @Before
    fun setUp() {
        assetManager = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.assets } returns assetManager
        every { assetManager.open(any()) } answers {
            ByteArrayInputStream(fakeBlocklist.toByteArray())
        }
        manager = BlocklistManager(context)
    }

    @Test
    fun `isBlocked returns false before blocklist is loaded`() {
        // Without load(), no domains should be blocked
        assertFalse(manager.isBlocked("ads.applovin.com"))
    }

    @Test
    fun `exact adblock domain is blocked`() = runTest {
        manager.load()
        assertTrue(manager.isBlocked("ads.applovin.com"))
    }

    @Test
    fun `wildcard suffix blocks subdomains`() = runTest {
        manager.load()
        assertTrue(manager.isBlocked("sdk.mintegral.com"))
        assertTrue(manager.isBlocked("ad.mintegral.com"))
        assertTrue(manager.isBlocked("deep.sub.mintegral.com"))
    }

    @Test
    fun `wildcard does not block apex domain`() = runTest {
        manager.load()
        // ||*.mintegral.com^ only blocks subdomains, not the apex mintegral.com
        assertFalse(manager.isBlocked("mintegral.com"))
    }

    @Test
    fun `wildcard does not block unrelated domain`() = runTest {
        manager.load()
        assertFalse(manager.isBlocked("google.com"))
    }

    @Test
    fun `adblock domain blocks both apex and subdomains`() = runTest {
        manager.load()
        // ||pangle.io^ should block apex pangle.io and subdomains like sdk.pangle.io
        assertTrue(manager.isBlocked("pangle.io"))
        assertTrue(manager.isBlocked("sdk.pangle.io"))
    }

    @Test
    fun `hosts format 0 0 0 0 is blocked`() = runTest {
        manager.load()
        assertTrue(manager.isBlocked("googleads.g.doubleclick.net"))
    }

    @Test
    fun `hosts format 127 0 0 1 is blocked`() = runTest {
        manager.load()
        assertTrue(manager.isBlocked("pagead2.googlesyndication.com"))
    }

    @Test
    fun `hardcoded whitelist domains are never blocked`() = runTest {
        manager.load()
        assertFalse(manager.isBlocked("connectivitycheck.gstatic.com"))
        assertFalse(manager.isBlocked("play.googleapis.com"))
        assertFalse(manager.isBlocked("fcm.googleapis.com"))
    }

    @Test
    fun `exception rule overrides blocking`() = runTest {
        manager.load()
        // @@||allowed.pangle.io^ should unblock this specific subdomain
        assertFalse(manager.isBlocked("allowed.pangle.io"))
    }

    @Test
    fun `empty domain returns false`() = runTest {
        manager.load()
        assertFalse(manager.isBlocked(""))
    }

    @Test
    fun `comment lines are ignored`() = runTest {
        manager.load()
        assertFalse(manager.isBlocked("# Comment line — should be ignored"))
    }

    @Test
    fun `non-ad domain passes through`() = runTest {
        manager.load()
        assertFalse(manager.isBlocked("dramabox.com"))
        assertFalse(manager.isBlocked("api.dramabox.com"))
        assertFalse(manager.isBlocked("cdn.reelshort.com"))
    }

    @Test
    fun `lowercase contract handles uppercase input`() = runTest {
        manager.load()
        // Caller must pass lowercase domain per contract
        assertTrue(manager.isBlocked("ADS.APPLOVIN.COM".lowercase()))
    }

    @Test
    fun `blocked count increments on each blocked query`() = runTest {
        manager.load()
        manager.resetSessionCount()
        manager.isBlocked("ads.applovin.com")
        manager.isBlocked("sdk.mintegral.com")
        manager.isBlocked("google.com") // not blocked
        assertEquals(2, manager.totalBlockedSessions)
    }
}
