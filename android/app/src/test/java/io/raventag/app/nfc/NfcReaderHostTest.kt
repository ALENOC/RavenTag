package io.raventag.app.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcReaderHostTest {

    @Test
    fun `accepts current and legacy RavenTag verification hosts`() {
        assertTrue(NfcReader.isAllowedVerifyHost("api.raventag.com"))
        assertTrue(NfcReader.isAllowedVerifyHost("VERIFY.RAVENTAG.COM"))
    }

    @Test
    fun `rejects unrelated or missing hosts`() {
        assertFalse(NfcReader.isAllowedVerifyHost("raventag.com.example.org"))
        assertFalse(NfcReader.isAllowedVerifyHost(null))
    }

    @Test
    fun `parses tags programmed by current RavenTag backend`() {
        val params = NfcReader.parseSunUrl(
            "https://api.raventag.com/verify?asset=BRAND/ITEM%23SN001" +
                "&e=00112233445566778899aabbccddeeff&m=0011223344556677"
        )

        assertEquals("BRAND/ITEM#SN001", params?.asset)
        assertEquals("00112233445566778899aabbccddeeff", params?.e)
        assertEquals("0011223344556677", params?.m)
    }

    @Test
    fun `keeps version 1_0_7 compatibility for legacy host and bare asset separator`() {
        val params = NfcReader.parseSunUrl(
            "https://verify.raventag.com/verify?asset=BRAND/ITEM#SN001" +
                "&e=ffeeddccbbaa99887766554433221100&m=7766554433221100"
        )

        assertEquals("BRAND/ITEM#SN001", params?.asset)
        assertEquals("ffeeddccbbaa99887766554433221100", params?.e)
    }

    @Test
    fun `rejects URLs that cannot be RavenTag verification tags`() {
        val validQuery = "e=00112233445566778899aabbccddeeff&m=0011223344556677"

        assertNull(NfcReader.parseSunUrl("https://example.org/verify?$validQuery"))
        assertNull(NfcReader.parseSunUrl("http://api.raventag.com/verify?$validQuery"))
        assertNull(NfcReader.parseSunUrl("https://api.raventag.com/other?$validQuery"))
        assertNull(NfcReader.parseSunUrl("https://user@api.raventag.com/verify?$validQuery"))
    }

    @Test
    fun `physical tags preserve version 1_0_7 custom backend support`() {
        val validQuery = "asset=BRAND/ITEM%23SN001" +
            "&e=00112233445566778899aabbccddeeff&m=0011223344556677"
        val customUrl = "https://brand.example.org:8443/verify?$validQuery"

        assertNull(NfcReader.parseSunUrl(customUrl))
        assertEquals(
            "BRAND/ITEM#SN001",
            NfcReader.parsePhysicalSunUrl(customUrl)?.asset
        )
    }

    @Test
    fun `physical custom backend still requires https verify path and no userinfo`() {
        val validQuery = "e=00112233445566778899aabbccddeeff&m=0011223344556677"

        assertNull(NfcReader.parsePhysicalSunUrl("http://brand.example.org/verify?$validQuery"))
        assertNull(NfcReader.parsePhysicalSunUrl("https://brand.example.org/other?$validQuery"))
        assertNull(NfcReader.parsePhysicalSunUrl("https://user@brand.example.org/verify?$validQuery"))
    }
}
