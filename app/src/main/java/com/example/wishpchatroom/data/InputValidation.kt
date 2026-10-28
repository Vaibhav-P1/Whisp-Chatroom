package com.example.wishpchatroom.data

private val EMAIL_PATTERN = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+([.][A-Za-z0-9-]+)*[.][A-Za-z]{2,}$")

/** Local check so an obviously malformed address never costs a network round trip. */
fun isValidEmail(email: String): Boolean = EMAIL_PATTERN.matches(email.trim())

const val MIN_PASSWORD_LENGTH = 6
