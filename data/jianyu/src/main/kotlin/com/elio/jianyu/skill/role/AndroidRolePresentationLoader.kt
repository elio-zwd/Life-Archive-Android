package com.elio.jianyu.skill.role

import android.content.Context
import com.elio.jianyu.skill.catalog.OfficialSkillCatalog

class AndroidRolePresentationLoader(context: Context) : RolePresentationLoader {
    private val context = context.applicationContext
    override fun load(catalog: OfficialSkillCatalog) = SkillRolePresentationCatalogLoader.load(context, catalog)
}
