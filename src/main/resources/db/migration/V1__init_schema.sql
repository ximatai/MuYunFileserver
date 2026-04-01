create table if not exists file_metadata (
    id text primary key,
    tenant_id text not null,
    original_filename text not null,
    extension text,
    mime_type text not null,
    size_bytes integer not null check (size_bytes >= 0),
    sha256 text not null,
    storage_provider text not null,
    storage_bucket text,
    storage_key text not null,
    status text not null check (status in ('ACTIVE', 'DELETED')),
    uploaded_by text not null,
    uploaded_at text not null,
    deleted_at text,
    delete_marked_by text,
    remark text
);

create index if not exists idx_file_metadata_tenant_status_uploaded_at
    on file_metadata (tenant_id, status, uploaded_at desc);

create index if not exists idx_file_metadata_status_deleted_at
    on file_metadata (status, deleted_at);

create index if not exists idx_file_metadata_tenant_id
    on file_metadata (tenant_id, id);
