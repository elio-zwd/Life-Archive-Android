package com.elio.jianyu.skill.role
import android.content.Context
import com.elio.jianyu.skill.catalog.OfficialSkillCatalog
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

object SkillRolePresentationCatalogLoader {
    const val DEFAULT_ASSET_PATH = "skill_role_presentation_v1.json"

    fun load(
        context: Context,
        officialCatalog: OfficialSkillCatalog,
        assetPath: String = DEFAULT_ASSET_PATH,
    ): SkillRolePresentationLoadResult = try {
        val source = context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val officialIds = officialCatalog.skills.mapTo(linkedSetOf()) { it.id }
        SkillRolePresentationCatalogParser.parse(source, officialIds)
    } catch (error: Exception) {
        SkillRolePresentationLoadResult.Failure(
            message = "invalid_manifest: 无法读取角色展示 Manifest",
            cause = error,
        )
    }
}
