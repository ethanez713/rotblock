package com.ezhou.rotblock

import com.ezhou.rotblock.setup.FeedProbe
import com.ezhou.rotblock.setup.Fix
import com.ezhou.rotblock.setup.Need
import com.ezhou.rotblock.setup.RequirementId
import com.ezhou.rotblock.setup.SettingsScreen
import com.ezhou.rotblock.setup.SetupFacts
import com.ezhou.rotblock.setup.Status
import com.ezhou.rotblock.setup.blocking
import com.ezhou.rotblock.setup.requirementsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the setup screen concludes from the state of the phone.
 *
 * The case worth having tests for is the middle one: granted and still not working. An
 * accessibility service that Android lists as enabled but has not bound is both the most common
 * broken state on a real phone and the one where the obvious advice ("turn it on") is useless,
 * because it is already on. Everything here is about not collapsing that into "missing".
 */
class ReadinessTest {

    private val t0 = 1_700_000_000_000L

    private fun facts(
        accessibilityEnabled: Boolean = true,
        serviceConnected: Boolean = true,
        lastAliveMs: Long = t0,
        overlayGranted: Boolean = true,
        notificationsGranted: Boolean = true,
        batteryUnrestricted: Boolean = true,
        secureSettingsGranted: Boolean = false,
        installedFromStore: Boolean = true,
        notificationReadoutWanted: Boolean = false,
        greyscaleRuleCount: Int = 0,
        processUptimeMs: Long = 60_000L,
        rottedFeeds: List<FeedProbe> = emptyList(),
        blockTestAtMs: Long = 0L,
        blockTestSeen: Boolean = false,
    ) = SetupFacts(
        accessibilityEnabled = accessibilityEnabled,
        serviceConnected = serviceConnected,
        lastAliveMs = lastAliveMs,
        overlayGranted = overlayGranted,
        notificationsGranted = notificationsGranted,
        batteryUnrestricted = batteryUnrestricted,
        secureSettingsGranted = secureSettingsGranted,
        installedFromStore = installedFromStore,
        processUptimeMs = processUptimeMs,
        notificationReadoutWanted = notificationReadoutWanted,
        greyscaleRuleCount = greyscaleRuleCount,
        packageName = "com.ezhou.rotblock",
        nowMs = t0,
        rottedFeeds = rottedFeeds,
        blockTestAtMs = blockTestAtMs,
        blockTestSeen = blockTestSeen,
    )

    private fun List<com.ezhou.rotblock.setup.Requirement>.byId(id: RequirementId) =
        firstOrNull { it.id == id }

    @Test
    fun `a fully set up phone reports nothing blocking`() {
        val requirements = requirementsFor(facts())
        assertTrue(requirements.blocking.isEmpty())
        assertEquals(Status.READY, requirements.byId(RequirementId.ENFORCEMENT)?.status)
    }

    @Test
    fun `accessibility switched off is missing, and blocks everything`() {
        val requirements = requirementsFor(facts(accessibilityEnabled = false, serviceConnected = false))
        assertEquals(Status.MISSING, requirements.byId(RequirementId.ENFORCEMENT)?.status)
        assertTrue(requirements.blocking.any { it.id == RequirementId.ENFORCEMENT })
    }

    @Test
    fun `enabled but not running is broken, not missing`() {
        // The whole reason this file exists. One UI kills sideloaded services and a force-stop
        // drops rotblock from the enabled list while the toggle still looks on -- and "turn it on"
        // is not the fix for a switch that is already on.
        val requirements = requirementsFor(facts(accessibilityEnabled = true, serviceConnected = false))
        val enforcement = requirements.byId(RequirementId.ENFORCEMENT)
        assertEquals(Status.BROKEN, enforcement?.status)
        assertTrue(requirements.blocking.any { it.id == RequirementId.ENFORCEMENT })
        assertTrue("should say to toggle it, not to turn it on", enforcement!!.fix.contains("off"))
    }

    @Test
    fun `only the broken service offers the in-app restart`() {
        // "Open accessibility settings" is the right button for a switch that was never turned on
        // and useless advice for one that is already on -- which is the whole point of BROKEN.
        val broken = requirementsFor(facts(accessibilityEnabled = true, serviceConnected = false))
        assertEquals(Fix.AttemptRebind, broken.byId(RequirementId.ENFORCEMENT)?.action)

        val missing = requirementsFor(facts(accessibilityEnabled = false, serviceConnected = false))
        assertEquals(
            Fix.OpenSettings(SettingsScreen.ACCESSIBILITY),
            missing.byId(RequirementId.ENFORCEMENT)?.action,
        )

        val ready = requirementsFor(facts())
        assertEquals(
            Fix.OpenSettings(SettingsScreen.ACCESSIBILITY),
            ready.byId(RequirementId.ENFORCEMENT)?.action,
        )
    }

    @Test
    fun `a phone that granted secure settings is offered the switch rather than a walk to it`() {
        // The list rotblock has been dropped from is a secure setting, so with that grant it can
        // put itself back -- see AccessibilityEnrolment. Without it the only honest answer is
        // still Settings.
        val withGrant = requirementsFor(
            facts(accessibilityEnabled = false, serviceConnected = false, secureSettingsGranted = true),
        )
        assertEquals(Fix.SelfEnable, withGrant.byId(RequirementId.ENFORCEMENT)?.action)

        val withoutGrant = requirementsFor(
            facts(accessibilityEnabled = false, serviceConnected = false, secureSettingsGranted = false),
        )
        assertEquals(
            Fix.OpenSettings(SettingsScreen.ACCESSIBILITY),
            withoutGrant.byId(RequirementId.ENFORCEMENT)?.action,
        )
    }

    @Test
    fun `writing the list is not offered for a service that is listed and merely unbound`() {
        // BROKEN is a different fault: the component is already enrolled, so writing that list
        // again changes nothing and would look like a button that does not work.
        val broken = requirementsFor(
            facts(accessibilityEnabled = true, serviceConnected = false, secureSettingsGranted = true),
        )
        assertEquals(Fix.AttemptRebind, broken.byId(RequirementId.ENFORCEMENT)?.action)
    }

    @Test
    fun `a service that has not had time to bind yet is not called broken`() {
        // Opening the app after the process was killed means a moment where accessibility is on
        // and the service is not bound. A red banner that clears itself two seconds later teaches
        // people to ignore the banner, which is the only thing this screen has.
        val justStarted = requirementsFor(facts(serviceConnected = false, processUptimeMs = 1_000L))
        assertEquals(Status.READY, justStarted.byId(RequirementId.ENFORCEMENT)?.status)
        assertTrue(justStarted.blocking.isEmpty())

        val settled = requirementsFor(facts(serviceConnected = false, processUptimeMs = 30_000L))
        assertEquals(Status.BROKEN, settled.byId(RequirementId.ENFORCEMENT)?.status)
    }

    @Test
    fun `a long silence is reported, a recent one is not`() {
        val quiet = requirementsFor(
            facts(serviceConnected = false, lastAliveMs = t0 - 3 * 60 * 60 * 1000L),
        ).byId(RequirementId.ENFORCEMENT)!!
        assertTrue(quiet.consequence.contains("Last seen"))

        // A service that stopped a moment ago is the ordinary case of a rebind in progress, and
        // saying "last seen 0m ago" is noise dressed up as diagnosis.
        val justNow = requirementsFor(
            facts(serviceConnected = false, lastAliveMs = t0 - 5_000L),
        ).byId(RequirementId.ENFORCEMENT)!!
        assertFalse(justNow.consequence.contains("Last seen"))
    }

    @Test
    fun `a service that has never run says nothing about when it was last seen`() {
        val fresh = requirementsFor(
            facts(accessibilityEnabled = false, serviceConnected = false, lastAliveMs = 0L),
        ).byId(RequirementId.ENFORCEMENT)!!
        assertFalse(fresh.consequence.contains("Last seen"))
    }

    @Test
    fun `the block screen is required and battery is not`() {
        // Both matter, and only one of them means nothing is being enforced right now. A banner
        // that cries wolf about battery optimisation is a banner people learn to scroll past.
        val requirements = requirementsFor(facts(overlayGranted = false, batteryUnrestricted = false))
        assertEquals(Need.REQUIRED, requirements.byId(RequirementId.BLOCK_SCREEN)?.need)
        assertEquals(Need.RECOMMENDED, requirements.byId(RequirementId.BATTERY)?.need)
        assertEquals(
            listOf(RequirementId.BLOCK_SCREEN),
            requirements.blocking.map { it.id },
        )
    }

    @Test
    fun `optional requirements are absent until something asks for them`() {
        val quiet = requirementsFor(facts())
        assertNull(quiet.byId(RequirementId.NOTIFICATIONS))
        assertNull(quiet.byId(RequirementId.GREYSCALE))

        val asked = requirementsFor(facts(notificationReadoutWanted = true, greyscaleRuleCount = 2))
        assertEquals(Need.OPTIONAL, asked.byId(RequirementId.NOTIFICATIONS)?.need)
        assertEquals(Need.OPTIONAL, asked.byId(RequirementId.GREYSCALE)?.need)
        assertTrue(asked.blocking.isEmpty())
    }

    @Test
    fun `a rule asking for greyscale it cannot do is broken, not merely ungranted`() {
        // The silent case: the rule says it drains the colour and it does not, and nothing on the
        // rule itself says so.
        val requirements = requirementsFor(facts(greyscaleRuleCount = 1, secureSettingsGranted = false))
        val greyscale = requirements.byId(RequirementId.GREYSCALE)!!
        assertEquals(Status.BROKEN, greyscale.status)
        assertTrue(greyscale.consequence.contains("One rule"))

        val many = requirementsFor(facts(greyscaleRuleCount = 3, secureSettingsGranted = false))
        assertTrue(many.byId(RequirementId.GREYSCALE)!!.consequence.contains("3 rules"))
    }

    @Test
    fun `a rotted feed is reported without raising the banner`() {
        // The one requirement built on a guess rather than on a fact about the phone. The banner
        // means "nothing is being enforced"; a heuristic must not be able to say that.
        val quiet = requirementsFor(facts())
        assertNull(quiet.byId(RequirementId.DETECTION))

        val rotted = requirementsFor(
            facts(rottedFeeds = listOf(FeedProbe("youtube_shorts", "YouTube Shorts", 200, 0))),
        )
        val detection = rotted.byId(RequirementId.DETECTION)!!
        assertEquals(Need.RECOMMENDED, detection.need)
        assertEquals(Status.BROKEN, detection.status)
        assertTrue(detection.consequence.contains("YouTube Shorts"))
        assertTrue(rotted.blocking.isEmpty())
    }

    @Test
    fun `a self test that saw nothing is broken even though the overlay permission is granted`() {
        // The whole reason the self test exists: the permission check alone cannot see an OEM
        // that grants SYSTEM_ALERT_WINDOW and then silently drops the window anyway.
        val requirements = requirementsFor(
            facts(overlayGranted = true, blockTestAtMs = t0, blockTestSeen = false),
        )
        val blockScreen = requirements.byId(RequirementId.BLOCK_SCREEN)!!
        assertEquals(Status.BROKEN, blockScreen.status)
        assertTrue(requirements.blocking.any { it.id == RequirementId.BLOCK_SCREEN })
    }

    @Test
    fun `a self test that was seen leaves the block screen ready`() {
        val requirements = requirementsFor(
            facts(overlayGranted = true, blockTestAtMs = t0, blockTestSeen = true),
        )
        assertEquals(Status.READY, requirements.byId(RequirementId.BLOCK_SCREEN)?.status)
        assertTrue(requirements.blocking.isEmpty())
    }

    @Test
    fun `a block screen that has never been tested reads exactly as before`() {
        val requirements = requirementsFor(facts(overlayGranted = true))
        assertEquals(Status.READY, requirements.byId(RequirementId.BLOCK_SCREEN)?.status)

        val ungranted = requirementsFor(facts(overlayGranted = false))
        assertEquals(Status.MISSING, ungranted.byId(RequirementId.BLOCK_SCREEN)?.status)
    }

    @Test
    fun `the adb command carries the real package name`() {
        val action = requirementsFor(facts(greyscaleRuleCount = 1)).byId(RequirementId.GREYSCALE)?.action
        assertTrue(action is Fix.RunOverAdb)
        assertTrue((action as Fix.RunOverAdb).command.contains("com.ezhou.rotblock"))
    }

    @Test
    fun `restricted settings advice only appears for a sideloaded install`() {
        // Play installs are not subject to it, and a paragraph about a problem that cannot happen
        // is what stops the rest of the screen being read.
        val sideloaded = requirementsFor(
            facts(accessibilityEnabled = false, serviceConnected = false, installedFromStore = false),
        ).byId(RequirementId.ENFORCEMENT)!!
        assertTrue(sideloaded.fix.contains("ADB"))

        val fromStore = requirementsFor(
            facts(accessibilityEnabled = false, serviceConnected = false, installedFromStore = true),
        ).byId(RequirementId.ENFORCEMENT)!!
        assertFalse(fromStore.fix.contains("ADB"))
    }

    @Test
    fun `every unmet requirement says what to do about it`() {
        // A setup screen that reports a problem without a next step is a worse version of silence.
        val everythingWrong = facts(
            accessibilityEnabled = false,
            serviceConnected = false,
            overlayGranted = false,
            notificationsGranted = false,
            batteryUnrestricted = false,
            secureSettingsGranted = false,
            installedFromStore = false,
            notificationReadoutWanted = true,
            greyscaleRuleCount = 1,
        )
        val requirements = requirementsFor(everythingWrong)
        assertEquals(5, requirements.size)
        for (requirement in requirements) {
            assertFalse(requirement.isReady)
            assertTrue("${requirement.id} has no fix", requirement.fix.isNotBlank())
            assertTrue("${requirement.id} has no action", requirement.action != null)
            assertTrue("${requirement.id} has no consequence", requirement.consequence.isNotBlank())
        }
    }
}
