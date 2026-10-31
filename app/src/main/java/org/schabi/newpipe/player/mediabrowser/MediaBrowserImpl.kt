package org.schabi.newpipe.player.mediabrowser

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.media.MediaBrowserServiceCompat
import androidx.media.MediaBrowserServiceCompat.BrowserRoot.EXTRA_RECENT
import androidx.media.MediaBrowserServiceCompat.Result
import androidx.media.utils.MediaConstants
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.function.Consumer
import org.schabi.newpipe.MainActivity.DEBUG
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.history.model.StreamHistoryEntry
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.playlist.PlaylistLocalItem
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.InfoItem.InfoType
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.local.bookmark.MergedPlaylistManager
import org.schabi.newpipe.local.playlist.LocalPlaylistManager
import org.schabi.newpipe.local.playlist.RemotePlaylistManager
import org.schabi.newpipe.util.ChannelTabHelper
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.ServiceHelper
import org.schabi.newpipe.util.image.ImageStrategy

/**
 * This class is used to cleanly separate the Service implementation (in
 * [org.schabi.newpipe.player.PlayerService]) and the media browser implementation (in this file).
 *
 * @param notifyChildrenChanged takes the parent id of the children that changed
 */
class MediaBrowserImpl(
    private val context: Context,
    // parentId
    private val notifyChildrenChanged: Consumer<String>
) {
    private val packageValidator = PackageValidator(context)
    private val database = NewPipeDatabase.getInstance(context)
    private var disposables = CompositeDisposable()
    @Volatile private var carSaveActionIds = listOf<String>()
    private val carSavesPlaylistName = "Car Saves"
    // These categories are available even when the fork's separate local library is empty.
    // Android Auto itself supplies the voice / parked text-search interface.
    private data class DiscoverCategory(val name: String, val query: String, val filter: String?)
    private val discoveryCategories = listOf(
        DiscoverCategory("Popular songs", "popular music hits", "music_songs"),
        DiscoverCategory("New music", "new music songs", "music_songs"),
        DiscoverCategory("Chill music", "relaxing chill music", "music_songs"),
        DiscoverCategory("Rock music", "rock music hits", "music_songs"),
        DiscoverCategory("Hindi music", "Hindi songs", "music_songs"),
        DiscoverCategory("Electronic music", "electronic dance music", "music_songs"),
        DiscoverCategory("Music playlists", "top music playlists", "music_playlists"),
        DiscoverCategory("Hindi playlists", "Hindi hits playlists", "playlists"),
        DiscoverCategory("Podcast playlists", "popular podcasts", "playlists"),
        DiscoverCategory("Artists and channels", "popular music artists", "channels")
    )

    init {
        // this will listen to changes in the bookmarks until this MediaBrowserImpl is dispose()d
        disposables.add(
            getMergedPlaylists().subscribe { notifyChildrenChanged.accept(ID_BOOKMARKS) }
        )
    }

    //region Cleanup
    fun dispose() {
        disposables.dispose()
    }
    //endregion

    //region onGetRoot
    fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): MediaBrowserServiceCompat.BrowserRoot? {
        if (DEBUG) {
            Log.d(TAG, "onGetRoot($clientPackageName, $clientUid, $rootHints)")
        }

        if (!packageValidator.isKnownCaller(clientPackageName, clientUid)) {
            // this is a caller we can't trust (see PackageValidator's rules taken from uamp)
            return null
        }

        if (rootHints?.getBoolean(EXTRA_RECENT, false) == true) {
            // the system is asking for a root to do media resumption, but we can't handle that yet,
            // see https://developer.android.com/media/implement/surfaces/mobile#mediabrowserservice_implementation
            return null
        }

        val extras = Bundle()
        extras.putBoolean(
            MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED,
            true
        )
        // The vehicle host controls whether per-item playlist actions are available.
        val maxActions = rootHints?.getInt(
            MediaConstants.BROWSER_ROOT_HINTS_KEY_CUSTOM_BROWSER_ACTION_LIMIT, 0
        ) ?: 0
        if (maxActions > 0) {
            val actions = arrayListOf<Bundle>()
            actions.add(createCarAction(CAR_SAVE_ACTION, "Save to Car Saves"))
            try {
                LocalPlaylistManager(database).getPlaylists().firstOrError().blockingGet()
                    .filter { !it.orderingName.isNullOrBlank() && it.orderingName != carSavesPlaylistName }
                    .take((maxActions - 1).coerceAtLeast(0).coerceAtMost(3))
                    .forEach { playlist ->
                        actions.add(createCarAction(
                            "$CAR_SAVE_TO_PLAYLIST_PREFIX${playlist.uid}",
                            "Save to ${playlist.orderingName}"
                        ))
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Could not fetch car playlist shortcuts", e)
            }
            val finalActions = ArrayList(actions.take(maxActions))
            carSaveActionIds = finalActions.mapNotNull {
                it.getString(MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ID)
            }
            extras.putParcelableArrayList(
                MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ROOT_LIST,
                finalActions
            )
        } else {
            carSaveActionIds = emptyList()
        }
        return MediaBrowserServiceCompat.BrowserRoot(ID_ROOT, extras)
    }
    private fun createCarAction(actionId: String, label: String): Bundle {
        val iconUri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(context.packageName)
            .appendPath("drawable")
            .appendPath("ic_playlist_add")
            .build()
        return Bundle().apply {
            putString(MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ID, actionId)
            putString(MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_LABEL, label)
            putString(MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ICON_URI, iconUri.toString())
        }
    }
    //endregion

    //region onLoadChildren
    fun onLoadChildren(parentId: String, result: Result<List<MediaBrowserCompat.MediaItem>>) {
        if (DEBUG) {
            Log.d(TAG, "onLoadChildren($parentId)")
        }

        result.detach() // allows sendResult() to happen later
        disposables.add(
            onLoadChildren(parentId)
                .subscribe(
                    { result.sendResult(it) },
                    { throwable ->
                        // null indicates an error, see the docs of MediaSessionCompat.onSearch()
                        // A failed remote list or empty local database should not leave the
                        // Android Auto browser spinning indefinitely.
                        result.sendResult(emptyList())
                        Log.e(TAG, "onLoadChildren error for parentId=$parentId: $throwable")
                    }
                )
        )
    }

    private fun onLoadChildren(parentId: String): Single<List<MediaBrowserCompat.MediaItem>> {
        try {
            val parentIdUri = parentId.toUri()
            val path = ArrayList(parentIdUri.pathSegments)

            if (path.isEmpty()) {
                return Single.just(
                    listOf(
                        createRootMediaItem(
                            ID_WHATS_NEW,
                            "What's New",
                            R.drawable.ic_whatshot
                        ),
                        createRootMediaItem(
                            ID_SUBSCRIPTIONS,
                            "Subscriptions",
                            R.drawable.ic_subscriptions
                        ),
                        createRootMediaItem(
                            ID_TRENDING,
                            "Trending",
                            R.drawable.ic_trending_up
                        ),
                        createRootMediaItem(
                            ID_RELATED,
                            "Related to Recent",
                            R.drawable.ic_smart_display
                        ),
                        createRootMediaItem(
                            ID_DISCOVER,
                            "Explore music & playlists",
                            R.drawable.ic_music_note
                        ),
                        createRootMediaItem(
                            ID_BOOKMARKS,
                            context.resources.getString(R.string.tab_bookmarks_short),
                            R.drawable.ic_bookmark_white
                        ),
                        createRootMediaItem(
                            ID_HISTORY,
                            context.resources.getString(R.string.action_history),
                            R.drawable.ic_history_white
                        )
                    )
                )
            }

            when (path.removeAt(0)) {
                ID_WHATS_NEW -> {
                    if (path.isEmpty()) return populateWhatsNew()
                    throw parseError(parentId)
                }

                ID_TRENDING -> {
                    if (path.isEmpty()) return populateTrending()
                    throw parseError(parentId)
                }

                ID_RELATED -> {
                    if (path.isEmpty()) return populateRelatedToRecent()
                    throw parseError(parentId)
                }

                ID_SUBSCRIPTIONS -> {
                    if (path.isEmpty()) return populateSubscriptions()
                    if (path.size == 1) {
                        val subscriptionId = path[0].toLongOrNull()
                        if (subscriptionId != null) {
                            return populateSubscriptionStreams(subscriptionId)
                        }
                    }
                    throw parseError(parentId)
                }

                ID_DISCOVER -> {
                    if (path.isEmpty()) {
                        return Single.just(discoveryCategories.mapIndexed { index, category ->
                            createRootMediaItem(
                                "$ID_DISCOVER/$index",
                                category.name,
                                R.drawable.ic_music_note
                            )
                        })
                    }
                    val index = path.singleOrNull()?.toIntOrNull()
                    if (index != null && index in discoveryCategories.indices) {
                        return searchMusicBySongTitle(discoveryCategories[index].query, discoveryCategories[index].filter)
                            .subscribeOn(Schedulers.io())
                            .map { search ->
                                search.relatedItems.filterIsInstance<StreamInfoItem>()
                                    .take(30)
                                    .mapNotNull(this::createInfoItemMediaItem)
                            }
                    }
                    throw parseError(parentId)
                }

                ID_BOOKMARKS -> {
                    if (path.isEmpty()) {
                        return populateBookmarks()
                    }
                    if (path.size == 2) {
                        val localOrRemote = path[0]
                        val playlistId = path[1].toLong()
                        if (localOrRemote == ID_LOCAL) {
                            return populateLocalPlaylist(playlistId)
                        } else if (localOrRemote == ID_REMOTE) {
                            return populateRemotePlaylist(playlistId)
                        }
                    }
                    Log.w(TAG, "Unknown playlist URI: $parentId")
                    throw parseError(parentId)
                }

                ID_HISTORY -> return populateHistory()

                else -> throw parseError(parentId)
            }
        } catch (e: ContentNotAvailableException) {
            return Single.error(e)
        }
    }

    private fun createRootMediaItem(
        mediaId: String?,
        folderName: String?,
        @DrawableRes iconResId: Int
    ): MediaBrowserCompat.MediaItem {
        val builder = MediaDescriptionCompat.Builder()
        builder.setMediaId(mediaId)
        builder.setTitle(folderName)
        val resources = context.resources
        builder.setIconUri(
            Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(resources.getResourcePackageName(iconResId))
                .appendPath(resources.getResourceTypeName(iconResId))
                .appendPath(resources.getResourceEntryName(iconResId))
                .build()
        )

        val extras = Bundle()
        extras.putString(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE,
            context.getString(R.string.app_name)
        )
        builder.setExtras(extras)
        return MediaBrowserCompat.MediaItem(
            builder.build(),
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )
    }

    private fun createPlaylistMediaItem(playlist: PlaylistLocalItem): MediaBrowserCompat.MediaItem {
        val builder = MediaDescriptionCompat.Builder()
        builder
            .setMediaId(createMediaIdForInfoItem(playlist is PlaylistRemoteEntity, playlist.uid))
            .setTitle(playlist.orderingName)
            .setIconUri(imageUriOrNullIfDisabled(playlist.thumbnailUrl))

        val extras = Bundle()
        extras.putString(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE,
            context.resources.getString(R.string.tab_bookmarks)
        )
        builder.setExtras(extras)
        return MediaBrowserCompat.MediaItem(
            builder.build(),
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )
    }

    private fun createInfoItemMediaItem(item: InfoItem): MediaBrowserCompat.MediaItem? {
        val builder = MediaDescriptionCompat.Builder()
        builder.setMediaId(createMediaIdForInfoItem(item))
            .setTitle(item.name)

        when (item.infoType) {
            InfoType.STREAM -> builder.setSubtitle((item as StreamInfoItem).uploaderName)
            InfoType.PLAYLIST -> builder.setSubtitle((item as PlaylistInfoItem).uploaderName)
            InfoType.CHANNEL -> builder.setSubtitle((item as ChannelInfoItem).description)
            else -> return null
        }

        ImageStrategy.choosePreferredImage(item.thumbnails)?.let {
            builder.setIconUri(imageUriOrNullIfDisabled(it))
        }
        if (item.infoType == InfoType.STREAM && carSaveActionIds.isNotEmpty()) {
            val extras = Bundle()
            extras.putStringArrayList(
                MediaConstants.DESCRIPTION_EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ID_LIST,
                ArrayList(carSaveActionIds)
            )
            builder.setExtras(extras)
        }

        return MediaBrowserCompat.MediaItem(
            builder.build(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
    }

    private fun buildMediaId(): Uri.Builder {
        return Uri.Builder().authority(ID_AUTHORITY)
    }

    private fun buildPlaylistMediaId(playlistType: String?): Uri.Builder {
        return buildMediaId()
            .appendPath(ID_BOOKMARKS)
            .appendPath(playlistType)
    }

    private fun buildLocalPlaylistItemMediaId(isRemote: Boolean, playlistId: Long): Uri.Builder {
        return buildPlaylistMediaId(if (isRemote) ID_REMOTE else ID_LOCAL)
            .appendPath(playlistId.toString())
    }

    private fun buildInfoItemMediaId(item: InfoItem): Uri.Builder {
        return buildMediaId()
            .appendPath(ID_INFO_ITEM)
            .appendPath(infoItemTypeToString(item.infoType))
            .appendPath(item.serviceId.toString())
            .appendQueryParameter(ID_URL, item.url)
    }

    private fun createMediaIdForInfoItem(isRemote: Boolean, playlistId: Long): String {
        return buildLocalPlaylistItemMediaId(isRemote, playlistId)
            .build().toString()
    }

    private fun createLocalPlaylistStreamMediaItem(
        playlistId: Long,
        item: PlaylistStreamEntry,
        index: Int
    ): MediaBrowserCompat.MediaItem {
        val builder = MediaDescriptionCompat.Builder()
        builder.setMediaId(createMediaIdForPlaylistIndex(false, playlistId, index))
            .setTitle(item.streamEntity.title)
            .setSubtitle(item.streamEntity.uploader)
            .setIconUri(imageUriOrNullIfDisabled(item.streamEntity.thumbnailUrl))

        return MediaBrowserCompat.MediaItem(
            builder.build(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
    }

    private fun createRemotePlaylistStreamMediaItem(
        playlistId: Long,
        item: StreamInfoItem,
        index: Int
    ): MediaBrowserCompat.MediaItem {
        val builder = MediaDescriptionCompat.Builder()
        builder.setMediaId(createMediaIdForPlaylistIndex(true, playlistId, index))
            .setTitle(item.name)
            .setSubtitle(item.uploaderName)

        ImageStrategy.choosePreferredImage(item.thumbnails)?.let {
            builder.setIconUri(imageUriOrNullIfDisabled(it))
        }

        return MediaBrowserCompat.MediaItem(
            builder.build(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
    }

    private fun createMediaIdForPlaylistIndex(
        isRemote: Boolean,
        playlistId: Long,
        index: Int
    ): String {
        return buildLocalPlaylistItemMediaId(isRemote, playlistId)
            .appendPath(index.toString())
            .build().toString()
    }

    private fun createMediaIdForInfoItem(item: InfoItem): String {
        return buildInfoItemMediaId(item).build().toString()
    }

    // Same local subscription update feed shown as "What's New" on the phone.
    // This reads cached updates; refresh subscriptions in the phone app to load new items.
    private fun populateWhatsNew(): Single<List<MediaBrowserCompat.MediaItem>> {
        return FeedDatabaseManager(context).getStreams(
            FeedGroupEntity.GROUP_ALL_ID,
            true,
            true,
            true
        ).defaultIfEmpty(emptyList())
            .map { streams ->
                streams.take(50)
                    .map { it.stream.toStreamInfoItem() }
                    .mapNotNull(this::createInfoItemMediaItem)
            }
            .subscribeOn(Schedulers.io())
    }

    // The service's public trending kiosk is distinct from the subscription feed.
    private fun populateTrending(): Single<List<MediaBrowserCompat.MediaItem>> {
        val serviceId = ServiceHelper.getSelectedServiceId(context)
        return Single.fromCallable {
            val kiosks = NewPipe.getService(serviceId).kioskList
            val kioskId = kiosks.defaultKioskId
            kiosks.getListLinkHandlerFactoryByType(kioskId).fromId(kioskId).url
        }.flatMap { kioskUrl ->
            ExtractorHelper.getKioskInfo(serviceId, kioskUrl, false)
        }.subscribeOn(Schedulers.io())
            .map { kiosk ->
                kiosk.relatedItems.take(50).mapNotNull(this::createInfoItemMediaItem)
            }
    }

    // Reads subscriptions from the current Flow Play installation's database.
    private fun populateSubscriptions(): Single<List<MediaBrowserCompat.MediaItem>> {
        return SubscriptionManager(context).subscriptions().firstOrError()
            .map { subscriptions ->
                subscriptions.mapNotNull(this::createSubscriptionMediaItem)
            }
            .subscribeOn(Schedulers.io())
    }

    private fun createSubscriptionMediaItem(
        subscription: SubscriptionEntity
    ): MediaBrowserCompat.MediaItem? {
        if (subscription.url.isNullOrBlank()) return null
        val description = MediaDescriptionCompat.Builder()
            .setMediaId("$ID_SUBSCRIPTIONS/${subscription.uid}")
            .setTitle(subscription.name ?: "Subscribed channel")
            .setIconUri(imageUriOrNullIfDisabled(subscription.avatarUrl))
            .build()
        return MediaBrowserCompat.MediaItem(
            description,
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )
    }

    private fun populateSubscriptionStreams(
        subscriptionId: Long
    ): Single<List<MediaBrowserCompat.MediaItem>> {
        return Single.fromCallable {
            database.subscriptionDAO().getSubscription(subscriptionId)
        }.subscribeOn(Schedulers.io())
            .flatMap { channel ->
                val url = channel.url
                    ?: throw ContentNotAvailableException("Subscription has no URL")
                ExtractorHelper.getChannelInfo(channel.serviceId, url, false)
            }
            .flatMap { channelInfo ->
                val streamsTab = channelInfo.tabs.firstOrNull {
                    ChannelTabHelper.isStreamsTab(it)
                } ?: return@flatMap Single.just(emptyList<MediaBrowserCompat.MediaItem>())
                ExtractorHelper.getChannelTab(channelInfo.serviceId, streamsTab, false)
                    .map { tab ->
                        tab.relatedItems.filterIsInstance<StreamInfoItem>()
                            .take(40)
                            .mapNotNull(this::createInfoItemMediaItem)
                    }
            }
    }

    private fun populateRelatedToRecent(): Single<List<MediaBrowserCompat.MediaItem>> {
        return database.streamHistoryDAO().history.firstOrError()
            .flatMap { history ->
                val recent = history.firstOrNull()
                    ?: return@flatMap Single.just(emptyList<MediaBrowserCompat.MediaItem>())
                ExtractorHelper.getStreamInfo(
                    recent.streamEntity.serviceId,
                    recent.streamEntity.url,
                    false
                ).map { info ->
                    info.relatedItems.filterIsInstance<StreamInfoItem>()
                        .take(30)
                        .mapNotNull(this::createInfoItemMediaItem)
                }
            }.subscribeOn(Schedulers.io())
    }

    private fun populateHistory(): Single<List<MediaBrowserCompat.MediaItem>> {
        val history = database.streamHistoryDAO().history.firstOrError()
        return history.map { items ->
            items.map { this.createHistoryMediaItem(it) }
        }
    }

    private fun createHistoryMediaItem(streamHistoryEntry: StreamHistoryEntry): MediaBrowserCompat.MediaItem {
        val builder = MediaDescriptionCompat.Builder()
        val mediaId = buildMediaId()
            .appendPath(ID_HISTORY)
            .appendPath(streamHistoryEntry.streamId.toString())
            .build().toString()
        builder.setMediaId(mediaId)
            .setTitle(streamHistoryEntry.streamEntity.title)
            .setSubtitle(streamHistoryEntry.streamEntity.uploader)
            .setIconUri(imageUriOrNullIfDisabled(streamHistoryEntry.streamEntity.thumbnailUrl))

        return MediaBrowserCompat.MediaItem(
            builder.build(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
    }

    private fun getMergedPlaylists(): Flowable<MutableList<PlaylistLocalItem>> {
        return MergedPlaylistManager.getMergedOrderedPlaylists(
            LocalPlaylistManager(database),
            RemotePlaylistManager(database)
        )
    }

    private fun populateBookmarks(): Single<List<MediaBrowserCompat.MediaItem>> {
        val playlists = getMergedPlaylists().firstOrError()
        return playlists.map { playlist ->
            playlist.map { this.createPlaylistMediaItem(it) }
        }
    }

    private fun populateLocalPlaylist(playlistId: Long): Single<List<MediaBrowserCompat.MediaItem>> {
        val playlist = LocalPlaylistManager(database).getPlaylistStreams(playlistId).firstOrError()
        return playlist.map { items ->
            items.mapIndexed { index, item ->
                createLocalPlaylistStreamMediaItem(playlistId, item, index)
            }
        }
    }

    private fun populateRemotePlaylist(playlistId: Long): Single<List<MediaBrowserCompat.MediaItem>> {
        return RemotePlaylistManager(database).getPlaylist(playlistId).firstOrError()
            .flatMap { ExtractorHelper.getPlaylistInfo(it.serviceId, it.url, false) }
            .map {
                // ignore it.errors, i.e. ignore errors about specific items, since there would
                // be no way to show the error properly in Android Auto anyway
                it.relatedItems.mapIndexed { index, item ->
                    createRemotePlaylistStreamMediaItem(playlistId, item, index)
                }
            }
    }
    //endregion


    // Android Auto invokes this on a supported browse action, without opening a
    // phone dialog. A default "Car Saves" playlist is created on first use.
    // For hosts allowing more actions, the next shortcuts target existing playlists.
    fun onCustomAction(
        action: String,
        extras: Bundle?,
        result: Result<Bundle>
    ) {
        val mediaId = extras?.getString(
            MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_MEDIA_ITEM_ID
        )
        val uri = mediaId?.toUri()
        val path = uri?.pathSegments.orEmpty()
        val url = uri?.getQueryParameter(ID_URL)
        val serviceId = path.getOrNull(2)?.toIntOrNull()
        if (
            action !in carSaveActionIds ||
            path.size != 3 ||
            path[0] != ID_INFO_ITEM ||
            path[1] != ID_STREAM ||
            serviceId == null ||
            url.isNullOrBlank()
        ) {
            result.sendError(Bundle().apply {
                putString(
                    MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_RESULT_MESSAGE,
                    "Save not available for this item"
                )
            })
            return
        }
        result.detach()
        val playlists = LocalPlaylistManager(database)
        disposables.add(
            ExtractorHelper.getStreamInfo(serviceId, url, false)
                .flatMap { info ->
                    val stream = StreamEntity(info)
                    playlists.getPlaylists().firstOrError().flatMap { saved ->
                        val chosen = if (action == CAR_SAVE_ACTION) {
                            saved.find { it.orderingName == carSavesPlaylistName }
                        } else {
                            saved.find { "$CAR_SAVE_TO_PLAYLIST_PREFIX${it.uid}" == action }
                        }
                        if (chosen != null) {
                            playlists.appendToPlaylist(chosen.uid, listOf(stream)).toSingle()
                                .map { "Saved to ${chosen.orderingName}" }
                        } else if (action == CAR_SAVE_ACTION) {
                            playlists.createPlaylist(carSavesPlaylistName, listOf(stream))
                                .toSingle()
                                .map { "Saved to Car Saves" }
                        } else {
                            Single.error<String>(IllegalArgumentException("Playlist unavailable"))
                        }
                    }
                }
                .subscribeOn(Schedulers.io())
                .subscribe(
                    { message ->
                        notifyChildrenChanged.accept(ID_BOOKMARKS)
                        result.sendResult(Bundle().apply {
                            putString(
                                MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_RESULT_MESSAGE,
                                message
                            )
                        })
                    },
                    { error ->
                        Log.e(TAG, "Could not save item to car playlist", error)
                        result.sendError(Bundle().apply {
                            putString(
                                MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_RESULT_MESSAGE,
                                "Could not save this item"
                            )
                        })
                    }
                )
        )
    }

    // Let compatible car hosts refresh a saved item's browse actions.
    fun onLoadItem(
        mediaId: String,
        result: Result<MediaBrowserCompat.MediaItem>
    ) {
        val uri = mediaId.toUri()
        val path = uri.pathSegments
        val serviceId = path.getOrNull(2)?.toIntOrNull()
        val url = uri.getQueryParameter(ID_URL)
        if (path.size != 3 || path[0] != ID_INFO_ITEM ||
            path[1] != ID_STREAM || serviceId == null || url.isNullOrBlank()
        ) {
            result.sendResult(null)
            return
        }
        result.detach()
        disposables.add(
            ExtractorHelper.getStreamInfo(serviceId, url, false)
                .subscribeOn(Schedulers.io())
                .map<MediaBrowserCompat.MediaItem> { info ->
                    val stream = StreamInfoItem(
                        info.serviceId, info.url, info.name, info.streamType
                    )
                    stream.uploaderName = info.uploaderName
                    createInfoItemMediaItem(stream)
                        ?: throw IllegalStateException("Unable to create refreshed item")
                }
                .subscribe(
                    { result.sendResult(it) },
                    { error ->
                        Log.w(TAG, "Unable to refresh browse item", error)
                        result.sendResult(null)
                    }
                )
        )
    }

    //region Search
    fun onSearch(
        query: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>
    ) {
        if (DEBUG) {
            Log.d(TAG, "onSearch($query)")
        }

        result.detach() // allows sendResult() to happen later
        disposables.add(
            searchForCar(query)
                .subscribeOn(Schedulers.io())
                .subscribe(
                    { result.sendResult(it) },
                    { throwable ->
                        result.sendResult(emptyList())
                        Log.e(TAG, "Search error for query=\"$query\": $throwable")
                    }
                )
        )
    }

    // Prefixes work with Android Auto's own voice / parked search field.
    // Examples: "songs: artist", "playlists: 80s", "channels: jazz".
    private fun searchForCar(query: String): Single<List<MediaBrowserCompat.MediaItem>> {
        val trimmed = query.trim()
        if (trimmed.startsWith("my playlists", ignoreCase = true)) {
            val filter = trimmed.substringAfter("my playlists", "").trim().trimStart(':').trim()
            return LocalPlaylistManager(database).getPlaylists().firstOrError()
                .map { playlists ->
                    playlists.filter { filter.isBlank() ||
                        it.orderingName.orEmpty().contains(filter, ignoreCase = true) }
                        .take(40)
                        .map(this::createPlaylistMediaItem)
                }
        }
        val filterPattern = Regex(
            "^(songs?|tracks?|music|videos?|playlists?|channels?|artists?):\\s*",
            RegexOption.IGNORE_CASE
        )
        val match = filterPattern.find(trimmed)
        val actualQuery = if (match == null) trimmed else trimmed.substring(match.value.length)
        val filter = when (match?.groupValues?.get(1)?.lowercase()) {
            "song", "songs", "track", "tracks", "music" -> "music_songs"
            "video", "videos" -> "videos"
            "playlist", "playlists" -> "playlists"
            "channel", "channels", "artist", "artists" -> "channels"
            else -> null
        }
        return searchMusicBySongTitle(actualQuery, filter).map { info ->
            info.relatedItems.take(50).mapNotNull(this::createInfoItemMediaItem)
        }
    }

    private fun searchMusicBySongTitle(query: String?, typeFilter: String? = null): Single<SearchInfo> {
        val serviceId = ServiceHelper.getSelectedServiceId(context)
        val normalizedQuery = query?.trim().takeUnless { it.isNullOrBlank() } ?: "popular music"
        val available = try {
            NewPipe.getService(serviceId).searchQHFactory.availableContentFilter.toList()
        } catch (e: Exception) {
            emptyList()
        }
        val selected = typeFilter?.takeIf { it in available }
        return ExtractorHelper.searchFor(
            serviceId,
            normalizedQuery,
            if (selected == null) emptyList() else listOf(selected),
            ""
        )
    }
    //endregion

    companion object {
        private val TAG: String = MediaBrowserImpl::class.java.getSimpleName()

        fun imageUriOrNullIfDisabled(url: String?): Uri? {
            return if (ImageStrategy.shouldLoadImages()) {
                url?.toUri()
            } else {
                null
            }
        }
    }
}
