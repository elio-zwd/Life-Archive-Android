package com.elio.jianyu.skill.role

import com.elio.jianyu.skill.catalog.OfficialSkillCatalog

fun interface RolePresentationLoader {
    fun load(catalog: OfficialSkillCatalog): SkillRolePresentationLoadResult
}
