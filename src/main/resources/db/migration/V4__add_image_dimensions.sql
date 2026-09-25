alter table file_metadata add column image_width integer check (image_width is null or image_width > 0);
alter table file_metadata add column image_height integer
    check ((image_width is null and image_height is null)
        or (image_width is not null and image_height is not null and image_width > 0 and image_height > 0));
