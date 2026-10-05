package com.example.medicalrecord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateServiceTest {
    private fun update(code: Long, source: String) = AppUpdate(
        "0.5.$code", code, 1, false, emptyList(), "https://$source/example.apk", "a".repeat(64)
    )

    private fun source(code: Long, name: String): Pair<String, () -> AppUpdate> =
        name to { update(code, name) }

    private fun failed(name: String): Pair<String, () -> AppUpdate> =
        name to { error("连接失败") }

    @Test fun choosesNewerWebsiteRelease() {
        assertEquals(16L, AppUpdateService.checkSources(14, listOf(source(16, "site"), source(14, "gitee")))?.versionCode)
    }

    @Test fun choosesNewerGiteeRelease() {
        assertEquals(17L, AppUpdateService.checkSources(14, listOf(source(16, "site"), source(17, "gitee")))?.versionCode)
    }

    @Test fun prefersWebsiteForEqualVersions() {
        assertEquals("https://site/example.apk", AppUpdateService.checkSources(14,
            listOf(source(16, "site"), source(16, "gitee")))?.downloadUrl)
    }

    @Test fun usesGiteeWhenWebsiteFails() {
        assertEquals(16L, AppUpdateService.checkSources(14, listOf(failed("site"), source(16, "gitee")))?.versionCode)
    }

    @Test fun usesWebsiteWhenGiteeFails() {
        assertEquals(16L, AppUpdateService.checkSources(14, listOf(source(16, "site"), failed("gitee")))?.versionCode)
    }

    @Test fun reportsLatestOnlyWhenBothSourcesSucceed() {
        assertNull(AppUpdateService.checkSources(16, listOf(source(16, "site"), source(14, "gitee"))))
    }

    @Test fun doesNotReportLatestWhenASourceFails() {
        val result = runCatching { AppUpdateService.checkSources(16, listOf(source(16, "site"), failed("gitee"))) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("gitee"))
    }

    @Test fun reportsBothSourcesWhenBothFail() {
        val result = runCatching { AppUpdateService.checkSources(16, listOf(failed("site"), failed("gitee"))) }
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("site"))
        assertTrue(message.contains("gitee"))
    }
}
