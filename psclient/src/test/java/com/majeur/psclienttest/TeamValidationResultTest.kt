package com.majeur.psclienttest

import com.majeur.psclient.model.common.TeamValidationResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamValidationResultTest {
    @Test fun recognizesServerValidationPopups() {
        assertTrue(TeamValidationResult.fromPopup("Your team is valid for [Gen 9] OU.").valid)
        val illegalTeam = TeamValidationResult.fromPopup(
                "Your team was rejected for the following reasons:\n- Pikachu can't learn Spore")
        assertFalse(illegalTeam.valid)
        assertTrue(illegalTeam.rejected)
        assertTrue(TeamValidationResult.fromPopup("The format 'missing' does not exist.").rejected)
        assertFalse(TeamValidationResult(false, "Team validation timed out").rejected)
    }
}
