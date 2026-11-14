package com.elio.jianyu.backup

import java.io.IOException
import kotlinx.serialization.Serializable

enum class BackupErrorCode(val storageValue: String) {
    INVALID_MAGIC("invalid_magic"),
    UNSUPPORTED_ENVELOPE_VERSION("unsupported_envelope_version"),
    UNSUPPORTED_MANIFEST_VERSION("unsupported_manifest_version"),
    UNSUPPORTED_KDF("unsupported_kdf"),
    KDF_PARAMETERS_OUT_OF_POLICY("kdf_parameters_out_of_policy"),
    KDF_RESOURCE_UNAVAILABLE("kdf_resource_unavailable"),
    UNSUPPORTED_AEAD("unsupported_aead"),
    UNSUPPORTED_REQUIRED_FEATURE("unsupported_required_feature"),
    INVALID_HEADER("invalid_header"),
    AUTHENTICATION_FAILED("authentication_failed"),
    TRUNCATED_PAYLOAD("truncated_payload"),
    TRAILING_DATA("trailing_data"),
    CHUNK_ORDER_INVALID("chunk_order_invalid"),
    DUPLICATE_CHUNK("duplicate_chunk"),
    ENTRY_LIMIT_EXCEEDED("entry_limit_exceeded"),
    ENTRY_SIZE_EXCEEDED("entry_size_exceeded"),
    TOTAL_SIZE_EXCEEDED("total_size_exceeded"),
    PATH_INVALID("path_invalid"),
    SOURCE_CHANGED("source_changed"),
    PURGE_IN_PROGRESS("purge_in_progress"),
    ACTIVE_WORK_IN_PROGRESS("active_work_in_progress"),
    INSUFFICIENT_SPACE("insufficient_space"),
    TARGET_WRITE_FAILED("target_write_failed"),
    VERIFICATION_FAILED("verification_failed"),
    OPERATION_CANCELED("operation_canceled"),
    TEMPORARY_CLEANUP_FAILED("temporary_cleanup_failed"),
    SNAPSHOT_KEY_UNAVAILABLE("snapshot_key_unavailable"),
    SNAPSHOT_CORRUPTED("snapshot_corrupted"),
    DATABASE_CHECKPOINT_FAILED("database_checkpoint_failed"),
    DATABASE_INTEGRITY_FAILED("database_integrity_failed"),
    OPERATION_ALREADY_RUNNING("operation_already_running"),
    UNSUPPORTED_LEGACY_DATA("unsupported_legacy_data"),
    PROVIDER_CAPABILITY_MISSING("provider_capability_missing"),
}

class BackupException(
    val code: BackupErrorCode,
    cause: Throwable? = null,
) : IOException(code.storageValue, cause)

/** Restricted deterministic CBOR value model used by V1 headers and records. */
