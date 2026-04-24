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

    @Value("\${apk-rebirth.keystore-path:release.keystore}")
    private lateinit var keystorePath: String

    @Value("\${apk-rebirth.keystore-alias:rebirth}")
    private lateinit var keystoreAlias: String

    @Value("\${apk-rebirth.keystore-password:apk-rebirth}")
    private lateinit var keystorePassword: String

    @Value("\${apk-rebirth.min-sdk-floor:21}")
    private var minSdkFloor: Int = 21

    // Runtime targetSdkVersion written into the rebuilt AndroidManifest.
    //
    // 34 (Android 14) is the highest value we can reach safely — it clears every
    // OEM soft-warning threshold observed in the wild (including tightened HyperOS
    // 3 beta / One UI 8 builds that push their floor to 33-34) while all of the
    // hard enforcements it introduces are actively shimmed here:
    //   - 31+ exported attribute enforcement       → modifyManifest
    //   - 31+ PendingIntent flag immutability      → injectPendingIntentCompatShim
    //   - 33+ POST_NOTIFICATIONS runtime permission → declared in manifest; a user
    //         denial silently drops notifications, no launch crash
    //   - 34+ Context.registerReceiver() requires RECEIVER_EXPORTED/NOT_EXPORTED
    //         → injectReceiverCompatShim rewrites call sites to OR-in the flag
    //   - 34+ foreground-service-type enforcement  → every service is stamped with
    //         multi-type `dataSync|specialUse` + PROPERTY_SPECIAL_USE_FGS_SUBTYPE,
    //         so startForeground() survives whichever type it actually passes
    //
    // We deliberately STOP at 34 to stay below the API levels whose runtime traps
    // cannot be opted-out from a post-compiled APK:
    //   - target ≥ 35: edge-to-edge enforcement. Window draws behind system bars;
    //       legacy layouts that don't query WindowInsets get clipped or crash
    //       during measure/layout. Manifest-level opt-out is NOT available — it's
    //       a theme attribute (`android:windowOptOutEdgeToEdgeEnforcement`), which
    //       we can't reliably inject into an arbitrary decoded resource table.
    //   - target ≥ 35 on 16KB-page Android 15 devices (Pixel 9+): native libs
    //       built with NDK < r26 are not 16KB-aligned and fail to load at runtime.
    //       Completely unfixable without re-linking the .so files.
    //
    // The OEM "이 앱은 최신 Android 와 호환되지 않습니다" dialog is driven by the
    // DEX container version + compileSdkVersion build marker + (on stricter OEMs)
    // the manifest targetSdk floor. Those "freshly compiled" markers are
    // controlled by [buildApiLevel] below, which stays at 35 so the APK still
    // looks modern to the installer; targetSdkVersion=34 covers the OEM
    // target-floor heuristics even on tightened firmware.
    @Value("\${apk-rebirth.target-sdk-version:34}")
    private var targetSdkVersion: Int = 34

    // API level for the DEX container emission (apktool `--api-level`) and the
    // manifest-root build markers (compileSdkVersion, compileSdkVersionCodename,
    // platformBuildVersionCode, platformBuildVersionName).
    //
    // This is what makes OEM package installers (HyperOS, One UI, ColorOS) treat
    // the APK as freshly compiled against a modern SDK and stop showing the
    // "최신 Android 와 호환되지 않습니다" dialog. Intentionally decoupled from
    // [targetSdkVersion]:
    //   - buildApiLevel governs how the APK LOOKS to the installer (static metadata)
    //   - targetSdkVersion governs how the app BEHAVES at runtime (OS-enforced rules)
    // Conflating the two is what caused the previous "installs but won't launch"
    // regression — legacy app code cannot survive target-35 runtime rules, but can
    // happily ride in a DEX 040 container.
    @Value("\${apk-rebirth.build-api-level:35}")
    private var buildApiLevel: Int = 35

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
        ensureReleaseKeystore()
        println("🔧 Using apktool: $finalApktoolPath")
        println("🔧 Using uber-apk-signer: $finalSignerPath")
        println("🔐 Using keystore: ${File(keystorePath).absolutePath}")
        println("📁 Workspace: ${workspaceRoot.absolutePath}")
    }

    /**
     * Replace uber-apk-signer's built-in `CN=Android Debug` keystore with a long-lived
     * release-style keystore the first time the service boots. Google Play Protect and
     * several OEM package installers (HyperOS 2, One UI 7+) treat a signer DN of
     * `CN=Android Debug` as a first-class "sideloaded / unverified" signal and respond
     * with the "Android 최신 버전과 호환되지 않습니다" dialog — exactly the warning we've
     * been chasing. Signing with a non-debug DN clears that heuristic.
     *
     * Validity is 100 years so one keystore survives the service's entire lifetime.
     * The file is kept alongside the jars (configurable via apk-rebirth.keystore-path).
     * Defaults are only there so the service bootstraps out-of-the-box; production
     * deployments should override via env (APK_REBIRTH_KEYSTORE_* equivalents).
     */
    private fun ensureReleaseKeystore() {
        val ks = File(keystorePath)
        if (ks.exists() && ks.length() > 0) return

        ks.absoluteFile.parentFile?.mkdirs()
        println("🔐 Generating release keystore at ${ks.absolutePath} ...")
        val cmd = listOf(
            "keytool",
            "-genkeypair",
            "-keystore", ks.absolutePath,
            "-alias", keystoreAlias,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "36500",
            "-storepass", keystorePassword,
            "-keypass", keystorePassword,
            "-dname", "CN=Apk Rebirth, OU=Signing, O=apk-rebirth, C=KR",
        )
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
        check(finished && process.exitValue() == 0 && ks.exists() && ks.length() > 0) {
            "릴리즈 키스토어 생성 실패: $output"
        }
        println("✅ Keystore ready")
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
        injectReceiverCompatShim(job, extractedDir)
        if (newPackageName != null && originalPackage != null) {
            jobManager.log(job, "🕵️ 패키지 이름을 '$originalPackage' → '$newPackageName' 로 바꿨어요")
        }

        val modifiedApk = File(tempDir, "modified.apk")
        jobManager.updateStage(job, JobStage.REBUILDING, "새 APK 파일로 다시 묶고 있어요")
        // --api-level $buildApiLevel forces the smali assembler to emit a modern DEX
        // container (version 040 for API >= 33, 039 for API >= 28, ...). Without this flag
        // apktool defaults to DEX 035 — the Android-1.0-era format — which OEM package
        // installers (HyperOS, One UI, ColorOS) treat as a decisive "ancient APK" marker
        // and respond to with the "Android 최신 버전과 호환되지 않습니다" dialog regardless
        // of anything in AndroidManifest.xml. This is the fix that actually silences the
        // dialog — every prior manifest tweak was necessary for runtime compatibility but
        // insufficient to clear the OEM container check.
        //
        // Note: uses [buildApiLevel] (static "looks modern" marker, default 35), NOT the
        // runtime [targetSdkVersion] (behavior rules, default 31). The two are decoupled
        // so legacy apps get a modern DEX container without inheriting the Android-14+
        // runtime-compat traps (foreground-service-type, edge-to-edge, 16KB pages, ...).
        executeCommand(
            job, tempDir,
            *(apktoolCmd + listOf(
                "b", extractedDir.absolutePath,
                "-o", modifiedApk.absolutePath,
                "-f", "--no-crunch",
                "--api-level", buildApiLevel.toString(),
            )).toTypedArray(),
        )

        val signedDir = File(tempDir, "signed").apply { mkdirs() }
        jobManager.updateStage(job, JobStage.SIGNING, "설치할 수 있게 서명하고 정리 중")
        executeCommand(
            job, tempDir,
            "java", "-jar", finalSignerPath,
            "--apks", modifiedApk.absolutePath,
            "--out", signedDir.absolutePath,
            "--ks", File(keystorePath).absolutePath,
            "--ksAlias", keystoreAlias,
            "--ksPass", keystorePassword,
            "--ksKeyPass", keystorePassword,
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
        // We force-overwrite them to match [buildApiLevel] (the "looks modern" knob) so the
        // APK appears freshly compiled without altering the runtime target SDK.
        val buildCode = buildApiLevel.toString()
        val buildCodename = sdkCodename(buildApiLevel)
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

        // targetSandboxVersion=2 is the "modern SELinux-isolated" sandbox (introduced
        // in API 26). Several OEM package installers (HyperOS, One UI) include this
        // attribute in their "looks freshly-built" heuristic — absence is weighed as
        // another "ancient APK" signal. It's purely metadata from the app's POV and
        // does not change runtime behavior for apps targeting API ≥ 26, so forcing
        // it to "2" is safe across the board.
        upsertRootAttr("targetSandboxVersion", "2", inAndroidNs = true)

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
            // At target ≥ 34, a service that declares foregroundServiceType=X must
            // ALSO hold the matching FOREGROUND_SERVICE_X permission, or
            // startForeground() throws SecurityException the first time it's called.
            // We default every unlabeled service to `dataSync|specialUse` below,
            // so both permissions are needed. Declaring them here is harmless on
            // older Android (install-time only, no runtime dialog).
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
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
                // Android 13+ predictive back: default-on at target ≥ 34 is what breaks
                // most legacy activities (they haven't wired OnBackInvokedCallback). Opt out.
                "enableOnBackInvokedCallback" to "false",
                // Generic category so launcher/installer treats this like a game rather
                // than "unknown legacy app".
                "appCategory" to "game",
                // Signals a modern build to strict OEM installers (HyperOS 2+, One UI 7+)
                // that additionally check whether the manifest looks like it came out of
                // a modern Android Studio template. Legacy LTR layouts render unchanged
                // on LTR locales; only RTL locales see mirroring, which isn't a crash.
                "supportsRtl" to "true",
            )
            flags.forEach { (name, value) ->
                val attr = app.attribute(qn(name))
                if (attr == null) app.addAttribute(qn(name), value) else attr.value = value
            }

            app.addAttribute(qn("networkSecurityConfig"), "@xml/network_security_config")

            // Window-compat opt-outs. These <property> names are the ACTUAL constants
            // defined on android.view.WindowManager / android.content.pm.PackageManager;
            // any other name is silently ignored by the framework (the previous revision
            // shipped a non-existent `PROPERTY_ALLOW_DISPLAY_CUTOUT_DISPLAY_LEVEL_ONLY`
            // which contributed to nothing). Edge-to-edge opt-out is a theme attribute
            // (`android:windowOptOutEdgeToEdgeEnforcement`), not a <property>, so it
            // cannot be injected from here — that's handled instead by keeping
            // [targetSdkVersion] ≤ 34 so Android 15 doesn't enforce edge-to-edge at all.
            fun addProperty(name: String, value: String) {
                val exists = app.elements("property").any { (it as Element).attributeValue(qn("name")) == name }
                if (!exists) {
                    app.addElement("property")
                        .addAttribute(qn("name"), name)
                        .addAttribute(qn("value"), value)
                }
            }
            // Orientation-request-loop relief for legacy portrait-locked activities on
            // large screens (Samsung foldables, tablets running One UI 6+).
            addProperty("android.window.PROPERTY_COMPAT_ALLOW_IGNORING_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED", "true")
            // Lets resizeable/letter-boxed activities receive focus even when the window
            // manager splits them into compat sandboxes.
            addProperty("android.window.PROPERTY_COMPAT_ENABLE_FAKE_FOCUS", "true")

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
                        // At target >= 34, a service that calls startForeground(flags,type)
                        // must have ALL of the types it passes at runtime declared on the
                        // manifest element, otherwise the framework throws
                        // MissingForegroundServiceTypeException / SecurityException.
                        //
                        // We don't know what each legacy service actually does, so we widen
                        // the default declaration to two types simultaneously (Android
                        // accepts pipe-separated values):
                        //   - dataSync    : matches generic background work (sync, upload)
                        //   - specialUse  : escape hatch for everything else
                        // specialUse must be accompanied by a <property> explaining the
                        // use case (otherwise PackageManager rejects on Play Store;
                        // sideload is more permissive but we include it for safety).
                        if (element.attribute(qn("foregroundServiceType")) == null) {
                            element.addAttribute(qn("foregroundServiceType"), "dataSync|specialUse")
                        }
                        val hasSpecialUseProp = element.elements("property").any {
                            (it as Element).attributeValue(qn("name")) == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                        }
                        if (!hasSpecialUseProp) {
                            element.addElement("property")
                                .addAttribute(qn("name"), "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE")
                                .addAttribute(qn("value"), "legacy_app_modernization")
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
     * Pick the safest manifest targetSdk for a legacy APK.
     *
     * Ceiling  = [targetSdkVersion] (default 31). Above this level, Android 14/15 turn on
     *            runtime-compat traps (foreground-service-type at 34+, edge-to-edge at 35+,
     *            16KB page alignment at 35+) that a post-compiled APK cannot fully escape.
     *            Pushing above the ceiling is what caused the "installs but won't launch"
     *            regression on Android 14/15, so the ceiling is a hard cap.
     * Floor    = 30, clamped to ≤ ceiling. Samsung/Xiaomi/HyperOS stop showing the
     *            "구버전 앱" warning once target ≥ 30 (Samsung 29, Xiaomi 30).
     *
     * When the original target already sits in [floor, ceiling] we keep it to minimise
     * churn; otherwise we clamp to ceiling. Apps whose original target sat above the
     * ceiling are intentionally LOWERED — they'd install but fail the runtime traps we
     * can't opt out of from a patched APK.
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

        // Legacy APKs from the pre-v7a era ship native code under `lib/armeabi/` (plain ARMv5).
        // Modern OEM package installers (HyperOS, One UI, ColorOS) treat a naked `armeabi`
        // folder as the single strongest "ancient APK" signal and pop the Android 최신 버전과
        // 호환되지 않습니다 dialog, even when targetSdkVersion/compileSdkVersion look current.
        //
        // ARMv7 CPUs execute ARMv5 instructions natively (the ISA is strictly additive), so it's
        // safe to re-tag the same .so files as armeabi-v7a. This rescues the APK on every 32-bit-
        // capable device currently shipping (including Xiaomi/Samsung flagships with HyperOS 2 /
        // One UI 7). It does NOT help on pure-64-bit devices (Pixel 7+, some Android-15-only
        // builds) since those still need arm64-v8a code we don't have.
        val armeabi = File(libDir, "armeabi")
        val armv7a = File(libDir, "armeabi-v7a")
        if (armeabi.exists() && armeabi.isDirectory) {
            if (!armv7a.exists()) {
                if (armeabi.renameTo(armv7a)) {
                    jobManager.log(job, "🔧 lib/armeabi → lib/armeabi-v7a 재태깅 (OEM 호환성 경고 회피)")
                } else {
                    // rename can fail across filesystems; fall back to copy + delete
                    armv7a.mkdirs()
                    armeabi.copyRecursively(armv7a, overwrite = true)
                    armeabi.deleteRecursively()
                    jobManager.log(job, "🔧 lib/armeabi → lib/armeabi-v7a 복제-이동 (OEM 호환성 경고 회피)")
                }
            } else {
                // Both exist: armeabi is redundant (v7a supersedes it). Drop the legacy folder.
                armeabi.deleteRecursively()
                jobManager.log(job, "🔧 lib/armeabi 제거 (lib/armeabi-v7a 우선)")
            }
        }

        val abis = libDir.listFiles { f -> f.isDirectory }?.map { it.name }.orEmpty()
        if (abis.isEmpty()) return@withContext
        val has64 = "arm64-v8a" in abis || "x86_64" in abis
        if (has64) {
            jobManager.log(job, "ℹ 네이티브 ABI: ${abis.joinToString()} (64비트 포함)")
        } else {
            jobManager.log(job, "ℹ 네이티브 ABI: ${abis.joinToString()} (32비트 전용 — 64비트 전용 기기에는 설치 불가)")
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

    /**
     * Starting with targetSdk=34 (Android 14), every `Context.registerReceiver(receiver,
     * filter[, broadcastPermission, scheduler])` call **must** pass either `RECEIVER_EXPORTED`
     * or `RECEIVER_NOT_EXPORTED` in a trailing int flags arg, or the runtime throws
     * `SecurityException` the first time a dynamic receiver is registered. The 3-/5-arg
     * `registerReceiver(..., int flags)` overloads only exist on API ≥ 33, so we can't
     * unconditionally delegate to them — the shim picks at runtime based on SDK_INT.
     *
     * We inject `Lcom/apkrebirth/compat/RcvCompat;` with static methods that take the
     * original Context as an explicit first parameter (since invoke-static has no
     * implicit receiver) and forward to the instance method via invoke-virtual, OR-ing
     * in `RECEIVER_EXPORTED` (0x2) when the device is on Android 13+. Then we rewrite
     * every `invoke-virtual[/range] {...}, Landroid/content/Context;->registerReceiver(...)`
     * call site to `invoke-static[/range] {...}, Lcom/apkrebirth/compat/RcvCompat;->...`.
     * The register list is reused verbatim because invoke-virtual's implicit `this` and
     * invoke-static's explicit first parameter land in the same register position.
     *
     * Defaults to `RECEIVER_EXPORTED` rather than `RECEIVER_NOT_EXPORTED` because legacy
     * apps frequently listen for system broadcasts (SCREEN_ON, BOOT_COMPLETED, ...) which
     * would be silently dropped under NOT_EXPORTED. EXPORTED is also a no-op for system
     * broadcasts specifically, so we don't regress that path.
     */
    private suspend fun injectReceiverCompatShim(job: Job, extractedDir: File) = withContext(Dispatchers.IO) {
        val smaliRoots = extractedDir.listFiles { f -> f.isDirectory && f.name.startsWith("smali") }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (smaliRoots.isEmpty()) {
            jobManager.log(job, "ℹ smali 디렉토리가 없어 registerReceiver shim 주입 건너뜀")
            return@withContext
        }

        val primaryRoot = smaliRoots.first()
        val shimDir = File(primaryRoot, "com/apkrebirth/compat").apply { mkdirs() }
        File(shimDir, "RcvCompat.smali").writeText(RCV_COMPAT_SMALI)

        val signatures = listOf(
            "(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;",
            "(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;Ljava/lang/String;Landroid/os/Handler;)Landroid/content/Intent;",
        )
        val targetClasses = listOf("Landroid/content/Context;", "Landroid/content/ContextWrapper;")
        val invokePairs = listOf("invoke-virtual" to "invoke-static", "invoke-virtual/range" to "invoke-static/range")

        val replacements = buildList {
            for (sig in signatures) {
                val staticSig = "(Landroid/content/Context;" + sig.removePrefix("(")
                for (cls in targetClasses) {
                    for ((virtualOp, staticOp) in invokePairs) {
                        val pattern = Regex(
                            "${Regex.escape(virtualOp)} (\\{[^}]+\\}), " +
                                "${Regex.escape(cls)}->registerReceiver${Regex.escape(sig)}",
                        )
                        val replacement =
                            "$staticOp $1, Lcom/apkrebirth/compat/RcvCompat;->registerReceiver$staticSig"
                        add(pattern to replacement)
                    }
                }
            }
        }

        var filesPatched = 0
        var callsitesPatched = 0
        val shimPathFragment = "com/apkrebirth/compat/RcvCompat.smali"
        for (root in smaliRoots) {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "smali" }
                .filter { !it.absolutePath.endsWith(shimPathFragment) }
                .forEach { smali ->
                    val original = smali.readText()
                    var patched = original
                    var localHits = 0
                    for ((regex, replacement) in replacements) {
                        val hits = regex.findAll(patched).count()
                        if (hits > 0) {
                            patched = regex.replace(patched, replacement)
                            localHits += hits
                        }
                    }
                    if (localHits > 0) {
                        smali.writeText(patched)
                        filesPatched++
                        callsitesPatched += localHits
                    }
                }
        }
        jobManager.log(job, "✔ registerReceiver 호환 shim 주입 완료 ($callsitesPatched 호출, $filesPatched 파일 리다이렉트)")
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

        /**
         * Smali wrapper that adds `RECEIVER_EXPORTED` (0x2) to the flags parameter of
         * `Context.registerReceiver` on Android 13+ (API 33+, when the int-flags
         * overloads were introduced). On older Android the flag-less overload is used
         * instead — it never had the requirement. See [injectReceiverCompatShim].
         *
         * `.registers` layout: for a static method with N single-width params and
         * L locals, `.registers = N + L`. Params occupy the LAST N registers
         * (p0 = v(total-N), ..., pN-1 = v(total-1)); locals are v0..v(L-1).
         * 0x21 = 33 fits in `const/16`.
         */
        private val RCV_COMPAT_SMALI = """.class public Lcom/apkrebirth/compat/RcvCompat;
.super Ljava/lang/Object;
.source "RcvCompat.smali"


.method public static registerReceiver(Landroid/content/Context;Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;
    .registers 5
    sget v0, Landroid/os/Build${'$'}VERSION;->SDK_INT:I
    const/16 v1, 0x21
    if-lt v0, v1, :legacy
    const/4 v1, 0x2
    invoke-virtual {p0, p1, p2, v1}, Landroid/content/Context;->registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;I)Landroid/content/Intent;
    move-result-object v0
    return-object v0
    :legacy
    invoke-virtual {p0, p1, p2}, Landroid/content/Context;->registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;
    move-result-object v0
    return-object v0
.end method

.method public static registerReceiver(Landroid/content/Context;Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;Ljava/lang/String;Landroid/os/Handler;)Landroid/content/Intent;
    .registers 11
    sget v0, Landroid/os/Build${'$'}VERSION;->SDK_INT:I
    const/16 v1, 0x21
    if-lt v0, v1, :legacy
    move-object v0, p0
    move-object v1, p1
    move-object v2, p2
    move-object v3, p3
    move-object v4, p4
    const/4 v5, 0x2
    invoke-virtual/range {v0 .. v5}, Landroid/content/Context;->registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;Ljava/lang/String;Landroid/os/Handler;I)Landroid/content/Intent;
    move-result-object v0
    return-object v0
    :legacy
    invoke-virtual {p0, p1, p2, p3, p4}, Landroid/content/Context;->registerReceiver(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;Ljava/lang/String;Landroid/os/Handler;)Landroid/content/Intent;
    move-result-object v0
    return-object v0
.end method
"""
    }
}
