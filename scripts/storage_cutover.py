#!/usr/bin/env python3
"""Verify copied objects and switch FileServer locations in an offline SQLite database."""
import argparse
from contextlib import closing
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import sqlite3
import sys
from urllib.parse import urlsplit


@dataclass(frozen=True)
class Location:
    id: str
    namespace: str
    role: str

    @property
    def type(self):
        return "local" if self.namespace.startswith("local:") else "s3"

    @property
    def bucket(self):
        return None if self.type == "local" else self.namespace.rsplit("/", 1)[1]


class LocalObjects:
    def __init__(self, location):
        self.root = Path(location.namespace.removeprefix("local:")).resolve(strict=True)

    def open(self, key):
        path = (self.root / key).resolve()
        if path == self.root or not path.is_relative_to(self.root):
            raise ValueError(f"object key escapes local storage: {key!r}")
        return path.open("rb")


class S3Objects:
    def __init__(self, location, profile):
        try:
            import boto3
            from botocore.config import Config
            from botocore.exceptions import BotoCoreError, ClientError
        except ImportError as e:
            raise ValueError("S3 verification requires boto3") from e

        address = urlsplit(location.namespace)
        if address.scheme not in ("http", "https") or not address.hostname or not location.bucket:
            raise ValueError(f"invalid S3 namespace for {location.id}")
        self.bucket = location.bucket
        self.client_error = ClientError
        self.sdk_error = BotoCoreError
        try:
            session = boto3.Session(profile_name=profile)
            self.client = session.client(
                "s3", endpoint_url=f"{address.scheme}://{address.netloc}",
                region_name=session.region_name or "us-east-1",
                config=Config(signature_version="s3v4", s3={"addressing_style": "path"}),
            )
        except self.sdk_error as e:
            raise ValueError(f"could not initialize S3 reader for {location.id!r}: {e}") from e

    def open(self, key):
        try:
            return self.client.get_object(Bucket=self.bucket, Key=key)["Body"]
        except self.client_error as e:
            code = e.response["Error"]["Code"]
            if code == "NoSuchKey":
                raise FileNotFoundError(key) from e
            raise ValueError(f"could not read {self.bucket}/{key}: {code}") from e
        except self.sdk_error as e:
            raise ValueError(f"could not read {self.bucket}/{key}: {e}") from e

    def list_keys(self, prefix):
        try:
            paginator = self.client.get_paginator("list_objects_v2")
            return {item["Key"] for page in paginator.paginate(Bucket=self.bucket, Prefix=prefix)
                    for item in page.get("Contents", [])}
        except (self.client_error, self.sdk_error) as e:
            raise ValueError(f"could not list {self.bucket}/{prefix}: {e}") from e


def location(db, location_id):
    row = db.execute("select namespace, role from storage_location_binding where location_id=?", (location_id,)).fetchone()
    if row is None:
        raise ValueError(f"unregistered location {location_id!r}; configure it in FileServer first")
    return Location(location_id, *row)


def digest(store, key):
    try:
        body = store.open(key)
    except FileNotFoundError:
        return None
    sha = hashlib.sha256()
    size = 0
    with closing(body):
        while chunk := body.read(1024 * 1024):
            size += len(chunk)
            sha.update(chunk)
    return size, sha.hexdigest()


def compare_object(source, target, key):
    original = digest(source, key)
    if digest(target, key) != original:
        raise ValueError(f"object missing or different between locations: {key!r}")
    return original


def asset_references(db, location_id):
    return db.execute("""
        select storage_key, size_bytes, sha256, 1 from file_metadata where storage_location_id=?
        union all
        select storage_key, size_bytes, sha256, 1 from file_preview_artifact
            where storage_location_id=? and storage_key is not null
        union all
        select storage_key, null, null, 0 from file_reception_staged where storage_location_id=?
        """, (location_id,) * 3)


def check_asset_key_collisions(db, old, new):
    target_keys = {row[0] for row in asset_references(db, new.id)}
    for key, *_ in asset_references(db, old.id):
        if key in target_keys:
            raise ValueError(f"object key already referenced in target location: {key!r}")


def compare_assets(db, old, source, target):
    checked = {}
    for key, size, sha, required in asset_references(db, old.id):
        if key not in checked:
            checked[key] = compare_object(source, target, key)
        original = checked[key]
        if original is None:
            if required:
                raise ValueError(f"required source object missing: {key!r}")
            continue  # A cleanup candidate may already be absent in both locations.
        if ((size is not None and original[0] != size)
                or (sha is not None and original[1] != sha.lower())):
            raise ValueError(f"source object differs from recorded metadata: {key!r}")
    return sum(value is not None for value in checked.values())


def compare_receptions(db, old, source, target):
    checked = set()
    for prefix, in db.execute("select prefix from file_reception where storage_location_id=?", (old.id,)):
        if not prefix or not prefix.endswith("/"):
            raise ValueError(f"invalid reception prefix: {prefix!r}")
        source_keys = source.list_keys(prefix)
        if source_keys != target.list_keys(prefix):
            raise ValueError(f"reception prefix contents differ: {prefix!r}")
        for key in source_keys - checked:
            if compare_object(source, target, key) is None:
                raise ValueError(f"listed reception object missing: {key!r}")
        checked.update(source_keys)
    return len(checked)


def switch_references(db, old, new):
    if old.role == "RECEPTION":
        return db.execute("""
            update file_reception set storage_location_id=?, bucket=? where storage_location_id=?
            """, (new.id, new.bucket, old.id)).rowcount
    changed = 0
    for table in ("file_metadata", "file_preview_artifact"):
        changed += db.execute(f"""
            update {table} set storage_location_id=?, storage_provider=?, storage_bucket=?
                where storage_location_id=?
            """, (new.id, new.type, new.bucket, old.id)).rowcount
    changed += db.execute("""
        update file_reception_staged set storage_location_id=? where storage_location_id=?
        """, (new.id, old.id)).rowcount
    return changed


def check_and_switch(database, moves, profiles, apply=False, preflight=False):
    if apply and preflight:
        raise ValueError("the preflight cannot apply changes")
    ids = [location_id for move in moves for location_id in move]
    if len(set(ids)) != len(ids):
        raise ValueError("each location may appear only once in a cutover")
    if set(profiles) - set(ids):
        raise ValueError("credential profiles must refer to a location in the cutover")

    def reader(info):
        if info.type == "local":
            return LocalObjects(info)
        return S3Objects(info, profiles.get(info.id))

    mode = "rw" if apply else "ro"
    uri = database.resolve(strict=True).as_uri() + "?mode=" + mode
    report = []
    with closing(sqlite3.connect(uri, uri=True, timeout=5)) as db, db:
        db.execute("begin immediate" if apply else "begin")
        for old_id, new_id in moves:
            old, new = location(db, old_id), location(db, new_id)
            if old.role != new.role or old.namespace == new.namespace:
                raise ValueError(f"incompatible locations: {old.id!r} -> {new.id!r}")
            if old.role == "ASSET":
                check_asset_key_collisions(db, old, new)
            elif old.role != "RECEPTION" or old.type != "s3" or new.type != "s3":
                raise ValueError(f"unsupported location pair: {old.id!r} -> {new.id!r}")
            result = {"source": old.id, "target": new.id, "role": old.role}
            if preflight:
                report.append(result)
                continue
            source, target = reader(old), reader(new)
            if old.role == "RECEPTION":
                verified = compare_receptions(db, old, source, target)
            else:
                verified = compare_assets(db, old, source, target)
            updated = switch_references(db, old, new) if apply else 0
            report.append({**result, "objectsVerified": verified, "referencesUpdated": updated})
    return report


def assignment(value):
    left, sep, right = value.partition("=")
    if not sep or not left or not right:
        raise argparse.ArgumentTypeError("expected NAME=VALUE")
    return left, right


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=Path)
    parser.add_argument("--move", action="append", type=assignment, required=True, metavar="SOURCE=TARGET")
    parser.add_argument("--profile", action="append", type=assignment, default=[], metavar="LOCATION=AWS_PROFILE")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--preflight", action="store_true", help="check location and key conflicts before copying data")
    mode.add_argument("--apply", action="store_true", help="update references after verification; stop all writers first")
    args = parser.parse_args()
    try:
        result = check_and_switch(args.database, args.move, dict(args.profile), args.apply, args.preflight)
    except (OSError, ValueError, sqlite3.Error) as e:
        parser.exit(1, f"cutover refused: {e}\n")
    print(json.dumps({"preflight": args.preflight, "applied": args.apply, "moves": result}, indent=2))


if __name__ == "__main__":
    main()
