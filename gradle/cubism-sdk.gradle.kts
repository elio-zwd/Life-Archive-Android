import java.util.Properties
import java.security.MessageDigest

// SDK 由使用者从官方取得；绝对路径和受许可的原始包不写入仓库。
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val sdkPath = providers.environmentVariable("CUBISM_SDK_DIR").orNull ?: local.getProperty("cubism.sdk.dir")
    ?: error("请设置 CUBISM_SDK_DIR 或 local.properties 的 cubism.sdk.dir，指向 Cubism SDK for Java 5 R5。见 core/live2d/README.md")
val sdkDirectory = file(sdkPath)
val sdkVersion = extensions.getByType<VersionCatalogsExtension>().named("libs").findVersion("cubism-java").get().requiredVersion
val cubismSampleModels = listOf("Hiyori", "Haru", "Mao", "Mark", "Natori", "Ren", "Rice", "Wanko")
extra["cubismSdkDirectory"] = sdkDirectory
extra["cubismCoreAar"] = File(sdkDirectory, "Core/android/Live2DCubismCore.aar")
extra["cubismSampleModels"] = cubismSampleModels

tasks.register("verifyCubismSdk") {
    group = "verification"
    description = "核对本机 Cubism 5 R5 的 Core、Framework、着色器和 8 个内置示例模型素材哈希"
    doLast {
        check(Regex("(?m)^\\s*version:\\s*${Regex.escape(sdkVersion)}\\s*$").containsMatchIn(File(sdkDirectory, "cubism-info.yml").readText())) { "SDK 版本不符合 libs.versions.toml：$sdkVersion" }
        val entries = rootProject.file("gradle/cubism-sdk.sha256").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        entries.forEach { line ->
            val (expected, relative) = line.split("  ", limit = 2)
            val source = File(sdkDirectory, relative)
            check(source.isFile) { "SDK 文件缺失：$relative" }
            val actual = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
            check(expected == actual) { "SDK 文件与固定的 5 R5 不符：$relative；不要静默替换版本。" }
        }
        val locked = entries.map { it.split("  ", limit = 2)[1] }.toSet()
        val roots = listOf("Framework/framework/src/main/java", "Framework/framework/src/main/assets") +
            cubismSampleModels.map { "Sample/src/main/assets/$it" }
        val sources = roots.flatMap { relative -> File(sdkDirectory, relative).walkTopDown().filter { it.isFile }.toList() }
        check(sources.all { it.relativeTo(sdkDirectory).invariantSeparatorsPath in locked }) { "SDK 中存在未锁定的 Framework 或示例模型输入文件" }
        logger.lifecycle("Cubism SDK 5 R5: ${entries.size} 个输入文件校验通过（含 ${cubismSampleModels.size} 个内置模型）")
    }
}
