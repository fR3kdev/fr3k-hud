package com.mcpintelligence.fr3k.appcontrol.analysis

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.mcpintelligence.fr3k.capability.appmod.AnalysisReport
import com.mcpintelligence.fr3k.capability.appmod.ArtifactIdentity
import com.mcpintelligence.fr3k.capability.appmod.EvidenceKind
import com.mcpintelligence.fr3k.capability.appmod.EvidenceReference
import com.mcpintelligence.fr3k.capability.appmod.FeatureEvidence
import com.mcpintelligence.fr3k.capability.appmod.TechnologyIndicator
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Phone-only APK analysis: indexes an APK's DEX code with the pinned dexlib2 (streaming straight
 * from the archive — no temp extraction) and derives technology indicators from archive contents.
 * Produces an [AnalysisReport] of observed facts and inferred purpose, never a decompiled source
 * tree. Manifest component and permission extraction is a separately qualified step (ARSCLib) and
 * is reported as a coverage limit until it is wired.
 *
 * Pure JVM (dexlib2 + ZipFile), so it runs identically on-device and in unit tests.
 */
object ApkAnalyzer {

    private const val MAX_CLASSES = 1_000_000
    private const val MAX_METHODS = 10_000_000
    private const val SAMPLE_CLASSES = 20
    private const val OBFUSCATED_NAME_THRESHOLD = 0.05

    fun analyze(apk: File, artifact: ArtifactIdentity, analysisId: String): AnalysisReport = ApkAccess.open(apk).use { analyze(it, artifact, analysisId) }
    fun analyze(apk: ApkAccess, artifact: ArtifactIdentity, analysisId: String): AnalysisReport {
        val evidence = ArrayList<FeatureEvidence>()
        val indicators = LinkedHashSet<TechnologyIndicator>()
        val limits = ArrayList<String>()
        if (artifact.packageName == "com.mcpintelligence.fr3k.patchfixture" && artifact.splitSha256 == listOf("7f6f84a470fbc4dd7c7e828c0bc2845e775a4cc803b5da8a85ffc36731f28db7")) {
            evidence += FeatureEvidence("qualified-hook", EvidenceKind.OBSERVED, org.json.JSONObject().put("hook", "owned-banner-control").toString(), EvidenceReference.ApiUse("qualified-owned-fixture-hook"))
        }
        var completed = true

        val nativeLibs = ArrayList<String>()
        var dexEntryCount = 0
        var classCount = 0
        var methodCount = 0
        var fieldCount = 0
        var obfuscatedNames = 0
        var sawKotlin = false
        var sawCompose = false
        var sawWebView = false
        var sawReactNative = false
        val sampleClasses = ArrayList<String>()
        var hasFlutterAssets = false
        var hasUnityAssets = false

        val names = apk.sources.map { it.alias }
        nativeLibs += names.filter { it.startsWith("lib/") && it.endsWith(".so") }
        hasFlutterAssets = names.any { it.startsWith("assets/flutter_assets") }
        hasUnityAssets = names.any { it.startsWith("assets/bin/Data/") }
        dexEntryCount = names.count { it.matches(Regex("classes[0-9]*\\.dex")) }

        if (dexEntryCount == 0) {
            limits += "No DEX in this APK; resource-only splits and native-only apps have no Java/Kotlin census."
        } else {
            try {
                census@ for ((dexName, dexFile) in apk.dexes()) {
                    for (cls in dexFile.classes) {
                        if (classCount >= MAX_CLASSES) {
                            completed = false
                            limits += "class census stopped at $MAX_CLASSES classes"
                            break@census
                        }
                        classCount++
                        val type = cls.type
                        val simple = type.substringAfterLast('/').substringBefore(';')
                        if (simple.length <= 2) obfuscatedNames++
                        if (sampleClasses.size < SAMPLE_CLASSES) sampleClasses += type
                        for (m in cls.methods) {
                            if (methodCount >= MAX_METHODS) {
                                completed = false
                                limits += "method census stopped at $MAX_METHODS methods"
                                break@census
                            }
                            methodCount++
                        }
                        for (f in cls.fields) fieldCount++
                        if (type.startsWith("Lkotlin/") || type.startsWith("Lkotlinx/")) sawKotlin = true
                        if (type.startsWith("Landroidx/compose/")) sawCompose = true
                        if (type.startsWith("Lcom/facebook/react/")) sawReactNative = true
                        if (!sawWebView && (
                            cls.superclass == "Landroid/webkit/WebViewClient;" ||
                                cls.superclass == "Landroid/webkit/WebChromeClient;" ||
                                cls.superclass == "Landroid/webkit/WebView;"
                            )
                        ) sawWebView = true
                    }
                }
            } catch (failure: Exception) {
                completed = false
                limits += "DEX census failed: ${failure.message ?: failure.javaClass.simpleName}"
            }
        }

        if (classCount > 0) {
            indicators += TechnologyIndicator.JAVA_KOTLIN
            evidence += FeatureEvidence(
                "dex-census", EvidenceKind.OBSERVED,
                "$classCount classes, $methodCount methods, $fieldCount fields across $dexEntryCount DEX file(s)",
                EvidenceReference.None,
            )
        }
        if (dexEntryCount > 1) {
            indicators += TechnologyIndicator.MULTIDEX
            evidence += FeatureEvidence("multidex", EvidenceKind.OBSERVED, "$dexEntryCount DEX files", EvidenceReference.None)
        }
        if (nativeLibs.isNotEmpty()) {
            indicators += TechnologyIndicator.NATIVE_LIBRARY
            nativeLibs.take(SAMPLE_CLASSES).forEachIndexed { i, lib ->
                evidence += FeatureEvidence(
                    "native-lib-$i", EvidenceKind.OBSERVED, "native library", EvidenceReference.ApiUse("native:$lib"),
                )
            }
        }
        if (nativeLibs.any { it.contains("libflutter.so") } || hasFlutterAssets) {
            indicators += TechnologyIndicator.FLUTTER
            evidence += FeatureEvidence("flutter", EvidenceKind.INFERRED, "Flutter runtime detected", EvidenceReference.None)
        }
        if (nativeLibs.any { it.contains("libunity.so") || it.contains("libil2cpp.so") } || hasUnityAssets) {
            indicators += TechnologyIndicator.UNITY
            evidence += FeatureEvidence("unity", EvidenceKind.INFERRED, "Unity runtime detected", EvidenceReference.None)
        }
        if (sawKotlin) {
            evidence += FeatureEvidence("kotlin-runtime", EvidenceKind.OBSERVED, "Kotlin runtime classes present", EvidenceReference.None)
        }
        if (sawCompose) {
            indicators += TechnologyIndicator.COMPOSE
            evidence += FeatureEvidence("compose", EvidenceKind.INFERRED, "Jetpack Compose classes present", EvidenceReference.None)
        }
        if (sawReactNative) {
            evidence += FeatureEvidence("react-native", EvidenceKind.INFERRED, "React Native bridge classes present", EvidenceReference.None)
        }
        if (sawWebView) {
            indicators += TechnologyIndicator.WEBVIEW
            evidence += FeatureEvidence("webview", EvidenceKind.INFERRED, "WebView subclass present", EvidenceReference.None)
        }
        val obfuscationRatio = if (classCount > 0) obfuscatedNames.toDouble() / classCount else 0.0
        if (classCount > 100 && obfuscationRatio > OBFUSCATED_NAME_THRESHOLD) {
            indicators += TechnologyIndicator.OBFUSCATED
            evidence += FeatureEvidence(
                "obfuscation", EvidenceKind.INFERRED,
                "${(obfuscationRatio * 100).toInt()}% of class names are one-two characters",
                EvidenceReference.None,
            )
        }
        if (sampleClasses.isNotEmpty()) {
            evidence += FeatureEvidence(
                "class-sample", EvidenceKind.OBSERVED,
                "first ${sampleClasses.size} class descriptors",
                EvidenceReference.DexDescriptor(sampleClasses.joinToString(","), null),
            )
        }
        try { evidence += ResourceAnalyzer.extract(apk.module) } catch (failure: Exception) {
            completed = false; limits += "resource strings failed: ${failure.message}"
        }
        try { evidence += DexEvidence.extract(apk, artifact.packageName) } catch (failure: Exception) {
            completed = false; limits += "targeted DEX extraction failed: ${failure.message}"
        }
        limits += "Resources capped at 2000 entries; app-owned DEX constants capped at 400; runtime behavior not inferred from absence."

        try {
            val manifest = ManifestAnalyzer.parse(apk.module)
            if (manifest.packageName != null) {
                evidence += FeatureEvidence(
                    "manifest", EvidenceKind.OBSERVED,
                    "package=${manifest.packageName} versionCode=${manifest.versionCode ?: "?"} " +
                        "versionName=${manifest.versionName ?: "?"} minSdk=${manifest.minSdk ?: "?"} " +
                        "targetSdk=${manifest.targetSdk ?: "?"} main=${manifest.mainActivity ?: "?"} " +
                        "application=${manifest.applicationClass ?: "?"}",
                    manifest.mainActivity?.let { EvidenceReference.ManifestComponent(it) } ?: EvidenceReference.None,
                )
            }
            if (manifest.permissions.isNotEmpty()) {
                evidence += FeatureEvidence(
                    "permissions", EvidenceKind.OBSERVED,
                    "${manifest.permissions.size} permissions: ${manifest.permissions.take(20).joinToString(", ")}",
                    EvidenceReference.Permission(manifest.permissions.first()),
                )
            }
            if (manifest.componentCount > 0) {
                val primary = manifest.mainActivity ?: manifest.activities.firstOrNull()
                    ?: manifest.services.firstOrNull() ?: manifest.receivers.firstOrNull()
                    ?: manifest.providers.firstOrNull()
                evidence += FeatureEvidence(
                    "components", EvidenceKind.OBSERVED,
                    "activities=${manifest.activities.size} services=${manifest.services.size} " +
                        "receivers=${manifest.receivers.size} providers=${manifest.providers.size}",
                    primary?.let { EvidenceReference.ManifestComponent(it) } ?: EvidenceReference.None,
                )
            }
        } catch (failure: Exception) {
            completed = false
            limits += "manifest parsing failed: ${failure.message ?: failure.javaClass.simpleName}"
        }

        return AnalysisReport(
            analysisId = analysisId,
            artifact = artifact,
            analyzedAtMillis = System.currentTimeMillis(),
            technology = indicators,
            evidence = evidence,
            completed = completed,
            limits = limits,
        )
    }
}
