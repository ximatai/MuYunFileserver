package net.ximatai.muyun.fileserver.application;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.gif.GifHeaderDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.png.PngDirectory;
import com.drew.metadata.webp.WebpDirectory;
import net.ximatai.muyun.fileserver.common.exception.ValidationException;
import net.ximatai.muyun.fileserver.common.exception.StorageException;

import java.io.IOException;
import java.io.EOFException;
import java.nio.file.Path;
import java.util.Set;

/** Encoded canvas dimensions; no pixel decoding, orientation transformation or business shape policy. */
record ImageFileFacts(String mimeType, int width, int height) {
    private static final Set<String> SUPPORTED = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    static ImageFileFacts read(Path path, String mimeType) {
        if (!SUPPORTED.contains(mimeType)) return null;
        try {
            ImageFileFacts facts = from(ImageMetadataReader.readMetadata(path.toFile()));
            if (!mimeType.equals(facts.mimeType())) throw new ValidationException("image metadata does not match media type");
            return facts;
        } catch (EOFException exception) {
            throw new ValidationException("image dimensions are unavailable");
        } catch (IOException exception) {
            throw new StorageException("failed to read image metadata", exception);
        } catch (ImageProcessingException | IllegalArgumentException exception) {
            throw new ValidationException("image dimensions are unavailable");
        }
    }

    private static ImageFileFacts from(Metadata metadata) {
        JpegDirectory jpeg = metadata.getFirstDirectoryOfType(JpegDirectory.class);
        if (jpeg != null) return dimensions("image/jpeg", jpeg, JpegDirectory.TAG_IMAGE_WIDTH, JpegDirectory.TAG_IMAGE_HEIGHT);
        PngDirectory png = metadata.getFirstDirectoryOfType(PngDirectory.class);
        if (png != null) return dimensions("image/png", png, PngDirectory.TAG_IMAGE_WIDTH, PngDirectory.TAG_IMAGE_HEIGHT);
        GifHeaderDirectory gif = metadata.getFirstDirectoryOfType(GifHeaderDirectory.class);
        if (gif != null) return dimensions("image/gif", gif, GifHeaderDirectory.TAG_IMAGE_WIDTH, GifHeaderDirectory.TAG_IMAGE_HEIGHT);
        WebpDirectory webp = metadata.getFirstDirectoryOfType(WebpDirectory.class);
        if (webp != null) return dimensions("image/webp", webp, WebpDirectory.TAG_IMAGE_WIDTH, WebpDirectory.TAG_IMAGE_HEIGHT);
        throw new IllegalArgumentException("image dimensions are unavailable");
    }

    private static ImageFileFacts dimensions(String mimeType, Directory directory, int widthTag, int heightTag) {
        Integer width = directory.getInteger(widthTag);
        Integer height = directory.getInteger(heightTag);
        if (width == null || height == null || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image dimensions are unavailable");
        }
        return new ImageFileFacts(mimeType, width, height);
    }
}
