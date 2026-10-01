package com.aliothmoon.maafw.supplement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 本地扫描纯逻辑测试。
 *
 * 校验和的期望值同样来自本机 `git hash-object`（见 [SupplementPackTest] 的说明），
 * 内容用确定性字节，避免测试自己拼一遍算法再自己断言。
 */
class SupplementPackLocalTest {

    private val hello = "hello".toByteArray()
    private val big = ByteArray(200_000) { i -> ((i * 7 + 13) % 251).toByte() }

    private val helloBlob = "b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0"
    private val bigBlob = "4640ffc49ec7665e2d82c862e3cf58e13f0b7927"

    private fun pack() = SupplementPack.Pack(
        id = "test-pack",
        nameKey = "supplement.pack.test.name",
        summaryKey = "supplement.pack.test.summary",
        requires = emptyList(),
        totalBytes = (hello.size + big.size).toLong(),
        files = listOf(
            SupplementPack.FileSpec("map/a.txt", hello.size.toLong(), helloBlob),
            SupplementPack.FileSpec("map/b.bin", big.size.toLong(), bigBlob),
        ),
    )

    private fun withTempRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("supplement-scan").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun File.write(bytes: ByteArray): File {
        parentFile?.mkdirs()
        writeBytes(bytes)
        return this
    }

    // ───────────────────── 扫描 ─────────────────────

    @Test
    fun `完整目录扫描出大小与 git blob 校验和`() {
        withTempRoot { root ->
            File(root, "map/a.txt").write(hello)
            File(root, "map/b.bin").write(big)

            val local = SupplementPackLocal.scanLocal(root, pack())

            assertEquals(2, local.size)
            assertEquals(SupplementPack.LocalFile(5, helloBlob), local["map/a.txt"])
            assertEquals(SupplementPack.LocalFile(200_000, bigBlob), local["map/b.bin"])
        }
    }

    @Test
    fun `缺失的文件不出现在扫描结果里`() {
        withTempRoot { root ->
            File(root, "map/a.txt").write(hello)

            val local = SupplementPackLocal.scanLocal(root, pack())

            assertEquals(setOf("map/a.txt"), local.keys)
        }
    }

    @Test
    fun `大小对不上时不读内容 校验和记为 null`() {
        withTempRoot { root ->
            // 清单声明 5 字节，实际 3 字节；内容对不对已无意义，不该白读
            File(root, "map/a.txt").write("abc".toByteArray())
            File(root, "map/b.bin").write(big)

            val local = SupplementPackLocal.scanLocal(root, pack())

            assertEquals(3L, local.getValue("map/a.txt").size)
            assertNull(local.getValue("map/a.txt").sha1)
        }
    }

    // ───────────────────── 与状态判定联动 ─────────────────────

    @Test
    fun `大小与校验和都对时 stateOf 判为已安装`() {
        withTempRoot { root ->
            File(root, "map/a.txt").write(hello)
            File(root, "map/b.bin").write(big)
            val target = pack()

            val local = SupplementPackLocal.scanLocal(root, target)

            assertEquals(SupplementPack.State.INSTALLED, SupplementPack.stateOf(target, local))
        }
    }

    @Test
    fun `只下了一半时 stateOf 判为未完成`() {
        withTempRoot { root ->
            File(root, "map/a.txt").write(hello)
            val target = pack()

            val local = SupplementPackLocal.scanLocal(root, target)

            assertEquals(SupplementPack.State.PARTIAL, SupplementPack.stateOf(target, local))
        }
    }

    // ───────────────────── 体积格式化 ─────────────────────

    @Test
    fun `readableSize 按 1024 进制显示`() {
        assertEquals("0 B", SupplementPackLocal.readableSize(0))
        assertEquals("512 B", SupplementPackLocal.readableSize(512))
        assertEquals("1 KiB", SupplementPackLocal.readableSize(1536))
        assertEquals("22 MiB", SupplementPackLocal.readableSize(23_083_663))
        assertTrue(SupplementPackLocal.readableSize(141_274_234).endsWith("MiB"))
    }

    // ───────────────────── 已装版本标记 ─────────────────────

    @Test
    fun `版本落盘后可读回`() {
        withTempRoot { root ->
            SupplementPackLocal.writeInstalledVersion(root, "2026.9.28")
            assertEquals("2026.9.28", SupplementPackLocal.readInstalledVersion(root))
        }
    }

    @Test
    fun `未写版本时读回为 null`() {
        withTempRoot { root ->
            assertNull(SupplementPackLocal.readInstalledVersion(root))
        }
    }

    @Test
    fun `空白版本不落盘`() {
        withTempRoot { root ->
            SupplementPackLocal.writeInstalledVersion(root, "   ")
            assertNull(SupplementPackLocal.readInstalledVersion(root))
        }
    }

    @Test
    fun `版本标记不干扰本地扫描与状态判定`() {
        withTempRoot { root ->
            File(root, "map/a.txt").write(hello)
            File(root, "map/b.bin").write(big)
            SupplementPackLocal.writeInstalledVersion(root, "2026.9.28")

            val target = pack()
            val local = SupplementPackLocal.scanLocal(root, target)

            assertEquals(setOf("map/a.txt", "map/b.bin"), local.keys)
            assertEquals(SupplementPack.State.INSTALLED, SupplementPack.stateOf(target, local))
        }
    }
}
