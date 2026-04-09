package com.tfournet.treadspan.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import java.text.NumberFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchStepScreen()
        }
    }
}

@Composable
fun WatchStepScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("treadspan_watch", Context.MODE_PRIVATE)
    val fmt = NumberFormat.getNumberInstance(Locale.getDefault())

    var todaySteps by remember { mutableIntStateOf(prefs.getInt(KEY_TODAY_STEPS, 0)) }
    var sessionSteps by remember { mutableIntStateOf(prefs.getInt(KEY_SESSION_STEPS, 0)) }
    var speed by remember { mutableStateOf(prefs.getFloat(KEY_SPEED, 0f).toDouble()) }
    var isWalking by remember { mutableStateOf(prefs.getBoolean(KEY_IS_WALKING, false)) }

    // Listen for updates from StepDataListenerService
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                todaySteps = prefs.getInt(KEY_TODAY_STEPS, 0)
                sessionSteps = prefs.getInt(KEY_SESSION_STEPS, 0)
                speed = prefs.getFloat(KEY_SPEED, 0f).toDouble()
                isWalking = prefs.getBoolean(KEY_IS_WALKING, false)
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter("com.tfournet.treadspan.STEPS_UPDATED"),
            Context.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    val teal = Color(0xFF52DBC3)
    val darkBg = Color(0xFF0E1513)

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(darkBg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Status
            if (isWalking) {
                Text(
                    text = "Walking — $speed mph",
                    color = teal,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(4.dp))
            }

            // Hero step count
            Text(
                text = fmt.format(todaySteps),
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "steps today",
                color = Color(0xFF6F7976),
                fontSize = 12.sp,
            )

            if (sessionSteps > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "+${fmt.format(sessionSteps)} this session",
                    color = teal,
                    fontSize = 14.sp,
                )
            }
        }
    }
}
