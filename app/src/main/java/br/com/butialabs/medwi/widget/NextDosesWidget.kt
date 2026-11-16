package br.com.butialabs.medwi.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import br.com.butialabs.medwi.R
import br.com.butialabs.medwi.core.model.DoseStatus
import br.com.butialabs.medwi.data.DataChangeListener
import br.com.butialabs.medwi.data.MedRepository
import br.com.butialabs.medwi.data.db.DoseView
import br.com.butialabs.medwi.data.settings.AppSettings
import br.com.butialabs.medwi.ui.MainActivity
import android.graphics.Bitmap
import br.com.butialabs.medwi.ui.doseText
import br.com.butialabs.medwi.ui.medTitle
import br.com.butialabs.medwi.ui.pill.PillColors
import br.com.butialabs.medwi.ui.pill.PillRenderer
import br.com.butialabs.medwi.ui.theme.DarkColors
import br.com.butialabs.medwi.ui.theme.LightColors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun repository(): MedRepository
}

private fun repo(context: Context): MedRepository =
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).repository()

private val hm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Home-screen widget: today's next unanswered doses, each with a "Take" button. */
class NextDosesWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val open = repo(context).dosesBetween(from, to)
            .filter { it.status == DoseStatus.PENDING.name || it.status == DoseStatus.SNOOZED.name }
            .take(MAX_ROWS)
        val hideNames = AppSettings.isLockEnabled(context)
        val hiddenLabel = context.getString(R.string.widget_hidden_med)
        val takeLabel = context.getString(R.string.action_take)
        val doneLabel = context.getString(R.string.widget_all_done)
        val iconPx = (40 * context.resources.displayMetrics.density).toInt()
        val rows = open.map { d ->
            WidgetRow(
                dose = d,
                title = if (hideNames) hiddenLabel else medTitle(d.medicineName, d.strengthValue, d.strengthUnit),
                amount = doseText(context.resources, d.dosageValue, d.dosageUnit),
                // With the app lock on, the drawing could identify the medicine: generic pill.
                icon = if (hideNames) PillRenderer.bitmap(null, PillColors.WHITE, null, iconPx)
                else PillRenderer.bitmap(d.iconKey, d.colorHex, d.color2Hex, iconPx),
            )
        }

        provideContent {
            GlanceTheme(colors = ColorProviders(light = LightColors, dark = DarkColors)) {
                WidgetBody(rows, takeLabel, doneLabel)
            }
        }
    }

    companion object {
        const val MAX_ROWS = 3
    }
}

private class WidgetRow(val dose: DoseView, val title: String, val amount: String, val icon: Bitmap)

@Composable
private fun WidgetBody(
    rows: List<WidgetRow>,
    takeLabel: String,
    doneLabel: String,
) {
    val zone = ZoneId.systemDefault()
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(R.drawable.ic_medwi_mark), contentDescription = null, modifier = GlanceModifier.size(22.dp))
            Spacer(GlanceModifier.width(8.dp))
            Text(
                "Medwi",
                style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 15.sp),
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        if (rows.isEmpty()) {
            Text(doneLabel, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp))
        }
        rows.forEach { row ->
            val dose = row.dose
            val time = Instant.ofEpochMilli(dose.scheduledAtMillis).atZone(zone).toLocalTime().format(hm)
            Row(
                GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(ImageProvider(row.icon), contentDescription = null, modifier = GlanceModifier.size(30.dp))
                Spacer(GlanceModifier.width(8.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Text(
                        time,
                        style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp),
                    )
                    Text(
                        "${row.title} · ${row.amount}",
                        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                        maxLines = 1,
                    )
                }
                Text(
                    takeLabel,
                    style = TextStyle(color = GlanceTheme.colors.onPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                    modifier = GlanceModifier
                        .background(GlanceTheme.colors.primary)
                        .cornerRadius(16.dp)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clickable(actionRunCallback<TakeDoseAction>(actionParametersOf(TakeDoseAction.ITEM_ID to dose.itemId))),
                )
            }
        }
    }
}

/** "Take" from the widget: marks the dose; the repository then refreshes every widget. */
class TakeDoseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val itemId = parameters[ITEM_ID] ?: return
        repo(context).markDose(itemId, DoseStatus.TAKEN)
        NextDosesWidget().updateAll(context)
    }

    companion object {
        val ITEM_ID = ActionParameters.Key<Long>("item_id")
    }
}

class NextDosesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextDosesWidget()
}

/** Refreshes the widget whenever the repository reports a data change. */
class WidgetUpdater(private val context: Context) : DataChangeListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override fun onDataChanged() {
        scope.launch { runCatching { NextDosesWidget().updateAll(context) } }
    }
}
