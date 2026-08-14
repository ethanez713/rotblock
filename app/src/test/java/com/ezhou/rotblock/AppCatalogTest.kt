package com.ezhou.rotblock

import com.ezhou.rotblock.insights.AppCatalog
import com.ezhou.rotblock.insights.Category
import com.ezhou.rotblock.insights.Productivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCatalogTest {

    @Test
    fun `parses a well-formed catalog`() {
        val json = """
            [
              {"pkg": "com.reddit.frontpage", "category": "SOCIAL", "productivity": "VERY_DISTRACTING"},
              {"pkg": "com.slack", "category": "MESSAGING", "productivity": "PRODUCTIVE"}
            ]
        """.trimIndent()
        val entries = AppCatalog.parse(json)
        assertEquals(Category.SOCIAL, entries["com.reddit.frontpage"]?.category)
        assertEquals(Productivity.VERY_DISTRACTING, entries["com.reddit.frontpage"]?.productivity)
        assertEquals(Category.MESSAGING, entries["com.slack"]?.category)
    }

    @Test
    fun `malformed json yields an empty catalog rather than throwing`() {
        assertEquals(emptyMap<String, Any>(), AppCatalog.parse("{ not json at all"))
        assertEquals(emptyMap<String, Any>(), AppCatalog.parse(""))
    }

    @Test
    fun `an entry with an unrecognised enum value falls back to its default`() {
        val json = """[{"pkg": "com.example.app", "category": "MADE_UP", "productivity": "MADE_UP"}]"""
        val entries = AppCatalog.parse(json)
        assertEquals(Category.UNKNOWN, entries["com.example.app"]?.category)
        assertEquals(Productivity.UNCLASSIFIED, entries["com.example.app"]?.productivity)
    }

    @Test
    fun `an entry missing its package is skipped rather than stored under a blank key`() {
        val json = """[{"category": "SOCIAL", "productivity": "DISTRACTING"}]"""
        assertTrue(AppCatalog.parse(json).isEmpty())
    }

    @Test
    fun `classify defaults an absent package to unknown category and unclassified productivity`() {
        val classification = AppCatalog.classify("com.never.seen.this")
        assertEquals(Category.UNKNOWN, classification.category)
        assertEquals(Productivity.UNCLASSIFIED, classification.productivity)
    }
}
