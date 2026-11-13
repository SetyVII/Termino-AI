package com.termino.ui.screens

import com.termino.core.ai.ProviderEndpoint
import com.termino.core.ai.VerifyFailure
import com.termino.core.ai.VerifyStage
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termino.R
import kotlinx.coroutines.delay

private enum class OnboardingStep { WELCOME, CONNECT, READY }

/**
 * Bienvenida al abrir la app por primera vez. A priori solo pide una API Key
 * o una URL; lo demás (modelos, razonamiento, idioma) se deduce del proveedor
 * y queda configurable en "Avanzado" o en Ajustes.
 */
@Composable
fun OnboardingScreen(
    viewModel: ConnectionViewModel,
    onFinished: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var stepName by rememberSaveable { mutableStateOf(OnboardingStep.WELCOME.name) }
    val step = OnboardingStep.valueOf(stepName)
    androidx.activity.compose.BackHandler(enabled = step == OnboardingStep.CONNECT && !state.running) {
        stepName = OnboardingStep.WELCOME.name
    }

    LaunchedEffect(state.saved) {
        if (state.saved && step == OnboardingStep.CONNECT) {
            delay(650) // deja ver el check de "verificado" antes de avanzar
            stepName = OnboardingStep.READY.name
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            StepHeader(
                step = step,
                onBack = if (step == OnboardingStep.CONNECT) {
                    { stepName = OnboardingStep.WELCOME.name }
                } else {
                    null
                }
            )
            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f),
                transitionSpec = { stepTransition(initialState, targetState) },
                label = "onboarding-step"
            ) { current ->
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        when (current) {
                            OnboardingStep.WELCOME -> WelcomeStep(
                                onStart = { stepName = OnboardingStep.CONNECT.name }
                            )
                            OnboardingStep.CONNECT -> {
                                Text(
                                    text = stringResource(R.string.onb_connect_title),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontFamily = FontFamily.Serif,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.onb_connect_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(20.dp))
                                ConnectionForm(viewModel = viewModel, state = state)
                                Spacer(Modifier.height(8.dp))
                                TextButton(
                                    onClick = {
                                        viewModel.skipOnboarding()
                                        onFinished()
                                    },
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                ) {
                                    Text(stringResource(R.string.action_skip))
                                }
                            }
                            OnboardingStep.READY -> ReadyStep(
                                state = state,
                                onChooseModels = viewModel::chooseModels,
                                onFinish = onFinished
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun stepTransition(from: OnboardingStep, to: OnboardingStep): ContentTransform {
    val forward = to.ordinal > from.ordinal
    val sign = if (forward) 1 else -1
    val slide = spring<androidx.compose.ui.unit.IntOffset>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
    return (slideInHorizontally(slide) { it / 4 * sign } + fadeIn(tween(260, delayMillis = 60)))
        .togetherWith(slideOutHorizontally(slide) { -it / 4 * sign } + fadeOut(tween(160)))
}

@Composable
private fun StepHeader(step: OnboardingStep, onBack: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedVisibility(
                visible = onBack != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                IconButton(onClick = { onBack?.invoke() }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.action_back)
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        PageDots(current = step.ordinal, count = OnboardingStep.entries.size)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun PageDots(current: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val selected = index == current
            val width by animateDpAsState(
                targetValue = if (selected) 22.dp else 7.dp,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
                label = "dot-width"
            )
            val color by animateColorAsState(
                targetValue = if (index <= current) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                label = "dot-color"
            )
            Box(
                Modifier
                    .height(7.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
private fun ColumnScope.WelcomeStep(onStart: () -> Unit) {
    val logoScale = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) {
        logoScale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow))
    }
    Spacer(Modifier.height(24.dp))
    Box(
        modifier = Modifier
            .size(88.dp)
            .graphicsLayer {
                scaleX = logoScale.value
                scaleY = logoScale.value
            }
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.MenuBook,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp)
        )
    }
    Spacer(Modifier.height(28.dp))
    StaggeredItem(index = 0) {
        Text(
            text = stringResource(R.string.onb_welcome_title),
            style = MaterialTheme.typography.headlineLarge,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
    Spacer(Modifier.height(12.dp))
    StaggeredItem(index = 1) {
        Text(
            text = stringResource(R.string.onb_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(28.dp))
    val features = listOf(
        Icons.Outlined.PhoneAndroid to R.string.onb_feature_native,
        Icons.Outlined.Lock to R.string.onb_feature_private,
        Icons.Outlined.Key to R.string.onb_feature_byok
    )
    features.forEachIndexed { index, (icon, text) ->
        StaggeredItem(index = index + 2) {
            FeatureLine(icon = icon, text = stringResource(text))
        }
        Spacer(Modifier.height(10.dp))
    }
    Spacer(Modifier.height(28.dp))
    StaggeredItem(index = 5) {
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(stringResource(R.string.onb_start), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Entrada escalonada: cada elemento sube y aparece 70 ms después del anterior. */
@Composable
private fun StaggeredItem(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(120L + index * 70L)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(320)) + slideInVertically(
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
        ) { it / 3 }
    ) {
        content()
    }
}

@Composable
private fun FeatureLine(icon: ImageVector, text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * Formulario de conexión compartido por el onboarding y Ajustes: un campo
 * (clave o URL), selector de proveedor solo si la clave es ambigua, opciones
 * avanzadas plegadas y progreso real de la verificación.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionForm(
    viewModel: ConnectionViewModel,
    state: ConnectionUiState,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var showSecret by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val density = LocalDensity.current

    LaunchedEffect(state.failure) {
        if (state.failure != null) {
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
            for (offset in listOf(14f, -12f, 9f, -6f, 3f, 0f)) {
                shake.animateTo(offset, tween(45))
            }
        }
    }
    LaunchedEffect(state.result) {
        if (state.result != null) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.input,
            onValueChange = viewModel::setInput,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = with(density) { shake.value.dp.toPx() } },
            label = { Text(stringResource(R.string.onb_input_label)) },
            placeholder = { Text(stringResource(R.string.onb_input_placeholder), maxLines = 1) },
            singleLine = true,
            enabled = !state.running,
            isError = state.failure != null,
            visualTransformation = if (showSecret || state.isUrl) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (state.isUrl) KeyboardType.Uri else KeyboardType.Password,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false
            ),
            keyboardActions = KeyboardActions(onDone = { viewModel.verify() }),
            trailingIcon = {
                if (!state.isUrl && state.input.isNotEmpty()) {
                    IconButton(onClick = { showSecret = !showSecret }) {
                        Icon(
                            if (showSecret) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = stringResource(
                                if (showSecret) R.string.hide_key else R.string.show_key
                            )
                        )
                    }
                }
            },
            shape = RoundedCornerShape(14.dp)
        )

        // Proveedor: solo se pregunta si la clave no se puede atribuir por su formato.
        AnimatedContent(
            targetState = when {
                state.input.isBlank() -> 0
                state.isUrl -> 1
                else -> 2
            },
            transitionSpec = {
                (fadeIn(tween(200)) + expandVertically()) togetherWith (fadeOut(tween(120)) + shrinkVertically())
            },
            label = "provider-mode"
        ) { mode ->
            Column(Modifier.fillMaxWidth()) {
                when (mode) {
                    1 -> {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.onb_url_mode),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = state.apiKeyForUrl,
                            onValueChange = viewModel::setApiKeyForUrl,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.onb_url_key_label)) },
                            singleLine = true,
                            enabled = !state.running,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                    2 -> {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = if (state.providerSuggested && state.provider != null) {
                                stringResource(R.string.onb_provider_detected, state.provider!!.label)
                            } else {
                                stringResource(R.string.onb_provider_question)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ProviderEndpoint.known.forEach { endpoint ->
                                FilterChip(
                                    selected = state.providerId == endpoint.id,
                                    onClick = { viewModel.selectProvider(endpoint.id) },
                                    enabled = !state.running,
                                    label = { Text(endpoint.label) },
                                    leadingIcon = if (state.providerId == endpoint.id) {
                                        { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }
                                    } else {
                                        null
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        val chevron by animateFloatAsStateCompat(if (state.showAdvanced) 180f else 0f)
        TextButton(onClick = viewModel::toggleAdvanced, enabled = !state.running) {
            Text(stringResource(R.string.onb_advanced))
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp).rotate(chevron))
        }
        AnimatedVisibility(
            visible = state.showAdvanced,
            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium)) + fadeOut()
        ) {
            Column {
                Text(
                    stringResource(R.string.onb_advanced_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.manualChatModel,
                    onValueChange = viewModel::setManualChat,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.onb_manual_chat)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.manualFastModel,
                    onValueChange = viewModel::setManualFast,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.onb_manual_fast)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        AnimatedVisibility(
            visible = state.stage != null || state.failure != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            VerifyStatus(state)
        }

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { viewModel.verify() },
            enabled = state.canVerify && state.result == null,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            AnimatedContent(
                targetState = state.running,
                transitionSpec = {
                    (fadeIn() + scaleIn(initialScale = 0.85f)) togetherWith fadeOut()
                },
                label = "verify-button"
            ) { running ->
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (state.failure != null) R.string.action_retry else R.string.action_verify
                            ),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.Lock,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.onb_key_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun animateFloatAsStateCompat(target: Float): androidx.compose.runtime.State<Float> =
    androidx.compose.animation.core.animateFloatAsState(
        targetValue = target,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "chevron"
    )

@Composable
private fun VerifyStatus(state: ConnectionUiState) {
    val failure = state.failure
    val container by animateColorAsState(
        if (failure != null) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        label = "status-bg"
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = container
    ) {
        Column(Modifier.padding(14.dp)) {
            if (failure != null) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            stringResource(failure.messageRes()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        state.failureDetail?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            } else {
                val stages = listOf(
                    VerifyStage.CONNECTING to R.string.stage_connecting,
                    VerifyStage.LISTING_MODELS to R.string.stage_listing,
                    VerifyStage.TESTING_MODEL to R.string.stage_testing,
                    VerifyStage.DONE to R.string.stage_done
                )
                val current = state.stage?.ordinal ?: -1
                stages.forEach { (stage, label) ->
                    AnimatedVisibility(
                        visible = stage.ordinal <= current,
                        enter = fadeIn(tween(220)) + expandVertically() + slideInVertically { -it / 2 }
                    ) {
                        StageRow(
                            label = stringResource(label),
                            done = stage.ordinal < current || state.stage == VerifyStage.DONE
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StageRow(label: String, done: Boolean) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = done,
                transitionSpec = {
                    scaleIn(spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)) togetherWith fadeOut()
                },
                label = "stage-icon"
            ) { finished ->
                if (finished) {
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.Check,
                            null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                } else {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

fun VerifyFailure.messageRes(): Int = when (this) {
    VerifyFailure.MISSING_INPUT -> R.string.fail_missing_input
    VerifyFailure.MISSING_PROVIDER -> R.string.fail_missing_provider
    VerifyFailure.BAD_KEY -> R.string.fail_bad_key
    VerifyFailure.UNREACHABLE -> R.string.fail_unreachable
    VerifyFailure.NO_MODELS -> R.string.fail_no_models
    VerifyFailure.MODEL_REJECTED -> R.string.fail_model_rejected
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.ReadyStep(
    state: ConnectionUiState,
    onChooseModels: (fast: String, chat: String) -> Unit,
    onFinish: () -> Unit
) {
    val connection = state.result
    val scale = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
    }
    Spacer(Modifier.height(32.dp))
    Box(
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .size(96.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                rotationZ = (1f - scale.value) * -45f
            }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(52.dp))
    }
    Spacer(Modifier.height(24.dp))
    StaggeredItem(0) {
        Text(
            stringResource(R.string.onb_ready_title),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
    Spacer(Modifier.height(8.dp))
    if (connection != null) {
        StaggeredItem(1) {
            Text(
                stringResource(R.string.onb_ready_body, connection.providerLabel),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(20.dp))
        StaggeredItem(2) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    ModelPickRow(
                        label = stringResource(R.string.onb_ready_chat),
                        selected = connection.chatModel,
                        options = com.termino.core.ai.ProviderVerifier.rankChat(connection.models),
                        onPick = { onChooseModels(connection.fastModel, it) }
                    )
                    Spacer(Modifier.height(12.dp))
                    ModelPickRow(
                        label = stringResource(R.string.onb_ready_fast),
                        selected = connection.fastModel,
                        options = com.termino.core.ai.ProviderVerifier.rankFast(connection.models),
                        onPick = { onChooseModels(it, connection.chatModel) }
                    )
                    if (connection.models.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.onb_ready_models, connection.models.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(28.dp))
    StaggeredItem(3) {
        Button(
            onClick = onFinish,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(stringResource(R.string.onb_go_library), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Modelo elegido con sus alternativas (las 6 mejores del proveedor) como chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelPickRow(
    label: String,
    selected: String,
    options: List<String>,
    onPick: (String) -> Unit
) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    val shown = (listOf(selected) + options).distinct().take(6)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        shown.forEach { model ->
            FilterChip(
                selected = model == selected,
                onClick = { onPick(model) },
                label = { Text(model, maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}
