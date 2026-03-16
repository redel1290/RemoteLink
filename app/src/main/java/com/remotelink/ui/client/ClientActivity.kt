package com.remotelink.ui.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import com.remotelink.databinding.ActivityClientBinding
import com.remotelink.service.ClientConnection
import com.remotelink.service.HostServer
import kotlinx.coroutines.*

class ClientActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClientBinding
    private val connection = ClientConnection()
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var gestureDetector: GestureDetectorCompat

    private var viewWidth = 0
    private var viewHeight = 0

    // Опції декодування — обмежуємо розмір bitmap щоб не вийти з пам'яті
    private val bitmapOptions = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565  // вдвічі менше пам'яті ніж ARGB_8888
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClientBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupGestures()
        setupConnection()
        setupButtons()

        binding.ivScreen.post {
            viewWidth = binding.ivScreen.width
            viewHeight = binding.ivScreen.height
        }
    }

    private fun setupButtons() {
        binding.btnConnect.setOnClickListener {
            val code = binding.etCode.text.toString().trim()
            if (code.length != 6) {
                binding.tvCodeError.text = "Введи рівно 6 цифр"
                return@setOnClickListener
            }
            startConnecting(code)
            connection.connectWithCode(code)
        }

        binding.btnConnectIp.setOnClickListener {
            val code = binding.etCode.text.toString().trim()
            val ip = binding.etIp.text.toString().trim()
            if (code.length != 6) {
                binding.tvCodeError.text = "Спочатку введи 6-значний код"
                return@setOnClickListener
            }
            if (ip.isEmpty()) {
                binding.tvCodeError.text = "Введи IP адресу"
                return@setOnClickListener
            }
            startConnecting(code)
            connection.connectWithIp(ip, code)
        }

        binding.btnShowManualIp.setOnClickListener {
            binding.layoutManualIp.visibility = View.VISIBLE
            binding.btnShowManualIp.visibility = View.GONE
        }

        binding.btnDisconnect.setOnClickListener { finish() }
    }

    private fun startConnecting(code: String) {
        binding.tvCodeError.text = ""
        hideKeyboard()
        binding.layoutStatus.visibility = View.VISIBLE
        binding.tvStatus.text = "🔍 Шукаємо хост з кодом $code…"
        binding.tvStatusDetail.text = "Очікуємо до 15 секунд"
        binding.btnConnect.isEnabled = false
        binding.btnConnectIp.isEnabled = false
    }

    private fun setupConnection() {
        connection.onConnected = {
            mainScope.launch {
                binding.layoutCodeInput.visibility = View.GONE
                binding.layoutStatus.visibility = View.GONE
                binding.btnDisconnect.visibility = View.VISIBLE
            }
        }

        connection.onUdpTimeout = {
            mainScope.launch {
                binding.layoutStatus.visibility = View.GONE
                binding.layoutManualIp.visibility = View.VISIBLE
                binding.btnShowManualIp.visibility = View.GONE
                binding.btnConnect.isEnabled = true
                binding.btnConnectIp.isEnabled = true
                binding.tvCodeError.text = "⚠️ Хост не знайдено автоматично.\nВведи IP вручну (видно на екрані хоста)"
            }
        }

        connection.onWrongCode = {
            mainScope.launch {
                binding.layoutStatus.visibility = View.GONE
                binding.tvCodeError.text = "❌ Невірний код, спробуй ще раз"
                binding.btnConnect.isEnabled = true
                binding.btnConnectIp.isEnabled = true
            }
        }

        connection.onDisconnected = {
            mainScope.launch {
                if (binding.layoutCodeInput.visibility == View.GONE) {
                    binding.layoutCodeInput.visibility = View.VISIBLE
                    binding.btnDisconnect.visibility = View.GONE
                    binding.tvCodeError.text = "❌ З'єднання втрачено"
                } else {
                    binding.layoutStatus.visibility = View.GONE
                    binding.tvCodeError.text = "❌ Не вдалось підключитись"
                }
                binding.btnConnect.isEnabled = true
                binding.btnConnectIp.isEnabled = true
            }
        }

        connection.onFrame = { jpegBytes ->
            // Декодуємо в IO потоці
            try {
                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bitmapOptions)
                if (bitmap != null) {
                    mainScope.launch {
                        binding.ivScreen.setImageBitmap(bitmap)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun setupGestures() {
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                sendEvent(HostServer.TouchEvent("tap", e.x, e.y, screenWidth = viewWidth, screenHeight = viewHeight))
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                sendEvent(HostServer.TouchEvent("longpress", e.x, e.y, screenWidth = viewWidth, screenHeight = viewHeight))
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
                val start = e1 ?: return false
                sendEvent(HostServer.TouchEvent("swipe", start.x, start.y, e2.x, e2.y, viewWidth, viewHeight))
                return true
            }
        })

        binding.ivScreen.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (binding.btnDisconnect.visibility == View.VISIBLE) {
            sendEvent(HostServer.TouchEvent(type = "back"))
        } else {
            super.onBackPressed()
        }
    }

    private fun sendEvent(event: HostServer.TouchEvent) {
        mainScope.launch(Dispatchers.IO) {
            connection.sendTouchEvent(event)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etCode.windowToken, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
        connection.disconnect()
    }
}
