package com.aliothmoon.maafw.supplement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * 补充包纯逻辑测试。
 *
 * 校验和那几个期望值是**本机 git 算出来的**（`git hash-object`）——git 是 blob 哈希的
 * 权威实现，拿它当参照比我自己拼一遍算法再自己断言要可信。
 */
class SupplementPackTest {

    private fun manifestJson(totalBytes: Long = 999) = """
        {
          "schema": 1,
          "source": {
            "repo": "MaaEnd/MaaEnd-AI",
            "commit": "abc123",
            "baseUrl": "https://example.com/base/"
          },
          "packs": [
            {
              "id": "map-navmesh",
              "nameKey": "supplement.pack.map_navmesh.name",
              "summaryKey": "supplement.pack.map_navmesh.summary",
              "requires": ["map-locate"],
              "totalBytes": $totalBytes,
              "files": [
                { "path": "map/navmesh/base.nav.gz", "size": 100, "blob": "aa" },
                { "path": "map/navmesh/base.occluder.gz", "size": 50, "blob": "bb" }
              ]
            },
            {
              "id": "map-locate",
              "nameKey": "supplement.pack.map_locate.name",
              "summaryKey": "supplement.pack.map_locate.summary",
              "requires": [],
              "totalBytes": 0,
              "files": [
                { "path": "map/cls.onnx", "size": 30, "blob": "cc" }
              ]
            }
          ]
        }
    """.trimIndent()

    private fun pack(id: String): SupplementPack.Pack =
        SupplementPack.parseManifest(manifestJson())!!.packs.first { it.id == id }

    // ───────────────────── 清单解析 ─────────────────────

    @Test
    fun `解析清单`() {
        val m = SupplementPack.parseManifest(manifestJson())!!
        assertEquals(1, m.schema)
        assertEquals("MaaEnd/MaaEnd-AI", m.source.repo)
        assertEquals(2, m.packs.size)
        val nav = m.packs.first { it.id == "map-navmesh" }
        assertEquals(listOf("map-locate"), nav.requires)
        assertEquals(2, nav.files.size)
        assertEquals("map/navmesh/base.nav.gz", nav.files[0].path)
    }

    @Test
    fun `总大小以文件之和为准 不信清单里的冗余字段`() {
        // 清单里 totalBytes 故意写成 999，实际两个文件是 100+50
        val nav = pack("map-navmesh")
        assertEquals(150L, nav.totalBytes)
    }

    @Test
    fun `结构不对时返回 null 而不是抛异常`() {
        assertNull(SupplementPack.parseManifest(null))
        assertNull(SupplementPack.parseManifest("不是 json"))
        assertNull(SupplementPack.parseManifest("{}"))
        assertNull(SupplementPack.parseManifest("""{"schema":1}"""))
        // packs 为空也当无效
        assertNull(
            SupplementPack.parseManifest(
                """{"schema":1,"source":{"repo":"a","commit":"b","baseUrl":"c"},"packs":[]}""",
            ),
        )
    }

    @Test
    fun `缺文件的包被丢弃`() {
        val m = SupplementPack.parseManifest(
            """{"schema":1,"source":{"repo":"a","commit":"b","baseUrl":"c"},
                "packs":[{"id":"x","nameKey":"n","summaryKey":"s","requires":[],
                          "files":[{"path":"p","size":1,"blob":"d"}]}]}""",
        )!!
        assertEquals(1, m.packs.size)
    }

    @Test
    fun `下载地址拼接`() {
        val m = SupplementPack.parseManifest(manifestJson())!!
        val file = m.packs.first { it.id == "map-locate" }.files.first()
        assertEquals("https://example.com/base/map/cls.onnx", SupplementPack.urlFor(m, file))
    }

    @Test
    fun `下载地址带镜像回退`() {
        val m = SupplementPack.parseManifest(manifestJson())!!
        val file = m.packs.first { it.id == "map-locate" }.files.first()
        val urls = SupplementPack.candidateUrlsFor(m, file)
        // 主源（清单里的 baseUrl，raw.githubusercontent 在国内常被重置）+ 两家镜像；
        // 每一条都指向同一 commit 的同一文件，下载后按 blob SHA-1 校验，所以换源不改变可信度。
        assertEquals(3, urls.size)
        assertEquals("https://example.com/base/map/cls.onnx", urls[0])
        assertEquals("https://cdn.jsdelivr.net/gh/MaaEnd/MaaEnd-AI@abc123/map/cls.onnx", urls[1])
        assertEquals(
            "https://ghproxy.net/https://raw.githubusercontent.com/MaaEnd/MaaEnd-AI/abc123/map/cls.onnx",
            urls[2],
        )
    }

    // ───────────────────── 安装状态 ─────────────────────

    @Test
    fun `一个文件都没有是未安装`() {
        assertEquals(
            SupplementPack.State.NOT_INSTALLED,
            SupplementPack.stateOf(pack("map-navmesh"), emptyMap()),
        )
    }

    @Test
    fun `文件齐且大小校验和都对才算已安装`() {
        val local = mapOf(
            "map/navmesh/base.nav.gz" to SupplementPack.LocalFile(100, "aa"),
            "map/navmesh/base.occluder.gz" to SupplementPack.LocalFile(50, "bb"),
        )
        assertEquals(SupplementPack.State.INSTALLED, SupplementPack.stateOf(pack("map-navmesh"), local))
    }

    @Test
    fun `只下了一半算部分安装`() {
        val local = mapOf("map/navmesh/base.nav.gz" to SupplementPack.LocalFile(100, "aa"))
        assertEquals(SupplementPack.State.PARTIAL, SupplementPack.stateOf(pack("map-navmesh"), local))
    }

    @Test
    fun `大小对但校验和对不上算部分安装`() {
        // 半个 navmesh 拿去寻路只会得到莫名其妙的结果，所以这里必须严格
        val local = mapOf(
            "map/navmesh/base.nav.gz" to SupplementPack.LocalFile(100, "WRONG"),
            "map/navmesh/base.occluder.gz" to SupplementPack.LocalFile(50, "bb"),
        )
        assertEquals(SupplementPack.State.PARTIAL, SupplementPack.stateOf(pack("map-navmesh"), local))
    }

    @Test
    fun `大小对不上也算部分安装`() {
        val local = mapOf(
            "map/navmesh/base.nav.gz" to SupplementPack.LocalFile(99, "aa"),
            "map/navmesh/base.occluder.gz" to SupplementPack.LocalFile(50, "bb"),
        )
        assertEquals(SupplementPack.State.PARTIAL, SupplementPack.stateOf(pack("map-navmesh"), local))
    }

    @Test
    fun `校验和大小写不敏感`() {
        val local = mapOf(
            "map/navmesh/base.nav.gz" to SupplementPack.LocalFile(100, "AA"),
            "map/navmesh/base.occluder.gz" to SupplementPack.LocalFile(50, "BB"),
        )
        assertEquals(SupplementPack.State.INSTALLED, SupplementPack.stateOf(pack("map-navmesh"), local))
    }

    // ───────────────────── 依赖 ─────────────────────

    @Test
    fun `缺依赖会被指出`() {
        assertEquals(listOf("map-locate"), SupplementPack.unmetRequirements(pack("map-navmesh"), emptySet()))
        assertTrue(SupplementPack.unmetRequirements(pack("map-navmesh"), setOf("map-locate")).isEmpty())
        assertTrue(SupplementPack.unmetRequirements(pack("map-locate"), emptySet()).isEmpty())
    }

    @Test
    fun `装 navmesh 会连带要求先装 locate`() {
        val all = SupplementPack.parseManifest(manifestJson())!!.packs
        val need = SupplementPack.requiredWithDependencies(pack("map-navmesh"), all, emptySet())
        assertEquals(listOf("map-locate", "map-navmesh"), need.map { it.id })
    }

    @Test
    fun `已装好的依赖不会再出现在清单里`() {
        val all = SupplementPack.parseManifest(manifestJson())!!.packs
        val need = SupplementPack.requiredWithDependencies(pack("map-navmesh"), all, setOf("map-locate"))
        assertEquals(listOf("map-navmesh"), need.map { it.id })
    }

    // ───────────────────── 校验和（参照 git） ─────────────────────

    @Test
    fun `gitBlobSha1 与 git hash-object 一致`() {
        // 这几个期望值来自本机 `git hash-object`
        assertEquals(
            "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391",
            SupplementPack.gitBlobSha1(0, ByteArray(0)),
        )
        assertEquals(
            "b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0",
            SupplementPack.gitBlobSha1(5, "hello".toByteArray()),
        )
        assertEquals(
            "ce013625030ba8dba906f756967f9e9ca394464a",
            SupplementPack.gitBlobSha1(6, "hello\n".toByteArray()),
        )
    }

    @Test
    fun `流式校验和与一次性结果一致（跨多次 read 分块）`() {
        // 确定性内容，期望值同样来自 git hash-object
        val data = ByteArray(200_000) { i -> ((i * 7 + 13) % 251).toByte() }
        assertEquals("4640ffc49ec7665e2d82c862e3cf58e13f0b7927", SupplementPack.gitBlobSha1(200_000, data))

        val (sha, total) = SupplementPack.gitBlobSha1OfStream(200_000, ByteArrayInputStream(data))
        assertEquals("4640ffc49ec7665e2d82c862e3cf58e13f0b7927", sha)
        assertEquals(200_000L, total)
    }

    @Test
    fun `流式校验和能发现被截断的流`() {
        val data = "hello world".toByteArray()
        // 声明 size 与真实内容不符 -> 哈希自然对不上（下载截断就是这种情形）
        val (sha, _) = SupplementPack.gitBlobSha1OfStream(999, ByteArrayInputStream(data))
        assertTrue(sha != SupplementPack.gitBlobSha1(data.size.toLong(), data))
    }
}
