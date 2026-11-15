package com.example.lalem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PgyerInstallPageTest {
    @Test
    fun readsAndroidVersionWithoutUsingPgyerBuildNumber() {
        val html = """
            <div class="span12 gray-text app-info mt-10 hide" style="text-align:center;">
                版本：1.10 (build 2)
            </div>
        """.trimIndent()

        val version = requireNotNull(PgyerInstallPage.parseVersion(html))
        assertEquals("1.10", version.name)
        assertTrue(version > requireNotNull(PublishedVersion.parse("1.9")))
    }

    @Test
    fun equivalentVersionsShareOneIgnoreKey() {
        val short = requireNotNull(PublishedVersion.parse("1.2"))
        val long = requireNotNull(PublishedVersion.parse("1.2.0"))

        assertEquals(0, short.compareTo(long))
        assertEquals(short.key, long.key)
    }

    @Test
    fun unrelatedContentIsNotAnUpdate() {
        assertNull(PgyerInstallPage.parseVersion("<div class=\"release-notes\">版本：99.0</div>"))
        assertNull(PublishedVersion.parse("1.3-beta"))
    }
}
