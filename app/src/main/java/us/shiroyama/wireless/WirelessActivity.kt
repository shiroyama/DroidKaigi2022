package us.shiroyama.wireless

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import us.shiroyama.wireless.network.Lifecycle

abstract class WirelessActivity : AppCompatActivity() {
    abstract val contentView: Int
    abstract val lifecycleObject: Lifecycle
    abstract val name: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = name
        setContentView(contentView)
        lifecycleObject.onCreate(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        lifecycleObject.onStart()
    }

    override fun onResume() {
        super.onResume()
        lifecycleObject.onResume()
    }

    override fun onPause() {
        lifecycleObject.onPause()
        super.onPause()
    }

    override fun onStop() {
        lifecycleObject.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        lifecycleObject.onDestroy()
        super.onDestroy()
    }
}