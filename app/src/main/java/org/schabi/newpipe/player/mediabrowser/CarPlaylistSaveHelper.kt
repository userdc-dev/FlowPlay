package org.schabi.newpipe.player.mediabrowser

import android.content.Context
import android.util.Log
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.local.playlist.LocalPlaylistManager

/**
 * Saves the current stream to the same local "Car Saves" playlist
 * used by Android Auto custom browse actions.
 */
object CarPlaylistSaveHelper {
    @JvmStatic
    fun saveToCarSaves(context: Context, stream: StreamInfo) {
        val manager = LocalPlaylistManager(NewPipeDatabase.getInstance(context))
        manager.getPlaylists().firstOrError()
            .flatMap { playlists ->
                val saved = playlists.find { it.orderingName == "Car Saves" }
                val entity = StreamEntity(stream)
                if (saved != null) {
                    manager.appendToPlaylist(saved.uid, listOf(entity)).toSingle()
                } else {
                    manager.createPlaylist("Car Saves", listOf(entity)).toSingle()
                }
            }
            .subscribeOn(Schedulers.io())
            .subscribe(
                { Log.i("FlowPlayCar", "Saved current item to Car Saves") },
                { e -> Log.e("FlowPlayCar", "Unable to save current item", e) }
            )
    }
}
