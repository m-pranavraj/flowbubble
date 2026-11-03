package com.flowbubble.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPlaybooksTest {

    private fun screen(
        vararg labels: String,
        pkg: String = "com.whatsapp",
        passwordFocused: Boolean = false
    ) = ScreenObservation(
        revision = 3,
        packageName = pkg,
        labels = labels.toList(),
        textHash = labels.joinToString(" ").hashCode(),
        passwordFocused = passwordFocused
    )

    private fun route(key: String, family: PlaybookFamily): PlaybookRoute = AppPlaybooks.route(key, family)

    private fun playbook(key: String, family: PlaybookFamily): Playbook {
        val found = route(key, family)
        return requireNotNull(found.playbookOrNull) { "no playbook for $key / $family ($found)" }
    }

    // ---- selection ---------------------------------------------------------------------------

    @Test
    fun `picks the recipe written for a known app by package and by spoken name`() {
        val byPackage = route("com.whatsapp", PlaybookFamily.SEARCH_IN_APP)
        assertTrue("expected a curated recipe, got $byPackage", byPackage is PlaybookRoute.Curated)
        byPackage as PlaybookRoute.Curated
        assertEquals("whatsapp.search", byPackage.playbook.id)
        assertEquals("com.whatsapp", byPackage.matchedBy)
        assertEquals(byPackage.playbook, route("WhatsApp", PlaybookFamily.SEARCH_IN_APP).playbookOrNull)
    }

    @Test
    fun `every app the command layer already knows gets a recipe`() {
        val known = listOf(
            "com.whatsapp", "com.android.chrome", "com.google.android.gm",
            "com.google.android.apps.maps", "com.google.android.youtube", "clock", "outlook"
        )
        for (key in known) {
            assertTrue("nothing recognises $key", AppPlaybooks.recipes().any { it.matchedBy(key) != null })
        }
        val searchable = listOf(
            "com.whatsapp", "com.android.chrome", "com.google.android.gm",
            "com.google.android.apps.maps", "com.google.android.youtube"
        )
        for (key in searchable) {
            assertTrue("$key needs a written search recipe", route(key, PlaybookFamily.SEARCH_IN_APP) is PlaybookRoute.Curated)
        }
    }

    @Test
    fun `matches an unknown app by role and claims nothing about it`() {
        val found = route("com.nobody.knows.this", PlaybookFamily.SEARCH_IN_APP)
        assertTrue("expected a role match, got $found", found is PlaybookRoute.ByRole)
        found as PlaybookRoute.ByRole
        assertTrue(found.playbook.isTwin)
        assertFalse("a twin may not claim a watched screen", found.playbook.provenOnDevice)
        val type = found.playbook.steps.first { it.op == PlaybookOp.TYPE_QUERY }
        assertEquals(ScreenRole.SEARCH_BOX, type.locate.role)
        assertTrue(type.locate.patterns.isEmpty())
    }

    @Test
    fun `a family with no twin leaves the app to the ordinary engine`() {
        val found = route("com.nobody.knows.this", PlaybookFamily.FIND_ON_PAGE)
        assertTrue("expected the engine, got $found", found is PlaybookRoute.EngineFallback)
    }

    // ---- the engine, unchanged -----------------------------------------------------------------

    @Test
    fun `the generic fallback emits exactly the chain the planner already emits`() {
        val local = LocalPlanner.plan("open Fancyapp and search for milk")!!
        val resolved = playbook("Fancyapp", PlaybookFamily.GENERIC)
            .resolve(PlaybookSlots(app = "Fancyapp", query = "milk"))

        assertEquals(3, local.steps.size)
        assertEquals(local.steps.map { it.action }, resolved.runnable.map { it.action })
        assertEquals(AgentStep(AgentAction.OpenApp("Fancyapp"), "Open Fancyapp"), resolved.runnable.first())
        assertEquals(AgentAction.Wait(900), resolved.runnable[1].action)
        assertEquals("Wait for the screen", resolved.runnable[1].description)
        assertEquals(
            AgentAction.TypeText("milk", pressEnter = true, searchFieldOnly = true),
            resolved.runnable.last().action
        )
        assertEquals(PlanSource.LOCAL, resolved.agentPlan("open Fancyapp and search for milk")?.source)
    }

    @Test
    fun `the role twin runs the same chain as the planner, not a new one`() {
        val local = LocalPlanner.plan("open Fancyapp and search for milk")!!
        val resolved = playbook("com.nobody.knows.this", PlaybookFamily.SEARCH_IN_APP)
            .resolve(PlaybookSlots(app = "Fancyapp", query = "milk"))
        assertEquals(local.steps.map { it.action }, resolved.runnable.map { it.action })
    }

    @Test
    fun `a scroll recipe reaches the row and opens it in one engine action`() {
        val resolved = playbook("com.nobody.knows.this", PlaybookFamily.SCROLL_TO_RESULT)
            .resolve(PlaybookSlots(app = "YouTube", result = "lo-fi live"))
        val scroll = resolved.runnable.last().action as AgentAction.ScrollUntil
        assertEquals("lo-fi live", scroll.label)
        assertTrue("the matching row has to be opened, not just reached", scroll.tapAfter)
        assertEquals(GoalKind.FIND, resolved.goal("scroll down to lo-fi live and open it")?.kind)
    }

    @Test
    fun `a submit with a control of its own stays a step and takes Enter off the field`() {
        val book = Playbook(
            id = "test.button.search",
            family = PlaybookFamily.SEARCH_IN_APP,
            steps = listOf(
                PlaybookStep("Type it", PlaybookOp.TYPE_QUERY, ScreenLocator(role = ScreenRole.SEARCH_BOX)),
                PlaybookStep(
                    "Press search",
                    PlaybookOp.SUBMIT_SEARCH,
                    ScreenLocator(patterns = listOf("search"), role = ScreenRole.GO_BUTTON)
                )
            )
        )
        val resolved = book.resolve(PlaybookSlots(query = "milk"))
        assertEquals(2, resolved.runnable.size)
        assertFalse((resolved.runnable[0].action as AgentAction.TypeText).pressEnter)
        assertEquals(AgentAction.TapTarget("search", null), resolved.runnable[1].action)
    }

    // ---- evidence -----------------------------------------------------------------------------

    @Test
    fun `a search is only proven by the words coming back and the screen moving`() {
        val resolved = playbook("com.google.android.youtube", PlaybookFamily.SEARCH_IN_APP)
            .resolve(PlaybookSlots(app = "YouTube", query = "lo-fi beats"))
        val typed = resolved.steps.first { it.step.op == PlaybookOp.TYPE_QUERY }
        assertEquals(2, typed.doneWhen.size)
        assertTrue(typed.doneWhen.any { it is GoalCondition.LabelAppeared })
        assertTrue(typed.doneWhen.any { it is GoalCondition.ScreenChanged })
        assertTrue(resolved.evidence().contains("lo-fi beats"))
        assertTrue(typed.step.wrongWay.contains("no results"))
    }

    @Test
    fun `empty results stop the run instead of scrolling on`() {
        val resolved = playbook("com.google.android.youtube", PlaybookFamily.SEARCH_IN_APP)
            .resolve(PlaybookSlots(app = "YouTube", query = "lo-fi beats"))
        assertNotNull(resolved.stopReason(screen("No results found", "Try a different search")))
        assertNull(resolved.stopReason(screen("Lo-fi beats", "Mixtape 2026", "Playlists")))
    }

    @Test
    fun `screen predicates decide from what is actually labelled`() {
        val omnibox = playbook("com.android.chrome", PlaybookFamily.SEARCH_IN_APP)
            .steps.first { it.op == PlaybookOp.TYPE_QUERY }
            .locate
        assertTrue(omnibox.liveOn(screen("Address and search bar", "New tab")))
        assertFalse(omnibox.liveOn(screen("Chats", "Status", "Communities")))

        val byRole = playbook("com.nobody.knows.this", PlaybookFamily.SEARCH_IN_APP)
            .steps.first { it.op == PlaybookOp.TYPE_QUERY }
            .locate
        assertTrue("a bare role does not depend on the app's wording", byRole.liveOn(screen("Anything at all")))
    }

    @Test
    fun `a recipe names the magnifier and the tab in that app's own order`() {
        val youtube = playbook("com.google.android.youtube", PlaybookFamily.SEARCH_IN_APP)
            .steps.first { it.op == PlaybookOp.REVEAL_SEARCH }
        assertEquals(ScreenRole.SEARCH_BUTTON, youtube.locate.role)
        assertEquals(ScreenRole.SEARCH_TAB, youtube.elseLocate?.role)

        val whatsapp = playbook("com.whatsapp", PlaybookFamily.SEARCH_IN_APP)
            .steps.first { it.op == PlaybookOp.REVEAL_SEARCH }
        assertEquals(ScreenRole.SEARCH_TAB, whatsapp.locate.role)
        assertEquals(ScreenRole.SEARCH_BUTTON, whatsapp.elseLocate?.role)
    }

    // ---- the commit belongs to the user -------------------------------------------------------

    @Test
    fun `the commit step is flagged as needing the user and gets no action`() {
        val send = playbook("com.whatsapp", PlaybookFamily.COMMIT_FLOW)
        val commit = send.steps.last()
        assertEquals(PlaybookOp.COMMIT, commit.op)
        assertEquals(UserGate.REQUIRED, commit.gate)
        assertTrue(commit.needsUser)
        assertEquals(commit, send.gatedStep)

        val resolved = send.resolve(
            PlaybookSlots(app = "WhatsApp", result = "Pranav", fieldValues = mapOf("message" to "on my way"))
        )
        assertTrue(resolved.commitLeftToUser)
        assertNull(resolved.steps.last().action)
        assertTrue(resolved.handoff.isNotBlank())
        assertFalse(
            "nothing the agent runs may press send",
            resolved.runnable.any { (it.action as? AgentAction.TapTarget)?.label.equals("send", true) }
        )
        assertEquals(AgentAction.TypeText("on my way"), resolved.runnable.last().action)
    }

    @Test
    fun `a recipe author cannot un-gate a commit`() {
        val rogue = Playbook(
            id = "test.rogue",
            family = PlaybookFamily.COMMIT_FLOW,
            steps = listOf(
                PlaybookStep(
                    "Pay now",
                    PlaybookOp.COMMIT,
                    ScreenLocator(patterns = listOf("pay now")),
                    gate = UserGate.NONE
                )
            )
        )
        assertTrue(rogue.steps.single().needsUser)
        val resolved = rogue.resolve(PlaybookSlots(app = "Shop"))
        assertTrue(resolved.runnable.isEmpty())
        assertNull(resolved.agentPlan("pay for it"))
        assertTrue(resolved.commitLeftToUser)
    }

    @Test
    fun `credentials are left to the user and never reach the plan`() {
        val login = Playbook(
            id = "test.login.form",
            family = PlaybookFamily.COMMIT_FLOW,
            steps = listOf(
                PlaybookStep(
                    "Password",
                    PlaybookOp.FILL_FIELD,
                    ScreenLocator(role = ScreenRole.FORM_FIELD),
                    field = "password"
                ),
                PlaybookStep("Send", PlaybookOp.COMMIT, gate = UserGate.REQUIRED)
            )
        )
        val resolved = login.resolve(PlaybookSlots(fieldValues = mapOf("password" to "hunter2")))
        assertTrue(resolved.runnable.isEmpty())
        assertTrue(resolved.steps.first().needsUser)
        assertFalse(resolved.steps.any { it.description.contains("hunter2") })
        assertFalse(resolved.handoff.contains("hunter2"))
    }

    @Test
    fun `an email is drafted with what the user named and send stays untouched`() {
        val resolved = playbook("com.google.android.gm", PlaybookFamily.COMMIT_FLOW).resolve(
            PlaybookSlots(app = "Gmail", query = "thanks for today", fieldValues = mapOf("to" to "ana@example.com"))
        )
        val typed = resolved.runnable.mapNotNull { (it.action as? AgentAction.TypeText)?.text }
        assertEquals(listOf("ana@example.com", "thanks for today"), typed)
        assertTrue(resolved.commitLeftToUser)
    }

    @Test
    fun `a form with nothing to type stops and says what it needs`() {
        val resolved = playbook("com.google.android.gm", PlaybookFamily.COMMIT_FLOW)
            .resolve(PlaybookSlots(app = "Gmail"))
        assertTrue(resolved.handoff.isNotBlank())
        assertTrue(resolved.blocked.isNotBlank())
        assertTrue(resolved.commitLeftToUser)
        assertTrue(resolved.runnable.isNotEmpty())
    }

    @Test
    fun `directions are shown but booking is not`() {
        val resolved = playbook("com.google.android.apps.maps", PlaybookFamily.NAVIGATE_TO)
            .resolve(PlaybookSlots(app = "Maps", place = "Pete's Pizza"))
        assertTrue(resolved.runnable.any { (it.action as? AgentAction.TapTarget)?.label == "directions" })
        assertTrue(resolved.runnable.any { (it.action as? AgentAction.TypeText)?.text == "Pete's Pizza" })
        assertTrue(resolved.commitLeftToUser)
        assertEquals(GoalKind.TAP, resolved.goal("navigate to Pete's Pizza")?.kind)
    }

    // ---- honest refusals ----------------------------------------------------------------------

    @Test
    fun `an unverified app gets a refusal instead of a guess`() {
        val cases = mapOf(
            "org.telegram.messenger" to "Telegram",
            "Telegram" to "Telegram",
            "signal" to "Signal",
            "com.instagram.android" to "Instagram"
        )
        for ((key, label) in cases) {
            val found = route(key, PlaybookFamily.SEARCH_IN_APP)
            assertTrue("$key expected a refusal, got $found", found is PlaybookRoute.Refused)
            found as PlaybookRoute.Refused
            assertEquals(label, found.label)
            assertNull("a refusal may not hand back a recipe", found.playbookOrNull)
            assertEquals(OutcomeKind.UNSUPPORTED, found.outcome)
            assertEquals("unsupported", found.outcome.historyStatus())
            assertTrue(found.reason.contains("haven't verified"))
            assertTrue(found.reason.contains("ordinary screen engine"))
        }
    }

    @Test
    fun `a payment commit is refused while searching that app is still offered`() {
        assertTrue(route("paytm", PlaybookFamily.COMMIT_FLOW) is PlaybookRoute.Refused)
        assertTrue(route("net.one97.paytm", PlaybookFamily.COMMIT_FLOW) is PlaybookRoute.Refused)
        assertTrue(route("paytm", PlaybookFamily.SEARCH_IN_APP) is PlaybookRoute.ByRole)
        assertTrue(AppPlaybooks.unverified.any { it.label == "Telegram" })
    }

    @Test
    fun `a lock or a human check stops the run before any step is taken`() {
        assertTrue(
            AppPlaybooks.blockedBy(screen("Password", passwordFocused = true))
                .orEmpty()
                .contains("password field is focused")
        )
        assertTrue(
            AppPlaybooks.blockedBy(screen("Verify you are human"))
                .orEmpty()
                .contains("won't get around")
        )
        assertNull(AppPlaybooks.blockedBy(screen("Inbox", "Work", "Drafts")))
    }

    @Test
    fun `spoken words resolve to a role and to the app's display name`() {
        assertEquals(ScreenRole.SEARCH_TAB, ScreenRole.named("search tab"))
        assertNull(ScreenRole.named("purple button"))
        assertEquals("Whatsapp", AppPlaybooks.labelFor("com.whatsapp"))
        assertEquals("Telegram", AppPlaybooks.labelFor("org.telegram.messenger"))
    }

    @Test
    fun `user text is kept short and never becomes a control`() {
        val long = "a".repeat(400)
        val resolved = playbook("com.google.android.youtube", PlaybookFamily.SEARCH_IN_APP)
            .resolve(PlaybookSlots(app = "YouTube", query = long))
        val typed = resolved.runnable.first { it.action is AgentAction.TypeText }.action as AgentAction.TypeText
        assertEquals(160, typed.text.length)
        assertTrue(resolved.steps.none { it.action is AgentAction.TapTarget && it.description.contains(long) })
    }
}
