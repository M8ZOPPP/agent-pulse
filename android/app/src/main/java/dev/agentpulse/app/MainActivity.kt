package dev.agentpulse.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var relayUrlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var eventsContainer: LinearLayout
    private lateinit var pairButton: MaterialButton
    private lateinit var testButton: MaterialButton

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) toast("Notification permission is required for agent alerts")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = color(R.color.canvas)
        window.navigationBarColor = color(R.color.canvas)
        setContentView(buildScreen())
        bindSavedValues()
        renderStatus()
        renderEvents()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        renderEvents()
        if (AppPrefs.enabled(this)) {
            Thread {
                runCatching { RelayClient.syncEvents(this) }
                    .onSuccess { runOnUiThread { renderEvents() } }
                    .onFailure {
                        AppPrefs.setStatus(this, "Push active", "Catch-up failed: ${it.message}")
                        runOnUiThread { renderStatus() }
                    }
            }.start()
        }
    }

    private fun buildScreen(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(color(R.color.canvas))
            isFillViewport = true
        }
        val root = vertical().apply {
            setPadding(dp(20), dp(22), dp(20), dp(40))
        }
        scroll.addView(root)

        root.addView(text("AGENT PULSE", 12f, R.color.accent, Typeface.BOLD).apply {
            letterSpacing = 0.18f
        })
        root.addView(text("Your coding agents, within reach.", 26f, R.color.ink, Typeface.BOLD).apply {
            setPadding(0, dp(8), 0, 0)
        })
        root.addView(text(
            "Immediate FCM alerts with relay catch-up for missed events.",
            14f,
            R.color.ink_muted
        ).apply { setPadding(0, dp(6), 0, dp(22)) })

        root.addView(buildStatusCard())
        root.addView(sectionLabel("PAIRING"))
        root.addView(buildPairingCard())
        root.addView(sectionLabel("ALERT ROUTING"))
        root.addView(buildFiltersCard())
        root.addView(sectionLabel("RECENT EVENTS"))
        root.addView(buildEventsCard())
        root.addView(text(
            "Agent Pulse stores only your relay address, pairing token, and the latest 40 event summaries on this device.",
            12f,
            R.color.ink_muted
        ).apply { setPadding(dp(4), dp(18), dp(4), 0) })
        return scroll
    }

    private fun buildStatusCard(): View = card().apply {
        val row = horizontal().apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            gravity = Gravity.CENTER_VERTICAL
        }
        val pulse = TextView(this@MainActivity).apply {
            text = "●"
            textSize = 22f
            setTextColor(color(R.color.complete))
            gravity = Gravity.CENTER
        }
        row.addView(pulse, LinearLayout.LayoutParams(dp(34), dp(42)))
        val copy = vertical()
        statusText = text("Not paired", 16f, R.color.ink, Typeface.BOLD)
        statusDetail = text("Add your relay endpoint to start", 12f, R.color.ink_muted)
        copy.addView(statusText)
        copy.addView(statusDetail)
        row.addView(copy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val badge = text("FCM", 11f, R.color.accent, Typeface.BOLD).apply {
            setBackgroundResource(R.drawable.bg_badge)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        row.addView(badge)
        addView(row)
    }

    private fun buildPairingCard(): View = card().apply {
        val content = vertical().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        content.addView(text(
            "Connect to your HTTPS relay. Firebase client settings are fetched during pairing; no Firebase secrets are stored in the APK.",
            13f,
            R.color.ink_muted
        ).apply { setPadding(dp(2), 0, dp(2), dp(14)) })

        val urlField = input("Relay URL", false)
        relayUrlInput = urlField.editText!!
        relayUrlInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        content.addView(urlField)
        content.addView(space(10))

        val tokenField = input("Pairing token", true)
        tokenInput = tokenField.editText!!
        content.addView(tokenField)
        content.addView(space(14))

        pairButton = MaterialButton(this@MainActivity).apply {
            text = "Pair this phone"
            setOnClickListener { pairPhone() }
        }
        content.addView(pairButton, matchWidth())
        content.addView(space(8))

        val secondaryRow = horizontal()
        testButton = outlineButton("Send test").apply {
            setOnClickListener { sendTest() }
        }
        val syncButton = outlineButton("Sync missed").apply {
            setOnClickListener { syncNow() }
        }
        secondaryRow.addView(testButton, weighted())
        secondaryRow.addView(space(8))
        secondaryRow.addView(syncButton, weighted())
        content.addView(secondaryRow, matchWidth())
        addView(content)
    }

    private fun buildFiltersCard(): View = card().apply {
        val content = vertical()
        content.addView(filterRow("approval", "Permission requests", "Commands or tools need approval", R.color.approval))
        content.addView(divider())
        content.addView(filterRow("question", "Questions", "The agent is waiting for input", R.color.question))
        content.addView(divider())
        content.addView(filterRow("completed", "Task completed", "A Codex or Claude turn finished", R.color.complete))
        content.addView(divider())
        content.addView(filterRow("error", "Errors", "A hook or agent run failed", R.color.error))
        addView(content)
    }

    private fun buildEventsCard(): View = card().apply {
        val content = vertical().apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }
        val header = horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("Event timeline", 14f, R.color.ink, Typeface.BOLD), weighted())
        header.addView(outlineButton("Clear").apply {
            minHeight = 0
            minimumHeight = 0
            insetTop = 0
            insetBottom = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                EventStore.clear(this@MainActivity)
                renderEvents()
            }
        })
        content.addView(header)
        content.addView(space(8))
        eventsContainer = vertical()
        content.addView(eventsContainer)
        addView(content)
    }

    private fun filterRow(type: String, title: String, subtitle: String, tint: Int): View {
        val row = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(10), dp(12))
        }
        row.addView(text("●", 18f, tint), LinearLayout.LayoutParams(dp(28), dp(42)))
        val copy = vertical()
        copy.addView(text(title, 14f, R.color.ink, Typeface.BOLD))
        copy.addView(text(subtitle, 12f, R.color.ink_muted))
        row.addView(copy, weighted())
        row.addView(SwitchMaterial(this).apply {
            isChecked = AppPrefs.eventEnabled(this@MainActivity, type)
            setOnCheckedChangeListener { _, enabled ->
                AppPrefs.setEventEnabled(this@MainActivity, type, enabled)
            }
        })
        return row
    }

    private fun pairPhone() {
        val url = relayUrlInput.text.toString().trim().trimEnd('/')
        val token = tokenInput.text.toString().trim()
        if (!url.startsWith("https://")) {
            toast("Use an HTTPS relay URL")
            return
        }
        if (token.length < 16) {
            toast("Pairing token must be at least 16 characters")
            return
        }
        requestNotificationPermission()
        AppPrefs.saveConnection(this, url, token)
        AppPrefs.setStatus(this, "Pairing")
        pairButton.isEnabled = false
        pairButton.text = "Pairing..."
        renderStatus()
        RelayClient.pair(application as Application) { result ->
            runOnUiThread {
                pairButton.isEnabled = true
                pairButton.text = if (result.ok) "Paired" else "Try pairing again"
                renderStatus()
                renderEvents()
                toast(result.message)
            }
        }
    }

    private fun sendTest() {
        testButton.isEnabled = false
        Thread {
            val result = RelayClient.sendTest(this)
            runOnUiThread {
                testButton.isEnabled = true
                toast(result.message)
            }
        }.start()
    }

    private fun syncNow() {
        Thread {
            val result = runCatching { RelayClient.syncEvents(this) }
            runOnUiThread {
                result.onSuccess {
                    renderEvents()
                    toast(if (it == 0) "Timeline is up to date" else "Recovered $it event(s)")
                }.onFailure { toast(it.message ?: "Sync failed") }
            }
        }.start()
    }

    private fun bindSavedValues() {
        relayUrlInput.setText(AppPrefs.relayUrl(this))
        tokenInput.setText(AppPrefs.token(this))
        testButton.isEnabled = AppPrefs.enabled(this)
    }

    private fun renderStatus() {
        if (!::statusText.isInitialized) return
        statusText.text = AppPrefs.status(this)
        val error = AppPrefs.lastError(this)
        statusDetail.text = when {
            error.isNotBlank() -> error
            AppPrefs.enabled(this) -> "FCM registered; timeline catch-up enabled"
            else -> "Add your relay endpoint to start"
        }
        testButton.isEnabled = AppPrefs.enabled(this)
    }

    private fun renderEvents() {
        if (!::eventsContainer.isInitialized) return
        eventsContainer.removeAllViews()
        val events = EventStore.get(this)
        if (events.isEmpty()) {
            eventsContainer.addView(text(
                "No events yet. Pair the phone, then use Send test.",
                13f,
                R.color.ink_muted
            ).apply { setPadding(0, dp(12), 0, dp(12)) })
            return
        }
        events.take(12).forEachIndexed { index, event ->
            if (index > 0) eventsContainer.addView(divider())
            eventsContainer.addView(eventRow(event))
        }
    }

    private fun eventRow(event: AgentEvent): View {
        val row = horizontal().apply {
            setPadding(0, dp(12), 0, dp(12))
            gravity = Gravity.TOP
        }
        val tint = when (event.type) {
            "approval" -> R.color.approval
            "question" -> R.color.question
            "error" -> R.color.error
            else -> R.color.complete
        }
        row.addView(View(this).apply { setBackgroundColor(color(tint)) }, LinearLayout.LayoutParams(dp(3), dp(54)))
        val copy = vertical().apply { setPadding(dp(12), 0, 0, 0) }
        val meta = listOf(event.provider.uppercase(), event.project).filter { it.isNotBlank() }.joinToString(" · ")
        copy.addView(text(meta, 11f, tint, Typeface.BOLD).apply { letterSpacing = 0.08f })
        copy.addView(text(event.title, 14f, R.color.ink, Typeface.BOLD).apply { setPadding(0, dp(3), 0, 0) })
        copy.addView(text(event.message, 12f, R.color.ink_muted).apply { maxLines = 3 })
        copy.addView(text(
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(event.timestamp)),
            11f,
            R.color.ink_muted
        ).apply { setPadding(0, dp(4), 0, 0) })
        row.addView(copy, weighted())
        return row
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun card() = MaterialCardView(this).apply {
        radius = dp(18).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(color(R.color.surface))
        strokeColor = color(R.color.line)
        strokeWidth = dp(1)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(22) }
    }

    private fun input(hint: String, password: Boolean): TextInputLayout {
        val layout = TextInputLayout(this).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxStrokeColor = color(R.color.line)
            setBoxCornerRadii(dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat())
            if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val input = EditText(this).apply {
            setTextColor(color(R.color.ink))
            setHintTextColor(color(R.color.ink_muted))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        layout.addView(input, matchWidth(54))
        return layout
    }

    private fun outlineButton(label: String) = MaterialButton(
        this,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        text = label
        isAllCaps = false
        strokeColor = android.content.res.ColorStateList.valueOf(color(R.color.line))
        setTextColor(color(R.color.ink))
    }

    private fun sectionLabel(label: String) = text(label, 11f, R.color.ink_muted, Typeface.BOLD).apply {
        letterSpacing = 0.14f
        setPadding(dp(4), 0, 0, dp(8))
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(color(R.color.line))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
        }
    }

    private fun text(value: String, size: Float, colorRes: Int, style: Int = Typeface.NORMAL) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color(colorRes))
            setTypeface(typeface, style)
            setLineSpacing(0f, 1.12f)
        }

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun horizontal() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun space(height: Int) = Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, height) }
    private fun weighted() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    private fun matchWidth(height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun color(res: Int) = ContextCompat.getColor(this, res)
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
