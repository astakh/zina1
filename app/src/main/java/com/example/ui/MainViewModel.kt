package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.model.CustomVoiceRule
import com.example.data.local.model.VoiceCommandLog
import com.example.engine.OfflineTtsEngine
import com.example.service.ZinaidaVoiceService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val dao = database.voiceDao()
    private var testTtsEngine: OfflineTtsEngine? = null

    val isServiceRunning: StateFlow<Boolean> = ZinaidaVoiceService.isRunning
    val assistantState: StateFlow<ZinaidaVoiceService.AssistantState> = ZinaidaVoiceService.state
    val audioAmplitude: StateFlow<Float> = ZinaidaVoiceService.audioAmplitude
    val isScreenOff: StateFlow<Boolean> = ZinaidaVoiceService.isScreenOff
    val lastCommand: StateFlow<String?> = ZinaidaVoiceService.lastCommand
    val lastResponse: StateFlow<String?> = ZinaidaVoiceService.lastResponse
    val serviceEvents: StateFlow<String?> = MutableStateFlow<String?>(null)

    val logs: StateFlow<List<VoiceCommandLog>> = dao.getAllLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customRules: StateFlow<List<CustomVoiceRule>> = dao.getAllRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab = _selectedTab.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage = _userMessage.asStateFlow()

    init {
        testTtsEngine = OfflineTtsEngine(application)
    }

    fun selectTab(index: Int) {
        _selectedTab.value = index
    }

    fun dismissUserMessage() {
        _userMessage.value = null
    }

    fun toggleService() {
        val app = getApplication<Application>()
        if (isServiceRunning.value) {
            ZinaidaVoiceService.stopService(app)
            _userMessage.value = "Фоновая служба ассистента остановлена"
        } else {
            ZinaidaVoiceService.startService(app)
            _userMessage.value = "Ассистент Зинаида запущен в фоне!"
        }
    }

    fun triggerTestCommand(command: String) {
        val app = getApplication<Application>()
        if (!isServiceRunning.value) {
            ZinaidaVoiceService.startService(app)
        }
        ZinaidaVoiceService.triggerTestCommand(app, command)
    }

    fun speakText(text: String) {
        testTtsEngine?.speak(text)
    }

    fun addCustomRule(trigger: String, response: String) {
        if (trigger.isBlank() || response.isBlank()) return
        viewModelScope.launch {
            dao.insertRule(
                CustomVoiceRule(
                    triggerPhrase = trigger.trim(),
                    spokenResponse = response.trim()
                )
            )
            _userMessage.value = "Новое правило добавлено!"
        }
    }

    fun toggleRule(rule: CustomVoiceRule) {
        viewModelScope.launch {
            dao.updateRule(rule.copy(isEnabled = !rule.isEnabled))
        }
    }

    fun deleteRule(rule: CustomVoiceRule) {
        viewModelScope.launch {
            dao.deleteRule(rule)
            _userMessage.value = "Правило удалено"
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            dao.clearLogs()
            _userMessage.value = "История команд очищена"
        }
    }

    override fun onCleared() {
        super.onCleared()
        testTtsEngine?.release()
        testTtsEngine = null
    }
}
