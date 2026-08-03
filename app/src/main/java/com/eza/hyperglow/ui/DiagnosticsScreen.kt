package com.eza.hyperglow.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.eza.hyperglow.BuildConfig
import com.eza.hyperglow.R
import com.eza.hyperglow.diagnostics.DiagnosticCaptureManager
import com.eza.hyperglow.diagnostics.DiagnosticDraftStore
import com.eza.hyperglow.diagnostics.DiagnosticGitHubIssue
import com.eza.hyperglow.diagnostics.DiagnosticLimits
import com.eza.hyperglow.diagnostics.DiagnosticJsonPreviewFormatter
import com.eza.hyperglow.diagnostics.DiagnosticReportCodec
import com.eza.hyperglow.diagnostics.DiagnosticReportEnvelope
import com.eza.hyperglow.diagnostics.DiagnosticReportFactory
import com.eza.hyperglow.diagnostics.DiagnosticReportReceipt
import com.eza.hyperglow.diagnostics.DiagnosticUploadResult
import com.eza.hyperglow.diagnostics.DiagnosticUploader
import com.eza.hyperglow.diagnostics.HyperGlowReportCategory
import com.eza.hyperglow.diagnostics.HyperGlowSetupChecks
import com.eza.hyperglow.diagnostics.HYPERGLOW_DIAGNOSTIC_DATA_POLICY_URL
import com.eza.hyperglow.diagnostics.buildHyperGlowGitHubIssue
import com.eza.hyperglow.diagnostics.utf8Prefix
import com.eza.hyperglow.diagnostics.utf8Size
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private data class SuccessfulDiagnosticReport(
    val receipt: DiagnosticReportReceipt,
    val issue: DiagnosticGitHubIssue,
    val payloadJson: String
)

@Composable
internal fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initialCapture = remember { DiagnosticCaptureManager.activeSession(context) }
    val initialDraft = remember { DiagnosticDraftStore.load(context) }
    var categoryName by rememberSaveable {
        mutableStateOf(
            initialCapture?.category?.name ?: initialDraft?.category
                ?.let(HyperGlowReportCategory::fromWireValue)?.name
                ?: HyperGlowReportCategory.COMPATIBILITY.name
        )
    }
    var description by rememberSaveable {
        mutableStateOf(initialCapture?.description ?: initialDraft?.description.orEmpty())
    }
    var activeCapture by remember { mutableStateOf(initialCapture) }
    var draft by remember { mutableStateOf(initialDraft) }
    var success by remember { mutableStateOf<SuccessfulDiagnosticReport?>(null) }
    var retentionAccepted by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var showPreviewDialog by remember { mutableStateOf(false) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    val category = HyperGlowReportCategory.entries.firstOrNull { it.name == categoryName }
        ?: HyperGlowReportCategory.COMPATIBILITY
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val payload = pendingExportJson
        pendingExportJson = null
        if (uri == null || payload == null) return@rememberLauncherForActivityResult
        val written = runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write(payload)
            } ?: error("Diagnostic JSON file unavailable")
        }.isSuccess
        Toast.makeText(
            context,
            context.getString(
                if (written) R.string.diagnostic_json_saved
                else R.string.diagnostic_json_save_failed
            ),
            Toast.LENGTH_LONG
        ).show()
    }

    BackHandler(onBack = onBack)

    LaunchedEffect(activeCapture?.startedAtElapsedMillis) {
        while (activeCapture != null) {
            delay(1_000L)
            val current = DiagnosticCaptureManager.activeSession(context)
            if (current == null) {
                activeCapture = null
                statusMessage = context.getString(R.string.diagnostic_status_capture_expired)
            }
        }
    }

    fun invalidateDraft() {
        if (draft == null) return
        DiagnosticDraftStore.clear(context)
        draft = null
        retentionAccepted = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.action_report_problem),
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←") }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = innerPadding.calculateBottomPadding() + 20.dp
            )
        ) {
            item { SmallTitle(text = stringResource(R.string.diagnostic_section_problem)) }
            item {
                SettingsCard {
                    ArrowPreference(
                        title = stringResource(R.string.diagnostic_category),
                        summary = categoryLabel(context, category),
                        onClick = { if (activeCapture == null && !busy) showCategoryDialog = true },
                        enabled = activeCapture == null && !busy
                    )
                    DiagnosticDescriptionField(
                        value = description,
                        onValueChange = { next ->
                            if (activeCapture == null && !busy) {
                                description = next.utf8Prefix(DiagnosticLimits.DESCRIPTION_BYTES)
                                invalidateDraft()
                                success = null
                                statusMessage = ""
                            }
                        },
                        enabled = activeCapture == null && !busy
                    )
                }
            }

            if (activeCapture != null) {
                item { SmallTitle(text = stringResource(R.string.diagnostic_guided_capture)) }
                item {
                    SettingsCard {
                        Text(
                            text = stringResource(R.string.diagnostic_capture_active),
                            modifier = Modifier.padding(16.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            TextButton(
                                text = stringResource(R.string.action_cancel),
                                modifier = Modifier.weight(1f),
                                enabled = !busy,
                                onClick = {
                                    if (DiagnosticCaptureManager.cancel(context)) {
                                        activeCapture = null
                                        statusMessage = context.getString(
                                            R.string.diagnostic_status_capture_cancelled
                                        )
                                    } else {
                                        statusMessage = context.getString(
                                            R.string.diagnostic_status_restore_failed
                                        )
                                    }
                                }
                            )
                            Spacer(Modifier.width(16.dp))
                            TextButton(
                                text = if (busy) {
                                    stringResource(R.string.diagnostic_finishing)
                                } else {
                                    stringResource(R.string.diagnostic_finish_capture)
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !busy,
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                onClick = {
                                    busy = true
                                    statusMessage = ""
                                    scope.launch {
                                        val report = try {
                                            DiagnosticCaptureManager.finish(context)?.let {
                                                DiagnosticReportFactory.createCapturedReport(
                                                    context,
                                                    it
                                                )
                                            }
                                        } catch (_: Exception) {
                                            null
                                        }
                                        if (report != null && DiagnosticDraftStore.save(context, report)) {
                                            draft = report
                                            categoryName = requireNotNull(
                                                HyperGlowReportCategory.fromWireValue(report.category)
                                            ).name
                                            description = report.description
                                            statusMessage = context.getString(
                                                R.string.diagnostic_status_capture_ready
                                            )
                                        } else {
                                            statusMessage = context.getString(
                                                R.string.diagnostic_status_prepare_failed
                                            )
                                        }
                                        activeCapture = DiagnosticCaptureManager.activeSession(context)
                                        if (activeCapture != null) {
                                            statusMessage = context.getString(
                                                R.string.diagnostic_status_capture_remains_active
                                            )
                                        }
                                        busy = false
                                    }
                                }
                            )
                        }
                    }
                }
            } else if (draft == null && success == null) {
                item {
                    SmallTitle(
                        text = stringResource(
                            if (category.requiresCapture) R.string.diagnostic_guided_capture
                            else R.string.diagnostic_section_report
                        )
                    )
                }
                item {
                    SettingsCard {
                        if (category.requiresCapture) {
                            Text(
                                text = stringResource(R.string.diagnostic_capture_explanation),
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                        TextButton(
                            text = stringResource(
                                if (category.requiresCapture) R.string.diagnostic_start_capture
                                else R.string.diagnostic_prepare_report
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            enabled = description.isNotBlank() && !busy,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = {
                                statusMessage = ""
                                if (category.requiresCapture) {
                                    val session = DiagnosticCaptureManager.start(
                                        context,
                                        category,
                                        description
                                    )
                                    if (session != null) {
                                        activeCapture = session
                                    } else {
                                        statusMessage = context.getString(
                                            R.string.diagnostic_status_start_failed
                                        )
                                    }
                                } else {
                                    busy = true
                                    scope.launch {
                                        val report = runCatching {
                                            DiagnosticReportFactory.createCompatibilityReport(
                                                context,
                                                category,
                                                description
                                            )
                                        }.getOrNull()
                                        if (report != null &&
                                            DiagnosticDraftStore.save(context, report)
                                        ) {
                                            draft = report
                                            statusMessage = context.getString(
                                                R.string.diagnostic_status_report_ready
                                            )
                                        } else {
                                            statusMessage = context.getString(
                                                R.string.diagnostic_status_prepare_failed
                                            )
                                        }
                                        busy = false
                                    }
                                }
                            }
                        )
                    }
                }
            }

            draft?.let { report ->
                item { SmallTitle(text = stringResource(R.string.diagnostic_section_review)) }
                item {
                    SettingsCard {
                        DiagnosticSetupChecklist(report.productMetadata.setupChecks)
                        BasicDiagnosticSummary(report)
                        ArrowPreference(
                            title = stringResource(R.string.diagnostic_preview_json),
                            onClick = { showPreviewDialog = true },
                            enabled = !busy
                        )
                        ArrowPreference(
                            title = stringResource(R.string.diagnostic_data_policy),
                            onClick = {
                                openExternalUrl(context, HYPERGLOW_DIAGNOSTIC_DATA_POLICY_URL)
                            },
                            enabled = !busy
                        )
                        SwitchPreference(
                            retentionAccepted,
                            { retentionAccepted = it },
                            stringResource(R.string.diagnostic_accept_policy),
                            enabled = !busy
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            TextButton(
                                text = stringResource(R.string.action_discard),
                                modifier = Modifier.weight(1f),
                                enabled = !busy,
                                onClick = {
                                    DiagnosticDraftStore.clear(context)
                                    draft = null
                                    retentionAccepted = false
                                    statusMessage = context.getString(
                                        R.string.diagnostic_status_discarded
                                    )
                                }
                            )
                            Spacer(Modifier.width(16.dp))
                            TextButton(
                                text = if (busy) {
                                    stringResource(R.string.diagnostic_uploading)
                                } else {
                                    stringResource(R.string.diagnostic_upload)
                                },
                                modifier = Modifier.weight(1f),
                                enabled = retentionAccepted && !busy,
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                onClick = {
                                    busy = true
                                    statusMessage = ""
                                    scope.launch {
                                        val currentReport = DiagnosticDraftStore.load(context)
                                        if (currentReport == null ||
                                            currentReport.reportId != report.reportId
                                        ) {
                                            draft = null
                                            busy = false
                                            statusMessage = context.getString(
                                                R.string.diagnostic_status_draft_expired
                                            )
                                            return@launch
                                        }
                                        when (val result = DiagnosticUploader(
                                            BuildConfig.DIAGNOSTIC_INTAKE_URL
                                        ).upload(currentReport)) {
                                            is DiagnosticUploadResult.Success -> {
                                                val issue = buildHyperGlowGitHubIssue(currentReport)
                                                DiagnosticDraftStore.clear(context)
                                                draft = null
                                                success = SuccessfulDiagnosticReport(
                                                    result.receipt,
                                                    issue,
                                                    DiagnosticReportCodec.encodePretty(currentReport)
                                                )
                                                retentionAccepted = false
                                            }
                                            is DiagnosticUploadResult.Failure -> {
                                                statusMessage = uploadFailureMessage(context, result.kind)
                                            }
                                        }
                                        busy = false
                                    }
                                }
                            )
                        }
                    }
                }
            }

            success?.let { completed ->
                item { SmallTitle(text = stringResource(R.string.diagnostic_report_received)) }
                item {
                    SettingsCard {
                        Text(
                            text = stringResource(
                                R.string.diagnostic_report_id,
                                completed.receipt.reportId
                            ),
                            modifier = Modifier.padding(16.dp)
                        )
                        ArrowPreference(
                            title = stringResource(R.string.diagnostic_data_policy),
                            onClick = {
                                openExternalUrl(context, HYPERGLOW_DIAGNOSTIC_DATA_POLICY_URL)
                            }
                        )
                        ArrowPreference(
                            title = stringResource(R.string.diagnostic_view_json),
                            onClick = { showPreviewDialog = true }
                        )
                        ArrowPreference(
                            title = stringResource(R.string.diagnostic_save_json),
                            onClick = {
                                pendingExportJson = completed.payloadJson
                                runCatching {
                                    exportLauncher.launch(
                                        "HyperGlow-${completed.receipt.reportId}.json"
                                    )
                                }.onFailure {
                                    pendingExportJson = null
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.diagnostic_json_save_failed),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        )
                        ArrowPreference(
                            title = stringResource(R.string.action_copy_id),
                            onClick = {
                                context.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(
                                        ClipData.newPlainText(
                                            context.getString(R.string.diagnostic_clip_label),
                                            completed.receipt.reportId
                                        )
                                    )
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.diagnostic_report_id_copied),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                        ArrowPreference(
                            title = stringResource(R.string.action_open_github_issue),
                            onClick = { openGitHubIssue(context, completed.issue) }
                        )
                    }
                }
            }

            if (statusMessage.isNotBlank()) {
                item {
                    Text(
                        text = statusMessage,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                    )
                }
            }
        }
    }

    if (showCategoryDialog) {
        WindowDialog(
            title = stringResource(R.string.diagnostic_category),
            show = true,
            onDismissRequest = { showCategoryDialog = false }
        ) {
            Column {
                HyperGlowReportCategory.entries.forEach { option ->
                    RadioButtonPreference(
                        categoryLabel(context, option),
                        category == option,
                        {
                            categoryName = option.name
                            invalidateDraft()
                            success = null
                            statusMessage = ""
                            showCategoryDialog = false
                        }
                    )
                }
            }
        }
    }

    val previewJson = remember(draft, success) {
        draft?.let { DiagnosticReportCodec.encodePretty(it) } ?: success?.payloadJson
    }
    val readablePreview = remember(previewJson) {
        previewJson?.let(DiagnosticJsonPreviewFormatter::format)
    }
    if (showPreviewDialog && previewJson != null) {
        WindowDialog(
            title = stringResource(R.string.diagnostic_included_json),
            show = true,
            onDismissRequest = { showPreviewDialog = false }
        ) {
            LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                if (readablePreview == null) {
                    item { DiagnosticPreviewText(previewJson) }
                } else {
                    item { DiagnosticPreviewText(readablePreview.reportJson) }
                    item { DiagnosticPreviewHeading("rawDiagnostics") }
                    item {
                        DiagnosticPreviewBlock(
                            "diagnosticEventsAndLogs",
                            readablePreview.diagnosticEventsAndLogs
                        )
                    }
                    item {
                        DiagnosticPreviewBlock("crashExcerpt", readablePreview.crashExcerpt)
                    }
                    item {
                        DiagnosticPreviewBlock(
                            "lsposedModuleLines",
                            readablePreview.lsposedModuleLines
                        )
                    }
                    item {
                        DiagnosticPreviewBlock(
                            "runtimeSettings",
                            readablePreview.runtimeSettingsJson
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticPreviewHeading(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 12.dp, top = 16.dp, end = 12.dp, bottom = 4.dp),
        fontSize = 13.sp
    )
}

@Composable
private fun DiagnosticPreviewBlock(label: String, value: String) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text = label, fontSize = 11.sp)
        Text(
            text = value.ifEmpty { "(empty)" },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .background(
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(10.dp)
                )
                .padding(10.dp),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun DiagnosticPreviewText(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(12.dp),
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace
    )
}

@Composable
private fun DiagnosticDescriptionField(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .heightIn(min = 168.dp)
            .background(
                color = MiuixTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 34.dp)
            .alpha(if (enabled) 1f else 0.56f)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart),
            enabled = enabled,
            textStyle = TextStyle(
                color = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                fontSize = 16.sp
            ),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            singleLine = false,
            minLines = 4,
            maxLines = 10,
            decorationBox = { input ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.diagnostic_description),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 16.sp
                        )
                    }
                    input()
                }
            }
        )
        Text(
            text = stringResource(
                R.string.diagnostic_byte_count,
                value.utf8Size(),
                DiagnosticLimits.DESCRIPTION_BYTES
            ),
            modifier = Modifier.align(Alignment.BottomEnd),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun DiagnosticSetupChecklist(checks: HyperGlowSetupChecks) {
    val stateLabel = stringResource(
        when (checks.setupState) {
            "ready" -> R.string.diagnostic_setup_ready
            "failed" -> R.string.diagnostic_setup_failed
            else -> R.string.diagnostic_setup_warning
        }
    )
    val lines = listOf(
        checklistLine(
            checks.rootAccessStatus == "granted",
            stringResource(R.string.diagnostic_check_root)
        ),
        checklistLine(
            checks.capabilityReportPresent && checks.systemUiHookActive,
            stringResource(R.string.diagnostic_check_systemui)
        ),
        checklistLine(
            checks.profileSupported,
            stringResource(R.string.diagnostic_check_profile)
        ),
        checklistLine(
            checks.spotifyProducerBridgePresent,
            stringResource(R.string.diagnostic_check_spotify_bridge),
            warning = true
        ),
        checklistLine(
            checks.requiredPackagesPresent,
            stringResource(R.string.diagnostic_check_packages)
        )
    )
    Text(
        text = "$stateLabel\n${lines.joinToString("\n")}",
        modifier = Modifier.padding(16.dp),
        fontSize = 13.sp
    )
}

private fun checklistLine(passed: Boolean, label: String, warning: Boolean = false): String =
    "${if (passed) "✓" else if (warning) "!" else "×"} $label"

@Composable
private fun BasicDiagnosticSummary(report: DiagnosticReportEnvelope) {
    val context = LocalContext.current
    Text(
        text = stringResource(
            R.string.diagnostic_summary,
            report.reportId,
            HyperGlowReportCategory.fromWireValue(report.category)?.let {
                categoryLabel(context, it)
            } ?: report.category
        ),
        modifier = Modifier.padding(16.dp),
        fontSize = 13.sp
    )
}

private fun categoryLabel(context: Context, category: HyperGlowReportCategory): String =
    context.getString(
        when (category) {
            HyperGlowReportCategory.COMPATIBILITY -> R.string.diagnostic_category_compatibility
            HyperGlowReportCategory.AOD_SURFACE -> R.string.diagnostic_category_aod
            HyperGlowReportCategory.LOCKSCREEN_SURFACE -> R.string.diagnostic_category_lockscreen
            HyperGlowReportCategory.PLAYBACK_BRIDGE -> R.string.diagnostic_category_spotify_bridge
            HyperGlowReportCategory.SYSTEM_UI_FAILURE -> R.string.diagnostic_category_systemui
            HyperGlowReportCategory.CONFIGURATION -> R.string.diagnostic_category_configuration
            HyperGlowReportCategory.OTHER -> R.string.diagnostic_category_other
        }
    )

private fun profileStateLabel(context: Context, value: String): String = context.getString(
    when (value) {
        "verified_profile" -> R.string.status_verified_profile
        "verified_profile_missing_symbols" -> R.string.status_verified_profile_missing_symbols
        "experimental_eligible" -> R.string.status_experimental_eligible
        "experimental_active" -> R.string.status_experimental_active
        else -> R.string.status_unsupported_profile
    }
)

private fun uploadFailureMessage(
    context: Context,
    kind: DiagnosticUploadResult.Failure.Kind
): String = context.getString(when (kind) {
    DiagnosticUploadResult.Failure.Kind.INVALID_REPORT -> R.string.diagnostic_failure_invalid
    DiagnosticUploadResult.Failure.Kind.REPORT_ID_COLLISION ->
        R.string.diagnostic_failure_collision
    DiagnosticUploadResult.Failure.Kind.REQUEST_TOO_LARGE -> R.string.diagnostic_failure_too_large
    DiagnosticUploadResult.Failure.Kind.RATE_LIMITED ->
        R.string.diagnostic_failure_rate_limited
    DiagnosticUploadResult.Failure.Kind.STORAGE_UNAVAILABLE ->
        R.string.diagnostic_failure_storage
    DiagnosticUploadResult.Failure.Kind.SERVER_ERROR ->
        R.string.diagnostic_failure_server
    DiagnosticUploadResult.Failure.Kind.REDIRECT_REJECTED ->
        R.string.diagnostic_failure_redirect
    DiagnosticUploadResult.Failure.Kind.TIMEOUT -> R.string.diagnostic_failure_timeout
    DiagnosticUploadResult.Failure.Kind.NETWORK -> R.string.diagnostic_failure_network
    DiagnosticUploadResult.Failure.Kind.INVALID_RESPONSE ->
        R.string.diagnostic_failure_receipt
})

private fun openGitHubIssue(context: Context, issue: DiagnosticGitHubIssue) {
    val uri = Uri.Builder()
        .scheme("https")
        .authority("github.com")
        .appendPath("amarinne")
        .appendPath("hyperglow")
        .appendPath("issues")
        .appendPath("new")
        .appendQueryParameter("title", issue.title)
        .appendQueryParameter("body", issue.body)
        .build()
    val opened = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }.isSuccess
    if (!opened) {
        Toast.makeText(
            context,
            context.getString(R.string.toast_no_github_handler),
            Toast.LENGTH_LONG
        ).show()
    }
}

private fun openExternalUrl(context: Context, url: String) {
    val opened = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.isSuccess
    if (!opened) {
        Toast.makeText(
            context,
            context.getString(R.string.toast_no_link_handler),
            Toast.LENGTH_LONG
        ).show()
    }
}
