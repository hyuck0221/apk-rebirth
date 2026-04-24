package com.hshim.apkrebirth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dom4j.Document
import org.dom4j.Element
import org.dom4j.io.SAXReader
import org.dom4j.io.XMLWriter
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@Service
class ApkService(private val jobManager: JobManager) {

    @Value("\${apk-rebirth.apktool-path:apktool.jar}")
    private lateinit var apktoolPath: String

    @Value("\${apk-rebirth.uber-signer-path:uber-apk-signer.jar}")
    private lateinit var uberSignerPath: String

    @Value("\${apk-rebirth.apktool-version:2.11.1}")
    private lateinit var apktoolVersion: String

    @Value("\${apk-rebirth.uber-signer-version:1.3.0}")
    private lateinit var uberSignerVersion: String

    @Value("\${apk-rebirth.workspace:}")
    private lateinit var workspaceProp: String

    @Value("\${apk-rebirth.min-sdk-floor:21}")
    private var minSdkFloor: Int = 21

    // 34 = Android 14. OEMs (HyperOS 2+, One UI 7+) crank their "호환되지 않습니다"
    // threshold year-by-year; 34 is the highest level that still lets us avoid Android
    // 15's edge-to-edge enforcement. Mitigations for the hazards this flips on:
    //   - target ≥ 31 exported enforcement  → handled in modifyManifest
    //   - target ≥ 31 PendingIntent mutability → handled by injectPendingIntentCompatShim
    //   - target ≥ 34 foreground-service-type required → handled in modifyManifest
    //     (we add foregroundServiceType="dataSync" + the matching permission)
    @Value("\${apk-rebirth.target-sdk-version:34}")
    private var targetSdkVersion: Int = 34

    private val workspaceRoot: File by lazy {
        val dir = if (workspaceProp.isNotBlank()) File(workspaceProp)
        else File(System.getProperty("java.io.tmpdir"), "apk-rebirth-workspace")
        dir.apply { mkdirs() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val finalSignerPath: String by lazy { File(uberSignerPath).absolutePath }

    private val finalApktoolPath: String by lazy { File(apktoolPath).absolutePath }

    @jakarta.annotation.PostConstruct
    fun checkAndDownloadTools() {
        val signerFile = File(uberSignerPath)
        if (!signerFile.exists()) {
            println("🚀 uber-apk-signer.jar not found. Downloading v$uberSignerVersion...")
            downloadFile(
                "https://github.com/patrickfav/uber-apk-signer/releases/download/v$uberSignerVersion/uber-apk-signer-$uberSignerVersion.jar",
                signerFile,
            )
        }
        val apktoolFile = File(apktoolPath)
        if (!apktoolFile.exists()) {
            println("🚀 apktool.jar not found. Downloading v$apktoolVersion...")
            downloadFile(
                "https://github.com/iBotPeaches/Apktool/releases/download/v$apktoolVersion/apktool_$apktoolVersion.jar",
                apktoolFile,
            )
        }
        check(File(finalSignerPath).exists()) { "uber-apk-signer.jar 를 준비할 수 없어요: $finalSignerPath" }
        check(File(finalApktoolPath).exists()) { "apktool.jar 를 준비할 수 없어요: $finalApktoolPath" }
        println("🔧 Using apktool: $finalApktoolPath")
        println("🔧 Using uber-apk-signer: $finalSignerPath")
        println("📁 Workspace: ${workspaceRoot.absolutePath}")
    }

    private fun downloadFile(urlStr: String, targetFile: File) {
        try {
            val url = java.net.URI(urlStr).toURL()
            url.openStream().use { input ->
                Files.copy(input, targetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            println("✅ Download complete: ${targetFile.absolutePath}")
        } catch (e: Exception) {
            println("❌ Failed to download from $urlStr: ${e.message}")
        }
    }

    fun submit(file: MultipartFile, renamePackage: Boolean = false): Job {
        val originalName = file.originalFilename ?: "upload.apk"
        val jobWorkDir = File(workspaceRoot, "job-${System.currentTimeMillis()}-${(0..9999).random()}").apply { mkdirs() }
        val job = jobManager.create(originalName, jobWorkDir)

        val inputApk = File(jobWorkDir, "input.apk")
        file.transferTo(inputApk)
        jobManager.updateStage(job, JobStage.UPLOADED, "${"%.1f".format(inputApk.length() / 1024.0 / 1024.0)}MB 받았어요")

        scope.launch {
            try {
                val result = processApk(job, inputApk, renamePackage)
                if (result != null) {
                    jobManager.complete(job, result)
                } else {
                    jobManager.fail(job, "결과 파일을 만들지 못했어요. 다른 APK로 다시 시도해 주세요.")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val friendly = friendlyError(job.stage, e.message)
                jobManager.fail(job, friendly)
            }
        }
        return job
    }

    private suspend fun processApk(job: Job, inputApk: File, renamePackage: Boolean): File? {
        val tempDir = job.workDir
        val extractedDir = File(tempDir, "extracted")

        val apktoolCmd = listOf("java", "-Xmx2g", "-jar", finalApktoolPath)

        jobManager.updateStage(job, JobStage.DECOMPILING, "앱 내용을 열어보고 있어요")
        executeCommand(
            job, tempDir,
            *(apktoolCmd + listOf("d", inputApk.absolutePath, "-o", extractedDir.absolutePath, "-f")).toTypedArray(),
        )

        val manifestFile = File(extractedDir, "AndroidManifest.xml")
        val originalPackage = readPackageName(manifestFile)
        val newPackageName = if (renamePackage) computeRenamedPackage(originalPackage) else null

        jobManager.updateStage(job, JobStage.PATCHING, "최신 안드로이드에 맞게 고치고 있어요")
        modifyManifest(job, manifestFile, originalPackage, newPackageName)
        writeNetworkSecurityConfig(job, extractedDir)
        writeBackupRules(job, extractedDir)
        patchApktoolYaml(job, extractedDir, newPackageName)
        normaliseNativeLibs(job, extractedDir)
        injectPendingIntentCompatShim(job, extractedDir)
        if (newPackageName != null && originalPackage != null) {
            jobManager.log(job, "🕵️ 패키지 이름을 '$originalPackage' → '$newPackageName' 로 바꿨어요")
        }

        val modifiedApk = File(tempDir, "modified.apk")
        jobManager.updateStage(job, JobStage.REBUILDING, "새 APK 파일로 다시 묶고 있어요")
        executeCommand(
            job, tempDir,
            *(apktoolCmd + listOf("b", extractedDir.absolutePath, "-o", modifiedApk.absolutePath, "-f", "--no-crunch")).toTypedArray(),
        )

        val signedDir = File(tempDir, "signed").apply { mkdirs() }
        jobManager.updateStage(job, JobStage.SIGNING, "설치할 수 있게 서명하고 정리 중")
        executeCommand(
            job, tempDir,
            "java", "-jar", finalSignerPath,
            "--apks", modifiedApk.absolutePath,
            "--out", signedDir.absolutePath,
            "--allowResign",
        )

        val produced = signedDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            .toList()
        jobManager.log(job, "📦 서명 산출물: ${produced.joinToString { it.name }.ifBlank { "(없음)" }}")

        val signedApk = produced.firstOrNull {
            val n = it.name.lowercase()
            "aligned" in n || "signed" in n
        } ?: produced.firstOrNull()

        if (signedApk == null) {
            jobManager.log(job, "⚠ 서명 결과물이 비어있어 원본 rebuild APK 로 대체합니다")
            val finalApk = File(tempDir, job.outputFileName)
            modifiedApk.copyTo(finalApk, overwrite = true)
            return finalApk
        }

        val finalApk = File(tempDir, job.outputFileName)
        signedApk.copyTo(finalApk, overwrite = true)
        return finalApk
    }

    private fun readPackageName(manifestFile: File): String? {
        if (!manifestFile.exists()) return null
        return runCatching { SAXReader().read(manifestFile).rootElement.attributeValue("package") }.getOrNull()
    }

    private suspend fun modifyManifest(
        job: Job,
        manifestFile: File,
        originalPackage: String?,
        renameTo: String?,
    ) = withContext(Dispatchers.IO) {
        if (!manifestFile.exists()) return@withContext

        val reader = SAXReader()
        val document: Document = reader.read(manifestFile)
        val root = document.rootElement

        if (renameTo != null && originalPackage != null) {
            // 1) Convert relative android:name references (".Foo") to absolute using the ORIGINAL package
            //    so the classes still resolve after we change the manifest package attribute.
            absolutizeRelativeNames(root, originalPackage)
            // 2) Rewrite the root package attribute
            root.attribute("package")?.let { it.value = renameTo }
            // 3) Update FileProvider authorities etc. that embed the old package string
            renameProviderAuthorities(root, originalPackage, renameTo)
        }

        val androidNs = root.getNamespaceForPrefix("android")
            ?: org.dom4j.Namespace.get("android", "http://schemas.android.com/apk/res/android").also { root.add(it) }

        fun qn(name: String) = org.dom4j.QName(name, androidNs)

        root.attribute(qn("sharedUserId"))?.let { root.remove(it) }
        root.attribute(qn("sharedUserLabel"))?.let { root.remove(it) }

        // OEM package installers (HyperOS 2, One UI 6+) read these manifest-root attributes
        // as a "this APK was built against a modern SDK" signal. Apktool's rebuild does NOT
        // re-inject them automatically, so without this step every patched APK shows the
        // "이 앱은 Android 최신 버전과 호환되지 않습니다" dialog regardless of targetSdkVersion.
        // We force-overwrite them to match our target so the APK looks freshly compiled.
        val buildCode = targetSdkVersion.toString()
        val buildCodename = sdkCodename(targetSdkVersion)
        fun upsertRootAttr(name: String, value: String, inAndroidNs: Boolean) {
            if (inAndroidNs) {
                val attr = root.attribute(qn(name))
                if (attr != null) attr.value = value else root.addAttribute(qn(name), value)
            } else {
                val attr = root.attribute(name)
                if (attr != null) attr.value = value else root.addAttribute(name, value)
            }
        }
        upsertRootAttr("compileSdkVersion", buildCode, inAndroidNs = true)
        upsertRootAttr("compileSdkVersionCodename", buildCodename, inAndroidNs = true)
        // platformBuildVersionCode/Name live in the default (no-prefix) namespace.
        upsertRootAttr("platformBuildVersionCode", buildCode, inAndroidNs = false)
        upsertRootAttr("platformBuildVersionName", buildCodename, inAndroidNs = false)

        // Remove install-restricting siblings that often block on modern devices
        root.elements("compatible-screens").toList().forEach { root.remove(it as Element) }
        root.elements("supports-screens").toList().forEach { root.remove(it as Element) }
        root.elements("uses-configuration").toList().forEach { root.remove(it as Element) }

        // Downgrade hard feature requirements to soft so missing hardware doesn't block install
        root.elements("uses-feature").forEach { feat ->
            val f = feat as Element
            val req = f.attribute(qn("required"))
            if (req == null) {
                f.addAttribute(qn("required"), "false")
            } else if (req.value == "true") {
                req.value = "false"
            }
        }

        // Always force installLocation=auto (missing attribute = internal only on older Android).
        root.attribute(qn("installLocation"))?.let { root.remove(it) }
        root.addAttribute(qn("installLocation"), "auto")

        // Remove <uses-sdk> entirely from the manifest XML.
        // Modern Android builds drive minSdk/targetSdk exclusively from build config
        // (apktool.yml -> aapt2 CLI flags). Keeping a <uses-sdk> here can confuse aapt2
        // or be ignored, so we let apktool.yml be the single source of truth.
        root.elements("uses-sdk").toList().forEach { root.remove(it as Element) }

        val requiredPermissions = listOf(
            "android.permission.INTERNET",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            // Required at target >= 34 when any service declares foregroundServiceType="dataSync".
            // Harmless on older Android versions (just ignored), so we always add it.
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        )
        val existingPermNames = root.elements("uses-permission")
            .map { (it as Element).attributeValue(qn("name")) }
            .toMutableSet()

        root.elements("uses-permission").forEach { permEl ->
            val el = permEl as Element
            el.attribute(qn("maxSdkVersion"))?.let { el.remove(it) }
        }

        requiredPermissions.forEach { perm ->
            if (!existingPermNames.contains(perm)) {
                root.addElement("uses-permission").addAttribute(qn("name"), perm)
                existingPermNames.add(perm)
            }
        }

        val application = root.element("application")
        application?.let { app ->
            app.attribute(qn("testOnly"))?.let { app.remove(it) }
            app.attribute(qn("appComponentFactory"))?.let { app.remove(it) }
            app.attribute(qn("debuggable"))?.let { app.remove(it) }
            app.attribute(qn("fullBackupContent"))?.let { app.remove(it) }
            app.attribute(qn("dataExtractionRules"))?.let { app.remove(it) }

            val flags = mapOf(
                "extractNativeLibs" to "true",
                "usesCleartextTraffic" to "true",
                "requestLegacyExternalStorage" to "true",
                "preserveLegacyExternalStorage" to "true",
                "allowBackup" to "true",
                "hardwareAccelerated" to "true",
                "largeHeap" to "true",
                "allowNativeHeapPointerTagging" to "false",
            )
            flags.forEach { (name, value) ->
                val attr = app.attribute(qn(name))
                if (attr == null) app.addAttribute(qn(name), value) else attr.value = value
            }

            app.addAttribute(qn("networkSecurityConfig"), "@xml/network_security_config")

            // Soft-require any <uses-library> so missing libs (e.g. legacy Google Maps) don't block install
            app.elements("uses-library").forEach { lib ->
                val l = lib as Element
                val req = l.attribute(qn("required"))
                if (req == null) {
                    l.addAttribute(qn("required"), "false")
                } else if (req.value == "true") {
                    req.value = "false"
                }
            }

            // Old apps built before Android 9 (API 28) often link against the Apache HTTP
            // client classes (DefaultHttpClient, HttpGet, ...). That namespace was removed
            // from the Android framework at API 28+, so the app crashes at runtime with
            // ClassNotFoundException unless we opt back into the legacy shared library.
            // required=false means: load it when the OS has it, otherwise skip silently.
            @Suppress("UNCHECKED_CAST")
            val libNames = (app.elements("uses-library") as List<Element>)
                .mapNotNull { it.attributeValue(qn("name")) }
                .toSet()
            if ("org.apache.http.legacy" !in libNames) {
                app.addElement("uses-library")
                    .addAttribute(qn("name"), "org.apache.http.legacy")
                    .addAttribute(qn("required"), "false")
            }

            val components = listOf("activity", "activity-alias", "service", "receiver", "provider")
            components.forEach { compName ->
                @Suppress("UNCHECKED_CAST")
                val elements = app.elements(compName) as List<Element>
                for (element in elements) {
                    val hasIntentFilter = element.element("intent-filter") != null
                    val exportedAttr = element.attribute(qn("exported"))
                    if (exportedAttr == null) {
                        element.addAttribute(qn("exported"), if (hasIntentFilter) "true" else "false")
                    }
                    if (compName == "provider") {
                        if (element.attribute(qn("grantUriPermissions")) == null) {
                            element.addAttribute(qn("grantUriPermissions"), "true")
                        }
                    }
                    if (compName == "service") {
                        // At target >= 34, any service that calls startForeground() must have
                        // foregroundServiceType set AND hold a matching permission, or the OS
                        // throws MissingForegroundServiceTypeException on first startForeground().
                        // We don't know which services will be foregrounded, so we default all
                        // of them to "dataSync" — a generic bucket with permissive framework behavior.
                        if (element.attribute(qn("foregroundServiceType")) == null) {
                            element.addAttribute(qn("foregroundServiceType"), "dataSync")
                        }
                    }
                }
            }
        }

        if (root.element("queries") == null) {
            val queries = root.addElement("queries")
            listOf(
                "android.intent.action.VIEW",
                "android.intent.action.MAIN",
                "android.intent.action.SEND",
                "android.intent.action.SENDTO",
            ).forEach { action ->
                queries.addElement("intent").addElement("action").addAttribute(qn("name"), action)
            }
        }

        FileOutputStream(manifestFile).use { out ->
            val writer = XMLWriter(out)
            writer.write(document)
            writer.close()
        }
        jobManager.log(job, "✔ 앱 설정 업데이트 완료")
    }

    private suspend fun writeNetworkSecurityConfig(job: Job, extractedDir: File) = withContext(Dispatchers.IO) {
        val xmlDir = File(extractedDir, "res/xml").apply { mkdirs() }
        val nsc = File(xmlDir, "network_security_config.xml")
        nsc.writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </base-config>
</network-security-config>
""",
        )
        jobManager.log(job, "✔ 네트워크 설정 추가 완료")
    }

    private suspend fun patchApktoolYaml(job: Job, extractedDir: File, renameTo: String?) = withContext(Dispatchers.IO) {
        val yml = File(extractedDir, "apktool.yml")
        if (!yml.exists()) {
            jobManager.log(job, "ℹ apktool.yml 이 없어 SDK 메타 동기화는 건너뛸게요")
            return@withContext
        }

        val lines = yml.readLines()
        val out = mutableListOf<String>()
        var i = 0
        var sdkReplaced = false
        var packageReplaced = false

        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimEnd() == "sdkInfo:" || line.startsWith("sdkInfo:") -> {
                    out.add("sdkInfo:")
                    i++
                    var existingMin = minSdkFloor
                    var existingTarget = 0
                    while (i < lines.size) {
                        val l = lines[i]
                        val isChildOfBlock = l.startsWith(" ") || l.startsWith("\t") || l.isBlank()
                        if (!isChildOfBlock) break
                        val trimmed = l.trim()
                        if (trimmed.startsWith("minSdkVersion:")) {
                            val v = trimmed.substringAfter(":").trim().trim('\'', '"').toIntOrNull() ?: 0
                            if (v > existingMin) existingMin = v
                        }
                        if (trimmed.startsWith("targetSdkVersion:")) {
                            val v = trimmed.substringAfter(":").trim().trim('\'', '"').toIntOrNull() ?: 0
                            if (v > existingTarget) existingTarget = v
                        }
                        i++
                    }
                    if (existingMin < minSdkFloor) existingMin = minSdkFloor
                    val finalTarget = resolveTargetSdk(existingTarget)
                    if (existingMin > finalTarget) existingMin = finalTarget
                    out.add("  minSdkVersion: '$existingMin'")
                    out.add("  targetSdkVersion: '$finalTarget'")
                    sdkReplaced = true
                }
                line.trimEnd() == "packageInfo:" || line.startsWith("packageInfo:") -> {
                    out.add("packageInfo:")
                    i++
                    var forcedPackageId: String? = null
                    while (i < lines.size) {
                        val l = lines[i]
                        val isChildOfBlock = l.startsWith(" ") || l.startsWith("\t") || l.isBlank()
                        if (!isChildOfBlock) break
                        val trimmed = l.trim()
                        if (trimmed.startsWith("forcedPackageId:")) {
                            forcedPackageId = trimmed.substringAfter(":").trim()
                        }
                        i++
                    }
                    if (forcedPackageId != null) out.add("  forcedPackageId: $forcedPackageId")
                    out.add("  renameManifestPackage: ${renameTo ?: "null"}")
                    packageReplaced = true
                }
                else -> {
                    out.add(line)
                    i++
                }
            }
        }

        if (!sdkReplaced) {
            val fallbackTarget = resolveTargetSdk(0)
            out.add("sdkInfo:")
            out.add("  minSdkVersion: '$minSdkFloor'")
            out.add("  targetSdkVersion: '$fallbackTarget'")
        }
        if (!packageReplaced && renameTo != null) {
            out.add("packageInfo:")
            out.add("  renameManifestPackage: $renameTo")
        }

        val finalContent = out.joinToString("\n") + "\n"
        yml.writeText(finalContent)
        jobManager.log(job, "✔ SDK 버전 정보 동기화 완료 (min=$minSdkFloor, target=$targetSdkVersion · 최신 Android 호환 경고 회피 모드)")
        println("───── patched apktool.yml (job=${job.id}) ─────")
        println(finalContent)
        println("──────────────────────────────────────────────")
    }

    /**
     * Pick the safest targetSdk for a legacy APK.
     *
     * Floor = 30 (Samsung/Xiaomi/HyperOS stop showing the "not compatible with latest
     * Android" warning once target ≥ 30; Samsung's internal threshold is 29, Xiaomi's is 30).
     * Ceiling = `targetSdkVersion` setting (default 30). Going above 30 flips on exported
     * enforcement and PendingIntent immutability (target ≥ 31), which crash untouched legacy code.
     *
     * If the original target was already ≥ warning floor AND ≤ ceiling, keep it as-is to avoid
     * unnecessary re-baselining. Otherwise clamp to ceiling.
     */
    private fun resolveTargetSdk(originalTarget: Int): Int {
        val ceiling = targetSdkVersion
        val warningFloor = 30
        val effectiveFloor = minOf(warningFloor, ceiling)
        return when {
            originalTarget in effectiveFloor..ceiling -> originalTarget
            originalTarget > ceiling -> ceiling
            else -> ceiling
        }
    }

    /** Android platform codename for an API level — used for platformBuildVersionName / compileSdkVersionCodename. */
    private fun sdkCodename(api: Int): String = when (api) {
        21 -> "5"; 22 -> "5.1"
        23 -> "6"; 24 -> "7"; 25 -> "7.1"
        26 -> "8"; 27 -> "8.1"
        28 -> "9"
        29 -> "10"
        30 -> "11"
        31 -> "12"; 32 -> "12L"
        33 -> "13"
        34 -> "14"
        35 -> "15"
        36 -> "16"
        else -> api.toString()
    }

    private fun computeRenamedPackage(original: String?): String? {
        if (original.isNullOrBlank()) return null
        val parts = original.split(".")
        val prefix = parts.take(2).joinToString(".").ifBlank { "app.rebirth" }
        val rand = java.util.UUID.randomUUID().toString().replace("-", "").take(10)
        return "$prefix.rebirth$rand"
    }

    private fun absolutizeRelativeNames(root: Element, originalPackage: String) {
        val androidNs = root.getNamespaceForPrefix("android") ?: return
        val nameQn = org.dom4j.QName("name", androidNs)
        val targetActivityQn = org.dom4j.QName("targetActivity", androidNs)

        fun fix(el: Element, qn: org.dom4j.QName) {
            val attr = el.attribute(qn) ?: return
            val v = attr.value
            if (v.isBlank()) return
            attr.value = when {
                v.startsWith(".") -> originalPackage + v
                !v.contains(".") -> "$originalPackage.$v"
                else -> v
            }
        }

        val application = root.element("application") ?: return
        fix(application, nameQn)

        @Suppress("UNCHECKED_CAST")
        val components = listOf("activity", "activity-alias", "service", "receiver", "provider")
        for (tag in components) {
            (application.elements(tag) as List<Element>).forEach { el ->
                fix(el, nameQn)
                if (tag == "activity-alias") fix(el, targetActivityQn)
            }
        }
    }

    private fun renameProviderAuthorities(root: Element, oldPkg: String, newPkg: String) {
        val application = root.element("application") ?: return
        @Suppress("UNCHECKED_CAST")
        val providers = application.elements("provider") as List<Element>
        for (p in providers) {
            val authAttr = p.attribute(org.dom4j.QName("authorities", root.getNamespaceForPrefix("android")))
                ?: continue
            val authorities = authAttr.value.split(";")
                .map { it.trim() }
                .joinToString(";") { auth ->
                    when {
                        auth == oldPkg -> newPkg
                        auth.startsWith("$oldPkg.") -> newPkg + auth.removePrefix(oldPkg)
                        auth.contains(oldPkg) -> auth.replace(oldPkg, newPkg)
                        else -> auth
                    }
                }
            authAttr.value = authorities
        }
    }

    private suspend fun normaliseNativeLibs(job: Job, extractedDir: File) = withContext(Dispatchers.IO) {
        val libDir = File(extractedDir, "lib")
        if (!libDir.exists() || !libDir.isDirectory) return@withContext

        val abis = libDir.listFiles { f -> f.isDirectory }?.map { it.name } ?: return@withContext
        if (abis.isEmpty()) return@withContext
        val has64 = "arm64-v8a" in abis || "x86_64" in abis
        if (has64) {
            jobManager.log(job, "ℹ 네이티브 ABI: ${abis.joinToString()} (64비트 포함)")
        } else {
            jobManager.log(job, "⚠ 이 앱은 32비트 전용 네이티브 라이브러리만 포함 (${abis.joinToString()}). 대부분의 실기기(32/64-bit 겸용)에서는 설치·실행 가능하지만, 64비트 전용 기기(Apple Silicon 에뮬레이터 등)에는 설치되지 않을 수 있어요.")
        }
    }

    /**
     * Starting with targetSdk=31 (Android 12), every `PendingIntent.getActivity/getBroadcast/...`
     * call **must** pass either `FLAG_IMMUTABLE` or `FLAG_MUTABLE` in its flags argument, or the
     * runtime throws `IllegalArgumentException` the first time a notification/alarm fires — which
     * is exactly the "installs but won't run" failure mode for legacy apps bumped up to modern targets.
     *
     * We can't assume every old APK's code includes these flags, so we inject a shim class
     * `Lcom/apkrebirth/compat/PiCompat;` whose static methods mirror `PendingIntent.getX` 1-for-1 but
     * OR in `FLAG_IMMUTABLE` (`0x04000000`) before delegating. Then we rewrite every existing
     * `invoke-static ..., Landroid/app/PendingIntent;->getX(...)` reference in the decoded smali to
     * point at the shim. The call-site signature is identical so no register juggling is required.
     *
     * `FLAG_MUTABLE` (`0x02000000`) and `FLAG_IMMUTABLE` can't coexist, but legacy apps predating
     * Android 12 wouldn't be setting `FLAG_MUTABLE` anyway, so the OR is safe.
     */
    private suspend fun injectPendingIntentCompatShim(job: Job, extractedDir: File) = withContext(Dispatchers.IO) {
        val smaliRoots = extractedDir.listFiles { f -> f.isDirectory && f.name.startsWith("smali") }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (smaliRoots.isEmpty()) {
            jobManager.log(job, "ℹ smali 디렉토리가 없어 PendingIntent shim 주입 건너뜀")
            return@withContext
        }

        val primaryRoot = smaliRoots.first()
        val shimDir = File(primaryRoot, "com/apkrebirth/compat").apply { mkdirs() }
        File(shimDir, "PiCompat.smali").writeText(PI_COMPAT_SMALI)

        val redirects = listOf(
            "Landroid/app/PendingIntent;->getActivity(" to "Lcom/apkrebirth/compat/PiCompat;->getActivity(",
            "Landroid/app/PendingIntent;->getActivities(" to "Lcom/apkrebirth/compat/PiCompat;->getActivities(",
            "Landroid/app/PendingIntent;->getBroadcast(" to "Lcom/apkrebirth/compat/PiCompat;->getBroadcast(",
            "Landroid/app/PendingIntent;->getService(" to "Lcom/apkrebirth/compat/PiCompat;->getService(",
            "Landroid/app/PendingIntent;->getForegroundService(" to "Lcom/apkrebirth/compat/PiCompat;->getForegroundService(",
        )

        var filesPatched = 0
        var callsitesPatched = 0
        val shimPathFragment = "com/apkrebirth/compat/PiCompat.smali"
        for (root in smaliRoots) {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "smali" }
                .filter { !it.absolutePath.endsWith(shimPathFragment) }
                .forEach { smali ->
                    val original = smali.readText()
                    var patched = original
                    var localHits = 0
                    for ((from, to) in redirects) {
                        val count = patched.split(from).size - 1
                        if (count > 0) {
                            patched = patched.replace(from, to)
                            localHits += count
                        }
                    }
                    if (localHits > 0) {
                        smali.writeText(patched)
                        filesPatched++
                        callsitesPatched += localHits
                    }
                }
        }
        jobManager.log(job, "✔ PendingIntent 호환 shim 주입 완료 ($callsitesPatched 호출, $filesPatched 파일 리다이렉트)")
    }

    private suspend fun writeBackupRules(job: Job, extractedDir: File) = withContext(Dispatchers.IO) {
        val xmlDir = File(extractedDir, "res/xml").apply { mkdirs() }
        File(xmlDir, "backup_rules.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<full-backup-content />
""",
        )
        jobManager.log(job, "✔ 백업 설정 추가 완료")
    }

    private fun friendlyError(stage: JobStage, raw: String?): String {
        val lower = (raw ?: "").lowercase()
        val prefix = when {
            "notazipfile" in lower || "not a zip" in lower ->
                "올려주신 파일이 올바른 APK가 아닌 것 같아요."
            stage == JobStage.DECOMPILING ->
                "앱 내용을 여는 중 문제가 생겼어요."
            stage == JobStage.PATCHING ->
                "호환성 수정 중 문제가 생겼어요."
            stage == JobStage.REBUILDING ->
                "새 APK 를 만드는 중 문제가 생겼어요."
            stage == JobStage.SIGNING ->
                "마지막 서명 단계에서 문제가 생겼어요."
            else -> "처리 중 문제가 생겼어요."
        }
        val detail = raw?.trim()?.take(400)
        return if (detail.isNullOrBlank()) prefix else "$prefix\n· 상세: $detail"
    }

    private suspend fun executeCommand(job: Job, workingDir: File, vararg command: String) = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(*command)
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()

        val output = StringBuilder()
        val reader = process.inputStream.bufferedReader()
        Thread {
            reader.forEachLine { line ->
                output.appendLine(line)
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && shouldSurfaceLine(trimmed)) {
                    synchronized(job) { jobManager.log(job, "  · ${trimmed.take(200)}") }
                }
            }
        }.also { it.isDaemon = true; it.start() }

        val finished = process.waitFor(10, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException("처리 시간이 너무 오래 걸려 중단했어요.")
        }
        if (process.exitValue() != 0) {
            val cmdPreview = command.joinToString(" ").take(180)
            System.err.println("=== tool failure (exit ${process.exitValue()}) ===")
            System.err.println("cmd: $cmdPreview")
            System.err.println(output)
            System.err.println("===============================================")
            runCatching {
                val logFile = File(job.workDir, "tool-${System.currentTimeMillis()}.log")
                logFile.writeText("$ $cmdPreview\n\n$output")
                jobManager.log(job, "📝 실패 로그 저장: ${logFile.name}")
            }
            throw RuntimeException(extractErrorLine(output.toString()))
        }
    }

    private fun isNoiseLine(line: String): Boolean {
        val trimmed = line.trim()
        val lower = trimmed.lowercase()
        return when {
            trimmed.startsWith("at ") -> true
            trimmed.startsWith("...") && lower.contains("more") -> true
            lower.startsWith("picked up _") -> true
            lower.startsWith("information:") -> true
            lower.startsWith("for additional info") -> true
            lower.startsWith("for smali/baksmali info") -> true
            lower.startsWith("copyright ") -> true
            lower.startsWith("apache license") -> true
            lower.startsWith("apktool ") && "—" !in trimmed && "reengineering" in lower -> true
            lower.startsWith("with smali") -> true
            lower.startsWith("general options:") -> true
            lower.startsWith("apktool b|build") || lower.startsWith("apktool d") -> true
            lower.startsWith("unrecognized option:") -> true
            lower.startsWith("-") && lower.length < 80 && lower.contains("  ") -> true
            else -> false
        }
    }

    private fun shouldSurfaceLine(line: String): Boolean = !isNoiseLine(line)

    private fun extractErrorLine(output: String): String {
        val cleaned = output.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !isNoiseLine(it) }

        val unrecognizedOption = output.lines().firstOrNull { it.trim().startsWith("Unrecognized option:") }
        if (unrecognizedOption != null) {
            return "사용한 도구가 업그레이드되어 일부 옵션이 달라졌어요. 서버 관리자에게 문의해 주세요. ($unrecognizedOption)"
        }

        val errorish = cleaned.filter { l ->
            val low = l.lowercase()
            "exception" in low || "error" in low || "failed" in low ||
                low.startsWith("e:") || low.startsWith("w:") || low.startsWith("aapt:")
        }
        val focus = if (errorish.isNotEmpty()) errorish.takeLast(5) else cleaned.takeLast(5)
        return focus.joinToString(" | ").take(800)
    }

    /**
     * Smali wrapper that OR-s `FLAG_IMMUTABLE` (0x04000000) into the flags parameter before
     * delegating to the real `PendingIntent` static getters. See [injectPendingIntentCompatShim].
     *
     * Register layout: for a static method with N single-width params and 1 local (v0),
     * `.registers = N + 1`, the local is v0 and parameters occupy v1..vN. `const v0, 0x04000000`
     * uses the 32-bit const opcode (always supported) instead of const/high16 to stay portable
     * across smali tool versions.
     */
    private companion object {
        private val PI_COMPAT_SMALI = """.class public Lcom/apkrebirth/compat/PiCompat;
.super Ljava/lang/Object;
.source "PiCompat.smali"


.method public static getActivities(Landroid/content/Context;I[Landroid/content/Intent;I)Landroid/app/PendingIntent;
    .registers 5
    const v0, 0x4000000
    or-int/2addr p3, v0
    invoke-static {p0, p1, p2, p3}, Landroid/app/PendingIntent;->getActivities(Landroid/content/Context;I[Landroid/content/Intent;I)Landroid/app/PendingIntent;
    move-result-object v0
    return-object v0
.end method

.method public static getActivities(Landroid/content/Context;I[Landroid/content/Intent;ILandroid/os/Bundle;)Landroid/app/PendingIntent;
    .registers 6
    const v0, 0x4000000
    or-int/2addr p3, v0
    invoke-static {p0, p1, p2, p3, p4}, Landroid/app/PendingIntent;->getActivities(Landroid/content/Context;I[Landroid/content/Intent;ILandroid/os/Bundle;)Landroid/app/PendingIntent;
    move-result-object v0
    return-object v0
.end method

.method public static getActivity(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;
    .registers 5
    const v0, 0x4000000
    or-int/2addr p3, v0
    invoke-static {p0, p1, p2, p3}, Landroid/app/PendingIntent;->getActivity(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;
    move-result-object v0
    return-object v0
.end method

.method public static getActivity(Landroid/content/Context;ILandroid/content/Intent;ILandroid/os/Bundle;)Landroid/app/PendingIntent;
    .registers 6
    const v0, 0x4000000
    or-int/2addr p3, v0
    invoke-static {p0, p1, p2, p3, p4}, Landroid/app/PendingIntent;->getActivity(Landroid/content/Context;ILandroid/content/Intent;ILandroid/os/Bundle;)Landroid/app/PendingIntent;
    move-result-object v0
    return-object v0
.end method

.method public static getBroadcast(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;
    .registers 5
    const v0, 0x4000000
    or-int/2addr p3, v0
    invoke-static {p0, p1, p2, p3}, Landroid/app/PendingIntent;->getBroadcast(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;
    move-result-object v0
    return-object v0
.end method

.method public static getForegroundService(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;
    .registers 5
    const v0, 0x4000000
    or-int/2addr p3, v0
    invoke-static {p0, p1, p2, p3}, Landroid/app/PendingIntent;->getForegroundService(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;
    move-result-object v0
    return-object v0
.end method

.method public static getService(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;
    .registers 5
    const v0, 0x4000000
    or-int/2addr p3, v0
    invoke-static {p0, p1, p2, p3}, Landroid/app/PendingIntent;->getService(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;
    move-result-object v0
    return-object v0
.end method
"""
    }
}
