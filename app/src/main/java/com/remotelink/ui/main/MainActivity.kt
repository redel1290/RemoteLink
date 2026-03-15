package com.remotelink.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.remotelink.databinding.ActivityMainBinding
import com.remotelink.ui.client.ClientActivity
import com.remotelink.ui.host.HostActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnHost.setOnClickListener {
            startActivity(Intent(this, HostActivity::class.java))
        }

        binding.btnClient.setOnClickListener {
            startActivity(Intent(this, ClientActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            // TODO: SettingsActivity
        }
    }
}
