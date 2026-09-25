package com.example.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import com.example.data.local.AppDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rhasspy-style offline Natural Language Understanding (NLU) and Intent Execution engine.
 * Completely local, zero internet requests.
 */
class RhasspyNluEngine(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val database = AppDatabase.getInstance(context)

    data class IntentResult(
        val intentName: String,
        val spokenResponse: String,
        val actionSuccess: Boolean = true,
        val details: String = ""
    )

    suspend fun processCommand(rawText: String): IntentResult {
        val normalized = rawText.lowercase(Locale.getDefault())
            .replace("ё", "е")
            .replace(Regex("[^a-zа-я0-9\\s]"), " ")
            .trim()

        // 1. Check custom user rules from Room DB first
        val customRules = database.voiceDao().getEnabledRules()
        for (rule in customRules) {
            val ruleTrigger = rule.triggerPhrase.lowercase(Locale.getDefault()).replace("ё", "е").trim()
            if (ruleTrigger.isNotEmpty() && (normalized.contains(ruleTrigger) || ruleTrigger.contains(normalized))) {
                return IntentResult(
                    intentName = "CustomRule",
                    spokenResponse = rule.spokenResponse,
                    actionSuccess = true,
                    details = "Пользовательское правило: ${rule.triggerPhrase}"
                )
            }
        }

        // 2. Built-in Rhasspy Intent Slot Matching
        return when {
            // Time Intent
            matchesAny(normalized, "время", "сколько времени", "который час", "подскажи время", "точное время") -> {
                handleTimeIntent()
            }

            // Date Intent
            matchesAny(normalized, "дата", "какая дата", "какое число", "какой день", "какой сегодня день", "число") -> {
                handleDateIntent()
            }

            // Battery Intent
            matchesAny(normalized, "батарея", "заряд", "уровень заряда", "сколько процентов", "аккумулятор") -> {
                handleBatteryIntent()
            }

            // Flashlight ON
            matchesAny(normalized, "включи фонарик", "зажги фонарик", "включить фонарик", "зажечь фонарик", "свет включи") -> {
                handleFlashlightIntent(turnOn = true)
            }

            // Flashlight OFF
            matchesAny(normalized, "выключи фонарик", "погаси фонарик", "выключить фонарик", "погасить фонарик", "свет выключи") -> {
                handleFlashlightIntent(turnOn = false)
            }

            // Volume Up
            matchesAny(normalized, "громче", "прибавь звук", "прибавь громкость", "сделай громче", "увеличь громкость") -> {
                handleVolumeIntent(increase = true)
            }

            // Volume Down
            matchesAny(normalized, "тише", "убавь звук", "убавь громкость", "сделай тише", "уменьши громкость") -> {
                handleVolumeIntent(increase = false)
            }

            // Status / Privacy
            matchesAny(normalized, "статус", "как дела", "работаешь", "проверка связи", "состояние", "ты тут") -> {
                IntentResult(
                    intentName = "Status",
                    spokenResponse = "Ассистент Зинаида на связи! Все вычисления происходят локально на вашем устройстве, приватность гарантирована.",
                    actionSuccess = true
                )
            }

            // Help
            matchesAny(normalized, "помощь", "что ты умеешь", "команды", "справка", "список команд") -> {
                IntentResult(
                    intentName = "Help",
                    spokenResponse = "Я умею сообщать время и дату, проверять заряд батареи, управлять громкостью и фонариком, а также выполнять ваши собственные команды.",
                    actionSuccess = true
                )
            }

            // Greeting
            matchesAny(normalized, "привет", "здравствуй", "добрый день", "доброе утро", "добрый вечер") -> {
                IntentResult(
                    intentName = "Greeting",
                    spokenResponse = "Здравствуйте! Чем могу помочь?",
                    actionSuccess = true
                )
            }

            // Fallback
            else -> {
                val cleanPreview = if (normalized.length > 25) normalized.take(25) + "…" else normalized
                IntentResult(
                    intentName = "Unknown",
                    spokenResponse = if (normalized.isBlank()) {
                        "Я вас не услышала. Скажите команду после слова Зинаида."
                    } else {
                        "Команда «$cleanPreview» не распознана. Скажите «помощь» для списка команд."
                    },
                    actionSuccess = false,
                    details = "Не найдено соответствие интенту"
                )
            }
        }
    }

    private fun matchesAny(text: String, vararg keywords: String): Boolean {
        for (kw in keywords) {
            if (text.contains(kw)) return true
        }
        return false
    }

    private fun handleTimeIntent(): IntentResult {
        val now = Date()
        val hours = SimpleDateFormat("HH", Locale("ru")).format(now).toIntOrNull() ?: 0
        val minutes = SimpleDateFormat("mm", Locale("ru")).format(now).toIntOrNull() ?: 0

        val hoursString = getHoursSpelling(hours)
        val minutesString = getMinutesSpelling(minutes)

        val spoken = if (minutes == 0) {
            "Сейчас ровно $hoursString"
        } else {
            "Сейчас $hoursString $minutesString"
        }

        return IntentResult(
            intentName = "GetTime",
            spokenResponse = spoken,
            details = "Время: ${SimpleDateFormat("HH:mm", Locale("ru")).format(now)}"
        )
    }

    private fun handleDateIntent(): IntentResult {
        val now = Date()
        val dayOfWeek = SimpleDateFormat("EEEE", Locale("ru")).format(now)
        val day = SimpleDateFormat("d", Locale("ru")).format(now)
        val month = SimpleDateFormat("MMMM", Locale("ru")).format(now)
        val year = SimpleDateFormat("yyyy", Locale("ru")).format(now)

        val spoken = "Сегодня $dayOfWeek, $day $month $year года."
        return IntentResult(
            intentName = "GetDate",
            spokenResponse = spoken,
            details = spoken
        )
    }

    private fun handleBatteryIntent(): IntentResult {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            context.registerReceiver(null, filter)
        }
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct: Int = if (level >= 0 && scale > 0) (level * 100 / scale) else 0

        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val chargingText = if (isCharging) "устройство заряжается" else "работа от батареи"
        val spoken = "Заряд батареи $batteryPct процентов, $chargingText."

        return IntentResult(
            intentName = "BatteryLevel",
            spokenResponse = spoken,
            details = "$batteryPct% ($chargingText)"
        )
    }

    private fun handleVolumeIntent(increase: Boolean): IntentResult {
        val am = audioManager ?: return IntentResult("Volume", "Не удалось изменить громкость.", false)
        val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)

        val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val pct = (currentVol * 100) / maxVol

        val spoken = if (increase) "Прибавила громкость. Сейчас $pct процентов." else "Убавила громкость. Сейчас $pct процентов."
        return IntentResult(
            intentName = "VolumeControl",
            spokenResponse = spoken,
            details = "Громкость: $pct%"
        )
    }

    private fun handleFlashlightIntent(turnOn: Boolean): IntentResult {
        val cm = cameraManager ?: return IntentResult("Flashlight", "Фонарик недоступен на этом устройстве.", false)
        return try {
            val cameraId = cm.cameraIdList.firstOrNull()
            if (cameraId != null) {
                cm.setTorchMode(cameraId, turnOn)
                val spoken = if (turnOn) "Фонарик включен." else "Фонарик выключен."
                IntentResult(
                    intentName = "Flashlight",
                    spokenResponse = spoken,
                    details = "Фонарик: ${if (turnOn) "ВКЛ" else "ВЫКЛ"}"
                )
            } else {
                IntentResult("Flashlight", "Камера или фонарик не найдены.", false)
            }
        } catch (e: CameraAccessException) {
            IntentResult("Flashlight", "Не удалось переключить фонарик: доступ заблокирован.", false)
        } catch (e: Exception) {
            IntentResult("Flashlight", "Ошибка управления фонариком.", false)
        }
    }

    private fun getHoursSpelling(h: Int): String {
        val rem100 = h % 100
        val rem10 = h % 10
        val word = when {
            rem100 in 11..19 -> "часов"
            rem10 == 1 -> "час"
            rem10 in 2..4 -> "часа"
            else -> "часов"
        }
        return "$h $word"
    }

    private fun getMinutesSpelling(m: Int): String {
        val rem100 = m % 100
        val rem10 = m % 10
        val word = when {
            rem100 in 11..19 -> "минут"
            rem10 == 1 -> "минута"
            rem10 in 2..4 -> "минуты"
            else -> "минут"
        }
        return "$m $word"
    }
}
