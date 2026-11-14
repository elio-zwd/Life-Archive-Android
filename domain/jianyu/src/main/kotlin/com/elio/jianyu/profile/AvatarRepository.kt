package com.elio.jianyu.profile

import kotlinx.coroutines.flow.Flow

data class UserAvatarSnapshot(val encodedImage: ByteArray?, val revision: Long) {
    val hasCustomAvatar: Boolean get() = encodedImage != null
}

sealed interface UserAvatarMutationResult {
    data object Success : UserAvatarMutationResult
    data object InvalidImage : UserAvatarMutationResult
    data object SourceUnavailable : UserAvatarMutationResult
    data object StorageFailure : UserAvatarMutationResult
}

interface AvatarRepository {
    fun observeAvatar(): Flow<UserAvatarSnapshot>
    suspend fun importAvatar(uri: String): UserAvatarMutationResult
    suspend fun resetToDefault(): UserAvatarMutationResult
}
