package br.com.butialabs.medwi.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.fragment.app.FragmentActivity
import br.com.butialabs.medwi.ui.lock.AppLock
import br.com.butialabs.medwi.ui.lock.LockScreen
import br.com.butialabs.medwi.ui.settings.SettingsScreen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.butialabs.medwi.ui.settings.ReliabilityScreen
import br.com.butialabs.medwi.ui.settings.ReminderReliability
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import br.com.butialabs.medwi.R
import br.com.butialabs.medwi.ui.addmed.AddMedWizard
import br.com.butialabs.medwi.ui.addmed.EditMedScreen
import br.com.butialabs.medwi.ui.theme.MedwiTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val requestNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private lateinit var appLock: AppLock

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Rotation recreates the activity: don't ask to unlock again.
        appLock = AppLock(this, alreadyUnlocked = savedInstanceState?.getBoolean(KEY_UNLOCKED) == true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MedwiTheme {
                Box(Modifier.fillMaxSize()) {
                    AppRoot(onLockEnabled = appLock::onEnabled)
                    if (appLock.locked) LockScreen(onUnlock = appLock::prompt)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appLock.onStart()
    }

    override fun onStop() {
        super.onStop()
        appLock.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_UNLOCKED, !appLock.locked)
    }

    private companion object {
        const val KEY_UNLOCKED = "app_unlocked"
    }
}

private sealed class Dest(val route: String) {
    data object Today : Dest("today")
    data object Meds : Dest("meds")
    data object Progress : Dest("progress")
    data object AddMed : Dest("add")
    data object Reliability : Dest("reliability")
    data object Settings : Dest("settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(onLockEnabled: () -> Unit, vm: MedViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    // The add/edit flows and the reliability checklist own the whole screen (own top bar + back).
    val isWizard = current == Dest.AddMed.route || current?.startsWith("edit/") == true ||
        current?.startsWith("med/") == true ||
        current == Dest.Reliability.route || current == Dest.Settings.route

    // First launch: walk the user through the settings that keep reminders alive.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (ReminderReliability.hasCriticalIssues(context) && ReminderReliability.consumeOnboarding(context)) {
            nav.navigate(Dest.Reliability.route)
        }
    }

    Scaffold(
        topBar = {
            if (!isWizard) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painterResource(R.drawable.ic_medwi_mark),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                        }
                    },
                    actions = {
                        IconButton(onClick = { nav.navigate(Dest.Settings.route) { launchSingleTop = true } }) {
                            Icon(Icons.Outlined.Settings, stringResource(R.string.settings_title))
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (!isWizard) {
                NavigationBar {
                    NavigationBarItem(
                        selected = current == Dest.Today.route,
                        onClick = { nav.navigate(Dest.Today.route) { launchSingleTop = true } },
                        icon = { Icon(Icons.Default.Today, null) },
                        label = { Text(stringResource(R.string.tab_today)) },
                    )
                    NavigationBarItem(
                        selected = current == Dest.Meds.route,
                        onClick = { nav.navigate(Dest.Meds.route) { launchSingleTop = true } },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                        label = { Text(stringResource(R.string.tab_meds)) },
                    )
                    NavigationBarItem(
                        selected = current == Dest.Progress.route,
                        onClick = { nav.navigate(Dest.Progress.route) { launchSingleTop = true } },
                        icon = { Icon(Icons.Default.Insights, null) },
                        label = { Text(stringResource(R.string.tab_progress)) },
                    )
                }
            }
        },
        floatingActionButton = {
            // Adding a medicine belongs to Today / Meds; on Progress the FAB would cover the history.
            if (!isWizard && current != Dest.Progress.route) {
                FloatingActionButton(onClick = { nav.navigate(Dest.AddMed.route) }) {
                    Icon(Icons.Default.Add, stringResource(R.string.add_med))
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Dest.Today.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Dest.Today.route) {
                TodayScreen(vm, onOpenReliability = { nav.navigate(Dest.Reliability.route) { launchSingleTop = true } })
            }
            composable(Dest.Reliability.route) { ReliabilityScreen(onBack = { nav.popBackStack() }) }
            composable(Dest.Settings.route) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenReliability = { nav.navigate(Dest.Reliability.route) { launchSingleTop = true } },
                    onLockEnabled = onLockEnabled,
                )
            }
            composable(Dest.Meds.route) {
                MedListScreen(vm, onOpen = { medId -> nav.navigate("med/$medId") })
            }
            composable(
                route = "med/{medId}",
                arguments = listOf(navArgument("medId") { type = NavType.LongType }),
            ) { entry ->
                val medId = entry.arguments?.getLong("medId") ?: return@composable
                MedDetailScreen(
                    vm, medId,
                    onBack = { nav.popBackStack() },
                    onEdit = { nav.navigate("edit/$it") },
                )
            }
            composable(Dest.Progress.route) { ProgressScreen(vm) }
            composable(Dest.AddMed.route) { AddMedWizard(vm, onDone = { nav.popBackStack() }) }
            composable(
                route = "edit/{medId}",
                arguments = listOf(navArgument("medId") { type = NavType.LongType }),
            ) { entry ->
                val medId = entry.arguments?.getLong("medId") ?: return@composable
                EditMedScreen(vm, medId, onDone = { nav.popBackStack() })
            }
        }
    }
}
