package com.calyptra.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {

    @Test
    fun `same pin and salt produce the same hash`() {
        val salt = ByteArray(16) { it.toByte() }
        val first = PinHasher.hash("1234", salt)
        val second = PinHasher.hash("1234", salt)
        assertArrayEquals(first, second)
    }

    @Test
    fun `different salts produce different hashes for the same pin`() {
        val saltA = ByteArray(16) { it.toByte() }
        val saltB = ByteArray(16) { (it + 1).toByte() }
        val hashA = PinHasher.hash("1234", saltA)
        val hashB = PinHasher.hash("1234", saltB)
        assertFalse(hashA.contentEquals(hashB))
    }

    @Test
    fun `different pins produce different hashes for the same salt`() {
        val salt = ByteArray(16) { it.toByte() }
        assertFalse(PinHasher.hash("1234", salt).contentEquals(PinHasher.hash("1235", salt)))
    }

    @Test
    fun `verify accepts the correct pin`() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash("0000", salt)
        assertTrue(PinHasher.verify("0000", salt, hash))
    }

    @Test
    fun `verify rejects a wrong pin`() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash("0000", salt)
        assertFalse(PinHasher.verify("9999", salt, hash))
    }

    @Test
    fun `boundary pins 0000 and 9999 hash and verify`() {
        for (pin in listOf("0000", "9999")) {
            val salt = PinHasher.newSalt()
            val hash = PinHasher.hash(pin, salt)
            assertTrue("$pin should verify against its own hash", PinHasher.verify(pin, salt, hash))
        }
    }

    @Test
    fun `newSalt returns 16 random bytes`() {
        val a = PinHasher.newSalt()
        val b = PinHasher.newSalt()
        assertEquals(16, a.size)
        assertEquals(16, b.size)
        assertFalse("two salts should not collide", a.contentEquals(b))
    }

    @Test
    fun `verify rejects when hash lengths differ instead of throwing`() {
        val salt = PinHasher.newSalt()
        assertFalse(PinHasher.verify("1234", salt, ByteArray(5)))
    }

    @Test
    fun `hash output is stable across instances and non-trivial`() {
        val salt = ByteArray(16)
        val hash = PinHasher.hash("4321", salt)
        assertEquals("PBKDF2-SHA256 should yield 32 bytes", 32, hash.size)
        assertNotEquals("hash must not be all zeros", 0, hash.count { it != 0.toByte() })
    }
}
