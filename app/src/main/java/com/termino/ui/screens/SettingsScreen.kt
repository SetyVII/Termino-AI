package com.termino.ui.screens

import com.termino.core.ai.ProviderModel
import com.termino.core.ai.ReasoningEffort
import com.termino.core.reader.ReaderTheme
import android.app.LocaleConfig
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.termino.BuildConfig
import com.termino.R
import com.termino.TerminoApp
import com.termino.data.model.AiLanguage
import com.termino.data.prefs.AppSettings
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val testState by viewModel.testState.collectAsStateWithLifecycle()
    val modelsState by viewModel.models.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val savedMessage = stringResource(R.string.settings_saved)

    var showConnectSheet by rememberSaveable { mutableStateOf(false) }
    var showLanguageSheet by rememberSaveable { mutableStateOf(false) }
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(Modifier.fillMaxSize()) {
            SettingsTopBar(onBack = onBack)
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val contentWidth = minOf(maxWidth, 780.dp)
                LazyColumn(
                    modifier = Modifier
                        .width(contentWidth)
                        .fillMaxHeight()
                        .align(Alignment.TopCenter)
                        .imePadding(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item(key = "connection") {
                        ConnectionCard(
                            settings = settings,
                            modelCount = modelsState.models.size,
                            onConnect = { showConnectSheet = true },
                            onForget = { confirmForget = true }
                        )
                    }
                    item(key = "answers") {
                        AnswersCard(
                            settings = settings,
                            onPickLanguage = { showLanguageSheet = true },
                            onReasoning = viewModel::setReasoning,
                            onContextSize = viewModel::setContextSize
                        )
                    }
                    item(key = "reading") {
                        ReadingCard(settings = settings, viewModel = viewModel)
                    }
                    item(key = "general") {
                        GeneralCard(
                            appTheme = settings.appTheme,
                            onAppTheme = viewModel::setAppTheme,
                            onRedoOnboarding = viewModel::showOnboardingAgain
                        )
                    }
                    item(key = "advanced") {
                        AdvancedCard(
                            open = advancedOpen,
                            onToggle = { advancedOpen = !advancedOpen },
                            settings = settings,
                            modelsState = modelsState,
                            testState = testState,
                            onReload = viewModel::refreshModels,
                            onTest = viewModel::testConnection,
                            onSave = { draft ->
                                viewModel.saveAdvanced(draft)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        savedMessage,
                                        withDismissAction = true,
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            }
                        )
                    }
                    item(key = "about") { AboutCard() }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        )
    }

    if (showConnectSheet) {
        ConnectSheet(
            onDismiss = { showConnectSheet = false },
            onConnected = { baseUrl, apiKey ->
                showConnectSheet = false
                viewModel.clearTestState()
                viewModel.refreshModels(baseUrl, apiKey)
            }
        )
    }
    if (showLanguageSheet) {
        LanguageSheet(
            selected = settings.aiLanguage,
            onDismiss = { showLanguageSheet = false },
            onSelect = {
                viewModel.setAiLanguage(it)
                showLanguageSheet = false
            }
        )
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.settings_forget_title)) },
            text = { Text(stringResource(R.string.settings_forget_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.forgetConnection()
                    confirmForget = false
                }) {
                    Text(
                        stringResource(R.string.settings_forget_connection),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(60.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.action_back)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.settings_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    settings: AppSettings,
    modelCount: Int,
    onConnect: () -> Unit,
    onForget: () -> Unit
) {
    SettingsCard(
        icon = Icons.Outlined.Key,
        title = stringResource(R.string.settings_connection),
        subtitle = stringResource(R.string.settings_connection_sub)
    ) {
        if (!settings.isConfigured) {
            Text(
                stringResource(R.string.settings_not_connected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onConnect, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_connect))
            }
            return@SettingsCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    settings.providerLabel.ifBlank { settings.baseUrl },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (settings.apiKey.isBlank()) settings.baseUrl else settings.maskedKey,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        ModelSummary(stringResource(R.string.onb_ready_chat), settings.chatModel)
        ModelSummary(stringResource(R.string.onb_ready_fast), settings.fastModel.ifBlank { settings.chatModel })
        if (modelCount > 0) {
            Text(
                stringResource(R.string.onb_ready_models, modelCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onConnect, shape = RoundedCornerShape(14.dp)) {
                Text(stringResource(R.string.settings_change_connection))
            }
            TextButton(onClick = onForget) {
                Text(stringResource(R.string.settings_forget_connection), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ModelSummary(label: String, model: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(model, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

@Composable
private fun ConnectSheet(
    onDismiss: () -> Unit,
    onConnected: (baseUrl: String, apiKey: String) -> Unit
) {
    val app = LocalContext.current.applicationContext as TerminoApp
    val connectionViewModel: ConnectionViewModel = viewModel(
        key = "settings-connection",
        factory = ConnectionViewModel.factory(app)
    )
    val state by connectionViewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) { connectionViewModel.reset() }
    LaunchedEffect(state.saved) {
        val result = state.result
        if (state.saved && result != null) {
            kotlinx.coroutines.delay(500)
            sheetState.hide()
            onConnected(result.baseUrl, result.apiKey)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .imePadding()
        ) {
            Text(
                stringResource(R.string.onb_connect_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.onb_connect_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(18.dp))
            ConnectionForm(viewModel = connectionViewModel, state = state)
        }
    }
}

@Composable
private fun AnswersCard(
    settings: AppSettings,
    onPickLanguage: () -> Unit,
    onReasoning: (ReasoningEffort) -> Unit,
    onContextSize: (com.termino.data.model.ContextSize) -> Unit
) {
    SettingsCard(
        icon = Icons.Outlined.Translate,
        title = stringResource(R.string.settings_answers),
        subtitle = stringResource(R.string.settings_answers_sub)
    ) {
        ClickableRow(
            label = stringResource(R.string.settings_ai_language),
            value = settings.aiLanguage.nativeName ?: stringResource(R.string.settings_ai_language_auto),
            onClick = onPickLanguage
        )
        Spacer(Modifier.height(14.dp))
        SectionLabel(stringResource(R.string.settings_reasoning))
        ChipRow(
            options = ReasoningEffort.entries,
            selected = settings.reasoningEffort,
            label = { it.label },
            onSelect = onReasoning
        )
        Spacer(Modifier.height(6.dp))
        HintText(stringResource(R.string.settings_reasoning_hint))
        Spacer(Modifier.height(14.dp))
        SectionLabel(stringResource(R.string.settings_context_size))
        val contextLabels = listOf(
            stringResource(R.string.context_auto),
            stringResource(R.string.context_compact),
            stringResource(R.string.context_balanced),
            stringResource(R.string.context_wide)
        )
        ChipRow(
            options = com.termino.data.model.ContextSize.entries,
            selected = settings.contextSize,
            label = { contextLabels[it.ordinal] },
            onSelect = onContextSize
        )
        Spacer(Modifier.height(6.dp))
        HintText(stringResource(R.string.settings_context_size_hint))
    }
}

@Composable
private fun ReadingCard(settings: AppSettings, viewModel: SettingsViewModel) {
    // Los sliders guardan al soltar: durante el arrastre solo cambia el estado local.
    var fontSize by remember(settings.fontSize) { mutableIntStateOf(settings.fontSize) }
    var lineHeight by remember(settings.lineHeight) { mutableFloatStateOf(settings.lineHeight) }

    SettingsCard(
        icon = Icons.Outlined.MenuBook,
        title = stringResource(R.string.settings_reading),
        subtitle = stringResource(R.string.settings_reading_sub)
    ) {
        SectionLabel(stringResource(R.string.settings_theme))
        ChipRow(
            options = ReaderTheme.entries,
            selected = settings.readerTheme,
            label = { it.label },
            leading = { theme ->
                com.termino.ui.components.ThemeSwatch(theme = theme, size = 12.dp)
            },
            onSelect = { viewModel.setTheme(it) }
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.settings_font_size), Modifier.weight(1f))
            Text("$fontSize px", style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = fontSize.toFloat(),
            onValueChange = { fontSize = it.toInt() },
            onValueChangeFinished = { viewModel.setFontSize(fontSize) },
            valueRange = com.termino.core.reader.MIN_FONT_SIZE.toFloat()..com.termino.core.reader.MAX_FONT_SIZE.toFloat(),
            steps = com.termino.core.reader.MAX_FONT_SIZE - com.termino.core.reader.MIN_FONT_SIZE - 1
        )
        SectionLabel(
            stringResource(R.string.settings_line_height, String.format(Locale.US, "%.2f", lineHeight))
        )
        Slider(
            value = lineHeight,
            onValueChange = { lineHeight = it },
            onValueChangeFinished = { viewModel.setLineHeight(lineHeight) },
            valueRange = 1.2f..2.2f
        )
        SectionLabel(stringResource(R.string.settings_font_family))
        val fonts by viewModel.fonts.collectAsStateWithLifecycle()
        com.termino.ui.components.FontPickerRow(
            fonts = fonts,
            selected = settings.readerFont,
            accent = MaterialTheme.colorScheme.primary,
            foreground = MaterialTheme.colorScheme.onSurface,
            muted = MaterialTheme.colorScheme.onSurfaceVariant,
            typefaceFor = viewModel::typeface,
            onSelect = { viewModel.setFont(it) },
            onImport = viewModel::importFont,
            onDelete = viewModel::deleteFont
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel(stringResource(R.string.settings_eye_care))
                HintText(stringResource(R.string.settings_eye_care_sub))
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = settings.eyeCare, onCheckedChange = { viewModel.setEyeCare(it) })
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SwitchRow(
            title = stringResource(R.string.settings_tap_zones),
            subtitle = stringResource(R.string.settings_tap_zones_sub),
            checked = settings.tapZones,
            onChange = { viewModel.setTapZones(it) }
        )
        SwitchRow(
            title = stringResource(R.string.settings_volume_keys),
            checked = settings.volumeKeysTurnPages,
            onChange = { viewModel.setVolumeKeys(it) }
        )
        SwitchRow(
            title = stringResource(R.string.settings_keep_screen_on),
            checked = settings.keepScreenOn,
            onChange = { viewModel.setKeepScreenOn(it) }
        )
        SwitchRow(
            title = stringResource(R.string.settings_pdf_pages),
            subtitle = stringResource(R.string.settings_pdf_pages_sub),
            checked = settings.pdfOriginalPages,
            onChange = { viewModel.setPdfPages(it) }
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) HintText(subtitle)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun GeneralCard(
    appTheme: com.termino.data.model.AppThemeMode,
    onAppTheme: (com.termino.data.model.AppThemeMode) -> Unit,
    onRedoOnboarding: () -> Unit
) {
    val context = LocalContext.current
    var showAppLanguages by rememberSaveable { mutableStateOf(false) }
    val appThemeLabels = listOf(
        stringResource(R.string.settings_app_theme_system),
        stringResource(R.string.settings_app_theme_light),
        stringResource(R.string.settings_app_theme_dark)
    )
    SettingsCard(
        icon = Icons.Outlined.Language,
        title = stringResource(R.string.settings_app_language),
        subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentAppLanguageLabel(context) ?: stringResource(R.string.settings_app_language_system)
        } else {
            stringResource(R.string.settings_app_language_legacy)
        },
        action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            {
                IconButton(onClick = { showAppLanguages = true }) {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = stringResource(R.string.settings_app_language))
                }
            }
        } else {
            null
        }
    ) {
        SectionLabel(stringResource(R.string.settings_app_theme))
        ChipRow(
            options = com.termino.data.model.AppThemeMode.entries,
            selected = appTheme,
            label = { mode ->
                when (mode) {
                    com.termino.data.model.AppThemeMode.SYSTEM -> appThemeLabels[0]
                    com.termino.data.model.AppThemeMode.LIGHT -> appThemeLabels[1]
                    com.termino.data.model.AppThemeMode.DARK -> appThemeLabels[2]
                }
            },
            onSelect = onAppTheme
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onRedoOnboarding) {
            Text(stringResource(R.string.settings_redo_onboarding))
        }
    }
    if (showAppLanguages && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AppLanguageSheet(onDismiss = { showAppLanguages = false })
    }
}

private fun currentAppLanguageLabel(context: android.content.Context): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return null
    if (locales.isEmpty) return null
    val locale = locales[0]
    return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
}

/**
 * Idiomas de la app leídos del locales_config generado en compilación a partir
 * de las carpetas values-*: no hay lista escrita a mano.
 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AppLanguageSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(LocaleManager::class.java) }
    val supported = remember {
        val list = LocaleConfig(context).supportedLocales ?: LocaleList.getEmptyLocaleList()
        (0 until list.size()).map { list[it] }
    }
    val current = manager?.applicationLocales?.takeIf { !it.isEmpty }?.get(0)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            LanguageOption(
                label = stringResource(R.string.settings_app_language_system),
                selected = current == null,
                onClick = {
                    manager?.applicationLocales = LocaleList.getEmptyLocaleList()
                    onDismiss()
                }
            )
            supported.forEach { locale ->
                LanguageOption(
                    label = locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) },
                    selected = current?.language == locale.language,
                    onClick = {
                        manager?.applicationLocales = LocaleList(locale)
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun LanguageSheet(
    selected: AiLanguage,
    onDismiss: () -> Unit,
    onSelect: (AiLanguage) -> Unit
) {
    val options = remember { AiLanguage.options() }
    var query by rememberSaveable { mutableStateOf("") }
    val autoLabel = stringResource(R.string.settings_ai_language_auto)
    val filtered = remember(query, options) {
        val q = query.trim().lowercase()
        if (q.isBlank()) {
            options
        } else {
            options.filter { lang ->
                val native = lang.nativeName.orEmpty().lowercase()
                val local = lang.locale?.displayName.orEmpty().lowercase()
                native.contains(q) || local.contains(q) || lang.tag.lowercase().startsWith(q)
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                placeholder = { Text(stringResource(R.string.settings_search_language)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .imePadding(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filtered, key = { it.tag }) { language ->
                    LanguageOption(
                        label = language.nativeName ?: autoLabel,
                        secondary = language.locale?.displayName?.takeIf { it != language.nativeName },
                        selected = language.tag == selected.tag,
                        onClick = { onSelect(language) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    secondary: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (secondary != null) {
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AnimatedVisibility(visible = selected, enter = fadeIn(), exit = fadeOut()) {
            Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedCard(
    open: Boolean,
    onToggle: () -> Unit,
    settings: AppSettings,
    modelsState: ProviderModelsState,
    testState: ConnectionTestState,
    onReload: (String, String) -> Unit,
    onTest: (AppSettings) -> Unit,
    onSave: (AppSettings) -> Unit
) {
    var baseUrl by rememberSaveable(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var apiKey by rememberSaveable(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var fastModel by rememberSaveable(settings.fastModel) { mutableStateOf(settings.fastModel) }
    var chatModel by rememberSaveable(settings.chatModel) { mutableStateOf(settings.chatModel) }
    var temperature by rememberSaveable(settings.temperature) { mutableFloatStateOf(settings.temperature) }
    var maxTokens by rememberSaveable(settings.maxTokens) { mutableIntStateOf(settings.maxTokens) }
    var showKey by remember { mutableStateOf(false) }

    fun draft() = settings.copy(
        baseUrl = baseUrl,
        apiKey = apiKey,
        fastModel = fastModel,
        chatModel = chatModel,
        temperature = temperature,
        maxTokens = maxTokens
    )

    val chevron by animateFloatAsState(
        if (open) 180f else 0f,
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "advanced-chevron"
    )
    SettingsCard(
        icon = Icons.Outlined.Tune,
        title = stringResource(R.string.settings_advanced),
        subtitle = stringResource(R.string.settings_advanced_sub),
        onHeaderClick = onToggle,
        showDivider = open,
        action = {
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.rotate(chevron),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    ) {
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeOut()
        ) {
            Column {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_base_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_api_key)) },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(if (showKey) R.string.hide_key else R.string.show_key)
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(stringResource(R.string.settings_models_from_provider), Modifier.weight(1f))
                    IconButton(onClick = { onReload(baseUrl, apiKey) }) {
                        Icon(Icons.Outlined.Refresh, stringResource(R.string.settings_reload_models))
                    }
                }
                AnimatedVisibility(visible = modelsState.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                }
                OutlinedTextField(
                    value = chatModel,
                    onValueChange = { chatModel = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_chat_model)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                ModelChips(modelsState, chatModel) { chatModel = it }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = fastModel,
                    onValueChange = { fastModel = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_fast_model)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                ModelChips(modelsState, fastModel) { fastModel = it }
                Spacer(Modifier.height(14.dp))
                SectionLabel(
                    stringResource(R.string.settings_temperature, String.format(Locale.US, "%.2f", temperature))
                )
                Slider(value = temperature, onValueChange = { temperature = it }, valueRange = 0f..1f)
                SectionLabel(stringResource(R.string.settings_max_tokens, maxTokens))
                Slider(
                    value = maxTokens.toFloat(),
                    onValueChange = { maxTokens = (it / 64).toInt() * 64 },
                    valueRange = 256f..8192f
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onTest(draft()) },
                        modifier = Modifier.weight(1f),
                        enabled = !testState.running,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(if (testState.running) R.string.action_testing else R.string.action_test))
                    }
                    Button(
                        onClick = { onSave(draft()) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                }
                AnimatedVisibility(
                    visible = testState.message != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    val ok = testState.success == true
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = if (ok) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        }
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                                null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(testState.message.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                SectionLabel(stringResource(R.string.settings_rag))
                HintText(stringResource(R.string.settings_rag_body))
            }
        }
    }
}

/** Chips con los modelos del proveedor; el icono de razonamiento sale de sus metadatos. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelChips(
    state: ProviderModelsState,
    selected: String,
    onSelect: (String) -> Unit
) {
    if (state.loaded && state.models.isEmpty()) {
        Spacer(Modifier.height(6.dp))
        HintText(stringResource(R.string.settings_models_empty))
        return
    }
    if (state.models.isEmpty()) return
    val chat = com.termino.core.ai.ProviderVerifier.chatCapable(state.models)
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) chat else chat.take(8)
    Spacer(Modifier.height(6.dp))
    FlowRow(
        modifier = Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        shown.forEach { model: ProviderModel ->
            FilterChip(
                selected = selected.trim() == model.id,
                onClick = { onSelect(model.id) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.id, maxLines = 1)
                        if (model.supportsReasoning == true) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Outlined.Psychology,
                                contentDescription = stringResource(R.string.settings_reasoning_model),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
        if (chat.size > 8) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "−" else "+${chat.size - 8}")
            }
        }
    }
}

@Composable
private fun AboutCard() {
    SettingsCard(
        icon = Icons.Outlined.Info,
        title = stringResource(R.string.settings_about),
        subtitle = stringResource(R.string.settings_about_sub)
    ) {
        Text(
            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.settings_about_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        HintText(stringResource(R.string.settings_about_privacy))
        Spacer(Modifier.height(6.dp))
        HintText(stringResource(R.string.settings_about_based_on))
        Spacer(Modifier.height(8.dp))
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        val sourceUrl = stringResource(R.string.source_code_url)
        var showLicenses by rememberSaveable { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { runCatching { uriHandler.openUri(sourceUrl) } }, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.settings_source_code))
            }
            TextButton(onClick = { showLicenses = true }) {
                Text(stringResource(R.string.settings_licenses))
            }
        }
        if (showLicenses) {
            AlertDialog(
                onDismissRequest = { showLicenses = false },
                title = { Text(stringResource(R.string.settings_licenses)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.settings_licenses_intro), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        OPEN_SOURCE_COMPONENTS.forEach { (name, license) ->
                            Row(Modifier.padding(vertical = 4.dp)) {
                                Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(8.dp))
                                Text(license, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLicenses = false }) { Text(stringResource(R.string.action_close)) }
                }
            )
        }
    }
}

/** Componentes incluidos en el APK y su licencia (detalle en NOTICE.md). */
private val OPEN_SOURCE_COMPONENTS = listOf(
    "ReadAny (codedogQBY) — proyecto base" to "GPL-3.0",
    "Kotlin · kotlinx.coroutines" to "Apache-2.0",
    "AndroidX · Jetpack Compose · Material 3" to "Apache-2.0",
    "Room · WorkManager · DataStore" to "Apache-2.0",
    "Material Components for Android" to "Apache-2.0",
    "OkHttp · Okio" to "Apache-2.0",
    "jsoup" to "MIT",
    "PdfBox-Android (Tom Roush)" to "Apache-2.0"
)

@Composable
private fun SettingsCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    action: (@Composable () -> Unit)? = null,
    onHeaderClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onHeaderClick != null) Modifier.clickable(onClick = onHeaderClick) else Modifier)
                    .padding(start = 18.dp, end = 12.dp, top = 16.dp, bottom = if (showDivider) 12.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                action?.invoke()
            }
            if (showDivider) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
            Column(Modifier.padding(start = 18.dp, end = 18.dp, top = if (showDivider) 14.dp else 0.dp, bottom = 16.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun ClickableRow(label: String, value: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun HintText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    leading: (@Composable (T) -> Unit)? = null
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
                leadingIcon = leading?.let { { it(option) } },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}
