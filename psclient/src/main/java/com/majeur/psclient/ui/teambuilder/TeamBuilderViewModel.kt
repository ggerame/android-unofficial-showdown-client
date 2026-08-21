package com.majeur.psclient.ui.teambuilder

import androidx.lifecycle.ViewModel
import com.majeur.psclient.model.common.Team

class TeamBuilderViewModel : ViewModel() {
    lateinit var team: Team
    val isInitialized get() = this::team.isInitialized
}
