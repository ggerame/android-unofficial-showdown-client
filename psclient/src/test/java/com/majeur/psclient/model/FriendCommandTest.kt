package com.majeur.psclient.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FriendCommandTest {

    @Test fun mapsEveryUiActionToAnOfficialNormalizedCommand() {
        assertEquals("/friends add misty", friendCommand(FriendAction.ADD, "+Misty"))
        assertEquals("/friends accept misty", friendCommand(FriendAction.ACCEPT, "Misty"))
        assertEquals("/friends reject misty", friendCommand(FriendAction.REJECT, "Misty"))
        assertEquals("/friends undorequest misty", friendCommand(FriendAction.UNDO_REQUEST, "Misty"))
        assertEquals("/friends remove misty", friendCommand(FriendAction.REMOVE, "Misty"))
        assertNull(friendCommand(FriendAction.ADD, "!!!"))
    }
}
