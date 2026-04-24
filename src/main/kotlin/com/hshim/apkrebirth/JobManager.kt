package com.hshim.apkrebirth

import tools.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

enum class JobStage(val label: String, val progress: Int) {
    PENDING("대기 중", 0),
    UPLOADED("업로드 완료", 5),
    DECOMPILING("APK 내용 분석 중", 20),
    PATCHING("호환성 문제 수정 중", 45),
    REBUILDING("새 APK 만드는 중", 65),
    SIGNING("설치 준비 마무리", 85),
    COMPLETED("완료", 100),
    FAILED("실패", 100),
}

data class JobSnapshot(
    val id: String,
    val stage: String,
    val stageLabel: String,
    val progress: Int,
    val message: String,
    val originalFileName: String?,
    val outputFileName: String?,
    val ready: Boolean,
    val error: String?,
    val logs: List<String>,
)

class Job(
    val id: String,
    val originalFileName: String,
    val workDir: File,
) {
    @Volatile var stage: JobStage = JobStage.PENDING
    @Volatile var message: String = "Waiting to start"
    @Volatile var resultFile: File? = null
    @Volatile var error: String? = null
    @Volatile var finishedAt: Long? = null
    val createdAt: Long = System.currentTimeMillis()
    val logs: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val emitters: CopyOnWriteArrayList<SseEmitter> = CopyOnWriteArrayList()

    val outputFileName: String
        get() = originalFileName.substringBeforeLast('.', originalFileName) + "-rebirth.apk"

    fun snapshot(): JobSnapshot = JobSnapshot(
        id = id,
        stage = stage.name,
        stageLabel = stage.label,
        progress = stage.progress,
        message = message,
        originalFileName = originalFileName,
        outputFileName = if (stage == JobStage.COMPLETED) outputFileName else null,
        ready = stage == JobStage.COMPLETED,
        error = error,
        logs = logs.toList(),
    )
}

@Component
class JobManager(private val objectMapper: ObjectMapper) {

    @Value("\${apk-rebirth.job-ttl-minutes:60}")
    private var jobTtlMinutes: Long = 60

    private val jobs = ConcurrentHashMap<String, Job>()

    fun create(originalFileName: String, workDir: File): Job {
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val job = Job(id, originalFileName, workDir)
        jobs[id] = job
        return job
    }

    fun get(id: String): Job? = jobs[id]

    fun registerEmitter(id: String, emitter: SseEmitter) {
        val job = jobs[id] ?: run { emitter.complete(); return }
        job.emitters.add(emitter)
        emitter.onCompletion { job.emitters.remove(emitter) }
        emitter.onTimeout { job.emitters.remove(emitter); emitter.complete() }
        emitter.onError { job.emitters.remove(emitter) }
        sendSnapshot(job, emitter)
        if (job.stage == JobStage.COMPLETED || job.stage == JobStage.FAILED) {
            emitter.complete()
        }
    }

    fun updateStage(job: Job, stage: JobStage, message: String = stage.label) {
        job.stage = stage
        job.message = message
        job.logs.add("[${stage.label}] $message")
        broadcast(job)
    }

    fun log(job: Job, line: String) {
        job.logs.add(line)
        broadcast(job)
    }

    fun complete(job: Job, resultFile: File) {
        job.resultFile = resultFile
        job.finishedAt = System.currentTimeMillis()
        updateStage(job, JobStage.COMPLETED, "새로 태어난 APK가 준비됐어요")
        closeEmitters(job)
    }

    fun fail(job: Job, error: String) {
        job.error = error
        job.finishedAt = System.currentTimeMillis()
        updateStage(job, JobStage.FAILED, error)
        closeEmitters(job)
    }

    @Scheduled(fixedDelayString = "\${apk-rebirth.cleanup-interval-ms:300000}")
    fun cleanupExpiredJobs() {
        val ttlMs = jobTtlMinutes * 60_000L
        val now = System.currentTimeMillis()
        val expired = jobs.values.filter { job ->
            val finished = job.finishedAt ?: return@filter false
            now - finished > ttlMs
        }
        expired.forEach { job ->
            jobs.remove(job.id)
            runCatching { job.workDir.deleteRecursively() }
            runCatching {
                job.resultFile?.let { if (it.exists()) it.delete() }
            }
        }
        if (expired.isNotEmpty()) {
            println("🧹 Cleaned up ${expired.size} expired jobs")
        }
    }

    private fun closeEmitters(job: Job) {
        job.emitters.toList().forEach {
            runCatching { it.complete() }
        }
        job.emitters.clear()
    }

    private fun broadcast(job: Job) {
        val dead = mutableListOf<SseEmitter>()
        job.emitters.forEach { emitter ->
            try {
                sendSnapshot(job, emitter)
            } catch (_: Exception) {
                dead.add(emitter)
            }
        }
        job.emitters.removeAll(dead)
    }

    private fun sendSnapshot(job: Job, emitter: SseEmitter) {
        val payload = objectMapper.writeValueAsString(job.snapshot())
        emitter.send(SseEmitter.event().name("state").data(payload))
    }
}
