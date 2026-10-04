package com.boxagent.app.llm

import com.boxagent.app.ui.screens.providerFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsDevTest {

    private fun catalog(): List<CatalogProvider> {
        val raw = requireNotNull(javaClass.getResource("/models_dev_catalog.json"))
            .readText()
        return ModelsDev.parse(raw)
    }

    @Test
    fun realCatalogParsesDeepSeekProvider() {
        val p = catalog().firstOrNull { it.id == "deepseek" }
        assertNotNull("deepseek provider must survive parse()", p)
        assertEquals("DeepSeek", p!!.name)
        assertEquals("https://api.deepseek.com", p.api)
        assertTrue(p.models.any { it.id == "deepseek-flash" })
    }

    @Test
    fun deepseekRanksFirstWhenSearchingDeepseek() {
        val hits = providerFilter(catalog(), "deepseek")
        assertTrue(hits.isNotEmpty())
        assertEquals("deepseek", hits[0].id)
    }

    @Test
    fun providerlessApiEntriesAreSkippedNotFatal() {
        // A provider without `api` and not in KNOWN_ENDPOINTS is dropped;
        // the rest still parse.
        val raw = """{
            "x": {"name":"X","models":{"m1":{"id":"m1"}}},
            "deepseek": {"id":"deepseek","api":"https://api.deepseek.com",
                "name":"DeepSeek","models":{"deepseek-flash":{"id":"deepseek-flash"}}}
        }"""
        val list = ModelsDev.parse(raw)
        assertEquals(1, list.size)
        assertEquals("deepseek", list[0].id)
    }
}
