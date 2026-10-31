package app.opentv.data.provider

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderRuntimeRulesTest {
    @After fun reset() = ProviderRuntimeRules.install(RemoteRuleSet())

    @Test fun changesOnlyConfiguredDocumentDomain() {
        ProviderRuntimeRules.install(
            RemoteRuleSet(
                version = 3,
                providers = mapOf("ddizibox" to RemoteProviderRule("https://new-series.example")),
            ),
        )
        assertEquals("new-series.example", ProviderRuntimeRules.remapHost("ddizibox.com"))
        assertNull(ProviderRuntimeRules.remapHost("play2.pilavyerplay.top"))
        assertNull(ProviderRuntimeRules.remapHost("ddizimom.net"))
        assertEquals(3, ProviderRuntimeRules.snapshot().version)
    }

    @Test fun selectorRuleIsScopedToProviderAndSection() {
        val spec = RemoteListingRule(
            mediaType = ProviderMediaType.SERIES, pathTemplate = "/diziler?page={page}",
            itemSelector = ".series-card", linkSelector = "a", titleSelector = "h3",
            imageSelector = "img", nextSelector = ".next",
        )
        ProviderRuntimeRules.install(
            RemoteRuleSet(4, mapOf("dizimom" to RemoteProviderRule(listings = mapOf("latest" to spec)))),
        )
        assertEquals(spec, ProviderRuntimeRules.listing("dizimom", "latest"))
        assertNull(ProviderRuntimeRules.listing("ddizibox", "latest"))
        assertNull(ProviderRuntimeRules.listing("dizimom", "drama"))
        assertNull(ProviderRuntimeRules.listing("dizimom", null))
    }

    @Test fun rollbackAtomicallyRestoresDefaultRules() {
        ProviderRuntimeRules.install(
            RemoteRuleSet(5, mapOf("jetfilmizle" to RemoteProviderRule("https://example.org"))),
        )
        ProviderRuntimeRules.install(RemoteRuleSet())
        assertEquals(0, ProviderRuntimeRules.snapshot().version)
        assertNull(ProviderRuntimeRules.remapHost("jetfilmizle.app"))
    }
}
