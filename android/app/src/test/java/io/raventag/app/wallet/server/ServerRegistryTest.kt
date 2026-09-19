package io.raventag.app.wallet.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRegistryTest {
    companion object {
        private const val NOW = 1_787_000_000_000L
        private val REAL_REGISTRY = """
            {
              "registry": {
                "expiresAt": "2027-08-19T09:28:53+00:00",
                "generatedAt": "2026-08-19T09:28:53+00:00",
                "registryVersion": 2,
                "schemaVersion": 1,
                "servers": {
                  "aq7vuqykup2voklcrpqljf6jnjkzrouowsjfrmybdou5kdhrpr6sjjid.onion": {
                    "backend_policy": "discovery-only; live capability required",
                    "pruning": "-",
                    "s": "50002",
                    "t": "50001",
                    "version": "1.11"
                  },
                  "electrumx.raventag.com": {
                    "backend_policy": "operator-controlled anchor; Ravencoin Core >=4.8.0 and ElectrumX-RVN >=1.13.0; newer Core releases require signed safety-policy certification; live capability required",
                    "operatorGroup": "ALENOC",
                    "pruning": "-",
                    "s": "50002",
                    "version": "1.11"
                  },
                  "rvn4lyfe.com": {
                    "backend_policy": "discovery-only; live capability required",
                    "pruning": "-",
                    "s": "50002",
                    "t": "50001",
                    "version": "1.11"
                  }
                }
              },
              "signature": {
                "algorithm": "ed25519",
                "keyId": "d7a50f481a496f3e",
                "value": "q3I6klcBq2Lel5v3GSy7wUCjw1m+WrjqpZvKQdggiPVpWpF8iYmfxqGCid/1mQlHyfJAm3ty7O48wxbknKLFAA=="
              }
            }
        """.trimIndent()
    }

    @Test
    fun `real signed registry verifies and strips unsupported onion endpoint`() {
        val verified = ServerRegistry.verify(REAL_REGISTRY, nowMs = NOW)
        assertEquals(2L, verified.registryVersion)
        assertEquals(listOf("electrumx.raventag.com", "rvn4lyfe.com"),
            verified.servers.map { it.host })
        assertTrue(verified.servers.first().canCorroborate)
        assertFalse(verified.servers.last().canCorroborate)
    }

    @Test
    fun `tampered registry is rejected`() {
        val tampered = REAL_REGISTRY.replace("\"s\": \"50002\"", "\"s\": \"50003\"")
        assertThrows(ServerRegistry.RegistryException::class.java) {
            ServerRegistry.verify(tampered, nowMs = NOW)
        }
    }

    @Test
    fun `registry older than high water is rejected`() {
        assertThrows(ServerRegistry.RegistryException::class.java) {
            ServerRegistry.verify(REAL_REGISTRY, nowMs = NOW, minimumRegistryVersion = 3L)
        }
    }

    @Test
    fun `expired registry is rejected`() {
        assertThrows(ServerRegistry.RegistryException::class.java) {
            ServerRegistry.verify(REAL_REGISTRY, nowMs = 1_830_000_000_000L)
        }
    }

    @Test
    fun `same version with different accepted digest is rejected as equivocation`() {
        assertThrows(ServerRegistry.RegistryException::class.java) {
            ServerRegistry.verify(
                REAL_REGISTRY,
                nowMs = NOW,
                minimumRegistryVersion = 2L,
                acceptedDigestAtMinimum = "00".repeat(32)
            )
        }
    }

    @Test
    fun `v3 signed registry with cipig verifies`() {
        val v3 = """
{
  "registry": {
    "expiresAt": "2027-09-19T19:28:48+00:00",
    "generatedAt": "2026-09-19T19:28:48+00:00",
    "registryVersion": 3,
    "schemaVersion": 1,
    "servers": {
      "aq7vuqykup2voklcrpqljf6jnjkzrouowsjfrmybdou5kdhrpr6sjjid.onion": {
        "backend_policy": "discovery-only; live capability required",
        "pruning": "-",
        "s": "50002",
        "t": "50001",
        "version": "1.11"
      },
      "electrum1.cipig.net": {
        "backend_policy": "discovery-only; operator-confirmed Ravencoin Core 4.8.0; hardened server.ravencoin_backend capability pending; live capability required",
        "pruning": "-",
        "s": "20051",
        "t": "10051",
        "version": "1.11"
      },
      "electrum2.cipig.net": {
        "backend_policy": "discovery-only; operator-confirmed Ravencoin Core 4.8.0; hardened server.ravencoin_backend capability pending; live capability required",
        "pruning": "-",
        "s": "20051",
        "t": "10051",
        "version": "1.11"
      },
      "electrum3.cipig.net": {
        "backend_policy": "discovery-only; operator-confirmed Ravencoin Core 4.8.0; hardened server.ravencoin_backend capability pending; live capability required",
        "pruning": "-",
        "s": "20051",
        "t": "10051",
        "version": "1.11"
      },
      "electrumx.raventag.com": {
        "backend_policy": "operator-controlled anchor; Ravencoin Core >=4.8.0 and ElectrumX-RVN >=1.13.0; newer Core releases require signed safety-policy certification; live capability required",
        "operatorGroup": "ALENOC",
        "pruning": "-",
        "s": "50002",
        "version": "1.11"
      },
      "rvn4lyfe.com": {
        "backend_policy": "discovery-only; live capability required",
        "pruning": "-",
        "s": "50002",
        "t": "50001",
        "version": "1.11"
      }
    }
  },
  "signature": {
    "algorithm": "ed25519",
    "keyId": "d7a50f481a496f3e",
    "value": "DkAKwChVADmAaZUmozJEsm10GTDN2dUE94JHoqkuGPSsck7WW0v4lkOQcvoyfwoAAJUnz+7Nf85bEYqMlqpMCA=="
  }
}
        """.trimIndent()
        val verified = ServerRegistry.verify(v3, nowMs = 1_787_000_000_000L)
        assertEquals(3L, verified.registryVersion)
        assertEquals(
            listOf(
                "electrum1.cipig.net",
                "electrum2.cipig.net",
                "electrum3.cipig.net",
                "electrumx.raventag.com",
                "rvn4lyfe.com"
            ),
            verified.servers.map { it.host }
        )
    }
}
