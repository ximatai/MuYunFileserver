create table if not exists file_preview_artifact (
    file_id text not null,
    artifact_key text not null,
    tenant_id text not null,
    source_kind text not null check (source_kind in ('ORIGINAL_PDF', 'GENERATED_PDF')),
    status text not null check (status in ('PROCESSING', 'READY', 'FAILED')),
    target_mime_type text not null,
    storage_provider text,
    storage_bucket text,
    storage_key text,
    size_bytes integer,
    sha256 text,
    generated_at text not null,
    last_accessed_at text not null,
    failure_code text,
    failure_message text,
    primary key (file_id, artifact_key)
);

create index if not exists idx_file_preview_artifact_tenant_file
    on file_preview_artifact (tenant_id, file_id, artifact_key);
