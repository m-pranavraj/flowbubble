package com.flowbubble.app.voice

import com.flowbubble.app.core.LocalModelCatalog
import com.flowbubble.app.core.LocalModelKind
import com.flowbubble.app.core.LocalModelSpec
import com.flowbubble.app.core.LocalVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every number a user sees before agreeing to a download, and every answer about whether this phone
 * can run this model, comes out of [LocalModelCatalog] as plain arithmetic. That means it can be
 * tested here rather than on a device, which matters because the same arithmetic is the only thing
 * standing between a tap and several gigabytes leaving the network.
 *
 * The sizes are pinned to exact byte counts on purpose: they were read from the file listings of the
 * repositories each spec downloads from and re-checked against each file's `Content-Length`. If one
 * changes upstream, this test fails instead of the app quietly lying about how much room is needed.
 */
class LocalModelCatalogTest {

    private val gb = 1_000_000_000L
    private val mb = 1_000_000L

    private fun spec(id: String): LocalModelSpec {
        val found = LocalModelCatalog.find(id)
        assertNotNull("no catalog entry for $id", found)
        return found!!
    }

    private fun verdictOf(
        id: String,
        ram: Long,
        free: Long,
        installed: Boolean = false,
        lowRam: Boolean = false
    ): LocalVerdict = LocalModelCatalog.verdict(spec(id), ram, free, installed, lowRam)

    // ---- the catalog itself ----

    @Test
    fun `every catalog entry is findable by a sloppy id`() {
        assertNotNull(LocalModelCatalog.find("  Qwen3-0.6B  "))
        assertEquals("qwen3-0.6b", LocalModelCatalog.find("QWEN3-0.6B")?.id)
        assertNull(LocalModelCatalog.find("gpt-4o-mini"))
        assertNull(LocalModelCatalog.find(null))
        assertEquals(
            "ids must be unique",
            LocalModelCatalog.all.size,
            LocalModelCatalog.all.map { it.id }.distinct().size
        )
        assertTrue(LocalModelCatalog.all.all { it.label.isNotBlank() && it.oneLine.isNotBlank() })
    }

    @Test
    fun `each entry downloads the file it names from the repository it names`() {
        val qwen = spec("qwen3-0.6b")
        assertEquals(
            "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
            qwen.downloadUrl
        )
        assertTrue(LocalModelCatalog.all.all { it.downloadUrl.startsWith("https://huggingface.co/") })
        assertTrue(LocalModelCatalog.all.all { it.fileName.endsWith(".litertlm") })
    }

    @Test
    fun `the published sizes are the byte counts the repositories report`() {
        assertEquals(284_426_240L, spec("functiongemma-270m").sizeBytes)
        assertEquals(613_722_264L, spec("qwen3-0.6b").sizeBytes)
        assertEquals(2_588_147_712L, spec("gemma-4-e2b").sizeBytes)
        assertEquals(3_659_530_240L, spec("gemma-4-e4b").sizeBytes)
        assertEquals(3_655_827_456L, spec("gemma-3n-e2b").sizeBytes)
        assertEquals(164_626_432L, spec("embeddinggemma-2-text-270m").sizeBytes)
        assertTrue(LocalModelCatalog.all.all { it.sizeBytes > 0 && it.minRamBytes > it.sizeBytes })
    }

    @Test
    fun `there is no such thing as a hundred megabyte chat model`() {
        // The honest answer to "can't you just use a 100 MB model": the only file under 200 MB in this
        // ecosystem makes embeddings, and the smallest thing that can actually serve a job is 284 MB.
        val tiny = LocalModelCatalog.all.filter { it.sizeBytes < 200 * mb }
        assertEquals(listOf("embeddinggemma-2-text-270m"), tiny.map { it.id })
        assertTrue(tiny.all { !it.roleCapable })
        assertTrue(LocalModelCatalog.roleAssignable.all { it.sizeBytes > 250 * mb })
        assertEquals(
            "no role may be routed to an embedding model",
            emptyList<String>(),
            LocalModelCatalog.roleAssignable.filter { it.kind == LocalModelKind.Embeddings }.map { it.id }
        )
        assertTrue(spec("embeddinggemma-2-text-270m").honestNote.contains("165 MB"))
    }

    @Test
    fun `functiongemma is described as the specialist it is`() {
        val fg = spec("functiongemma-270m")
        assertEquals(LocalModelKind.FunctionCalling, fg.kind)
        assertTrue(fg.honestNote.contains("not a chat model"))
        assertTrue(
            "the note must carry the real byte count, not the hoped-for one",
            fg.honestNote.contains("284 MB")
        )
        assertTrue(fg.oneLine.isNotBlank())
        assertTrue(spec("gemma-4-e2b").honestNote.contains("2.58 GB"))
        assertTrue(spec("gemma-4-e4b").honestNote.contains("3.66 GB"))
    }

    // ---- disk arithmetic ----

    @Test
    fun `a download always asks for more room than the file itself`() {
        for (entry in LocalModelCatalog.all) {
            val needed = LocalModelCatalog.requiredFreeBytes(entry)
            assertTrue("${entry.id} needs more than its own size", needed > entry.sizeBytes)
        }
        // A small file gets a flat 128 MB of headroom; a big one gets fifteen percent of itself.
        val small = spec("functiongemma-270m")
        assertEquals(small.sizeBytes + 128 * mb, LocalModelCatalog.requiredFreeBytes(small))
        val big = spec("gemma-4-e4b")
        val headroom = LocalModelCatalog.requiredFreeBytes(big) - big.sizeBytes
        assertTrue(
            "headroom was $headroom",
            headroom in (big.sizeBytes * 0.149).toLong()..(big.sizeBytes * 0.151).toLong()
        )
        assertTrue(LocalModelCatalog.requiredFreeBytes(big) < 5 * gb)
    }

    @Test
    fun `not enough free space is refused with both numbers`() {
        val fg = spec("functiongemma-270m")
        val needed = LocalModelCatalog.requiredFreeBytes(fg)
        val verdict = verdictOf(fg.id, 12 * gb, needed - 1)
        assertTrue(verdict.toString(), verdict is LocalVerdict.NotEnoughDisk)
        assertEquals(needed, (verdict as LocalVerdict.NotEnoughDisk).neededBytes)
        assertEquals(needed - 1, verdict.freeDiskBytes)
        assertFalse(verdict.usable)
        assertFalse(LocalModelCatalog.canDownload(verdict))
        assertTrue(verdict.reasonFor(fg).contains("free"))
        // Exactly enough room is enough room.
        assertTrue(verdictOf(fg.id, 12 * gb, needed) is LocalVerdict.Installable)
    }

    @Test
    fun `an installable model is the only one offered a download button`() {
        val verdict = verdictOf("qwen3-0.6b", 12 * gb, 20 * gb)
        assertTrue(verdict.toString(), verdict is LocalVerdict.Installable)
        assertEquals(20 * gb, (verdict as LocalVerdict.Installable).freeDiskBytes)
        assertTrue(LocalModelCatalog.canDownload(verdict))
        // The size is said out loud before the tap, not after it.
        assertTrue(
            verdict.reasonFor(spec("qwen3-0.6b")).contains(LocalModelCatalog.formatBytes(spec("qwen3-0.6b").sizeBytes))
        )
    }

    @Test
    fun `a model already on the phone is ready and needs no further room`() {
        val verdict = verdictOf("qwen3-0.6b", 12 * gb, 0, installed = true)
        assertTrue(verdict.toString(), verdict is LocalVerdict.Ready)
        assertTrue(verdict.usable)
        assertFalse(LocalModelCatalog.canDownload(verdict))
        assertEquals("On this phone and ready.", verdict.reasonFor(spec("qwen3-0.6b")))
    }

    // ---- memory and licence gates ----

    @Test
    fun `a phone that cannot hold the model is not invited to download it`() {
        val verdict = verdictOf("gemma-4-e4b", 6 * gb, 200 * gb)
        assertTrue(verdict.toString(), verdict is LocalVerdict.NotEnoughRam)
        assertEquals(spec("gemma-4-e4b").minRamBytes, (verdict as LocalVerdict.NotEnoughRam).modelNeedsBytes)
        assertEquals(6 * gb, verdict.phoneHasBytes)
        assertTrue(verdict.reasonFor(spec("gemma-4-e4b")).contains("memory"))
        assertTrue(verdictOf("gemma-4-e4b", 8 * gb, 200 * gb) is LocalVerdict.Installable)
    }

    @Test
    fun `a memory-constrained phone is held to a stricter floor not a looser one`() {
        // The ceiling is 8 GB and only speaks for models whose file is over a gigabyte, so it never
        // lowers a requirement and never raises a complaint about a small model.
        assertTrue(verdictOf("gemma-4-e2b", 7 * gb, 200 * gb) is LocalVerdict.Installable)
        val raised = verdictOf("gemma-4-e2b", 7 * gb, 200 * gb, lowRam = true)
        assertTrue(raised.toString(), raised is LocalVerdict.NotEnoughRam)
        assertEquals(8 * gb, (raised as LocalVerdict.NotEnoughRam).modelNeedsBytes)
        assertEquals(7 * gb, raised.phoneHasBytes)
        // Its own floor already sits at the ceiling, so E4B's answer does not move.
        assertTrue(verdictOf("gemma-4-e4b", 8 * gb, 200 * gb, lowRam = true) is LocalVerdict.Installable)
        assertEquals(
            spec("gemma-4-e4b").minRamBytes,
            (verdictOf("gemma-4-e4b", 7 * gb, 200 * gb, lowRam = true) as LocalVerdict.NotEnoughRam)
                .modelNeedsBytes
        )
        // A sub-gigabyte model is judged only by its own floor, flagged or not.
        assertTrue(verdictOf("qwen3-0.6b", 4500 * mb, 200 * gb, lowRam = true) is LocalVerdict.Installable)
        assertTrue(verdictOf("functiongemma-270m", 3 * gb, 200 * gb, lowRam = true) is LocalVerdict.Installable)
    }

    @Test
    fun `unknown memory does not block a model the phone may well be able to run`() {
        // totalRamBytes reads 0 when the phone would not say; the gate then stays quiet and the engine
        // reports the real failure if there is one, rather than the app guessing.
        assertTrue(verdictOf("functiongemma-270m", 0, 200 * gb) is LocalVerdict.Installable)
    }

    @Test
    fun `a licence-gated model is refused before any space arithmetic`() {
        val gated = spec("gemma-3n-e2b")
        assertTrue(gated.licenseGated)
        val verdict = verdictOf(gated.id, 16 * gb, 16 * gb)
        assertTrue(verdict.toString(), verdict is LocalVerdict.NeedsLicenceAcceptance)
        assertEquals(gated.downloadUrl, (verdict as LocalVerdict.NeedsLicenceAcceptance).webUrl)
        assertFalse(LocalModelCatalog.canDownload(verdict))
        assertTrue(verdict.reasonFor(gated).contains("licence"))
        // Even a half-downloaded gated model is not offered as ready, so the app never implies it
        // fetched something it could not.
        assertTrue(verdictOf(gated.id, 16 * gb, 16 * gb, installed = true) is LocalVerdict.NeedsLicenceAcceptance)
        assertFalse("the open models must not inherit the gate", spec("gemma-4-e2b").licenseGated)
    }

    @Test
    fun `an embedding model is never a candidate for a text job`() {
        val verdict = verdictOf("embeddinggemma-2-text-270m", 16 * gb, 16 * gb, installed = true)
        assertTrue(verdict.toString(), verdict is LocalVerdict.NotATextModel)
        assertFalse(verdict.usable)
        assertTrue(verdict.reasonFor(spec("embeddinggemma-2-text-270m")).contains("mbedding"))
        // The gate that matters for the settings screen: it is not offered for download as a model.
        assertFalse(LocalModelCatalog.canDownload(verdict))
        assertFalse(spec("embeddinggemma-2-text-270m").roleCapable)
    }

    @Test
    fun `the gates come before the download offer`() {
        // Too little RAM and too little disk at once: the RAM answer is the useful one, because the
        // download would be a waste of the user's data.
        assertTrue(verdictOf("gemma-4-e4b", 4 * gb, 0) is LocalVerdict.NotEnoughRam)
        // Not a text model and not enough room: the job mismatch wins.
        assertTrue(verdictOf("embeddinggemma-2-text-270m", 1 * gb, 0) is LocalVerdict.NotATextModel)
        // Gated and out of space: the licence is the thing the user has to act on.
        assertTrue(verdictOf("gemma-3n-e2b", 16 * gb, 0) is LocalVerdict.NeedsLicenceAcceptance)
    }

    // ---- bytes to words ----

    @Test
    fun `sizes are shown in the unit a person reads`() {
        assertEquals("0 MB", LocalModelCatalog.formatBytes(0))
        assertEquals("0 MB", LocalModelCatalog.formatBytes(-5))
        assertEquals("512 KB", LocalModelCatalog.formatBytes(512 * 1024))
        assertEquals("165 MB", LocalModelCatalog.formatBytes(164_626_432))
        assertEquals("284 MB", LocalModelCatalog.formatBytes(284_426_240))
        assertEquals("614 MB", LocalModelCatalog.formatBytes(613_722_264))
        assertEquals("2.59 GB", LocalModelCatalog.formatBytes(2_588_147_712))
        assertEquals("3.66 GB", LocalModelCatalog.formatBytes(3_659_530_240))
        assertEquals("1.00 GB", LocalModelCatalog.formatBytes(1_000_000_000))
    }

    @Test
    fun `every reason a model can give names something the user can act on`() {
        val fg = spec("functiongemma-270m")
        for (verdict in listOf(
            LocalVerdict.Ready,
            LocalVerdict.Installable(9 * gb),
            LocalVerdict.NotEnoughRam(8 * gb, 4 * gb),
            LocalVerdict.NotEnoughDisk(2 * gb, 1 * gb),
            LocalVerdict.NeedsLicenceAcceptance(fg.downloadUrl),
            LocalVerdict.NotATextModel("Embedding model: it does not answer questions.")
        )) {
            val reason = verdict.reasonFor(fg)
            assertTrue("$verdict gave no reason", reason.isNotBlank())
            assertFalse("$verdict leaked a URL or a filesystem path", reason.contains("http") || reason.contains("/data/"))
            assertTrue("$verdict ended without a full stop", reason.endsWith("."))
        }
    }

    @Test
    fun `the role-assignable list is the catalog minus the embedding model`() {
        assertEquals(
            LocalModelCatalog.all.filter { it.kind != LocalModelKind.Embeddings }.map { it.id },
            LocalModelCatalog.roleAssignable.map { it.id }
        )
        assertTrue(LocalModelCatalog.roleAssignable.any { it.sizeBytes < 1 * gb })
        assertTrue(LocalModelCatalog.roleAssignable.any { it.needsGigabytes })
    }
}
