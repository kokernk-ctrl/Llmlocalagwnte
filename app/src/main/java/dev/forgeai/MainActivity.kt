package dev.forgeai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.forgeai.core.ForgeViewModel
import dev.forgeai.intelligence.ChatViewModel
import dev.forgeai.ui.navigation.ForgeApp
import dev.forgeai.ui.theme.ForgeTheme

class MainActivity : ComponentActivity() {
    private val model: ForgeViewModel by viewModels()
    private val chat: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ForgeTheme { ForgeApp(model, chat) } }
    }
}
