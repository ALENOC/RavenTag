package io.raventag.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

fun confirmationsToDisplayString(c: Int): String = when {
    c >= 6 -> "Confermato"
    c >= 1 -> "$c/6 conferme"
    else -> "In attesa..."
}

fun shouldAutoDismiss(c: Int): Boolean = c >= 6

class ConfirmationPollingTest {

    // ── confirmationsToDisplayString ──────────────────────────────────────────

    @Test
    fun `display - pending at 0`() {
        assertEquals("In attesa...", confirmationsToDisplayString(0))
    }

    @Test
    fun `display - pending at negative`() {
        assertEquals("In attesa...", confirmationsToDisplayString(-1))
    }

    @Test
    fun `display - confirming at 1`() {
        assertEquals("1/6 conferme", confirmationsToDisplayString(1))
    }

    @Test
    fun `display - confirming at 3`() {
        assertEquals("3/6 conferme", confirmationsToDisplayString(3))
    }

    @Test
    fun `display - confirming at 5`() {
        assertEquals("5/6 conferme", confirmationsToDisplayString(5))
    }

    @Test
    fun `display - confirmed at 6`() {
        assertEquals("Confermato", confirmationsToDisplayString(6))
    }

    @Test
    fun `display - confirmed at 10`() {
        assertEquals("Confermato", confirmationsToDisplayString(10))
    }

    // ── shouldAutoDismiss ─────────────────────────────────────────────────────

    @Test
    fun `autoDismiss - false at 3`() {
        assertFalse(shouldAutoDismiss(3))
    }

    @Test
    fun `autoDismiss - true at 6`() {
        assertTrue(shouldAutoDismiss(6))
    }

    @Test
    fun `autoDismiss - true at 7`() {
        assertTrue(shouldAutoDismiss(7))
    }
}
