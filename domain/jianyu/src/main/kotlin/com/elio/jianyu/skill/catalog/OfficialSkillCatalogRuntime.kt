package com.elio.jianyu.skill.catalog



data class OfficialSkillCatalogRuntime(
    val catalog: OfficialSkillCatalog,
    val preferences: OfficialSkillPreferences,
    val validator: CatalogOfficialSkillIdValidator,
    val executionEligibility: OfficialSkillExecutionEligibility,
)

sealed interface OfficialSkillCatalogRuntimeResult {
    data class Success(
        val runtime: OfficialSkillCatalogRuntime,
    ) : OfficialSkillCatalogRuntimeResult

    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : OfficialSkillCatalogRuntimeResult
}
