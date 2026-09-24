package com.jev.relationship.feature.settings

object PairingTokenClipboard {
    fun normalize(token: String): String = token.filterNot(Char::isWhitespace)
}
