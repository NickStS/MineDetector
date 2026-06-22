package com.minedetector.ui.fragments

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.minedetector.R
import com.minedetector.data.models.MediaItem
import com.minedetector.data.models.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MediaAdapter(
    private val onMediaClick: (MediaItem) -> Unit
) : ListAdapter<MediaItem, MediaAdapter.MediaViewHolder>(MediaItemDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery_photo, parent, false)
        return MediaViewHolder(view, onMediaClick)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: MediaViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelLoad()
    }

    class MediaViewHolder(
        itemView: View,
        private val onMediaClick: (MediaItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val imageView: ImageView = itemView.findViewById(R.id.iv_photo)
        private val loadingIndicator: ProgressBar = itemView.findViewById(R.id.loading_indicator)
        private val videoIcon: ImageView = itemView.findViewById(R.id.iv_video_icon)

        private var loadJob: Job? = null
        private var scopeJob = Job()
        private var scope = CoroutineScope(Dispatchers.Main + scopeJob)
        private var currentPath: String? = null

        fun bind(mediaItem: MediaItem) {
            // Cancel previous load
            cancelLoad()

            // Track current path to prevent stale updates
            currentPath = mediaItem.path

            // Reset views
            imageView.setImageDrawable(null)
            loadingIndicator.visibility = View.GONE
            videoIcon.visibility = View.GONE

            when (mediaItem.type) {
                MediaType.PHOTO -> {
                    loadPhoto(mediaItem.path)
                }
                MediaType.VIDEO -> {
                    loadVideo(mediaItem.path)
                }
            }

            itemView.setOnClickListener { onMediaClick(mediaItem) }
        }

        private fun loadPhoto(path: String) {
            if (path.startsWith("content://")) {
                imageView.load(Uri.parse(path)) {
                    crossfade(true)
                    listener(
                        onError = { _, _ ->
                            if (currentPath == path) {
                                loadingIndicator.visibility = View.GONE
                            }
                        }
                    )
                }
            } else {
                val file = File(path)
                if (file.exists()) {
                    imageView.load(file) {
                        crossfade(true)
                        listener(
                            onError = { _, _ ->
                                if (currentPath == path) {
                                    loadingIndicator.visibility = View.GONE
                                }
                            }
                        )
                    }
                }
            }
        }

        private fun loadVideo(path: String) {
            loadingIndicator.visibility = View.VISIBLE

            loadJob = scope.launch {
                try {
                    val thumbnail = withContext(Dispatchers.IO) {
                        extractVideoThumbnail(path)
                    }

                    // Check if view is still bound to same item
                    if (currentPath == path) {
                        if (thumbnail != null) {
                            imageView.setImageBitmap(thumbnail)
                            videoIcon.visibility = View.VISIBLE
                        } else {
                            imageView.setImageResource(R.drawable.ic_video)
                        }
                        loadingIndicator.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    Log.e("MediaAdapter", "Error loading video thumbnail", e)
                    if (currentPath == path) {
                        imageView.setImageResource(R.drawable.ic_video)
                        loadingIndicator.visibility = View.GONE
                    }
                }
            }
        }

        private fun extractVideoThumbnail(path: String): Bitmap? {
            val retriever = MediaMetadataRetriever()
            return try {
                if (path.startsWith("content://")) {
                    val uri = Uri.parse(path)
                    retriever.setDataSource(itemView.context, uri)
                } else {
                    retriever.setDataSource(path)
                }
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (e: Exception) {
                Log.e("MediaAdapter", "Error extracting thumbnail", e)
                null
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        fun cancelLoad() {
            loadJob?.cancel()
            loadJob = null
            scopeJob.cancel()
            // Recreate scope for future reuse of this ViewHolder
            scopeJob = Job()
            scope = CoroutineScope(Dispatchers.Main + scopeJob)
            currentPath = null
        }
    }

    class MediaItemDiffCallback : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean {
            return oldItem.path == newItem.path
        }

        override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean {
            return oldItem == newItem
        }
    }
}