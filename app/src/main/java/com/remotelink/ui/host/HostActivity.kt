package com.remotelink.ui.host

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.remotelink.NetworkConfig
import com.remotelink.databinding.ActivityHostBinding
import com.remotelink.service.AccessibilityControlService
import com.remotelink.service.HostServer
import com.remotelink.service.ScreenCaptureService
import kotlinx.coroutines.*

class HostActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHostBinding
    private val hostServer = HostServer()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHostBinding.inflate(layoutInflater)
        setContentView(binding.root)

        showIpAddress()
        checkAccessibilityService()
        startHostServer()
        requestScreenCapture()

        binding.btnStop.setOnClickListener { finish() }

        binding.btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        checkAccessibilityService()
    }

    private fun checkAccessibilityService() {
        if (AccessibilityControlService.isEnabled()) {
            binding.cardAccessibility.visibility = View.GONE
        } else {
            binding.cardAccessibility.visibility = View.VISIBLE
        }
    }

    private fun showIpAddress() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wifiManager.connectionInfo.ipAddress
        val ipStr = String.format(
            "%d.%d.%d.%d",
            ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff
        )
        binding.tvIpAddress.text = "IP: $ipStr"
        binding.tvPort.text = "Порт: ${NetworkConfig.WS_PORT}"
    }

    private fun startHostServer() {
        hostServer.onClientConnected = { ip ->
            scope.launch {
                binding.tvStatus.text = "✅ Підключено: $ip"
            }
        }
        hostServer.onClientDisconnected = {
            scope.launch {
                binding.tvStatus.text = "⏳ Очікування підключення…"
            }
        }
        hostServer.onTouchEvent = { event ->
            val accessibility = AccessibilityControlService.instance
            if (accessibility != null) {
                val scaleX = resources.displayMetrics.widthPixels.toFloat() / event.screenWidth
                val scaleY = resources.displayMetrics.heightPixels.toFloat() / event.screenHeight
                val rx = event.x * scaleX
                val ry = event.y * scaleY
                val rx2 = event.x2 * scaleX
                val ry2 = event.y2 * scaleY

                when (event.type) {
                    "tap"       -> accessibility.performTap(rx, ry)
                    "swipe"     -> accessibility.performSwipe(rx, ry, rx2, ry2)
                    "longpress" -> accessibility.performLongPress(rx, ry)
                    "back"      -> accessibility.performBack()
                    "home"      -> accessibility.performHome()
                    "recents"   -> accessibility.performRecents()
                }
            }
        }
        hostServer.start()
    }

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(serviceIntent)

            // Як тільки сервіс запуститься — підключаємо колбек
            scope.launch {
                delay(500)
                ScreenCaptureService.instance?.onFrameReady = { frame ->
                    hostServer.sendFrame(frame)
                }
            }
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        hostServer.stop()
        ScreenCaptureService.instance?.let {
            stopService(Intent(this, ScreenCaptureService::class.java))
        }
    }
}
