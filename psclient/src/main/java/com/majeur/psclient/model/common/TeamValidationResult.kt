package com.majeur.psclient.model.common

data class TeamValidationResult(val valid: Boolean, val message: String) {
    companion object {
        fun fromPopup(message: String): TeamValidationResult {
            val rejected = message.contains("rejected", ignoreCase = true) ||
                    message.contains("does not exist", ignoreCase = true) ||
                    message.contains("not a valid format", ignoreCase = true)
            val accepted = message.contains("team is valid", ignoreCase = true)
            return TeamValidationResult(accepted && !rejected, message)
        }
    }
}
