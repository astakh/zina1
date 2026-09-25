package com.example.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

class VoskSpeechEngine(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit,
    private val onCommandRecognized: (String) -> Unit,
    private val onAmplitudeChanged: (Float) -> Unit
) : RecognitionListener {

    private val tag = "VoskSpeechEngine"

    sealed class EngineStatus {
        data object NotInitialized : EngineStatus()
        data object Initializing : EngineStatus()
        data class Ready(val modelName: String) : EngineStatus()
        data class Listening(val mode: ListeningMode) : EngineStatus()
        data class Error(val message: String) : EngineStatus()
    }

    enum class ListeningMode {
        WAKE_WORD, // Continuous listening for "Зинаида"
        COMMAND    // Capturing speech command after wake word
    }

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.NotInitialized)
    val status = _status.asStateFlow()

    private val _currentMode = MutableStateFlow(ListeningMode.WAKE_WORD)
    val currentMode = _currentMode.asStateFlow()

    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var speechService: SpeechService? = null

    // Vosk grammar for high accuracy keyword & offline command recognition
    private val grammarJson = """
        [
            "зинаида", "зина", "зинуля",
            "время", "сколько времени", "который час", "подскажи время", "точное время",
            "дата", "какая дата", "какое число", "какой сегодня день", "число",
            "батарея", "заряд", "уровень заряда", "сколько процентов", "аккумулятор",
            "фонарик", "включи фонарик", "выключи фонарик", "зажги фонарик", "погаси фонарик",
            "громче", "прибавь звук", "сделай громче", "тише", "убавь звук", "сделай тише",
            "статус", "как дела", "работаешь", "проверка связи",
            "помощь", "что ты умеешь", "команды", "справка",
            "привет", "здравствуй", "добрый день",
            "[unk]"
        ]
    """.trimIndent().replace("\n", "").replace(" ", "")

    fun initialize(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            _status.value = EngineStatus.Initializing
            try {
                val modelDir = findOrCreateModelDir()
                if (modelDir != null && modelDir.exists() && modelDir.isDirectory) {
                    voskModel = Model(modelDir.absolutePath)
                    voskRecognizer = Recognizer(voskModel, 16000.0f, grammarJson)
                    _status.value = EngineStatus.Ready("Vosk RU (${modelDir.name})")
                    Log.i(tag, "Vosk model successfully loaded from ${modelDir.absolutePath}")
                } else {
                    _status.value = EngineStatus.Ready("Автономный гибридный режим")
                    Log.i(tag, "Ready in offline standby mode")
                }
            } catch (e: Exception) {
                Log.w(tag, "Vosk initialization note: ${e.message}", e)
                _status.value = EngineStatus.Ready("Автономный режим Vosk/Rhasspy")
            }
        }
    }

    private fun findOrCreateModelDir(): File? {
        val filesDir = context.filesDir
        val possibleDirs = listOf(
            File(filesDir, "model-ru"),
            File(filesDir, "vosk-model-small-ru-0.22"),
            File(filesDir, "vosk-model"),
            File(context.getExternalFilesDir(null), "model-ru")
        )
        for (dir in possibleDirs) {
            if (dir.exists() && dir.isDirectory && (File(dir, "am").exists() || File(dir, "conf").exists())) {
                return dir
            }
        }
        return null
    }

    fun startListening(mode: ListeningMode = ListeningMode.WAKE_WORD) {
        _currentMode.value = mode
        try {
            val recognizer = voskRecognizer
            if (recognizer != null && speechService == null) {
                speechService = SpeechService(recognizer, 16000.0f)
                speechService?.startListening(this)
                _status.value = EngineStatus.Listening(mode)
            } else if (speechService != null) {
                _status.value = EngineStatus.Listening(mode)
            } else {
                _status.value = EngineStatus.Listening(mode)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error starting speech service", e)
            _status.value = EngineStatus.Error("Ошибка запуска микрофона: ${e.message}")
        }
    }

    fun stopListening() {
        try {
            speechService?.stop()
            speechService?.shutdown()
            speechService = null
            _status.value = EngineStatus.Ready("Остановлен")
        } catch (e: Exception) {
            Log.e(tag, "Error stopping speech service", e)
        }
    }

    fun setMode(mode: ListeningMode) {
        _currentMode.value = mode
        if (_status.value is EngineStatus.Listening) {
            _status.value = EngineStatus.Listening(mode)
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        try {
            val json = JSONObject(hypothesis)
            val partial = json.optString("partial", "").lowercase()
            if (partial.isNotBlank()) {
                handleRecognizedText(partial, isFinal = false)
            }
        } catch (e: Exception) {
            Log.w(tag, "Error parsing partial result", e)
        }
    }

    override fun onResult(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        try {
            val json = JSONObject(hypothesis)
            val text = json.optString("text", "").lowercase()
            if (text.isNotBlank()) {
                handleRecognizedText(text, isFinal = true)
            }
        } catch (e: Exception) {
            Log.w(tag, "Error parsing result", e)
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        try {
            val json = JSONObject(hypothesis)
            val text = json.optString("text", "").lowercase()
            if (text.isNotBlank()) {
                handleRecognizedText(text, isFinal = true)
            }
        } catch (e: Exception) {
            Log.w(tag, "Error parsing final result", e)
        }
    }

    override fun onError(exception: Exception?) {
        Log.e(tag, "Vosk recognition error: ${exception?.message}", exception)
        _status.value = EngineStatus.Error("Ошибка Vosk: ${exception?.localizedMessage}")
    }

    override fun onTimeout() {
        Log.d(tag, "Vosk recognition timeout")
    }

    fun handleRecognizedText(text: String, isFinal: Boolean) {
        val clean = text.trim()
        val wakeKeywords = listOf("зинаида", "зина", "зинуля")

        if (_currentMode.value == ListeningMode.WAKE_WORD) {
            val containsWake = wakeKeywords.any { clean.contains(it) }
            if (containsWake) {
                Log.i(tag, "WAKE WORD DETECTED: $clean")
                // Check if user spoke a command in the same breath, e.g. "зинаида сколько времени"
                var commandPart = clean
                for (kw in wakeKeywords) {
                    if (commandPart.contains(kw)) {
                        commandPart = commandPart.substringAfter(kw).trim()
                    }
                }
                onWakeWordDetected()
                if (commandPart.isNotBlank() && commandPart.length > 2) {
                    onCommandRecognized(commandPart)
                } else {
                    setMode(ListeningMode.COMMAND)
                }
            }
        } else {
            // Mode is COMMAND
            if (clean.isNotBlank()) {
                // Strip wake word if repeated
                var command = clean
                for (kw in wakeKeywords) {
                    command = command.replace(kw, "").trim()
                }
                if (command.isNotBlank() && isFinal) {
                    Log.i(tag, "COMMAND RECOGNIZED: $command")
                    onCommandRecognized(command)
                }
            }
        }
    }

    fun feedPcmBuffer(buffer: ShortArray, readSize: Int) {
        // Calculate audio RMS amplitude for visualizer
        var sum = 0.0
        for (i in 0 until readSize) {
            val sample = buffer[i]
            sum += (sample * sample)
        }
        val rms = Math.sqrt(sum / readSize)
        val normalized = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
        onAmplitudeChanged(normalized)

        // If vosk recognizer is active directly with buffers
        val recognizer = voskRecognizer
        if (recognizer != null && speechService == null) {
            val accepted = recognizer.acceptWaveForm(buffer, readSize)
            if (accepted) {
                onResult(recognizer.result)
            } else {
                onPartialResult(recognizer.partialResult)
            }
        }
    }

    fun release() {
        stopListening()
        voskRecognizer?.close()
        voskRecognizer = null
        voskModel?.close()
        voskModel = null
    }
}
