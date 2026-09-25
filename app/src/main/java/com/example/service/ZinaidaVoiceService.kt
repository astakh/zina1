package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.local.model.VoiceCommandLog
import com.example.engine.AudioRecordController
import com.example.engine.OfflineTtsEngine
import com.example.engine.RhasspyNluEngine
import com.example.engine.VoskSpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ZinaidaVoiceService : Service() {

    enum class AssistantState(val label: String) {
        IDLE_LISTENING("Ожидание («Зинаида»)"),
        COMMAND_LISTENING("Слушаю команду…"),
        PROCESSING("Обработка запроса…"),
        SPEAKING("Воспроизведение ответа…")
    }

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_TRIGGER_TEST = "ACTION_TRIGGER_TEST"
        const val EXTRA_TEST_COMMAND = "EXTRA_TEST_COMMAND"

        const val CHANNEL_ID = "zinaida_voice_assistant_channel"
        const val NOTIFICATION_ID = 1001

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private val _state = MutableStateFlow(AssistantState.IDLE_LISTENING)
        val state = _state.asStateFlow()

        private val _audioAmplitude = MutableStateFlow(0f)
        val audioAmplitude = _audioAmplitude.asStateFlow()

        private val _isScreenOff = MutableStateFlow(false)
        val isScreenOff = _isScreenOff.asStateFlow()

        private val _lastCommand = MutableStateFlow<String?>(null)
        val lastCommand = _lastCommand.asStateFlow()

        private val _lastResponse = MutableStateFlow<String?>(null)
        val lastResponse = _lastResponse.asStateFlow()

        private val _events = MutableSharedFlow<String>(extraBufferCapacity = 10)
        val events = _events.asSharedFlow()

        fun startService(context: Context) {
            val intent = Intent(context, ZinaidaVoiceService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ZinaidaVoiceService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun triggerTestCommand(context: Context, command: String = "сколько времени") {
            val intent = Intent(context, ZinaidaVoiceService::class.java).apply {
                action = ACTION_TRIGGER_TEST
                putExtra(EXTRA_TEST_COMMAND, command)
            }
            context.startService(intent)
        }
    }

    private val tag = "ZinaidaVoiceService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var ttsEngine: OfflineTtsEngine
    private lateinit var nluEngine: RhasspyNluEngine
    private lateinit var voskEngine: VoskSpeechEngine
    private lateinit var audioController: AudioRecordController
    private lateinit var database: AppDatabase

    private var commandTimeoutJob: Job? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    _isScreenOff.value = true
                    Log.i(tag, "Screen turned OFF. Continuous background voice listening remains ACTIVE.")
                    updateNotification("Ожидание в фоне (Экран выключен)")
                }
                Intent.ACTION_SCREEN_ON -> {
                    _isScreenOff.value = false
                    Log.i(tag, "Screen turned ON.")
                    updateNotification("Ожидание («Зинаида»)")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "Creating ZinaidaVoiceService")
        database = AppDatabase.getInstance(this)
        ttsEngine = OfflineTtsEngine(this)
        nluEngine = RhasspyNluEngine(this)

        voskEngine = VoskSpeechEngine(
            context = this,
            onWakeWordDetected = { handleWakeWordDetected() },
            onCommandRecognized = { text -> handleCommandRecognized(text) },
            onAmplitudeChanged = { amp -> _audioAmplitude.value = amp }
        )

        audioController = AudioRecordController(this) { buffer, readSize ->
            voskEngine.feedPcmBuffer(buffer, readSize)
        }

        voskEngine.initialize(serviceScope)

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, screenFilter)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TRIGGER_TEST -> {
                val command = intent.getStringExtra(EXTRA_TEST_COMMAND) ?: "сколько времени"
                handleTestCommand(command)
                return START_STICKY
            }
            else -> {
                startForegroundServiceWithNotification()
                startListening()
                _isRunning.value = true
                return START_STICKY
            }
        }
    }

    private fun startForegroundServiceWithNotification() {
        val notification = buildNotification("Слушаю в фоне (скажите «Зинаида»)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startListening() {
        _state.value = AssistantState.IDLE_LISTENING
        voskEngine.startListening(VoskSpeechEngine.ListeningMode.WAKE_WORD)
        audioController.start(serviceScope)
        Log.i(tag, "Zinaida listening started in background")
    }

    private fun handleWakeWordDetected() {
        serviceScope.launch {
            Log.i(tag, "Wake word detected!")
            vibrateFeedback()
            ttsEngine.playWakeTone()
            _state.value = AssistantState.COMMAND_LISTENING
            updateNotification("Слушаю команду…")
            _events.emit("Слово «Зинаида» распознано! Говорите команду…")

            // Timeout after 7 seconds if no command is spoken
            commandTimeoutJob?.cancel()
            commandTimeoutJob = launch {
                kotlinx.coroutines.delay(7000L)
                if (_state.value == AssistantState.COMMAND_LISTENING) {
                    _state.value = AssistantState.IDLE_LISTENING
                    voskEngine.setMode(VoskSpeechEngine.ListeningMode.WAKE_WORD)
                    updateNotification("Ожидание («Зинаида»)")
                }
            }
        }
    }

    private fun handleCommandRecognized(rawCommand: String) {
        commandTimeoutJob?.cancel()
        serviceScope.launch {
            val startTime = System.currentTimeMillis()
            _lastCommand.value = rawCommand
            _state.value = AssistantState.PROCESSING
            updateNotification("Обработка: $rawCommand")
            ttsEngine.playConfirmationTone()

            // Rhasspy NLU offline matching
            val result = nluEngine.processCommand(rawCommand)
            val duration = System.currentTimeMillis() - startTime
            val screenWasOff = _isScreenOff.value

            _lastResponse.value = result.spokenResponse
            _state.value = AssistantState.SPEAKING
            updateNotification("Ответ: ${result.spokenResponse}")

            // Persist to Room DB
            try {
                database.voiceDao().insertLog(
                    VoiceCommandLog(
                        commandText = rawCommand,
                        responseText = result.spokenResponse,
                        intentName = result.intentName,
                        wasScreenOff = screenWasOff,
                        executionTimeMs = duration,
                        success = result.actionSuccess
                    )
                )
            } catch (e: Exception) {
                Log.e(tag, "Error saving command log", e)
            }

            // Speak response via device speakers
            ttsEngine.speak(result.spokenResponse) {
                // Done speaking, return to idle wake-word listening
                serviceScope.launch {
                    _state.value = AssistantState.IDLE_LISTENING
                    voskEngine.setMode(VoskSpeechEngine.ListeningMode.WAKE_WORD)
                    updateNotification(if (_isScreenOff.value) "Ожидание в фоне (Экран выключен)" else "Ожидание («Зинаида»)")
                }
            }
        }
    }

    private fun handleTestCommand(command: String) {
        serviceScope.launch {
            vibrateFeedback()
            ttsEngine.playWakeTone()
            _events.emit("Тестовая команда запущена: $command")
            handleCommandRecognized(command)
        }
    }

    @SuppressLint("MissingPermission")
    private fun vibrateFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(120)
            }
        } catch (e: Exception) {
            Log.w(tag, "Vibration failed", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Фоновый голосовой ассистент Зинаида",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Обеспечивает непрерывное локальное распознавание речи при выключенном экране"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ZinaidaVoiceService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val testIntent = Intent(this, ZinaidaVoiceService::class.java).apply {
            action = ACTION_TRIGGER_TEST
            putExtra(EXTRA_TEST_COMMAND, "сколько времени")
        }
        val pendingTest = PendingIntent.getService(
            this,
            2,
            testIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Ассистент Зинаида")
            .setContentText(statusText)
            .setSubText("100% Offline • Vosk & Rhasspy")
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(0, "Тест команды", pendingTest)
            .addAction(0, "Остановить", pendingStop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(tag, "Destroying ZinaidaVoiceService")
        _isRunning.value = false
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.w(tag, "Error unregistering receiver", e)
        }
        audioController.stop()
        voskEngine.release()
        ttsEngine.release()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
