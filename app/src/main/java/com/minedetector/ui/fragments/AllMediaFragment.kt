package com.minedetector.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.minedetector.R
import com.minedetector.data.models.MediaItem
import com.minedetector.data.models.MediaType
import com.minedetector.ui.MediaViewerActivity
import com.minedetector.ui.TestVideoActivity
import com.minedetector.viewmodels.DetectionViewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@UnstableApi
class AllMediaFragment : Fragment() {

    companion object {
        private const val ARG_IS_DETECT_MODE = "is_detect_mode"

        fun newInstance(isDetectMode: Boolean = false): AllMediaFragment {
            return AllMediaFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_IS_DETECT_MODE, isDetectMode)
                }
            }
        }
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var adapter: MediaAdapter
    private val viewModel: DetectionViewModel by activityViewModels()

    private var isDetectMode = false
    private var hasLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isDetectMode = arguments?.getBoolean(ARG_IS_DETECT_MODE, false) ?: false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_media_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recycler_media)
        emptyState = view.findViewById(R.id.empty_state)
        loadingIndicator = view.findViewById(R.id.loading_indicator)

        recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        adapter = MediaAdapter { mediaItem ->
            if (isDetectMode) {
                // Send to detection - use path to create Uri
                val uri = android.net.Uri.fromFile(File(mediaItem.path))
                viewModel.selectMediaForDetection(uri)
                // Switch to DETECT tab
                (activity as? TestVideoActivity)?.switchToDetectTab()
            } else {
                // Open in viewer
                openMediaViewer(mediaItem)
            }
        }
        recyclerView.adapter = adapter

        loadAllMedia()
    }

    private fun loadAllMedia() {
        if (!hasLoaded) {
            loadingIndicator.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            emptyState.visibility = View.GONE
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
            val mediaList = mutableListOf<MediaItem>()

            // Query images
            try {
                val imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val imageProjection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATA,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_MODIFIED
                )

                val imageCursor = ctx.contentResolver.query(
                    imageUri,
                    imageProjection,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
                )

                imageCursor?.use { cursor ->
                    val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

                    while (cursor.moveToNext()) {
                        val path = cursor.getString(pathColumn)
                        val name = cursor.getString(nameColumn)
                        val size = cursor.getLong(sizeColumn)
                        val date = cursor.getLong(dateColumn)

                        if (File(path).exists()) {
                            mediaList.add(MediaItem(path, MediaType.PHOTO, name, size, date))
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Query videos
            try {
                val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val videoProjection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_MODIFIED
                )

                val videoCursor = ctx.contentResolver.query(
                    videoUri,
                    videoProjection,
                    null,
                    null,
                    "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
                )

                videoCursor?.use { cursor ->
                    val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)

                    while (cursor.moveToNext()) {
                        val path = cursor.getString(pathColumn)
                        val name = cursor.getString(nameColumn)
                        val size = cursor.getLong(sizeColumn)
                        val date = cursor.getLong(dateColumn)

                        if (File(path).exists()) {
                            mediaList.add(MediaItem(path, MediaType.VIDEO, name, size, date))
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Sort by date modified (newest first)
            mediaList.sortByDescending { it.dateModified }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                hasLoaded = true
                loadingIndicator.visibility = View.GONE
                if (mediaList.isEmpty()) {
                    emptyState.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyState.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    adapter.submitList(mediaList)
                }
            }
        }
    }

    private fun openMediaViewer(mediaItem: MediaItem) {
        val intent = Intent(requireContext(), MediaViewerActivity::class.java)
        intent.putExtra("media_item", mediaItem)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        // Reload media when returning to fragment
        loadAllMedia()
    }
}