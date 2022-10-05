package us.shiroyama.wireless

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val buttonWiFi: Button = findViewById(R.id.buttonWiFi)
        buttonWiFi.setOnClickListener {
            val intent = Intent(this, WiFiActivity::class.java)
            startActivity(intent)
            return@setOnClickListener
        }

        val buttonBluetooth: Button = findViewById(R.id.buttonBluetooth)
        buttonBluetooth.setOnClickListener {
            val intent = Intent(this, BluetoothActivity::class.java)
            startActivity(intent)
            return@setOnClickListener
        }

        val buttonBLE: Button = findViewById(R.id.buttonBLE)
        buttonBLE.setOnClickListener {
            val intent = Intent(this, BluetoothActivity::class.java)
            startActivity(intent)
            return@setOnClickListener
        }
    }
}