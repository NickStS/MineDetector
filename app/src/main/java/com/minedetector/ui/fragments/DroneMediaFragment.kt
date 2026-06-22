package com.minedetector.ui.fragments

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.minedetector.MineDetectorApplication
import com.minedetector.R
import com.minedetector.dji.DJIMediaManager
import dji.sdk.media.MediaFile
import java.io.File
import java.util.Locale

@UnstableApi
class DroneMediaFragment : Fragment() {

    companion object {
        private const val TAG = "DroneMediaFragment"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var adapter: DroneMediaAdapter

    // XML не содержит loading_progress -> делаем его программно
    private var globalLoading: ProgressBar? = null

    // XML не содержит tv_empty_text -> берём TextView внутри empty_state без id
    private var emptyTextView: TextView? = null

    private var djiMediaManager: DJIMediaManager? = null
    private var isLoading = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_media_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recycler_media)
        emptyState = view.findViewById(R.id.empty_state)

        // найдём TextView внутри empty_state (он без id)
        emptyTextView = findFirstTextView(emptyState)

        // сделаем глобальный ProgressBar поверх экрана (XML не меняем)
        setupGlobalLoading(view)

        recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        adapter = DroneMediaAdapter(
            onItemClick = { droneFile -> downloadAndOpen(droneFile) },
            onThumbnailRequest = { droneFile, callback -> fetchThumbnail(droneFile, callback) }
        )
        recyclerView.adapter = adapter

        initMediaManager()
    }

    private fun setupGlobalLoading(root: View) {
        val parent = root as? ConstraintLayout ?: return

        val pb = ProgressBar(requireContext())
        pb.isIndeterminate = true
        pb.visibility = View.GONE
        pb.id = View.generateViewId()

        parent.addView(pb)

        val set = ConstraintSet()
        set.clone(parent)
        set.connect(pb.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        set.connect(pb.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        set.connect(pb.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        set.connect(pb.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        set.applyTo(parent)

        globalLoading = pb
    }

    private fun findFirstTextView(group: ViewGroup): TextView? {
        for (i in 0 until group.childCount) {
            val v = group.getChildAt(i)
            when (v) {
                is TextView -> return v
                is ViewGroup -> findFirstTextView(v)?.let { return it }
            }
        }
        return null
    }

    private fun initMediaManager() {
        if (MineDetectorApplication.TEST_MODE) {
            showEmptyState("Test mode - drone not connected")
            return
        }

        djiMediaManager = DJIMediaManager()

        if (djiMediaManager?.isAvailable() != true) {
            showEmptyState("Drone not connected or media manager unavailable")
            return
        }

        if (djiMediaManager?.initialize() != true) {
            showEmptyState("Failed to initialize media manager")
            return
        }

        loadDroneMedia()
    }

    private fun loadDroneMedia() {
        if (isLoading) return
        isLoading = true

        showLoading(true)

        djiMediaManager?.enterMediaMode { success, error ->
            if (!success) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    showLoading(false)
                    showEmptyState("Failed to enter media mode: $error")
                    isLoading = false
                }
                return@enterMediaMode
            }

            djiMediaManager?.startScheduler()

            djiMediaManager?.refreshMediaList(object : DJIMediaManager.MediaListCallback {
                override fun onSuccess(mediaFiles: List<DJIMediaManager.DroneMediaFile>) {
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        showLoading(false)
                        isLoading = false

                        if (mediaFiles.isEmpty()) {
                            showEmptyState("No media found on drone SD card")
                        } else {
                            Log.d(TAG, "Loaded ${mediaFiles.size} files from drone")
                            emptyState.visibility = View.GONE
                            recyclerView.visibility = View.VISIBLE
                            adapter.submitList(mediaFiles)
                        }
                    }
                }

                override fun onError(error: String) {
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        showLoading(false)
                        showEmptyState("Error loading media: $error")
                        isLoading = false
                    }
                }
            })
        }
    }

    private fun fetchThumbnail(
        droneFile: DJIMediaManager.DroneMediaFile,
        callback: (Bitmap?) -> Unit
    ) {
        djiMediaManager?.fetchThumbnail(droneFile, object : DJIMediaManager.BitmapCallback {
            override fun onSuccess(bitmap: Bitmap) {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    callback(bitmap)
                }
            }

            override fun onError(error: String) {
                Log.e(TAG, "Thumbnail error: $error")
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    callback(null)
                }
            }
        })
    }

    private fun downloadAndOpen(droneFile: DJIMediaManager.DroneMediaFile) {
        val downloadDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "MineDetector"
        )
        if (!downloadDir.exists()) downloadDir.mkdirs()

        val destFile = File(downloadDir, droneFile.fileName)

        Toast.makeText(requireContext(), "Downloading ${droneFile.fileName}...", Toast.LENGTH_SHORT).show()

        djiMediaManager?.downloadFile(
            droneFile,
            destFile.absolutePath,
            progressCallback = { progress ->
                Log.d(TAG, "Download progress: $progress%")
            },
            completionCallback = { success, error ->
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (success) {
                        Toast.makeText(requireContext(), "Downloaded: ${destFile.name}", Toast.LENGTH_SHORT).show()
                        openDownloadedFile(destFile, droneFile.mediaType)
                    } else {
                        Toast.makeText(requireContext(), "Download failed: $error", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    private fun openDownloadedFile(file: File, mediaType: MediaFile.MediaType) {
        val intent = Intent(requireContext(), com.minedetector.ui.MediaViewerActivity::class.java)

        val type = when (mediaType) {
            MediaFile.MediaType.JPEG, MediaFile.MediaType.RAW_DNG, MediaFile.MediaType.TIFF ->
                com.minedetector.data.models.MediaType.PHOTO
            else -> com.minedetector.data.models.MediaType.VIDEO
        }

        val mediaItem = com.minedetector.data.models.MediaItem(
            path = file.absolutePath,
            type = type,
            name = file.name,
            size = file.length(),
            dateModified = file.lastModified()
        )

        intent.putExtra("media_item", mediaItem)
        startActivity(intent)
    }

    private fun showLoading(show: Boolean) {
        globalLoading?.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            emptyState.visibility = View.GONE
            recyclerView.visibility = View.GONE
        }
    }

    private fun showEmptyState(message: String) {
        emptyState.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyTextView?.text = message
    }

    override fun onResume() {
        super.onResume()
        if (djiMediaManager?.isAvailable() == true) loadDroneMedia()
    }

    override fun onPause() {
        super.onPause()
        djiMediaManager?.stopScheduler()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        djiMediaManager?.cleanup()
        djiMediaManager = null
    }

    // ==================== Adapter ====================

    class DroneMediaAdapter(
        private val onItemClick: (DJIMediaManager.DroneMediaFile) -> Unit,
        private val onThumbnailRequest: (DJIMediaManager.DroneMediaFile, (Bitmap?) -> Unit) -> Unit
    ) : ListAdapter<DJIMediaManager.DroneMediaFile, DroneMediaAdapter.ViewHolder>(DiffCallback()) {

        private val thumbnailCache = mutableMapOf<String, Bitmap>()

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val ivPhoto: ImageView = itemView.findViewById(R.id.iv_photo)
            val loading: ProgressBar = itemView.findViewById(R.id.loading_indicator)
            val ivVideoIcon: ImageView = itemView.findViewById(R.id.iv_video_icon)
            val tvLabel: TextView = itemView.findViewById(R.id.tv_detection_count) // используем как duration
        }

        class DiffCallback : DiffUtil.ItemCallback<DJIMediaManager.DroneMediaFile>() {
            override fun areItemsTheSame(oldItem: DJIMediaManager.DroneMediaFile, newItem: DJIMediaManager.DroneMediaFile): Boolean =
                oldItem.fileName == newItem.fileName

            override fun areContentsTheSame(oldItem: DJIMediaManager.DroneMediaFile, newItem: DJIMediaManager.DroneMediaFile): Boolean =
                oldItem == newItem
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_gallery_photo, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = getItem(position)

            holder.ivPhoto.setImageResource(R.drawable.ic_gallery)

            val isVideo = item.mediaType == MediaFile.MediaType.MOV || item.mediaType == MediaFile.MediaType.MP4
            holder.ivVideoIcon.visibility = if (isVideo) View.VISIBLE else View.GONE

            // duration в tv_detection_count
            if (isVideo && item.durationMs > 0) {
                val seconds = (item.durationMs / 1000).toInt()
                val minutes = seconds / 60
                val secs = seconds % 60
                holder.tvLabel.text = String.format(Locale.US, "%d:%02d", minutes, secs)
                holder.tvLabel.visibility = View.VISIBLE
            } else {
                holder.tvLabel.visibility = View.GONE
            }

            // Thumbnails
            val cached = thumbnailCache[item.fileName]
            if (cached != null) {
                holder.loading.visibility = View.GONE
                holder.ivPhoto.setImageBitmap(cached)
            } else {
                holder.loading.visibility = View.VISIBLE
                onThumbnailRequest(item) { bmp ->
                    holder.loading.visibility = View.GONE
                    if (bmp != null) {
                        thumbnailCache[item.fileName] = bmp
                        holder.ivPhoto.setImageBitmap(bmp)
                    } else {
                        holder.ivPhoto.setImageResource(R.drawable.ic_gallery)
                    }
                }
            }

            holder.itemView.setOnClickListener { onItemClick(item) }
        }
    }
}
