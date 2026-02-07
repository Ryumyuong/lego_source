package com.main.lego

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.main.lego.databinding.ActivityIntroBinding

class IntroActivity : AppCompatActivity() {
    private val SPLASH_TIMEOUT: Long = 2000
    lateinit var binding: ActivityIntroBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        binding = ActivityIntroBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, DataActivity::class.java))
            finish()
        }, SPLASH_TIMEOUT)

        supportActionBar?.hide()
    }
}
