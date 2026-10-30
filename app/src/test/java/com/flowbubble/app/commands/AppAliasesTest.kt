package com.flowbubble.app.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAliasesTest {

    @Test
    fun `nicknames expand to the installed label words`() {
        assertTrue(AppAliases.keywordsFor("fb").contains("facebook"))
        assertTrue(AppAliases.keywordsFor("IG").contains("instagram"))
        assertTrue(AppAliases.keywordsFor("mobile match").contains("whatsapp"))
        assertTrue(AppAliases.keywordsFor("wats app").contains("whatsapp"))
        assertTrue(AppAliases.keywordsFor("calc").any { it == "calculator" || it == "calc" })
    }

    @Test
    fun `spacing and case do not change the lookup`() {
        assertEquals(AppAliases.keywordsFor("play store"), AppAliases.keywordsFor("  PlayStore "))
        assertTrue(AppAliases.keywordsFor("Play   Store").contains("play store"))
    }

    @Test
    fun `unknown words get no alias`() {
        assertTrue(AppAliases.keywordsFor("kettle").isEmpty())
        assertTrue(AppAliases.keywordsFor("").isEmpty())
    }

    @Test
    fun `store links only exist for stable package ids`() {
        assertEquals("com.whatsapp", AppAliases.storePackageFor("whatsapp"))
        assertEquals("com.google.android.youtube", AppAliases.storePackageFor("yt"))
        assertNull(AppAliases.storePackageFor("kettle"))
        assertNull(AppAliases.storePackageFor("blinkit"))
    }
}
