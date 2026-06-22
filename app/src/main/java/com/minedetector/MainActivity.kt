package com.minedetector

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.minedetector.ui.MainMenuActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_main)

        if (MineDetectorApplication.TEST_MODE) {
            Log.d(TAG, "Starting in TEST MODE (DJI SDK disabled)")
        }

        // Lifecycle-safe delayed navigation (no Handler leak)
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) {
                startActivity(Intent(this, MainMenuActivity::class.java))
                finish()
            }
        }, 1500)
    }
}
