package com.elio.lifearchive

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import javax.inject.Inject

internal class VariantRuntime @Inject constructor() {
    @Composable fun Content() { Text("架构底层已就绪，功能层待接入") }
}
