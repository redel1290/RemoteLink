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

        // Показуємо код
        binding.tvPairingCode.text = hostServer.pairingCode
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
        binding.cardAccessibility.visibility =
            if (AccessibilityControlService.isEnabled()) View.GONE else View.VISIBLE
    }

    private fun showIpAddress() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wifiManager.connectionInfo.ipAddress
        val ipStr = String.format(
            "%d.%d.%d.%d",
            ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff
        )
        binding.tvIpAddress.text = "IP: $ipStr"
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
                val scaleX = if (event.screenWidth > 0)
                    resources.displayMetrics.widthPixels.toFloat() / event.screenWidth else 1f
                val scaleY = if (event.screenHeight > 0)
                    resources.displayMetrics.heightPixels.toFloat() / event.screenHeight else 1f

                when (event.type) {
                    "tap"       -> accessibility.performTap(event.x * scaleX, event.y * scaleY)
                    "swipe"     -> accessibility.performSwipe(event.x * scaleX, event.y * scaleY, event.x2 * scaleX, event.y2 * scaleY)
                    "longpress" -> accessibility.performLongPress(event.x * scaleX, event.y * scaleY)
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

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(serviceIntent)

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
        stopService(Intent(this, ScreenCaptureService::class.java))
    }
}
