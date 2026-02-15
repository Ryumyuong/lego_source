package com.main.legos

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.main.legos.databinding.ActivityDataBinding

class DataActivity : AppCompatActivity() {
    lateinit var binding: ActivityDataBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        binding = ActivityDataBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonSelectFile.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        supportActionBar?.hide()
    }
}
