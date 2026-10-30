package com.flowbubble.app.commands

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactResolverTest {

    private data class Person(val name: String)

    @Test
    fun `an exact name is not repeated as a prefix and contains match`() {
        val ranked = ContactResolver.rankMatches(
            listOf(Person("Old Pranav"), Person("Pranav"), Person("Pranav Test")),
            "pranav"
        ) { it.name }
        assertEquals(listOf("Pranav", "Pranav Test", "Old Pranav"), ranked.map { it.name })
    }

    @Test
    fun `a number-only match stays at the end and appears once`() {
        val ranked = ContactResolver.rankMatches(
            listOf(Person("+91 98765 43210"), Person("Pranav")),
            "Pranav"
        ) { it.name }
        assertEquals(listOf("Pranav", "+91 98765 43210"), ranked.map { it.name })
    }
}
