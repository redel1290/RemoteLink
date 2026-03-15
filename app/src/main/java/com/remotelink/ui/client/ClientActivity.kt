package com.remotelink.ui.client

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
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

    // Розміри екрану клієнта — для масштабування координат
    private var viewWidth = 0
    private var viewHeight = 0

    // Координати початку свайпу
    private var swipeStartX = 0f
    private var swipeStartY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClientBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupGestures()
        setupConnection()

        binding.btnDisconnect.setOnClickListener {
            finish()
        }

        // Отримуємо розміри ImageView після рендеру
        binding.ivScreen.post {
            viewWidth = binding.ivScreen.width
            viewHeight = binding.ivScreen.height
        }
    }

    private fun setupConnection() {
        connection.onSearching = {
            mainScope.launch {
                binding.layoutStatus.visibility = View.VISIBLE
                binding.tvStatus.text = "🔍 Пошук хоста в мережі…"
                binding.tvStatusDetail.text = "Переконайся що хост активний"
                binding.btnDisconnect.visibility = View.GONE
            }
        }

        connection.onConnected = {
            mainScope.launch {
                binding.layoutStatus.visibility = View.GONE
                binding.btnDisconnect.visibility = View.VISIBLE
            }
        }

        connection.onDisconnected = {
            mainScope.launch {
                binding.layoutStatus.visibility = View.VISIBLE
                binding.tvStatus.text = "❌ З'єднання втрачено"
                binding.tvStatusDetail.text = "Спробуй знову"
                binding.btnDisconnect.visibility = View.GONE
            }
        }

        connection.onFrame = { jpegBytes ->
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return@onFrame
            mainScope.launch {
                binding.ivScreen.setImageBitmap(bitmap)
            }
        }

        connection.startDiscovery()
    }

    private fun setupGestures() {
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                sendEvent(HostServer.TouchEvent(
                    type = "tap",
                    x = e.x, y = e.y,
                    screenWidth = viewWidth, screenHeight = viewHeight
                ))
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                sendEvent(HostServer.TouchEvent(
                    type = "longpress",
                    x = e.x, y = e.y,
                    screenWidth = viewWidth, screenHeight = viewHeight
                ))
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                velocityX: Float, velocityY: Float
            ): Boolean {
                val start = e1 ?: return false
                sendEvent(HostServer.TouchEvent(
                    type = "swipe",
                    x = start.x, y = start.y,
                    x2 = e2.x, y2 = e2.y,
                    screenWidth = viewWidth, screenHeight = viewHeight
                ))
                return true
            }
        })

        binding.ivScreen.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    // Системні кнопки (Back / Home / Recents) через volume або окремі UI кнопки
    // Поки обробляємо Back як кнопку назад на хості
    override fun onBackPressed() {
        sendEvent(HostServer.TouchEvent(type = "back"))
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
