package ai.mobilecore.gallery.search

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest

class AndroidGalleryPhotoDiscovery(
    private val contentResolver: ContentResolver,
) : GalleryPhotoDiscovery {
    override fun discover(selection: GalleryPhotoSelection): List<GalleryPhoto> = when (selection) {
        is GalleryPhotoSelection.MediaStoreImages -> discoverMediaStore(selection)
        is GalleryPhotoSelection.GrantedContentUris -> selection.uris
            .sorted()
            .map(::discoverGrantedUri)
    }

    private fun discoverMediaStore(selection: GalleryPhotoSelection.MediaStoreImages): List<GalleryPhoto> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
        )
        val seconds = selection.modifiedAfterMs?.div(1_000L)
        val where = seconds?.let { "${MediaStore.Images.Media.DATE_MODIFIED} >= ?" }
        val args = seconds?.let { arrayOf(it.toString()) }
        val cursor = try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                where,
                args,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
            )
        } catch (error: SecurityException) {
            throw accessDenied(error)
        } ?: throw accessDenied(null)
        return cursor.use {
            buildList {
                while (it.moveToNext() && size < selection.maximumCount) {
                    val id = it.long(MediaStore.Images.Media._ID)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id,
                    )
                    add(it.toPhoto("media:$id", uri.toString()))
                }
            }
        }
    }

    private fun discoverGrantedUri(rawUri: String): GalleryPhoto {
        val uri = requireContentUri(rawUri)
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val cursor = try {
            contentResolver.query(uri, projection, null, null, null)
        } catch (error: SecurityException) {
            throw accessDenied(error)
        } ?: throw GallerySearchException(
            GallerySearchFailure(
                GallerySearchFailureCode.MEDIA_NOT_FOUND,
                "A previously granted photo is no longer available.",
                retryable = true,
            ),
        )
        return cursor.use {
            if (!it.moveToFirst()) {
                throw GallerySearchException(
                    GallerySearchFailure(
                        GallerySearchFailureCode.MEDIA_NOT_FOUND,
                        "A previously granted photo is no longer available.",
                        retryable = true,
                    ),
                )
            }
            it.toPhoto("grant:${sha256(rawUri).take(32)}", rawUri)
        }
    }

    private fun Cursor.toPhoto(mediaId: String, uri: String): GalleryPhoto {
        val mime = string(MediaStore.MediaColumns.MIME_TYPE).ifBlank { "image/*" }
        if (!mime.startsWith("image/")) {
            throw GallerySearchException(
                GallerySearchFailure(
                    GallerySearchFailureCode.UNSUPPORTED_URI,
                    "The granted item is not an image.",
                    retryable = false,
                ),
            )
        }
        return GalleryPhoto(
            mediaId = mediaId,
            contentUri = uri,
            displayName = string(MediaStore.MediaColumns.DISPLAY_NAME),
            modifiedAtMs = longOrZero(MediaStore.MediaColumns.DATE_MODIFIED) * 1_000L,
            sizeBytes = longOrZero(MediaStore.MediaColumns.SIZE),
            mimeType = mime,
        )
    }

    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

    private fun Cursor.longOrZero(column: String): Long {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) 0L else getLong(index).coerceAtLeast(0L)
    }

    private fun Cursor.string(column: String): String {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) "" else getString(index).orEmpty()
    }

    private fun accessDenied(cause: Throwable?) = GallerySearchException(
        GallerySearchFailure(
            GallerySearchFailureCode.ACCESS_DENIED,
            "Photo access is not granted or has been revoked.",
            retryable = true,
        ),
        cause,
    )
}

class ContentResolverGalleryMediaReader(
    private val contentResolver: ContentResolver,
) : GalleryMediaReader {
    override fun open(photo: GalleryPhoto): InputStream {
        val uri = requireContentUri(photo.contentUri)
        return try {
            contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
        } catch (error: SecurityException) {
            throw GallerySearchException(
                GallerySearchFailure(
                    GallerySearchFailureCode.ACCESS_DENIED,
                    "Photo access was revoked while building the local index.",
                    retryable = true,
                ),
                error,
            )
        } catch (error: FileNotFoundException) {
            throw GallerySearchException(
                GallerySearchFailure(
                    GallerySearchFailureCode.MEDIA_NOT_FOUND,
                    "A granted photo was removed before it could be indexed.",
                    retryable = true,
                ),
                error,
            )
        }
    }
}

private fun requireContentUri(value: String): Uri {
    val uri = Uri.parse(value)
    if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrBlank()) {
        throw GallerySearchException(
            GallerySearchFailure(
                GallerySearchFailureCode.UNSUPPORTED_URI,
                "Only locally granted content:// image URIs are accepted.",
                retryable = false,
            ),
        )
    }
    return uri
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
