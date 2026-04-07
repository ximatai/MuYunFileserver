alter table file_metadata add column temporary integer not null default 0
    check (temporary in (0, 1));

create index if not exists idx_file_metadata_temporary_uploaded_at
    on file_metadata (temporary, uploaded_at);
