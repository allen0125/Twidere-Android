package org.mariotaku.twidere.task.twitter

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.support.annotation.UiThread
import android.support.annotation.WorkerThread
import android.text.TextUtils
import android.webkit.MimeTypeMap
import com.twitter.Validator
import edu.tsinghua.hotmobi.HotMobiLogger
import edu.tsinghua.hotmobi.model.MediaUploadEvent
import net.ypresto.androidtranscoder.MediaTranscoder
import net.ypresto.androidtranscoder.format.MediaFormatStrategyPresets
import org.mariotaku.ktextension.*
import org.mariotaku.library.objectcursor.ObjectCursor
import org.mariotaku.microblog.library.MicroBlog
import org.mariotaku.microblog.library.MicroBlogException
import org.mariotaku.microblog.library.fanfou.model.PhotoStatusUpdate
import org.mariotaku.microblog.library.twitter.TwitterUpload
import org.mariotaku.microblog.library.twitter.model.*
import org.mariotaku.restfu.http.ContentType
import org.mariotaku.restfu.http.mime.Body
import org.mariotaku.restfu.http.mime.FileBody
import org.mariotaku.restfu.http.mime.SimpleBody
import org.mariotaku.sqliteqb.library.Expression
import org.mariotaku.twidere.R
import org.mariotaku.twidere.TwidereConstants.*
import org.mariotaku.twidere.annotation.AccountType
import org.mariotaku.twidere.app.TwidereApplication
import org.mariotaku.twidere.extension.model.mediaSizeLimit
import org.mariotaku.twidere.extension.model.newMicroBlogInstance
import org.mariotaku.twidere.extension.model.textLimit
import org.mariotaku.twidere.extension.text.twitter.extractReplyTextAndMentions
import org.mariotaku.twidere.extension.text.twitter.getTweetLength
import org.mariotaku.twidere.model.*
import org.mariotaku.twidere.model.account.AccountExtras
import org.mariotaku.twidere.model.analyzer.UpdateStatus
import org.mariotaku.twidere.model.schedule.ScheduleInfo
import org.mariotaku.twidere.model.util.ParcelableLocationUtils
import org.mariotaku.twidere.model.util.ParcelableStatusUtils
import org.mariotaku.twidere.preference.ServicePickerPreference
import org.mariotaku.twidere.provider.TwidereDataStore.Drafts
import org.mariotaku.twidere.task.BaseAbstractTask
import org.mariotaku.twidere.util.*
import org.mariotaku.twidere.util.io.ContentLengthInputStream
import org.mariotaku.twidere.util.premium.ExtraFeaturesService
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Created by mariotaku on 16/5/22.
 */
class UpdateStatusTask(
        context: Context,
        internal val stateCallback: UpdateStatusTask.StateCallback
) : BaseAbstractTask<Pair<ParcelableStatusUpdate, ScheduleInfo?>, UpdateStatusTask.UpdateStatusResult, Any?>(context) {

    override fun doLongOperation(params: Pair<ParcelableStatusUpdate, ScheduleInfo?>): UpdateStatusResult {
        val (update, info) = params
        val draftId = saveDraft(update)
        microBlogWrapper.addSendingDraftId(draftId)
        try {
            val result = doUpdateStatus(update, info, draftId)
            deleteOrUpdateDraft(update, result, draftId)
            return result
        } catch (e: UpdateStatusException) {
            return UpdateStatusResult(e, draftId)
        } finally {
            microBlogWrapper.removeSendingDraftId(draftId)
        }
    }

    override fun beforeExecute() {
        stateCallback.beforeExecute()
    }

    override fun afterExecute(callback: Any?, result: UpdateStatusResult) {
        stateCallback.afterExecute(result)
        logUpdateStatus(params.first, result)
    }

    private fun logUpdateStatus(statusUpdate: ParcelableStatusUpdate, result: UpdateStatusResult) {
        val mediaType = statusUpdate.media?.firstOrNull()?.type ?: ParcelableMedia.Type.UNKNOWN
        val hasLocation = statusUpdate.location != null
        val preciseLocation = statusUpdate.display_coordinates
        Analyzer.log(UpdateStatus(result.accountTypes.firstOrNull(), statusUpdate.draft_action,
                mediaType, hasLocation, preciseLocation, result.succeed,
                result.exceptions.firstOrNull() ?: result.exception))
    }

    @Throws(UpdateStatusException::class)
    private fun doUpdateStatus(update: ParcelableStatusUpdate, info: ScheduleInfo?, draftId: Long):
            UpdateStatusResult {
        val app = TwidereApplication.getInstance(context)
        val uploader = getMediaUploader(app)
        val shortener = getStatusShortener(app)

        val pendingUpdate = PendingStatusUpdate(update)

        /*
         * override status text, trim existing reply mentions, and add mentions that not
         * exists in status text into ignore list
         */
        regulateReplyText(update, pendingUpdate)

        val result: UpdateStatusResult
        try {
            uploadMedia(uploader, update, info, pendingUpdate)
            shortenStatus(shortener, update, pendingUpdate)

            if (info != null) {
                result = requestScheduleStatus(update, pendingUpdate, info, draftId)
            } else {
                result = requestUpdateStatus(update, pendingUpdate, draftId)
            }

            mediaUploadCallback(uploader, pendingUpdate, result)
            statusShortenCallback(shortener, pendingUpdate, result)

            // Cleanup
            pendingUpdate.deleteOnSuccess.forEach { item -> item.delete(context) }
        } catch (e: UploadException) {
            e.deleteAlways?.forEach { it.delete(context) }
            throw e
        } finally {
            // Cleanup
            pendingUpdate.deleteAlways.forEach { item -> item.delete(context) }
            uploader?.unbindService()
            shortener?.unbindService()
        }
        return result
    }

    private fun regulateReplyText(update: ParcelableStatusUpdate, pending: PendingStatusUpdate) {
        if (update.draft_action != Draft.Action.REPLY) return
        val inReplyTo = update.in_reply_to_status ?: return
        for (i in 0 until pending.length) {
            if (update.accounts[i].type != AccountType.TWITTER) continue
            val (_, replyText, _, excludedMentions, replyToOriginalUser) =
                    extractor.extractReplyTextAndMentions(pending.overrideTexts[i], inReplyTo)
            pending.overrideTexts[i] = replyText
            pending.excludeReplyUserIds[i] = excludedMentions.map { it.key.id }.toTypedArray()
            pending.replyToOriginalUser[i] = replyToOriginalUser
            // Fix status to at least make mentioned user know what status it is
            if (!replyToOriginalUser && update.attachment_url == null) {
                update.attachment_url = LinkCreator.getTwitterStatusLink(inReplyTo.user_screen_name,
                        inReplyTo.id).toString()
            }
        }
    }

    private fun deleteOrUpdateDraft(update: ParcelableStatusUpdate, result: UpdateStatusResult,
            draftId: Long) {
        val where = Expression.equalsArgs(Drafts._ID).sql
        val whereArgs = arrayOf(draftId.toString())
        var hasError = false
        val failedAccounts = ArrayList<UserKey>()
        for (i in update.accounts.indices) {
            val exception = result.exceptions[i]
            if (exception != null && !isDuplicate(exception)) {
                hasError = true
                failedAccounts.add(update.accounts[i].key)
            }
        }
        val cr = context.contentResolver
        if (hasError) {
            val values = ContentValues()
            values.put(Drafts.ACCOUNT_KEYS, failedAccounts.joinToString(","))
            cr.update(Drafts.CONTENT_URI, values, where, whereArgs)
            // TODO show error message
        } else {
            cr.delete(Drafts.CONTENT_URI, where, whereArgs)
        }
    }

    @Throws(UploadException::class)
    private fun uploadMedia(uploader: MediaUploaderInterface?,
            update: ParcelableStatusUpdate,
            info: ScheduleInfo?,
            pendingUpdate: PendingStatusUpdate) {
        stateCallback.onStartUploadingMedia()
        if (uploader != null) {
            uploadMediaWithExtension(uploader, update, pendingUpdate)
        } else if (info == null) {
            uploadMediaWithDefaultProvider(update, pendingUpdate)
        }
    }

    @Throws(UploadException::class)
    private fun uploadMediaWithExtension(uploader: MediaUploaderInterface,
            update: ParcelableStatusUpdate,
            pending: PendingStatusUpdate) {
        uploader.waitForService()
        val media: Array<UploaderMediaItem>
        try {
            media = UploaderMediaItem.getFromStatusUpdate(context, update)
        } catch (e: FileNotFoundException) {
            throw UploadException(e)
        }

        val sharedMedia = HashMap<UserKey, MediaUploadResult>()
        for (i in 0..pending.length - 1) {
            val account = update.accounts[i]
            // Skip upload if shared media found
            val accountKey = account.key
            var uploadResult: MediaUploadResult? = sharedMedia[accountKey]
            if (uploadResult == null) {
                uploadResult = uploader.upload(update, accountKey, media) ?: run {
                    throw UploadException()
                }
                if (uploadResult.media_uris == null) {
                    throw UploadException(uploadResult.error_message ?: "Unknown error")
                }
                pending.mediaUploadResults[i] = uploadResult
                if (uploadResult.shared_owners != null) {
                    for (sharedOwner in uploadResult.shared_owners) {
                        sharedMedia.put(sharedOwner, uploadResult)
                    }
                }
            }
            // Override status text
            pending.overrideTexts[i] = Utils.getMediaUploadStatus(context,
                    uploadResult.media_uris, pending.overrideTexts[i])
        }
    }

    @Throws(UpdateStatusException::class)
    private fun shortenStatus(shortener: StatusShortenerInterface?,
            update: ParcelableStatusUpdate,
            pending: PendingStatusUpdate) {
        if (shortener == null) return
        val validator = Validator()
        stateCallback.onShorteningStatus()
        val sharedShortened = HashMap<UserKey, StatusShortenResult>()
        for (i in 0 until pending.length) {
            val account = update.accounts[i]
            val text = pending.overrideTexts[i]
            val textLimit = account.textLimit
            val ignoreMentions = account.type == AccountType.TWITTER
            if (textLimit >= 0 && validator.getTweetLength(text, ignoreMentions,
                    update.in_reply_to_status) <= textLimit) {
                continue
            }
            shortener.waitForService()
            // Skip upload if this shared media found
            val accountKey = account.key
            var shortenResult: StatusShortenResult? = sharedShortened[accountKey]
            if (shortenResult == null) {
                shortenResult = shortener.shorten(update, accountKey, text) ?: run {
                    throw ShortenException()
                }
                if (shortenResult.shortened == null) {
                    throw ShortenException(shortenResult.error_message ?: "Unknown error")
                }
                pending.statusShortenResults[i] = shortenResult
                if (shortenResult.shared_owners != null) {
                    for (sharedOwner in shortenResult.shared_owners) {
                        sharedShortened.put(sharedOwner, shortenResult)
                    }
                }
            }
            // Override status text
            pending.overrideTexts[i] = shortenResult.shortened
        }
    }

    @Throws(UpdateStatusException::class)
    private fun requestScheduleStatus(
            statusUpdate: ParcelableStatusUpdate,
            pendingUpdate: PendingStatusUpdate,
            scheduleInfo: ScheduleInfo,
            draftId: Long
    ): UpdateStatusResult {

        stateCallback.onUpdatingStatus()
        if (!extraFeaturesService.isEnabled(ExtraFeaturesService.FEATURE_SCHEDULE_STATUS)) {
            throw SchedulerNotFoundException(context.getString(R.string.error_message_scheduler_not_available))
        }

        val controller = scheduleProvider ?: run {
            throw SchedulerNotFoundException(context.getString(R.string.error_message_scheduler_not_available))
        }

        controller.scheduleStatus(statusUpdate, pendingUpdate, scheduleInfo)

        return UpdateStatusResult(pendingUpdate.length, draftId)
    }

    @Throws(UpdateStatusException::class)
    private fun requestUpdateStatus(
            statusUpdate: ParcelableStatusUpdate,
            pendingUpdate: PendingStatusUpdate,
            draftId: Long
    ): UpdateStatusResult {

        stateCallback.onUpdatingStatus()

        val result = UpdateStatusResult(pendingUpdate.length, draftId)

        for (i in 0 until pendingUpdate.length) {
            val account = statusUpdate.accounts[i]
            result.accountTypes[i] = account.type
            val microBlog = MicroBlogAPIFactory.getInstance(context, account.key)
            try {
                val requestResult = when (account.type) {
                    AccountType.FANFOU -> {
                        // Call uploadPhoto if media present
                        if (statusUpdate.media.isNotNullOrEmpty()) {
                            // Fanfou only allow one photo
                            fanfouUpdateStatusWithPhoto(microBlog, statusUpdate, pendingUpdate,
                                    pendingUpdate.overrideTexts[i], account.mediaSizeLimit, i)
                        } else {
                            twitterUpdateStatus(microBlog, statusUpdate, pendingUpdate, i)
                        }
                    }
                    else -> {
                        twitterUpdateStatus(microBlog, statusUpdate, pendingUpdate, i)
                    }
                }
                result.statuses[i] = ParcelableStatusUtils.fromStatus(requestResult,
                        account.key, account.type, false)
            } catch (e: MicroBlogException) {
                result.exceptions[i] = e
            }
        }
        return result
    }

    @Throws(MicroBlogException::class, UploadException::class)
    private fun fanfouUpdateStatusWithPhoto(microBlog: MicroBlog, statusUpdate: ParcelableStatusUpdate,
            pendingUpdate: PendingStatusUpdate, overrideText: String,
            sizeLimit: SizeLimit, updateIndex: Int): Status {
        if (statusUpdate.media.size > 1) {
            throw MicroBlogException(context.getString(R.string.error_too_many_photos_fanfou))
        }
        val media = statusUpdate.media.first()
        try {
            return getBodyFromMedia(context, media, sizeLimit, false, ContentLengthInputStream.ReadListener { length, position ->
                stateCallback.onUploadingProgressChanged(-1, position, length)
            }).use { mediaBody ->
                val photoUpdate = PhotoStatusUpdate(mediaBody.body, pendingUpdate.overrideTexts[updateIndex])
                return@use microBlog.uploadPhoto(photoUpdate)
            }
        } catch (e: IOException) {
            throw UploadException(e)
        }
    }

    /**
     * Calling Twitter's upload method. This method sets multiple owner for bandwidth saving
     */
    @Throws(UploadException::class)
    private fun uploadMediaWithDefaultProvider(update: ParcelableStatusUpdate, pendingUpdate: PendingStatusUpdate) {
        // Return empty array if no media attached
        if (update.media.isNullOrEmpty()) return
        val ownersList = update.accounts.filter {
            AccountType.TWITTER == it.type
        }.map(AccountDetails::key)
        val ownerIds = ownersList.map {
            it.id
        }.toTypedArray()
        for (i in 0..pendingUpdate.length - 1) {
            val account = update.accounts[i]
            val mediaIds: Array<String>?
            when (account.type) {
                AccountType.TWITTER -> {
                    val upload = account.newMicroBlogInstance(context, cls = TwitterUpload::class.java)
                    if (pendingUpdate.sharedMediaIds != null) {
                        mediaIds = pendingUpdate.sharedMediaIds
                    } else {
                        val (ids, deleteOnSuccess, deleteAlways) = uploadAllMediaShared(context,
                                upload, account, update.media, ownerIds, true, stateCallback)
                        mediaIds = ids
                        deleteOnSuccess.addAllTo(pendingUpdate.deleteOnSuccess)
                        deleteAlways.addAllTo(pendingUpdate.deleteAlways)
                        pendingUpdate.sharedMediaIds = mediaIds
                    }
                }
                AccountType.FANFOU -> {
                    // Nope, fanfou uses photo uploading API
                    mediaIds = null
                }
                AccountType.STATUSNET -> {
                    // TODO use their native API
                    val upload = account.newMicroBlogInstance(context, cls = TwitterUpload::class.java)
                    val (ids, deleteOnSuccess, deleteAlways) = uploadAllMediaShared(context,
                            upload, account, update.media, ownerIds, false, stateCallback)
                    mediaIds = ids
                    deleteOnSuccess.addAllTo(pendingUpdate.deleteOnSuccess)
                    deleteAlways.addAllTo(pendingUpdate.deleteAlways)
                }
                else -> {
                    mediaIds = null
                }
            }
            pendingUpdate.mediaIds[i] = mediaIds
        }
        pendingUpdate.sharedMediaOwners = ownersList.toTypedArray()
    }

    @Throws(MicroBlogException::class)
    private fun twitterUpdateStatus(microBlog: MicroBlog, statusUpdate: ParcelableStatusUpdate,
            pendingUpdate: PendingStatusUpdate, index: Int): Status {
        val overrideText = pendingUpdate.overrideTexts[index]
        val status = StatusUpdate(overrideText)
        val inReplyToStatus = statusUpdate.in_reply_to_status
        if (inReplyToStatus != null) {
            status.inReplyToStatusId(inReplyToStatus.id)
            if (statusUpdate.accounts[index].type == AccountType.TWITTER) {
                val replyToOriginalUser = pendingUpdate.replyToOriginalUser[index]
                status.autoPopulateReplyMetadata(replyToOriginalUser)
                if (replyToOriginalUser) {
                    status.excludeReplyUserIds(pendingUpdate.excludeReplyUserIds[index])
                }
            }
        }
        if (statusUpdate.repost_status_id != null) {
            status.repostStatusId(statusUpdate.repost_status_id)
        }
        if (statusUpdate.attachment_url != null) {
            status.attachmentUrl(statusUpdate.attachment_url)
        }
        if (statusUpdate.location != null) {
            status.location(ParcelableLocationUtils.toGeoLocation(statusUpdate.location))
            status.displayCoordinates(statusUpdate.display_coordinates)
        }
        val mediaIds = pendingUpdate.mediaIds[index]
        if (mediaIds != null) {
            status.mediaIds(mediaIds)
        }
        if (statusUpdate.is_possibly_sensitive) {
            status.possiblySensitive(statusUpdate.is_possibly_sensitive)
        }
        return microBlog.updateStatus(status)
    }

    private fun statusShortenCallback(shortener: StatusShortenerInterface?,
            pendingUpdate: PendingStatusUpdate, updateResult: UpdateStatusResult) {
        if (shortener == null || !shortener.waitForService()) return
        for (i in 0..pendingUpdate.length - 1) {
            val shortenResult = pendingUpdate.statusShortenResults[i]
            val status = updateResult.statuses[i]
            if (shortenResult == null || status == null) continue
            shortener.callback(shortenResult, status)
        }
    }

    private fun mediaUploadCallback(uploader: MediaUploaderInterface?,
            pendingUpdate: PendingStatusUpdate, updateResult: UpdateStatusResult) {
        if (uploader == null || !uploader.waitForService()) return
        for (i in 0..pendingUpdate.length - 1) {
            val uploadResult = pendingUpdate.mediaUploadResults[i]
            val status = updateResult.statuses[i]
            if (uploadResult == null || status == null) continue
            uploader.callback(uploadResult, status)
        }
    }

    @Throws(UploaderNotFoundException::class, UploadException::class, ShortenerNotFoundException::class, ShortenException::class)
    private fun getStatusShortener(app: TwidereApplication): StatusShortenerInterface? {
        val shortenerComponent = preferences.getString(KEY_STATUS_SHORTENER, null)
        if (ServicePickerPreference.isNoneValue(shortenerComponent)) return null

        val shortener = StatusShortenerInterface.getInstance(app, shortenerComponent) ?: throw ShortenerNotFoundException()
        try {
            shortener.checkService { metaData ->
                if (metaData == null) throw ExtensionVersionMismatchException()
                val extensionVersion = metaData.getString(METADATA_KEY_EXTENSION_VERSION_STATUS_SHORTENER)
                if (!TextUtils.equals(extensionVersion, context.getString(R.string.status_shortener_service_interface_version))) {
                    throw ExtensionVersionMismatchException()
                }
            }
        } catch (e: ExtensionVersionMismatchException) {
            throw ShortenException(context.getString(R.string.shortener_version_incompatible))
        } catch (e: AbsServiceInterface.CheckServiceException) {
            throw ShortenException(e)
        }
        return shortener
    }

    @Throws(UploaderNotFoundException::class, UploadException::class)
    private fun getMediaUploader(app: TwidereApplication): MediaUploaderInterface? {
        val uploaderComponent = preferences.getString(KEY_MEDIA_UPLOADER, null)
        if (ServicePickerPreference.isNoneValue(uploaderComponent)) return null
        val uploader = MediaUploaderInterface.getInstance(app, uploaderComponent) ?:
                throw UploaderNotFoundException(context.getString(R.string.error_message_media_uploader_not_found))
        try {
            uploader.checkService { metaData ->
                if (metaData == null) throw ExtensionVersionMismatchException()
                val extensionVersion = metaData.getString(METADATA_KEY_EXTENSION_VERSION_MEDIA_UPLOADER)
                if (!TextUtils.equals(extensionVersion, context.getString(R.string.media_uploader_service_interface_version))) {
                    throw ExtensionVersionMismatchException()
                }
            }
        } catch (e: AbsServiceInterface.CheckServiceException) {
            if (e is ExtensionVersionMismatchException) {
                throw UploadException(context.getString(R.string.uploader_version_incompatible), e)
            }
            throw UploadException(e)
        }

        return uploader
    }

    private fun isDuplicate(exception: Exception): Boolean {
        return exception is MicroBlogException && exception.errorCode == ErrorInfo.STATUS_IS_DUPLICATE
    }


    private fun saveDraft(statusUpdate: ParcelableStatusUpdate): Long {
        return saveDraft(context, statusUpdate.draft_action ?: Draft.Action.UPDATE_STATUS) {
            this.unique_id = statusUpdate.draft_unique_id ?: UUID.randomUUID().toString()
            this.account_keys = statusUpdate.accounts.map { it.key }.toTypedArray()
            this.text = statusUpdate.text
            this.location = statusUpdate.location
            this.media = statusUpdate.media
            this.timestamp = System.currentTimeMillis()
            this.action_extras = statusUpdate.draft_extras
        }
    }

    class PendingStatusUpdate internal constructor(val length: Int, defaultText: String) {

        internal constructor(statusUpdate: ParcelableStatusUpdate) : this(statusUpdate.accounts.size,
                statusUpdate.text)

        var sharedMediaIds: Array<String>? = null
        var sharedMediaOwners: Array<UserKey>? = null

        val overrideTexts: Array<String> = Array(length) { defaultText }
        val excludeReplyUserIds: Array<Array<String>?> = arrayOfNulls(length)
        val replyToOriginalUser: BooleanArray = BooleanArray(length)
        val mediaIds: Array<Array<String>?> = arrayOfNulls(length)

        val mediaUploadResults: Array<MediaUploadResult?> = arrayOfNulls(length)
        val statusShortenResults: Array<StatusShortenResult?> = arrayOfNulls(length)

        val deleteOnSuccess: ArrayList<MediaDeletionItem> = arrayListOf()
        val deleteAlways: ArrayList<MediaDeletionItem> = arrayListOf()

    }

    class UpdateStatusResult {
        val statuses: Array<ParcelableStatus?>
        val exceptions: Array<MicroBlogException?>
        val accountTypes: Array<String?>

        val exception: UpdateStatusException?
        val draftId: Long

        val succeed: Boolean get() = exception == null && exceptions.none { it != null }

        constructor(count: Int, draftId: Long) {
            this.statuses = arrayOfNulls(count)
            this.exceptions = arrayOfNulls(count)
            this.accountTypes = arrayOfNulls(count)
            this.exception = null
            this.draftId = draftId
        }

        constructor(exception: UpdateStatusException, draftId: Long) {
            this.exception = exception
            this.statuses = arrayOfNulls(0)
            this.exceptions = arrayOfNulls(0)
            this.accountTypes = arrayOfNulls(0)
            this.draftId = draftId
        }
    }


    open class UpdateStatusException : Exception {
        protected constructor() : super()

        protected constructor(detailMessage: String, throwable: Throwable) : super(detailMessage, throwable)

        protected constructor(throwable: Throwable) : super(throwable)

        protected constructor(message: String) : super(message)
    }

    class UploaderNotFoundException(message: String) : UpdateStatusException(message)
    class SchedulerNotFoundException(message: String) : UpdateStatusException(message)

    class UploadException : UpdateStatusException {

        var deleteAlways: List<MediaDeletionItem>? = null

        constructor() : super()

        constructor(detailMessage: String, throwable: Throwable) : super(detailMessage, throwable)

        constructor(throwable: Throwable) : super(throwable)

        constructor(message: String) : super(message)
    }


    class ExtensionVersionMismatchException : AbsServiceInterface.CheckServiceException()

    class ShortenerNotFoundException : UpdateStatusException()

    class ShortenException : UpdateStatusException {

        constructor() : super()

        constructor(detailMessage: String, throwable: Throwable) : super(detailMessage, throwable)

        constructor(throwable: Throwable) : super(throwable)

        constructor(message: String) : super(message)
    }

    interface StateCallback : UploadCallback {

        @WorkerThread
        fun onShorteningStatus()

        @WorkerThread
        fun onUpdatingStatus()

        @UiThread
        fun afterExecute(result: UpdateStatusResult)

        @UiThread
        fun beforeExecute()
    }

    interface UploadCallback {
        @WorkerThread
        fun onStartUploadingMedia()

        @WorkerThread
        fun onUploadingProgressChanged(index: Int, current: Long, total: Long)

    }

    data class SizeLimit(
            val image: AccountExtras.ImageLimit,
            val video: AccountExtras.VideoLimit
    )

    data class MediaStreamBody(
            val body: Body,
            val geometry: Point?,
            val deleteOnSuccess: List<MediaDeletionItem>?,
            val deleteAlways: List<MediaDeletionItem>?
    ) : Closeable {
        override fun close() {
            body.close()
        }
    }

    interface MediaDeletionItem {
        fun delete(context: Context): Boolean
    }

    data class UriMediaDeletionItem(val uri: Uri) : MediaDeletionItem {
        override fun delete(context: Context): Boolean {
            return Utils.deleteMedia(context, uri)
        }
    }

    data class FileMediaDeletionItem(val file: File) : MediaDeletionItem {
        override fun delete(context: Context): Boolean {
            return file.delete()
        }

    }

    data class SharedMediaUploadResult(
            val ids: Array<String>,
            val deleteOnSuccess: List<MediaDeletionItem>,
            val deleteAlways: List<MediaDeletionItem>
    )

    companion object {

        private val BULK_SIZE = 256 * 1024// 128 Kib

        @Throws(UploadException::class)
        fun uploadAllMediaShared(
                context: Context,
                upload: TwitterUpload,
                account: AccountDetails,
                media: Array<ParcelableMediaUpdate>,
                ownerIds: Array<String>?,
                chucked: Boolean,
                callback: UploadCallback?
        ): SharedMediaUploadResult {
            val deleteOnSuccess = ArrayList<MediaDeletionItem>()
            val deleteAlways = ArrayList<MediaDeletionItem>()
            val mediaIds = media.mapIndexed { index, media ->
                val resp: MediaUploadResponse
                //noinspection TryWithIdenticalCatches
                var body: MediaStreamBody? = null
                try {
                    val sizeLimit = account.mediaSizeLimit
                    body = getBodyFromMedia(context, media, sizeLimit, chucked,
                            ContentLengthInputStream.ReadListener { length, position ->
                                callback?.onUploadingProgressChanged(index, position, length)
                            })
                    val mediaUploadEvent = MediaUploadEvent.create(context, media)
                    mediaUploadEvent.setFileSize(body.body.length())
                    body.geometry?.let { geometry ->
                        mediaUploadEvent.setGeometry(geometry.x, geometry.y)
                    }
                    if (chucked) {
                        resp = uploadMediaChucked(upload, body.body, ownerIds)
                    } else {
                        resp = upload.uploadMedia(body.body, ownerIds)
                    }
                    mediaUploadEvent.markEnd()
                    HotMobiLogger.getInstance(context).log(mediaUploadEvent)
                } catch (e: IOException) {
                    throw UploadException(e).apply {
                        this.deleteAlways = deleteAlways
                    }
                } catch (e: MicroBlogException) {
                    throw UploadException(e).apply {
                        this.deleteAlways = deleteAlways
                    }
                } finally {
                    Utils.closeSilently(body)
                }
                body?.deleteOnSuccess?.addAllTo(deleteOnSuccess)
                body?.deleteAlways?.addAllTo(deleteAlways)
                if (media.alt_text?.isNotEmpty() ?: false) {
                    try {
                        upload.createMetadata(NewMediaMetadata(resp.id, media.alt_text))
                    } catch (e: MicroBlogException) {
                        // Ignore
                    }
                }
                return@mapIndexed resp.id
            }
            return SharedMediaUploadResult(mediaIds.toTypedArray(), deleteOnSuccess, deleteAlways)
        }

        @Throws(IOException::class)
        fun getBodyFromMedia(
                context: Context,
                media: ParcelableMediaUpdate,
                sizeLimit: SizeLimit? = null,
                chucked: Boolean,
                readListener: ContentLengthInputStream.ReadListener
        ): MediaStreamBody {
            val resolver = context.contentResolver
            val mediaUri = Uri.parse(media.uri)
            val type = media.type
            val mediaType = resolver.getType(mediaUri) ?: run {
                if (mediaUri.scheme == ContentResolver.SCHEME_FILE) {
                    mediaUri.lastPathSegment?.substringAfterLast(".")?.let { ext ->
                        return@run MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    }
                }
                return@run null
            }

            val data = if (sizeLimit != null) when (type) {
                ParcelableMedia.Type.IMAGE -> imageStream(context, resolver, mediaUri, mediaType,
                        sizeLimit)
                ParcelableMedia.Type.VIDEO -> videoStream(context, resolver, mediaUri, mediaType,
                        sizeLimit, chucked)
                else -> null
            } else null

            val cis = data?.stream ?: run {
                val st = resolver.openInputStream(mediaUri) ?: throw FileNotFoundException(mediaUri.toString())
                val length = st.available().toLong()
                return@run ContentLengthInputStream(st, length)
            }
            cis.setReadListener(readListener)
            val mimeType = data?.type ?: mediaType ?: "application/octet-stream"
            val body = FileBody(cis, "attachment", cis.length(), ContentType.parse(mimeType))
            val deleteOnSuccess: MutableList<MediaDeletionItem> = mutableListOf()
            val deleteAlways: MutableList<MediaDeletionItem> = mutableListOf()
            if (media.delete_always) {
                deleteAlways.add(UriMediaDeletionItem(mediaUri))
            } else if (media.delete_on_success) {
                deleteOnSuccess.add(UriMediaDeletionItem(mediaUri))
            }
            data?.deleteOnSuccess?.addAllTo(deleteOnSuccess)
            data?.deleteAlways?.addAllTo(deleteAlways)
            return MediaStreamBody(body, data?.geometry, deleteOnSuccess, deleteAlways)
        }


        @Throws(IOException::class, MicroBlogException::class)
        private fun uploadMediaChucked(upload: TwitterUpload, body: Body,
                ownerIds: Array<String>?): MediaUploadResponse {
            val mediaType = body.contentType().contentType
            val length = body.length()
            val stream = body.stream()
            var response = upload.initUploadMedia(mediaType, length, ownerIds)
            val segments = if (length == 0L) 0 else (length / BULK_SIZE + 1).toInt()
            for (segmentIndex in 0..segments - 1) {
                val currentBulkSize = Math.min(BULK_SIZE.toLong(), length - segmentIndex * BULK_SIZE).toInt()
                val bulk = SimpleBody(ContentType.OCTET_STREAM, null, currentBulkSize.toLong(),
                        stream)
                upload.appendUploadMedia(response.id, segmentIndex, bulk)
            }
            response = upload.finalizeUploadMedia(response.id)
            var info: MediaUploadResponse.ProcessingInfo? = response.processingInfo
            while (info != null && shouldWaitForProcess(info)) {
                val checkAfterSecs = info.checkAfterSecs
                if (checkAfterSecs <= 0) {
                    break
                }
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(checkAfterSecs))
                } catch (e: InterruptedException) {
                    break
                }

                response = upload.getUploadMediaStatus(response.id)
                info = response.processingInfo
            }
            if (info != null && MediaUploadResponse.ProcessingInfo.State.FAILED == info.state) {
                val exception = MicroBlogException()
                val errorInfo = info.error
                if (errorInfo != null) {
                    exception.errors = arrayOf(errorInfo)
                }
                throw exception
            }
            return response
        }

        private fun shouldWaitForProcess(info: MediaUploadResponse.ProcessingInfo): Boolean {
            when (info.state) {
                MediaUploadResponse.ProcessingInfo.State.PENDING, MediaUploadResponse.ProcessingInfo.State.IN_PROGRESS -> return true
                else -> return false
            }
        }

        private fun imageStream(
                context: Context,
                resolver: ContentResolver,
                mediaUri: Uri,
                defaultType: String?,
                sizeLimit: SizeLimit
        ): MediaStreamData? {
            var mediaType = defaultType
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactoryUtils.decodeUri(resolver, mediaUri, null, o)
            // Try to use decoded media type
            if (o.outMimeType != null) {
                mediaType = o.outMimeType
            }
            // Skip if media is GIF
            if (o.outWidth <= 0 || o.outHeight <= 0 || mediaType == "image/gif") {
                return null
            }

            val imageLimit = sizeLimit.image
            if (imageLimit.check(o.outWidth, o.outHeight)) {
                return null
            }

            o.inSampleSize = Utils.calculateInSampleSize(o.outWidth, o.outHeight,
                    imageLimit.maxWidth, imageLimit.maxHeight)
            o.inJustDecodeBounds = false
            // Do actual image decoding
            val bitmap = context.contentResolver.openInputStream(mediaUri).use {
                BitmapFactory.decodeStream(it, null, o)
            } ?: return null

            val size = Point(bitmap.width, bitmap.height)
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mediaType)
            val tempFile = File.createTempFile("twidere__scaled_image_", ".$ext", context.cacheDir)

            when (mediaType) {
                "image/png", "image/x-png", "image/webp", "image-x-webp" -> {
                    tempFile.outputStream().use { os ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 0, os)
                    }
                }
                "image/jpeg" -> {
                    val origExif = context.contentResolver.openInputStream(mediaUri)
                            .use(::ExifInterface)
                    tempFile.outputStream().use { os ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, os)
                    }
                    val orientation = origExif.getAttribute(ExifInterface.TAG_ORIENTATION)
                    if (orientation != null) {
                        ExifInterface(tempFile.absolutePath).apply {
                            setAttribute(ExifInterface.TAG_ORIENTATION, orientation)
                            saveAttributes()
                        }
                    }
                }
                else -> {
                    tempFile.outputStream().use { os ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, os)
                    }
                }
            }
            return MediaStreamData(ContentLengthInputStream(tempFile), mediaType, size,
                    null, listOf(FileMediaDeletionItem(tempFile)))
        }

        private fun videoStream(
                context: Context,
                resolver: ContentResolver,
                mediaUri: Uri,
                defaultType: String?,
                sizeLimit: SizeLimit,
                chucked: Boolean
        ): MediaStreamData? {
            var mediaType = defaultType
            val videoLimit = sizeLimit.video
            val geometry = Point()
            var duration = -1L
            var framerate = -1.0
            var size = -1L
            // TODO only transcode video if needed, use `MediaMetadataRetriever`
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, mediaUri)
                val extractedMimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                if (extractedMimeType != null) {
                    mediaType = extractedMimeType
                }
                geometry.x = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toIntOr(-1)
                geometry.y = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toIntOr(-1)
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOr(-1)
                framerate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE).toDoubleOr(-1.0)

                size = resolver.openFileDescriptor(mediaUri, "r").use { it.statSize }
            } catch (e: Exception) {
                DebugLog.w(LOGTAG, "Unable to retrieve video info", e)
            } finally {
                retriever.releaseSafe()
            }

            if (geometry.x > 0 && geometry.y > 0 && videoLimit.checkGeometry(geometry.x, geometry.y)
                    && framerate > 0 && videoLimit.checkFrameRate(framerate)
                    && size > 0 && videoLimit.checkSize(size, chucked)) {
                // Size valid, upload directly
                DebugLog.d(LOGTAG, "Upload video directly")
                return null
            }

            if (!videoLimit.checkMinDuration(duration, chucked)) {
                throw UploadException(context.getString(R.string.message_video_too_short))
            }

            if (!videoLimit.checkMaxDuration(duration, chucked)) {
                throw UploadException(context.getString(R.string.message_video_too_long))
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
                // Go get a new phone
                return null
            }
            DebugLog.d(LOGTAG, "Transcoding video")

            val ext = mediaUri.lastPathSegment.substringAfterLast(".")
            val strategy = MediaFormatStrategyPresets.createAndroid720pStrategy()
            val listener = object : MediaTranscoder.Listener {
                override fun onTranscodeFailed(exception: Exception?) {
                }

                override fun onTranscodeCompleted() {
                }

                override fun onTranscodeProgress(progress: Double) {
                }

                override fun onTranscodeCanceled() {
                }

            }
            val pfd = resolver.openFileDescriptor(mediaUri, "r")
            val tempFile = File.createTempFile("twidere__encoded_video_", ".$ext", context.cacheDir)
            val future = MediaTranscoder.getInstance().transcodeVideo(pfd.fileDescriptor,
                    tempFile.absolutePath, strategy, listener)
            try {
                future.get()
            } catch (e: Exception) {
                DebugLog.w(LOGTAG, "Error transcoding video, try upload directly", e)
                tempFile.delete()
                return null
            }
            return MediaStreamData(ContentLengthInputStream(tempFile.inputStream(), tempFile.length()),
                    mediaType, geometry, null, listOf(FileMediaDeletionItem(tempFile)))
        }

        internal class MediaStreamData(
                val stream: ContentLengthInputStream?,
                val type: String?,
                val geometry: Point?,
                val deleteOnSuccess: List<MediaDeletionItem>?,
                val deleteAlways: List<MediaDeletionItem>?
        )

        fun saveDraft(context: Context, @Draft.Action action: String, config: Draft.() -> Unit): Long {
            val draft = Draft()
            draft.action_type = action
            draft.timestamp = System.currentTimeMillis()
            config(draft)
            val resolver = context.contentResolver
            val creator = ObjectCursor.valuesCreatorFrom(Draft::class.java)
            val draftUri = resolver.insert(Drafts.CONTENT_URI, creator.create(draft)) ?: return -1
            return draftUri.lastPathSegment.toLongOr(-1)
        }

        fun deleteDraft(context: Context, id: Long) {
            val where = Expression.equalsArgs(Drafts._ID).sql
            val whereArgs = arrayOf(id.toString())
            context.contentResolver.delete(Drafts.CONTENT_URI, where, whereArgs)
        }

        fun AccountExtras.ImageLimit.check(width: Int, height: Int): Boolean {
            return (width <= this.maxWidth && height <= this.maxHeight) || (height <= this.maxWidth
                    && width <= this.maxHeight)
        }
    }

}

