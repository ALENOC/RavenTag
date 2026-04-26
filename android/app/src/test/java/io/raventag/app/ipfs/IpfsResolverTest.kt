package io.raventag.app.ipfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IpfsResolverTest {

    @Test
    fun `candidateUrls preserves path and query from ipfs uri`() {
        val cid = "bafybeigdyrztipfstestcidvalue000000000000000000000"
        val urls = IpfsResolver.candidateUrls("ipfs://$cid/assets/front.webp?download=1")

        assertTrue(urls.isNotEmpty())
        assertTrue(urls.all { it.endsWith("/ipfs/$cid/assets/front.webp?download=1") })
    }

    @Test
    fun `candidateUrls preserves path and query from path gateway url`() {
        val cid = "bafybeigdyrztipfstestcidvalue111111111111111111111"
        val urls = IpfsResolver.candidateUrls("https://ipfs.io/ipfs/$cid/images/item.png?filename=item.png")

        assertTrue(urls.isNotEmpty())
        assertTrue(urls.all { it.endsWith("/ipfs/$cid/images/item.png?filename=item.png") })
    }

    @Test
    fun `candidateUrls preserves path and query from subdomain gateway url`() {
        val cid = "bafybeigdyrztipfstestcidvalue222222222222222222222"
        val urls = IpfsResolver.candidateUrls("https://$cid.ipfs.dweb.link/media/preview.jpg?size=small")

        assertTrue(urls.isNotEmpty())
        assertTrue(urls.all { it.endsWith("/ipfs/$cid/media/preview.jpg?size=small") })
    }

    @Test
    fun `candidateUrls keeps direct non ipfs urls unchanged`() {
        val url = "https://cdn.example.com/images/preview.jpg"

        assertEquals(listOf(url), IpfsResolver.candidateUrls(url))
    }
}
