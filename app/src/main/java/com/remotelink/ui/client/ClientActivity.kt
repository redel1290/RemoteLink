package com.remotelink.ui.client

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClientBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupGestures()
        setupConnection()

        binding.btnConnect.setOnClickListener {
            val code = binding.etCode.text.toString().trim()
            if (code.length != 6) {
                binding.tvCodeError.text = "Введи рівно 6 цифр"
                return@setOnClickListener
            }
            binding.tvCodeError.text = ""
            // Ховаємо клавіатуру
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.etCode.windowToken, 0)
            // Показуємо статус пошуку
            binding.layoutStatus.visibility = View.VISIBLE
            binding.tvStatus.text = "🔍 Пошук хоста з кодом $code…"
            binding.tvStatusDetail.text = "Переконайся що хост активний"
            binding.btnConnect.isEnabled = false

            connection.connectWithCode(code)
        }

        binding.btnDisconnect.setOnClickListener { finish() }

        binding.ivScreen.post {
            viewWidth = binding.ivScreen.width
            viewHeight = binding.ivScreen.height
        }
    }

    private fun setupConnection() {
        connection.onConnected = {
            mainScope.launch {
                binding.layoutCodeInput.visibility = View.GONE
                binding.layoutStatus.visibility = View.GONE
                binding.btnDisconnect.visibility = View.VISIBLE
            }
        }

        connection.onWrongCode = {
            mainScope.launch {
                binding.layoutStatus.visibility = View.GONE
                binding.tvCodeError.text = "❌ Невірний код, спробуй ще раз"
                binding.btnConnect.isEnabled = true
            }
        }

        connection.onDisconnected = {
            mainScope.launch {
                if (binding.layoutCodeInput.visibility == View.GONE) {
                    // Якщо вже були підключені — показуємо повідомлення
                    binding.layoutCodeInput.visibility = View.VISIBLE
                    binding.tvCodeError.text = "❌ З'єднання втрачено"
                    binding.btnConnect.isEnabled = true
                    binding.btnDisconnect.visibility = View.GONE
                } else {
                    binding.layoutStatus.visibility = View.GONE
                    binding.tvCodeError.text = "❌ Хост не знайдено. Перевір код і мережу"
                    binding.btnConnect.isEnabled = true
                }
            }
        }

        connection.onFrame = { jpegBytes ->
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            if (bitmap != null) {
                mainScope.launch {
                    binding.ivScreen.setImageBitmap(bitmap)
                }
            }
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

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
        connection.disconnect()
    }
}
