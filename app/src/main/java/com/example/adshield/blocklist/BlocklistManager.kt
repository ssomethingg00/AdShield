package com.example.adshield.blocklist

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages the blocklist of ad/tracking domains.
 *
 * Uses a thread-safe, immutable snapshot pattern:
 * - [BlocklistData.exactDomains]: O(1) HashSet for exact domain matches (e.g., "ads.example.com")
 * - [BlocklistData.wildcardSuffixes]: HashSet of suffixes for wildcard matching (e.g., "applovin.com")
 * - [BlocklistData.exceptionDomains]: Whitelisted domains via adblock exception rules ("@@||")
 */
class BlocklistManager(private val context: Context) {

    private data class BlocklistData(
        val exactDomains: Set<String>,
        val wildcardSuffixes: Set<String>,
        val exceptionDomains: Set<String> = emptySet()
    )

    companion object {
        private const val TAG = "BlocklistManager"

        // Bundled asset blocklist files (in app/src/main/assets/blocklists/)
        private val BUNDLED_LISTS = listOf(
            "blocklists/drama_apps.txt",
            "blocklists/adguard_dns.txt",
        )

        // Whitelist: Never block these (would break core Android/Google functionality)
        private val HARDCODED_WHITELIST = setOf(
            "connectivitycheck.gstatic.com",    // Android network check
            "clients3.google.com",              // Android network check
            "play.googleapis.com",              // Play Store
            "android.clients.google.com",       // Android system
            "mtalk.google.com",                 // FCM push notifications
            "fcm.googleapis.com",               // Firebase push
        )

        private val PLAIN_DOMAIN_RE = Regex("^[a-z0-9][a-z0-9.\\-]*\\.[a-z]{2,}$")
        private val WHITESPACE_RE = Regex("\\s+")
    }

    private val loadMutex = Mutex()

    @Volatile
    private var blocklistData = BlocklistData(emptySet(), emptySet(), emptySet())

    private val _blockedCount = AtomicInteger(0)
    val totalBlockedSessions: Int get() = _blockedCount.get()

    val domainCount: Int get() = blocklistData.exactDomains.size + blocklistData.wildcardSuffixes.size

    @Volatile
    var isLoaded: Boolean = false
        private set

    /**
     * Loads all bundled blocklists from assets.
     * Safe to call from any coroutine; switches to IO dispatcher internally.
     * Protected by [loadMutex] against concurrent invocations.
     * @return total number of unique domains loaded
     */
    suspend fun load(): Int = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            val exact = HashSet<String>(10_000)
            val wildcard = HashSet<String>(2_000)
            val exceptions = HashSet<String>(100)
            var lineCount = 0

            for (assetPath in BUNDLED_LISTS) {
                try {
                    context.assets.open(assetPath).use { stream ->
                        BufferedReader(InputStreamReader(stream)).forEachLine { rawLine ->
                            lineCount++
                            parseLine(rawLine.trim(), exact, wildcard, exceptions)
                        }
                    }
                    Log.i(TAG, "Loaded blocklist: $assetPath")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not load $assetPath: ${e.message}")
                }
            }

            // Atomically replace all sets (readers see consistent snapshot)
            blocklistData = BlocklistData(exact, wildcard, exceptions)
            isLoaded = true

            val total = exact.size + wildcard.size
            Log.i(TAG, "Blocklist ready: ${exact.size} exact + ${wildcard.size} wildcard = $total domains")
            total
        }
    }

    /**
     * Checks whether a [domain] should be blocked.
     * Contract: [domain] should be lowercase and without trailing dot.
     *
     * Lookup order (fastest-first):
     * 1. Hardcoded whitelist — always allow
     * 2. Adblock exception whitelist ("@@||")
     * 3. Exact match in [exactDomains]
     * 4. Walk up parent domains checking [wildcardSuffixes]
     *
     * @param domain Lowercase domain name without trailing dot
     * @return true if the domain should be blocked (NXDOMAIN response)
     */
    fun isBlocked(domain: String): Boolean {
        if (domain.isEmpty()) return false
        val data = blocklistData
        if (domain in HARDCODED_WHITELIST) return false
        if (domain in data.exceptionDomains) return false

        // 1. Exact match
        if (domain in data.exactDomains) {
            _blockedCount.incrementAndGet()
            return true
        }

        // 2. Wildcard: walk up parent domains
        var dotIndex = domain.indexOf('.')
        while (dotIndex != -1) {
            val parentDomain = domain.substring(dotIndex + 1)
            if (parentDomain in data.wildcardSuffixes) {
                _blockedCount.incrementAndGet()
                return true
            }
            dotIndex = domain.indexOf('.', dotIndex + 1)
        }

        return false
    }

    fun resetSessionCount() = _blockedCount.set(0)

    // ─────────────────────────────────────────────────────────────
    // Parsing
    // ─────────────────────────────────────────────────────────────

    private fun parseLine(
        rawLine: String,
        exact: HashSet<String>,
        wildcard: HashSet<String>,
        exceptions: HashSet<String>
    ) {
        val line = rawLine.substringBefore('#').substringBefore('!').trim()
        if (line.isEmpty()) return

        when {
            // Exception rule: "@@||example.com^"
            line.startsWith("@@||") -> {
                val domain = line.removePrefix("@@||")
                    .substringBefore('^')
                    .substringBefore('$')
                    .substringBefore('/')
                    .trim()
                    .lowercase()
                if (isValidDomain(domain)) exceptions.add(domain)
            }

            // Hosts format: "0.0.0.0 ads.example.com" or "127.0.0.1 ads.example.com" or "::1"
            line.startsWith("0.0.0.0") || line.startsWith("127.0.0.1") || line.startsWith("::1") -> {
                val parts = line.split(WHITESPACE_RE)
                if (parts.size >= 2) {
                    for (i in 1 until parts.size) {
                        val domain = parts[i].lowercase()
                        if (isValidDomain(domain)) exact.add(domain)
                    }
                }
            }

            // Adblock wildcard: "||*.applovin.com^"
            line.startsWith("||*.") -> {
                val suffix = line.removePrefix("||*.")
                    .substringBefore('^')
                    .substringBefore('$')
                    .substringBefore('/')
                    .trim()
                    .lowercase()
                if (isValidDomain(suffix)) wildcard.add(suffix)
            }

            // Adblock exact + subdomain block: "||ads.example.com^"
            line.startsWith("||") -> {
                val domain = line.removePrefix("||")
                    .substringBefore('^')
                    .substringBefore('$')
                    .substringBefore('/')
                    .trim()
                    .lowercase()
                if (isValidDomain(domain)) {
                    exact.add(domain)
                    wildcard.add(domain)
                }
            }

            // Plain domain line
            PLAIN_DOMAIN_RE.matches(line) -> {
                exact.add(line.lowercase())
            }
        }
    }

    private fun isValidDomain(domain: String): Boolean {
        if (domain.length !in 1..253) return false
        if (domain.contains('/') || domain.contains(':') || domain.contains('*')) return false
        if (domain == "localhost" || domain == "broadcasthost") return false
        return domain.contains('.') && !domain.startsWith('.') &&
                domain.all { it.isLetterOrDigit() || it == '.' || it == '-' }
    }
}
