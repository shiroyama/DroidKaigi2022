package us.shiroyama.wireless.network

import android.os.Bundle

interface Lifecycle {
    fun onCreate(savedInstanceState: Bundle?)
    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()
    fun onDestroy()
}