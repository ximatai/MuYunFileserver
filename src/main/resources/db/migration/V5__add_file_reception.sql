create table file_reception (
    id text primary key,
    tenant_id text not null,
    idempotency_key text not null,
    prefix text not null unique,
    state text not null check (state in ('OPEN','CONFIRMING','READY','CANCELLED','EXPIRED')),
    max_objects integer not null,
    max_bytes integer not null,
    expires_at integer not null,
    bucket text not null,
    ttl_seconds integer not null,
    manifest_hash text,
    lease_token text,
    lease_until integer,
    next_cleanup_at integer not null default 0,
    unique(tenant_id, idempotency_key)
);
create table file_reception_asset (
    reception_id text not null references file_reception(id),
    object_key text not null,
    file_id text not null references file_metadata(id) on delete cascade,
    primary key(reception_id, object_key)
);
create index idx_file_reception_expiry on file_reception(state, expires_at);
create table file_reception_staged (
    file_id text primary key,
    reception_id text not null references file_reception(id),
    lease_token text not null,
    storage_key text not null unique
);
