plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// 编译依赖是架构边界；新模块必须在这里声明允许的直接依赖。
val allowedModules = mapOf(
    ":app" to setOf(":core:designsystem", ":core:preferences", ":core:backup", ":domain:notes", ":data:notes", ":core:document", ":domain:sync", ":data:sync"),
    ":core:designsystem" to emptySet(),
    ":core:document" to emptySet(),
    ":core:backup" to emptySet(),
    ":core:preferences" to setOf(":core:backup"),
    ":domain:notes" to setOf(":core:document"),
    ":data:notes" to setOf(":domain:notes", ":core:document", ":core:backup"),
    ":feature:notes" to setOf(":domain:notes", ":core:designsystem", ":core:preferences"),
    ":feature:editor" to setOf(":domain:notes", ":core:document", ":core:designsystem", ":core:preferences"),
    ":feature:settings" to setOf(":domain:notes", ":domain:sync", ":core:preferences", ":core:backup", ":core:designsystem"),
    ":domain:sync" to setOf(":domain:notes"),
    ":data:sync" to setOf(":domain:sync", ":domain:notes", ":data:notes", ":core:document", ":core:backup"),
)
tasks.register("checkArchitecture") {
    group = "verification"
    doLast {
        val graph = subprojects.filter { it.buildFile.isFile }.associate { p ->
            p.path to p.configurations.flatMap { c ->
                c.dependencies.withType<org.gradle.api.artifacts.ProjectDependency>().map { it.path }
            }.filter { it != p.path }.toSet()
        }
        graph.forEach { (path, edges) ->
            if (path.startsWith(":feature:")) {
                check(edges.none { it.startsWith(":data:") || it.startsWith(":feature:") }) { "$path 禁止依赖 data 或其他 feature：$edges" }
            }
            val allowed = allowedModules[path] ?: error("请为 $path 明确声明依赖边界")
            check(edges.all { it in allowed || (path == ":app" && it.startsWith(":feature:")) }) { "$path 存在越界依赖：${edges - allowed}" }
            if (path.startsWith(":domain:") || path == ":core:document") {
                val p = project(path)
                check(!p.plugins.hasPlugin("com.android.library") && !p.plugins.hasPlugin("com.android.application"))
                p.configurations.flatMap { it.dependencies }.forEach { dep ->
                    check(dep.group?.let { it.startsWith("androidx") || it.startsWith("com.android") || it.startsWith("com.google.dagger") } != true) { "$path 依赖了平台库 $dep" }
                }
                p.fileTree("src").matching { include("**/*.kt") }.forEach { file ->
                    check(!Regex("(?m)^\\s*import\\s+(android\\.|androidx\\.|dagger\\.)").containsMatchIn(file.readText())) { "平台引用：$file" }
                }
            }
        }
        fun visit(node: String, stack: Set<String>) {
            check(node !in stack) { "循环依赖：$stack -> $node" }
            graph[node].orEmpty().forEach { visit(it, stack + node) }
        }
        graph.keys.forEach { visit(it, emptySet()) }
    }
}

tasks.register("verifyFoundation") {
    group = "verification"
    description = "仅电脑验证架构、核心底层与编辑样板、Lint 和 APK 构建，不连接设备"
    dependsOn(
        "checkArchitecture", ":core:document:test", ":core:backup:test",
        ":data:notes:testDebugUnitTest", ":core:preferences:testDebugUnitTest", ":app:testDebugUnitTest",
        ":app:lintDebug", ":core:designsystem:lintDebug", ":data:notes:lintDebug", ":core:preferences:lintDebug",
        ":app:assembleDebug", ":app:assembleDebugAndroidTest", ":app:compileReleaseKotlin",
        ":feature:notes:testDebugUnitTest", ":feature:editor:testDebugUnitTest",
        ":feature:notes:lintDebug", ":feature:editor:lintDebug",
    )
}

tasks.register("verifyEditorPrototype") {
    group = "verification"
    description = "验证一期编辑器样板及底层回归，不安装 APK 或调用 ADB"
    dependsOn("verifyFoundation")
}
tasks.register<Exec>("testReader") {
    group = "verification"
    dependsOn(":feature:editor:readerAssets", ":core:document:test")
    workingDir(layout.projectDirectory.dir("reader"))
    commandLine("node", "test/reader.test.mjs")
}
tasks.register("verifyPhaseOne") {
    group = "verification"
    description = "一期功能、迁移与本地阅读脚本验证；不连接设备"
    dependsOn("verifyFoundation", "testReader", ":feature:settings:testDebugUnitTest", ":feature:settings:lintDebug")
}
tasks.register("verifyPhaseThree") {
    group = "verification"
    description = "三期同步与实际 PC HTTP 往返、一期回归、Lint 和 APK；仅合成资料，不连接设备"
    dependsOn("verifyPhaseOne", ":domain:sync:test", ":data:sync:testDebugUnitTest", ":data:sync:lintDebug")
}
