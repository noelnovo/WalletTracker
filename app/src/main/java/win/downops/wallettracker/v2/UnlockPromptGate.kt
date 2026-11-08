package win.downops.wallettracker.v2

/** UI scheduling only: biometric authentication and CryptoObject verification stay unchanged. */
class UnlockPromptGate(var attempted: Boolean = false) {
    var active = false
        private set

    fun automatic(eligible: Boolean, resumed: Boolean): Boolean {
        if (!eligible || !resumed || attempted || active) return false
        return manual()
    }

    fun manual(): Boolean {
        if (active) return false
        active = true; attempted = true
        return true
    }

    fun finished() { active = false }
    fun foregroundEnded() { if (!active) attempted = false }
}
