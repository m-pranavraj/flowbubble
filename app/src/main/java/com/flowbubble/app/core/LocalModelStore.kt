package com.flowbubble.app.core

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The models that can run inside this app's own storage through LiteRT-LM, and the only way one of
 * them ever arrives on the phone.
 *
 * Two rules shape this file. First, every size here is a byte count read from the published file
 * listing of the repository it downloads from, not a guess and not a marketing number, because a
 * user is deciding whether to spend several gigabytes of their storage. Second, nothing downloads by
 * itself: [download] is only ever reached from a button that already showed the size, and it re-checks
 * the free space at the moment it runs in case that changed.
 */

/** What a model is good at. [Embeddings] cannot answer any of the six AI jobs. */
enum class LocalModelKind { FunctionCalling, Chat, Reasoning, Embeddings }

/**
 * One downloadable model.
 *
 * [sizeBytes] is the exact length reported for [fileName] in the Hugging Face repository at
 * [repoUrl], confirmed against the `Content-Length` of the file's resolve URL.
 */
data class LocalModelSpec(
    val id: String,
    val label: String,
    val kind: LocalModelKind,
    val repoUrl: String,
    val fileName: String,
    val sizeBytes: Long,
    val minRamBytes: Long,
    val oneLine: String,
    val honestNote: String,
    /** True when the repository needs a signed-in licence acceptance before it will serve the file. */
    val licenseGated: Boolean = false
) {
    val downloadUrl: String get() = "$repoUrl/resolve/main/$fileName"

    /** Embedding models have no chat answer to give, so no AI role may be routed to one. */
    val roleCapable: Boolean get() = kind != LocalModelKind.Embeddings

    /** Roughly "will this fit", used only for ordering the list; [LocalModelCatalog.verdict] decides. */
    val needsGigabytes: Boolean get() = sizeBytes >= 2_000_000_000L
}

/** Why a model can or cannot be used on this phone right now. Each case carries its own reason. */
sealed class LocalVerdict {
    /** The file is on the phone and this phone can run it. */
    data object Ready : LocalVerdict()

    /** Not on the phone yet, and this phone could run it once it is. */
    data class Installable(val freeDiskBytes: Long) : LocalVerdict()

    /** The phone does not have enough memory to hold this model while it runs. */
    data class NotEnoughRam(val modelNeedsBytes: Long, val phoneHasBytes: Long) : LocalVerdict()

    /** The download would not fit. [freeDiskBytes] is what the phone actually has spare. */
    data class NotEnoughDisk(val neededBytes: Long, val freeDiskBytes: Long) : LocalVerdict()

    /** The publisher will not hand the file over without the user accepting a licence themselves. */
    data class NeedsLicenceAcceptance(val webUrl: String) : LocalVerdict()

    /** A useful model, but not one that can answer a text job. */
    data class NotATextModel(val reason: String) : LocalVerdict()

    val usable: Boolean get() = this is Ready

    /** The sentence the settings screen shows. Honest, specific, and it never blames the user. */
    fun reasonFor(spec: LocalModelSpec): String = when (this) {
        Ready -> "On this phone and ready."
        is Installable -> "Not downloaded yet. ${LocalModelCatalog.formatBytes(spec.sizeBytes)} of storage."
        is NotEnoughRam -> "Needs about ${LocalModelCatalog.formatBytes(modelNeedsBytes)} of " +
            "memory to run and this phone has ${LocalModelCatalog.formatBytes(phoneHasBytes)}."
        is NotEnoughDisk -> "Needs ${LocalModelCatalog.formatBytes(neededBytes)} free to download and " +
            "this phone has ${LocalModelCatalog.formatBytes(freeDiskBytes)}."
        is NeedsLicenceAcceptance -> "The publisher only serves this file after you accept its licence " +
            "in a browser, so Your Bubble cannot download it for you."
        is NotATextModel -> reason
    }
}

/**
 * The catalog and every decision that can be made from numbers alone. Nothing in here touches
 * Android, so the eligibility and storage arithmetic is covered by plain JVM tests.
 */
object LocalModelCatalog {

    private const val GB = 1_000_000_000L
    private const val MB = 1_000_000L

    /**
     * On a phone that reports itself memory-constrained, a gigabyte-scale model is only offered when
     * the memory clears this floor as well as the model's own. Android's low-RAM flag means the OS is
     * already holding pages back, so the model's stated floor is not enough headroom on its own.
     */
    private val RAM_CLASS_CEILING = 8 * GB

    /**
     * Sizes read from the file listings of the repositories named in each spec on 2026-11-02, and
     * re-checked against each file's `Content-Length`. The RAM floors follow Google's own LiteRT-LM
     * performance table, which reports peak CPU memory of about 1.7 GB for a 2.5 GB model and about
     * 3.3 GB for a 3.6 GB model on a current flagship; a phone needs several gigabytes spare above
     * the file itself before one of these is worth offering.
     */
    val all: List<LocalModelSpec> = listOf(
        LocalModelSpec(
            id = "functiongemma-270m",
            label = "FunctionGemma 270M",
            kind = LocalModelKind.FunctionCalling,
            repoUrl = "https://huggingface.co/litert-community/" +
                "functiongemma-mobile-actions_q8_ekv1024.litertlm",
            fileName = "mobile-actions_q8_ekv1024.litertlm",
            sizeBytes = 284_426_240L,
            minRamBytes = 3 * GB,
            oneLine = "Routes commands and cleans short text. The smallest one that works.",
            honestNote = "A function-calling specialist, not a chat model. It is good at picking an " +
                "intent from a sentence and at tidying dictated text; it is not good at summarizing " +
                "a long screen or translating, and it will sometimes answer a question with a " +
                "tool call instead. This is the closest thing to a 100 MB model that exists: it is " +
                "284 MB, and the only 165 MB file in this ecosystem is the embedding model below."
        ),
        LocalModelSpec(
            id = "qwen3-0.6b",
            label = "Qwen3 0.6B",
            kind = LocalModelKind.Chat,
            repoUrl = "https://huggingface.co/litert-community/Qwen3-0.6B",
            fileName = "Qwen3-0.6B.litertlm",
            sizeBytes = 613_722_264L,
            minRamBytes = 4 * GB,
            oneLine = "General instruct model. Rewrites, short summaries, simple questions.",
            honestNote = "Bigger and slower than FunctionGemma but it can hold a conversation and " +
                "follow a rewrite instruction. Long screen text still gets truncated to keep it " +
                "responsive."
        ),
        LocalModelSpec(
            id = "gemma-4-e2b",
            label = "Gemma 4 E2B",
            kind = LocalModelKind.Reasoning,
            repoUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_588_147_712L,
            minRamBytes = 6 * GB,
            oneLine = "Reasoning-grade. The one to pick for screen summaries and planning.",
            honestNote = "2.58 GB of storage and about 1.7 GB of memory while it runs on a phone CPU. " +
                "This is what a real on-device assistant costs: gigabytes of storage and gigabytes " +
                "of RAM, not a few hundred megabytes."
        ),
        LocalModelSpec(
            id = "gemma-4-e4b",
            label = "Gemma 4 E4B",
            kind = LocalModelKind.Reasoning,
            repoUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm",
            fileName = "gemma-4-E4B-it.litertlm",
            sizeBytes = 3_659_530_240L,
            minRamBytes = 8 * GB,
            oneLine = "The best on-device answers, on the newest flagship phones only.",
            honestNote = "3.66 GB on disk and about 3.3 GB of memory while running on CPU. Only " +
                "worth downloading on a phone with 8 GB of RAM or more, and it is slower than a " +
                "hosted model even then."
        ),
        LocalModelSpec(
            id = "gemma-3n-e2b",
            label = "Gemma 3n E2B",
            kind = LocalModelKind.Reasoning,
            repoUrl = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm",
            fileName = "gemma-3n-E2B-it-int4.litertlm",
            sizeBytes = 3_655_827_456L,
            minRamBytes = 6 * GB,
            oneLine = "Older Gemma 3n generation. Needs a licence acceptance first.",
            honestNote = "Google's own repository only serves this file to a signed-in account that " +
                "has accepted the Gemma terms, so it is listed here for completeness and cannot be " +
                "downloaded by the app. The Gemma 4 files above are open.",
            licenseGated = true
        ),
        LocalModelSpec(
            id = "embeddinggemma-2-text-270m",
            label = "EmbeddingGemma 2 Text 270M",
            kind = LocalModelKind.Embeddings,
            repoUrl = "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm",
            fileName = "embeddinggemma-2-text-270m.litertlm",
            sizeBytes = 164_626_432L,
            minRamBytes = 3 * GB,
            oneLine = "Local embeddings for memory. 165 MB, and not a text model.",
            honestNote = "This is the 165 MB file people mean when they talk about a small local " +
                "model. It turns text into vectors for on-device memory search; it does not write " +
                "answers, so no AI job can be routed to it. Your Bubble's memory features would use " +
                "it, not the dictation or summary paths."
        )
    )

    fun find(id: String?): LocalModelSpec? =
        all.firstOrNull { it.id == id?.trim()?.lowercase(Locale.US) }

    /** The specs that may be given an AI role: everything except the embedding model. */
    val roleAssignable: List<LocalModelSpec> = all.filter { it.roleCapable }

    /**
     * A download needs the file itself plus room to unpack and re-save it and for the phone's own
     * housekeeping while it runs. Fifteen percent, and never less than 128 MB, because a phone with
     * exactly enough bytes for the file will still refuse the write.
     */
    fun requiredFreeBytes(spec: LocalModelSpec): Long =
        spec.sizeBytes + max(128 * MB, (spec.sizeBytes * 0.15).roundToInt().toLong())

    /**
     * The whole eligibility answer for one model on one phone, from numbers only, so it can be
     * tested without a device. The order matters: a licence gate or a wrong job for the model is a
     * more useful answer than a disk complaint, and a phone that cannot run the model should not be
     * invited to download it.
     */
    fun verdict(
        spec: LocalModelSpec,
        totalRamBytes: Long,
        freeDiskBytes: Long,
        installed: Boolean,
        lowRamDevice: Boolean = false
    ): LocalVerdict {
        if (!spec.roleCapable) {
            return LocalVerdict.NotATextModel(
                "Embedding model: it turns text into vectors for on-device memory and does not " +
                    "answer questions, so no AI job can use it."
            )
        }
        if (spec.licenseGated) {
            return LocalVerdict.NeedsLicenceAcceptance(spec.downloadUrl)
        }
        // A phone that says it is memory-constrained is held to a stricter floor, never a looser one:
        // the ceiling only ever raises the requirement above the model's own floor.
        val ramFloor = if (lowRamDevice && spec.sizeBytes > 1 * GB) {
            max(spec.minRamBytes, RAM_CLASS_CEILING)
        } else {
            spec.minRamBytes
        }
        if (totalRamBytes in 1..(ramFloor - 1)) {
            return LocalVerdict.NotEnoughRam(ramFloor, totalRamBytes)
        }
        if (installed) return LocalVerdict.Ready
        val needed = requiredFreeBytes(spec)
        if (freeDiskBytes in 0 until needed) return LocalVerdict.NotEnoughDisk(needed, freeDiskBytes)
        return LocalVerdict.Installable(freeDiskBytes)
    }

    /** The download is only offered when the phone can run it and has the room for it. */
    fun canDownload(verdict: LocalVerdict): Boolean = verdict is LocalVerdict.Installable

    fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> "0 MB"
        bytes >= GB -> String.format(Locale.US, "%.2f GB", bytes.toDouble() / GB)
        bytes >= MB -> String.format(Locale.US, "%.0f MB", bytes.toDouble() / MB)
        else -> String.format(Locale.US, "%.0f KB", bytes.toDouble() / 1024)
    }
}

/** One step of an explicit download, for the progress meter. */
sealed class LocalDownloadEvent {
    data class Started(val totalBytes: Long) : LocalDownloadEvent()
    data class Progress(val downloadedBytes: Long, val totalBytes: Long) : LocalDownloadEvent()
    data object Completed : LocalDownloadEvent()
    data class Failed(val reason: String) : LocalDownloadEvent()
}

/** What the settings screen shows for one model: the spec, whether it is here, and the verdict. */
data class LocalModelStatus(
    val spec: LocalModelSpec,
    val installed: Boolean,
    val onDiskBytes: Long,
    val verdict: LocalVerdict
) {
    val canDownload: Boolean get() = LocalModelCatalog.canDownload(verdict)
    val label: String get() = spec.label
}

/**
 * The filesystem side: where the files live, whether one is there, how much room the phone has, and
 * the one function that writes bytes to disk. Every multi-hundred-megabyte fetch in this app goes
 * through [download], and every call to it comes from a user tapping a button that already named the
 * size out loud.
 */
object LocalModelStore {

    private const val DIR = "litertlm"
    private const val PART_SUFFIX = ".part"

    @Volatile
    private var appContext: Context? = null

    /**
     * LiteRT-LM needs a filesystem path and the AI call sites only ever hand around a model id, so
     * the application context is kept here. Any context-bearing call refreshes it, which happens
     * before the first completion in every flow through [com.flowbubble.app.voice.LlmClient].
     */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun contextOrNull(): Context? = appContext

    private fun requireContext(): Context =
        appContext ?: error("No context yet, so no on-device model can be opened.")

    fun directory(context: Context): File =
        File(context.applicationContext.filesDir, DIR).apply { if (!exists()) mkdirs() }

    fun fileFor(context: Context, spec: LocalModelSpec): File = File(directory(context), spec.fileName)

    fun isInstalled(context: Context, modelId: String?): Boolean {
        val spec = LocalModelCatalog.find(modelId) ?: return false
        return fileFor(context, spec).let { it.isFile && it.length() > 0 }
    }

    /**
     * Whether this role's local model can answer right now. A model that is missing, or that this
     * phone cannot hold, makes the job report itself as unconfigured rather than falling through to
     * a hosted provider that would receive the text instead.
     */
    fun isReadyNow(context: Context, modelId: String?): Boolean {
        val spec = LocalModelCatalog.find(modelId) ?: return false
        if (!spec.roleCapable || !isInstalled(context, spec.id)) return false
        attach(context)
        val verdict = LocalModelCatalog.verdict(
            spec = spec,
            totalRamBytes = totalRamBytes(context),
            freeDiskBytes = freeDiskBytes(context),
            installed = true,
            lowRamDevice = isLowRamDevice(context)
        )
        return verdict is LocalVerdict.Ready
    }

    /** The predicate the resolver takes, bound to whatever context was attached last. */
    fun localModelReady(): (String) -> Boolean {
        val context = appContext ?: return { false }
        return { modelId -> isReadyNow(context, modelId) }
    }

    /** Free bytes on the partition Your Bubble's own files live on. Honest, and only its own partition. */
    fun freeDiskBytes(context: Context): Long = runCatching {
        StatFs(context.applicationContext.filesDir.absolutePath).availableBytes
    }.getOrDefault(0L)

    fun totalRamBytes(context: Context): Long {
        @Suppress("DEPRECATION")
        val fromActivityManager: Long = runCatching {
            val info = ActivityManager.MemoryInfo()
            manager(context)?.getMemoryInfo(info)
            info.totalMem
        }.getOrDefault(0L)
        return if (fromActivityManager > 0) fromActivityManager else memTotalFromProc()
    }

    /** `/proc/meminfo` says the same thing, and needs no service to ask. */
    private fun memTotalFromProc(): Long = runCatching {
        File("/proc/meminfo").useLines { lines ->
            val line = lines.firstOrNull { it.startsWith("MemTotal:") } ?: return@useLines 0L
            (line.filter { it.isDigit() }.toLongOrNull() ?: 0L) * 1024L
        }
    }.getOrDefault(0L)

    fun isLowRamDevice(context: Context): Boolean = runCatching {
        manager(context)?.isLowRamDevice ?: false
    }.getOrDefault(false)

    private fun manager(context: Context): ActivityManager? =
        context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    /** Total bytes Your Bubble's downloaded models are taking, for the "uses X of your storage" line. */
    fun bytesUsed(context: Context): Long =
        directory(context).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun status(context: Context, spec: LocalModelSpec): LocalModelStatus {
        attach(context)
        val file = fileFor(context, spec)
        return LocalModelStatus(
            spec = spec,
            installed = file.isFile && file.length() > 0,
            onDiskBytes = if (file.isFile) file.length() else 0L,
            verdict = LocalModelCatalog.verdict(
                spec = spec,
                totalRamBytes = totalRamBytes(context),
                freeDiskBytes = freeDiskBytes(context),
                installed = file.isFile && file.length() > 0,
                lowRamDevice = isLowRamDevice(context)
            )
        )
    }

    fun statuses(context: Context): List<LocalModelStatus> =
        LocalModelCatalog.all.map { status(context, it) }

    /**
     * Streams one model file into Your Bubble's private storage. The caller has to have shown the
     * size and taken a tap; this re-checks the room before writing, downloads to a `.part` name so a
     * half file can never be mistaken for a working model, and only renames once the byte count
     * matches what the listing promised.
     */
    suspend fun download(
        context: Context,
        modelId: String?,
        onProgress: (LocalDownloadEvent) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext
            attach(app)
            val spec = LocalModelCatalog.find(modelId)
                ?: error("That model is not in the list Your Bubble ships.")
            if (spec.licenseGated) {
                error("${spec.label} is only served after you accept its licence in a browser.")
            }
            val preflight = LocalModelCatalog.verdict(
                spec = spec,
                totalRamBytes = totalRamBytes(app),
                freeDiskBytes = freeDiskBytes(app),
                installed = false,
                lowRamDevice = isLowRamDevice(app)
            )
            if (preflight !is LocalVerdict.Installable) {
                error(preflight.reasonFor(spec))
            }
            val dir = directory(app)
            val target = File(dir, spec.fileName)
            val part = File(dir, spec.fileName + PART_SUFFIX)
            if (target.isFile && target.length() == spec.sizeBytes) {
                onProgress(LocalDownloadEvent.Completed)
                return@runCatching
            }
            runCatching { part.delete() }

            val connection = (URL(spec.downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "YourBubble")
            }
            try {
                val code = connection.responseCode
                check(code in 200..299) {
                    if (code == 401 || code == 403) {
                        "${spec.label} needs a licence acceptance before it can be downloaded."
                    } else {
                        "The model server returned HTTP $code."
                    }
                }
                val declared = connection.contentLengthLong.takeIf { it > 0 } ?: spec.sizeBytes
                onProgress(LocalDownloadEvent.Started(declared))
                var written = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (written % (4 * 1024 * 1024L) < read || written >= declared) {
                                onProgress(LocalDownloadEvent.Progress(written, declared))
                            }
                        }
                        output.flush()
                    }
                }
                check(written == spec.sizeBytes) {
                    "The download stopped at ${LocalModelCatalog.formatBytes(written)} of " +
                        "${LocalModelCatalog.formatBytes(spec.sizeBytes)}. Try again."
                }
                check(part.renameTo(target)) { "Your Bubble could not save ${spec.label} in its own storage." }
                onProgress(LocalDownloadEvent.Completed)
            } finally {
                runCatching { part.delete() }
                connection.disconnect()
            }
        }
    }

    /** Removes a downloaded model and its half-finished download. Freeing space is always allowed. */
    fun delete(context: Context, modelId: String?): Boolean {
        val spec = LocalModelCatalog.find(modelId) ?: return false
        val app = context.applicationContext
        attach(app)
        val file = fileFor(app, spec)
        val part = File(directory(app), spec.fileName + PART_SUFFIX)
        val removed = (!file.isFile || runCatching { file.delete() }.getOrDefault(false))
        runCatching { part.delete() }
        return removed
    }

    /** The path LiteRT-LM opens. Null when the file is not there, which is how a job turns off. */
    fun pathFor(context: Context, spec: LocalModelSpec): String? {
        val file = fileFor(context, spec)
        return if (file.isFile && file.length() > 0) file.absolutePath else null
    }

    /** A writable directory for LiteRT-LM's compile cache, which makes the second load faster. */
    fun cacheDir(context: Context): File =
        File(context.applicationContext.cacheDir, "litertlm-cache").apply { if (!exists()) mkdirs() }
}
