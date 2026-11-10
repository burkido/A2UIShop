package com.example.a2uishop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.a2uishop.ui.ChatScreen
import com.example.a2uishop.ui.theme.ShopTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShopTheme {
                ChatScreen()
            }
        }
    }
}
