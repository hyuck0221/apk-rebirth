package com.hshim.apkrebirth

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.nio.charset.StandardCharsets

@Controller
class ApkController(
    private val apkService: ApkService,
    private val jobManager: JobManager,
    private val qrService: QrService,
) {

    @GetMapping("/")
    fun index(): String = "index.html"

    @PostMapping("/api/jobs")
    @ResponseBody
    fun createJob(
        @RequestParam("file") file: MultipartFile,
        @RequestParam("renamePackage", required = false) renamePackage: Boolean = false,
    ): ResponseEntity<Map<String, Any>> {
        if (file.isEmpty) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Empty file"))
        }
        val name = file.originalFilename ?: "upload.apk"
        if (!name.endsWith(".apk", ignoreCase = true)) {
            return ResponseEntity.badRequest().body(mapOf("error" to "Only .apk files are accepted"))
        }
        val job = apkService.submit(file, renamePackage)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            mapOf(
                "jobId" to job.id,
                "originalFileName" to job.originalFileName,
            ),
        )
    }

    @GetMapping("/api/jobs/{id}/stream")
    fun streamJob(@PathVariable id: String): SseEmitter {
        val emitter = SseEmitter(0L)
        jobManager.registerEmitter(id, emitter)
        return emitter
    }

    @GetMapping("/api/jobs/{id}/status")
    @ResponseBody
    fun jobStatus(@PathVariable id: String): ResponseEntity<Any> {
        val job = jobManager.get(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(job.snapshot())
    }

    @GetMapping("/api/jobs/{id}/download")
    @ResponseBody
    fun downloadResult(@PathVariable id: String): ResponseEntity<Any> {
        val job = jobManager.get(id) ?: return ResponseEntity.notFound().build()
        val file = job.resultFile
            ?: return ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("error" to "Job not completed"))

        val cd = ContentDisposition.attachment()
            .filename(job.outputFileName, StandardCharsets.UTF_8)
            .build()
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
            .contentType(MediaType.parseMediaType("application/vnd.android.package-archive"))
            .contentLength(file.length())
            .body(file.readBytes())
    }

    @GetMapping("/api/jobs/{id}/qr")
    @ResponseBody
    fun jobQr(@PathVariable id: String, request: HttpServletRequest): ResponseEntity<ByteArray> {
        val job = jobManager.get(id) ?: return ResponseEntity.notFound().build()
        if (job.resultFile == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build()
        }
        val scheme = request.getHeader("X-Forwarded-Proto") ?: request.scheme
        val host = request.getHeader("X-Forwarded-Host") ?: request.serverName
        val port = request.serverPort
        val base = buildString {
            append(scheme).append("://").append(host)
            val defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
            if (!defaultPort && request.getHeader("X-Forwarded-Host") == null) {
                append(":").append(port)
            }
        }
        val url = "$base/api/jobs/$id/download"
        val png = qrService.generatePng(url)
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .header("X-Download-Url", url)
            .body(png)
    }
}
