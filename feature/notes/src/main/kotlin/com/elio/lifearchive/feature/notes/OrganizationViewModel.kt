package com.elio.lifearchive.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elio.lifearchive.core.document.ContentTimes
import com.elio.lifearchive.domain.notes.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class OrganizationUiState(
    val suggestions: List<OrganizationSuggestion> = emptyList(), val topics: List<String> = NoteOrganization.initialTopics,
    val selected: Set<NoteId> = emptySet(), val topic: String = "", val kind: NoteKind? = null,
    val replaceTags: Boolean = false, val tags: String = "", val confirmTimes: Boolean = false,
    val created: String = "", val updated: String = "", val preview: OrganizationPreview? = null,
    val taxonomy: TaxonomyPreview? = null, val taxonomyTopic: Boolean = true, val from: String = "", val to: String = "",
    val busy: Boolean = false, val error: String? = null, val result: String? = null,
)

class OrganizationViewModel(private val library: LibraryRepository, initial: Set<NoteId> = emptySet()) : ViewModel() {
    private val initialIds = initial
    private var initialLoaded = false
    private val mutable = MutableStateFlow(OrganizationUiState(selected = initial))
    val state = mutable.asStateFlow()
    init { refresh() }
    private fun work(action: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { action() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = state.value.copy(error = e.message ?: "操作失败，请重试") }
            finally { mutable.value = state.value.copy(busy = false) }
        }
    }
    private fun <T> NoteResult<T>.get(): T = when (this) { is NoteResult.Success -> value; is NoteResult.Committed -> value; else -> error(failureMessage()) }
    private suspend fun load() {
        val suggestions = library.organizationSuggestions().get()
        val topics = library.query().get().topics
        mutable.value = state.value.copy(suggestions = suggestions, topics = topics)
        if (!initialLoaded) {
            initialLoaded = true
            suggestions.singleOrNull { it.note.summary.id in initialIds }?.takeIf { initialIds.size == 1 }?.let { suggestion ->
                mutable.value = state.value.copy(topic = suggestion.placement.topic.orEmpty(), kind = suggestion.placement.kind,
                    tags = suggestion.note.propertyTags.joinToString(", "), created = suggestion.note.contentTimes.created.orEmpty(), updated = suggestion.note.contentTimes.updated.orEmpty())
            }
        }
    }
    fun refresh() = work { load() }
    fun edit(block: (OrganizationUiState) -> OrganizationUiState) {
        if (!state.value.busy) mutable.value = block(state.value).copy(preview = null, taxonomy = null, error = null, result = null)
    }
    fun select(id: NoteId) = edit { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }
    fun selectAll() = edit { it.copy(selected = it.suggestions.map { suggestion -> suggestion.note.summary.id }.toSet()) }
    fun suggest(id: NoteId) = edit { current ->
        val suggestion = current.suggestions.single { it.note.summary.id == id }
        current.copy(selected = setOf(id), topic = suggestion.topics.singleOrNull().orEmpty(), kind = suggestion.placement.kind, replaceTags = false, confirmTimes = false, tags = suggestion.note.propertyTags.joinToString(", "),
            created = suggestion.note.contentTimes.created.orEmpty(), updated = suggestion.note.contentTimes.updated.orEmpty())
    }
    fun preview() = work {
        val captured = state.value
        require(captured.selected.isNotEmpty()) { "请先选择笔记" }
        val tags = if (captured.replaceTags) captured.tags.split(',', '，').map(String::trim).filter(String::isNotBlank) else null
        val times = if (captured.confirmTimes) ContentTimes(captured.created.ifBlank { null }, captured.updated.ifBlank { null }) else null
        val changes = captured.suggestions.filter { it.note.summary.id in captured.selected }.map {
            OrganizationChange(it.note.summary.id, it.note.summary.version, captured.topic.ifBlank { null }, captured.kind, tags, times)
        }
        mutable.value = state.value.copy(preview = library.previewOrganization(changes).get())
    }
    fun apply() = work {
        val preview = state.value.preview ?: error("请先预览")
        val result = library.applyOrganization(preview).get()
        mutable.value = state.value.copy(preview = null, result = "完成 ${result.outcomes.count { it.status == "COMPLETED" }}，跳过 ${result.outcomes.count { it.status == "SKIPPED" }}，失败 ${result.outcomes.count { it.status == "FAILED" }}\n" + result.outcomes.filter { it.status != "COMPLETED" }.joinToString("\n") { "${it.id.value.take(8)}：${it.message}" })
        load()
    }
    fun previewTaxonomy() = work {
        val current = state.value
        mutable.value = current.copy(taxonomy = library.previewTaxonomy(TaxonomyChange(current.from, current.to, current.taxonomyTopic)).get())
    }
    fun applyTaxonomy() = work {
        library.applyTaxonomy(state.value.taxonomy ?: error("请先预览")).get()
        mutable.value = state.value.copy(taxonomy = null, result = "分类调整已完成")
        load()
    }
    fun addTopic() = work {
        val name = state.value.to.trim()
        require(NoteOrganization.validTopic(name)) { "请输入合法的主题名称" }
        library.createFolder("$name/${NoteKind.PERSONAL.folder}").get()
        mutable.value = state.value.copy(result = "主题已新增：$name")
        load()
    }
}
