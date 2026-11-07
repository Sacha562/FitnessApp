package dev.fitnessapp.app.update

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** The GitHub release parsing and comparison of D98 (1). */
class ReleasesTest {
    private val sha = "11f597af8602ca18bb9d7134818244ca03fba68f2ea6baf2c47e6ee6c4609259"

    private fun body(
        tag: String = "v0.0.170",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = """[{"name":"FitnessApp-0.0.170.apk","size":5034583,"digest":"sha256:$sha",
            "browser_download_url":"https://github.com/Sacha562/FitnessApp/releases/download/v0.0.170/FitnessApp-0.0.170.apk"}]""",
        notes: String = "## Install\\n\\n- Download `FitnessApp.apk`\\n- See [Obtainium](https://example.org)",
    ) = """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"body":"$notes","assets":$assets,"extra":{"x":1}}"""

    @Test
    fun `the version code is the tag's last number`() {
        Releases.versionCode("v0.0.163") shouldBe 163
        Releases.versionCode("v0.4.512") shouldBe 512
        Releases.versionCode("0.0.163") shouldBe null
        Releases.versionCode("v0.0.163-beta") shouldBe null
        Releases.versionCode("nightly") shouldBe null
    }

    @Test
    fun `a release with an apk asset is parsed with its digest and size`() {
        val r = Releases.parseLatest(body())!!
        r.tag shouldBe "v0.0.170"
        r.versionName shouldBe "0.0.170"
        r.versionCode shouldBe 170
        r.apkName shouldBe "FitnessApp-0.0.170.apk"
        r.apkSize shouldBe 5034583L
        r.sha256 shouldBe sha
        r.apkUrl shouldBe "https://github.com/Sacha562/FitnessApp/releases/download/v0.0.170/FitnessApp-0.0.170.apk"
    }

    @Test
    fun `drafts, pre-releases, odd tags and releases without an https apk are not offered`() {
        Releases.parseLatest(body(draft = true)) shouldBe null
        Releases.parseLatest(body(prerelease = true)) shouldBe null
        Releases.parseLatest(body(tag = "latest")) shouldBe null
        Releases.parseLatest(body(assets = "[]")) shouldBe null
        Releases.parseLatest(body(assets = """[{"name":"notes.txt","browser_download_url":"https://x/notes.txt"}]""")) shouldBe null
        Releases.parseLatest(body(assets = """[{"name":"a.apk","browser_download_url":"http://x/a.apk"}]""")) shouldBe null
        Releases.parseLatest("not json") shouldBe null
        Releases.parseLatest("[]") shouldBe null
    }

    @Test
    fun `a missing or malformed digest leaves the hash unchecked`() {
        Releases.parseLatest(body(assets = """[{"name":"a.apk","browser_download_url":"https://x/a.apk"}]"""))!!.sha256 shouldBe null
        Releases.parseLatest(body(assets = """[{"name":"a.apk","digest":"md5:abc","browser_download_url":"https://x/a.apk"}]"""))!!.sha256 shouldBe null
    }

    @Test
    fun `the first apk by name is taken when there are several`() {
        val assets = """[{"name":"z.apk","browser_download_url":"https://x/z.apk"},{"name":"b.apk","browser_download_url":"https://x/b.apk"}]"""
        Releases.parseLatest(body(assets = assets))!!.apkName shouldBe "b.apk"
    }

    @Test
    fun `only a higher version code is newer`() {
        val r = Releases.parseLatest(body())!!
        Releases.isNewer(r, 163) shouldBe true
        Releases.isNewer(r, 170) shouldBe false
        Releases.isNewer(r, 175) shouldBe false
        Releases.isNewer(null, 1) shouldBe false
    }

    @Test
    fun `release notes lose their markdown markers`() {
        val text = Releases.plainNotes("## Install\n\n\n1. Download **`App.apk`** from [the page](https://x).\n- one\n  * two\n```\napksigner verify\n```\n\n")
        text shouldBe "Install\n\n1. Download App.apk from the page.\n• one\n  • two"
        text shouldNotContain "```"
        Releases.plainNotes("x".repeat(50), maxChars = 10) shouldBe "xxxxxxxxx…"
    }

    @Test
    fun `cached apk names carry their version code`() {
        Releases.cachedCode("FitnessApp-170.apk") shouldBe 170
        Releases.cachedCode("FitnessApp-170.apk.part") shouldBe 170
        Releases.cachedCode("other.apk") shouldBe null
        Releases.sizeLabel(5_034_583) shouldBe "4.8 MB"
        Releases.sizeLabel(0) shouldBe ""
    }
}
