package com.majeur.psclient.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegistrationValidationTest {

    @Test fun requiresEveryField() {
        val valid = listOf("Player", "abcde", "abcde", "Pikachu")
        valid.indices.forEach { emptyIndex ->
            val fields = valid.toMutableList().also { it[emptyIndex] = "" }
            assertEquals(
                    RegistrationValidationError.REQUIRED_FIELDS,
                    validateRegistration(fields[0], fields[1], fields[2], fields[3]))
        }
    }

    @Test fun rejectsPasswordWithFewerThanFiveNonSpaceCharacters() {
        assertEquals(
                RegistrationValidationError.PASSWORD_TOO_SHORT,
                validateRegistration("Player", "a b c d", "a b c d", "Pikachu"))
    }

    @Test fun rejectsWhitespaceOnlyPassword() {
        assertEquals(
                RegistrationValidationError.REQUIRED_FIELDS,
                validateRegistration("Player", "     ", "     ", "Pikachu"))
    }

    @Test fun rejectsDifferentConfirmation() {
        assertEquals(
                RegistrationValidationError.PASSWORD_MISMATCH,
                validateRegistration("Player", "abcde", "abcdef", "Pikachu"))
    }

    @Test fun acceptsValidForm() {
        assertNull(validateRegistration("Player", "a b c d e", "a b c d e", "Pikachu"))
    }
}
