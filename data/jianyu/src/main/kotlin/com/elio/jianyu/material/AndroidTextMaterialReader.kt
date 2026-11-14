package com.elio.jianyu.material

import android.content.Context
import android.net.Uri

class AndroidTextMaterialReader(context: Context) : TextMaterialReader {
    private val context = context.applicationContext
    override suspend fun read(uri: String) = MaterialDocumentReader.read(context, Uri.parse(uri))
}
