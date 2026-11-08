package win.downops.wallettracker.v2

import org.junit.Assert.*
import org.junit.Test

class UnlockPromptGateTest {
    @Test fun onlyEligibleResumedWalletsPrompt() {
        val gate = UnlockPromptGate()
        assertFalse(gate.automatic(false, true))
        assertFalse(gate.automatic(true, false))
        assertTrue(gate.automatic(true, true))
        assertFalse(gate.automatic(true, true))
        assertFalse(gate.manual())
    }

    @Test fun cancellationDoesNotLoopButManualRetryWorks() {
        val gate = UnlockPromptGate()
        assertTrue(gate.automatic(true, true))
        gate.finished()
        assertFalse(gate.automatic(true, true))
        assertTrue(gate.manual())
    }

    @Test fun newForegroundCanPromptAndRestoredAttemptDoesNotDuplicate() {
        val gate = UnlockPromptGate(true)
        assertFalse(gate.automatic(true, true))
        gate.foregroundEnded()
        assertTrue(gate.automatic(true, true))
        gate.foregroundEnded()
        assertTrue(gate.active)
        assertTrue(gate.attempted)
    }
}
