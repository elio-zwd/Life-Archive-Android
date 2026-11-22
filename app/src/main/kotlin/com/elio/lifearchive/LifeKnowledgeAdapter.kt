package com.elio.lifearchive

import com.elio.jianyu.domain.LifeKnowledge
import com.elio.jianyu.domain.LifeKnowledgeHit
import com.elio.jianyu.domain.LifeKnowledgeQuery
import com.elio.jianyu.domain.LifeKnowledgeResult
import com.elio.jianyu.domain.LifeNoteKind
import com.elio.jianyu.domain.OpenLifeNote
import com.elio.lifearchive.domain.notes.KnowledgeQuery
import com.elio.lifearchive.domain.notes.LibraryRepository
import com.elio.lifearchive.domain.notes.NoteId
import com.elio.lifearchive.domain.notes.NoteKind
import com.elio.lifearchive.domain.notes.NoteResult

internal class LifeKnowledgeAdapter(
    private val library: LibraryRepository,
    private val openNote: (NoteDestination) -> Unit,
) : LifeKnowledge {
    override suspend fun search(query: LifeKnowledgeQuery): LifeKnowledgeResult {
        val kind = when (query.kind) {
            LifeNoteKind.PERSONAL -> NoteKind.PERSONAL
            LifeNoteKind.VIDEO -> NoteKind.VIDEO
            null -> null
        }
        val listing = library.searchKnowledge(KnowledgeQuery(query.text, query.topic, kind, query.tag))
        if (listing !is NoteResult.Success) return LifeKnowledgeResult.Unavailable
        return with(listing.value) {
            LifeKnowledgeResult.Found(hits.map { hit ->
                LifeKnowledgeHit(hit.note.id.value, hit.note.version.value, hit.title, hit.note.path.value,
                    hit.excerpt, hit.offset)
            }, total, topics, tags, pendingIndex)
        }
    }

    override suspend fun open(hit: LifeKnowledgeHit): OpenLifeNote {
        val result = library.metadata(NoteId(hit.noteId))
        return when {
            result is NoteResult.Success && result.value.deleted == null -> {
                openNote(NoteDestination(hit.noteId, offset = hit.offset, expectedVersion = hit.version))
                OpenLifeNote.OPENED
            }
            result is NoteResult.Success || result is NoteResult.NotFound -> OpenLifeNote.MISSING
            else -> OpenLifeNote.UNAVAILABLE
        }
    }
}
