package ir.hirmand.phonebridge

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import ir.hirmand.phonebridge.ui.MainActivity
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @Test
    fun dashboardLoadsAndTabsSwitchSections() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.dashboardSection)).check(matches(isDisplayed()))

            onView(withId(R.id.tabModules)).perform(click())
            onView(withId(R.id.modulesSection)).check(matches(isDisplayed()))

            onView(withId(R.id.tabSettings)).perform(click())
            onView(withId(R.id.settingsSection)).check(matches(isDisplayed()))

            onView(withId(R.id.tabDashboard)).perform(click())
            onView(withId(R.id.syncButton)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun privacyTextIsVisible() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.privacyText)).check(matches(isDisplayed()))
        }
    }
}
