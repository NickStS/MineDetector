package com.minedetector.ui.fragments

import android.content.Intent
import android.os.Bundle
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
import com.minedetector.data.local.AppDatabase
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
class AppMediaFragment : Fragment() {

    companion object {
        private const val ARG_IS_DETECT_MODE = "is_detect_mode"

        fun newInstance(isDetectMode: Boolean = false): AppMediaFragment {
            return AppMediaFragment().apply {
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
    private lateinit var database: AppDatabase
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

        database = AppDatabase.getDatabase(requireContext())

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

        loadAppMedia()
    }

    private fun loadAppMedia() {
        if (!hasLoaded) {
            loadingIndicator.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            emptyState.visibility = View.GONE
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val mediaList = mutableListOf<MediaItem>()

            try {
                // Get all detections from database
                val detections = database.detectionDao().getAllDetections()

                detections.forEach { detection ->
                    val file = File(detection.photoPath)
                    if (file.exists()) {
                        // Determine if it's a photo or video based on extension
                        val mediaType = when (file.extension.lowercase()) {
                            "jpg", "jpeg", "png", "webp", "bmp" -> MediaType.PHOTO
                            "mp4", "avi", "mkv", "mov", "3gp" -> MediaType.VIDEO
                            else -> MediaType.PHOTO
                        }

                        mediaList.add(
                            MediaItem(
                                path = detection.photoPath,
                                type = mediaType,
                                name = file.name,
                                size = file.length(),
                                dateModified = file.lastModified()
                            )
                        )
                    }
                }

                // Sort by date modified (newest first)
                mediaList.sortByDescending { it.dateModified }

            } catch (e: Exception) {
                e.printStackTrace()
            }

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
        loadAppMedia()
    }
}