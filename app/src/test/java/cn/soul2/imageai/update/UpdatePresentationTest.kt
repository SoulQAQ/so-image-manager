package cn.soul2.imageai.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdatePresentationTest {
    @Test
    fun parsesCommonGitHubReleaseMarkdownWithoutExposingSyntax() {
        val blocks = ReleaseNotesMarkdown.parse(
            """
            ## v0.17.2

            ### 功能
            - 支持 **Markdown** 标题
            - 查看 [Release 页面](https://example.com/release)

            1. 下载 `APK`
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                ReleaseNotesBlock.Heading(2, "v0.17.2"),
                ReleaseNotesBlock.Heading(3, "功能"),
                ReleaseNotesBlock.Bullet("支持 Markdown 标题"),
                ReleaseNotesBlock.Bullet("查看 Release 页面"),
                ReleaseNotesBlock.Bullet("下载 APK", ordinal = 1),
            ),
            blocks,
        )
    }

    @Test
    fun calculatesDownloadSpeedFromConsecutiveSamples() {
        val estimator = DownloadSpeedEstimator()

        assertEquals(null, estimator.observe(1_000L, 10_000L))
        assertEquals(2_000L, estimator.observe(2_500L, 10_750L))
        assertEquals(2_000L, estimator.observe(2_500L, 11_500L))
        assertEquals(null, estimator.observe(100L, 12_250L))
    }

    @Test
    fun keepsTheLastTrustedSpeedAcrossShortDownloadManagerPlateaus() {
        val estimator = DownloadSpeedEstimator(staleAfterMillis = 3_000L)

        assertEquals(null, estimator.observe(0L, 1_000L))
        assertEquals(2_000L, estimator.observe(2_000L, 2_000L))
        assertEquals(2_000L, estimator.observe(2_000L, 2_500L))
        assertEquals(2_000L, estimator.observe(2_000L, 4_999L))
        assertEquals(null, estimator.observe(2_000L, 5_001L))
    }

    @Test
    fun smoothsSpeedOverFiveSecondMonotonicWindowAndResetsWhenClockMovesBack() {
        val estimator = DownloadSpeedEstimator()

        assertEquals(null, estimator.observe(0L, 1_000L))
        assertEquals(1_000L, estimator.observe(1_000L, 2_000L))
        assertEquals(2_500L, estimator.observe(5_000L, 3_000L))
        assertEquals(1_400L, estimator.observe(8_000L, 7_000L))
        assertEquals(1_000L, estimator.observe(10_000L, 8_000L))
        assertEquals(null, estimator.observe(11_000L, 7_500L))
    }
}
