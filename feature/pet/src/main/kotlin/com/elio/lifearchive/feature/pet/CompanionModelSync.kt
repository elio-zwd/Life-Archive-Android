package com.elio.lifearchive.feature.pet

import com.elio.lifearchive.core.live2d.Live2DModelCatalog
import com.elio.lifearchive.core.live2d.Live2DModelSource
import com.elio.lifearchive.core.live2d.Live2DModelSpec
import com.elio.lifearchive.domain.pet.ImportedLive2DModel

internal fun syncLive2DCatalog(importedModels: List<ImportedLive2DModel>): List<Live2DModelSpec> {
    val external = importedModels.map { model ->
        Live2DModelSpec(
            id = model.id,
            displayName = model.displayName,
            subtitle = "本地导入模型",
            source = Live2DModelSource.Directory(model.directoryPath, model.modelJson),
        )
    }
    Live2DModelCatalog.syncExternal(external)
    return Live2DModelCatalog.all()
}
