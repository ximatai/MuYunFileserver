alter table file_metadata add column storage_location_id text;
alter table file_preview_artifact add column storage_location_id text;
alter table file_reception add column storage_location_id text;
alter table file_reception_staged add column storage_location_id text;

create table storage_location_binding (
    location_id text primary key,
    namespace text not null,
    role text not null
);

create index idx_file_metadata_location on file_metadata(storage_location_id);
create index idx_file_preview_location on file_preview_artifact(storage_location_id);
create index idx_file_reception_location on file_reception(storage_location_id);
create index idx_file_reception_staged_location on file_reception_staged(storage_location_id);
