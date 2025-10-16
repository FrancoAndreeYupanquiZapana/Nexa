package com.example.drowsinessdetectorapp.ui

//Guardar el nuvel de cansancio (score) de marena reactiva y para cualquier pandalla, capa de persistencia

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DrowsinessViewModel: ViewModel(){
    private val _drowsinessScore = MutableStateFlow(0f)
    val drowsinessScore: StateFlow<Float> = _drowsinessScore


    fun updateScroe(newScore : Float){
        viewModelScope.launch{
            _drowsinessScore.emit(newScore)
        }
    }

}