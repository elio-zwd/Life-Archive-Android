package com.elio.lifearchive.core.live2d

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** 通用 Live2D 模型资源来源：支持随包 assets 与后续外部目录导入。 */
sealed interface Live2DModelSource {
    val modelJson: String

    data class Asset(val directory: String, override val modelJson: String) : Live2DModelSource {
        val normalizedDirectory: String = directory.trim('/')
    }

    data class Directory(val directoryPath: String, override val modelJson: String) : Live2DModelSource
}

/** 单个可加载 Live2D 模型的描述元数据。 */
data class Live2DModelSpec(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val source: Live2DModelSource,
    val compactScale: Float = 0.74f,
) {
    init {
        require(id.isNotBlank()) { "模型 ID 不能为空" }
        require(displayName.isNotBlank()) { "模型显示名不能为空" }
        require(compactScale.isFinite() && compactScale > 0f) { "取景缩放比例无效" }
    }
}

/**
 * 通用 Live2D 模型容器注册表。
 * 默认预置 Cubism SDK 5 R5 自带的 8 个官方示例模型，同时允许运行时注册或移除外部目录模型。
 */
object Live2DModelCatalog {
    const val DEFAULT_MODEL_ID: String = "Hiyori"

    val builtIn: List<Live2DModelSpec> = listOf(
        builtInAsset("Hiyori", "Hiyori", "桃濑日和 · 官方标准样板", 0.74f),
        builtInAsset("Mao", "Mao", "虹色真央 · 小魔女", 0.74f),
        builtInAsset("Ren", "Ren", "Ren Foster · Cubism 5.3", 0.74f),
        builtInAsset("Haru", "Haru", "春 · 双马尾经典", 0.74f),
        builtInAsset("Rice", "Rice", "Rice Glassfield · 魔法少女", 0.74f),
        builtInAsset("Mark", "Mark", "Mark-kun · 轻量卡通", 0.70f),
        builtInAsset("Wanko", "Wanko", "年糕犬 · 萌系小狗", 0.66f),
        builtInAsset("Natori", "Natori", "名取仁 · 执事（仅限非商学习）", 0.74f),
    )

    private val dynamicModels = CopyOnWriteArrayList<Live2DModelSpec>()

    fun all(): List<Live2DModelSpec> = builtIn + dynamicModels

    fun find(id: String?): Live2DModelSpec? = id?.trim()?.takeIf { it.isNotEmpty() }?.let { target ->
        dynamicModels.firstOrNull { it.id == target } ?: builtIn.firstOrNull { it.id == target }
    }

    /** 找不到指定模型时安全回退到默认模型，避免旧配置或外部文件被移除后无法显示。 */
    fun resolve(id: String?): Live2DModelSpec = find(id) ?: builtIn.first()

    fun register(spec: Live2DModelSpec) {
        require(builtIn.none { it.id == spec.id }) { "不能覆盖内置模型 ID：${spec.id}" }
        dynamicModels.removeAll { it.id == spec.id }
        dynamicModels.add(spec)
    }

    fun unregister(id: String): Boolean = dynamicModels.removeAll { it.id == id }

    /** 扫描本地目录中的 *.model3.json，便于后续接入外部导入的 Live2D 模型。 */
    fun discoverInDirectory(
        id: String,
        displayName: String,
        subtitle: String,
        directory: File,
        compactScale: Float = 0.74f,
    ): Live2DModelSpec? {
        if (!directory.isDirectory) return null
        val json = directory.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".model3.json") } ?: return null
        return Live2DModelSpec(
            id = id,
            displayName = displayName,
            subtitle = subtitle,
            source = Live2DModelSource.Directory(directory.absolutePath, json.name),
            compactScale = compactScale,
        )
    }

    private fun builtInAsset(id: String, displayName: String, subtitle: String, compactScale: Float) =
        Live2DModelSpec(
            id = id,
            displayName = displayName,
            subtitle = subtitle,
            source = Live2DModelSource.Asset(directory = "live2d/$id", modelJson = "$id.model3.json"),
            compactScale = compactScale,
        )
}
