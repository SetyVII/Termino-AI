package com.termino

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.termino.data.model.AiPaneMode
import com.termino.data.prefs.AppSettings
import com.termino.ui.reader.ReaderViewModel
import com.termino.ui.screens.ConnectionViewModel
import com.termino.ui.screens.LibraryScreen
import com.termino.ui.screens.LibraryViewModel
import com.termino.ui.screens.OnboardingScreen
import com.termino.ui.screens.SettingsScreen
import com.termino.ui.screens.SettingsViewModel
import com.termino.ui.screens.TabletReaderScreen
import com.termino.ui.theme.LectorTheme
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** La splash se mantiene hasta leer los ajustes (evita un parpadeo biblioteca → onboarding). */
    @Volatile
    private var settingsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { !settingsReady }
        enableEdgeToEdge()
        handleIntent(intent)
        val settingsFlow = (application as TerminoApp).serviceLocator.settingsRepository.settings
        setContent {
            val appTheme by settingsFlow
                .map { it.appTheme }
                .collectAsStateWithLifecycle(initialValue = com.termino.data.model.AppThemeMode.SYSTEM)
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            LectorTheme(
                darkTheme = when (appTheme) {
                    com.termino.data.model.AppThemeMode.SYSTEM -> systemDark
                    com.termino.data.model.AppThemeMode.LIGHT -> false
                    com.termino.data.model.AppThemeMode.DARK -> true
                }
            ) {
                LectorRoot(onSettingsLoaded = { settingsReady = true })
            }
        }
    }

    /** Volumen abajo = página siguiente, arriba = anterior (si el lector lo pide). */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val turner = com.termino.core.reader.ReaderKeys.pageTurner
        if (turner != null) {
            val forward = when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> true
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> false
                else -> null
            }
            if (forward != null) {
                // Se consume también el ACTION_UP para que no suene el volumen.
                if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    turner(forward)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let(uris::add)
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let(uris::add)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(
                    intent,
                    Intent.EXTRA_STREAM,
                    Uri::class.java
                )?.let(uris::addAll)
            }
        }
        if (uris.isEmpty()) return
        val repository = (application as TerminoApp).serviceLocator.bookRepository
        uris.forEach { uri -> repository.enqueueImport(uri, null) }
        Toast.makeText(
            this,
            if (uris.size == 1) "Importando libro…" else "Importando ${uris.size} libros…",
            Toast.LENGTH_SHORT
        ).show()
    }
}

private enum class AppScreen {
    ONBOARDING,
    LIBRARY,
    READER,
    SETTINGS
}

@Composable
private fun LectorRoot(onSettingsLoaded: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as TerminoApp
    val settingsFlow = remember {
        app.serviceLocator.settingsRepository.settings.map<AppSettings, AppSettings?> { it }
    }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)

    var screen by rememberSaveable { mutableStateOf(AppScreen.LIBRARY) }
    var bookId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsReturn by rememberSaveable { mutableStateOf(AppScreen.LIBRARY) }
    // null = aún sin decidir (esperando a DataStore). Se decide una vez y solo
    // se reabre si Ajustes pide "ver la bienvenida de nuevo": guardar la
    // conexión marca el onboarding como completado, pero el paso final debe
    // seguir visible hasta que el usuario pulse "Ir a la biblioteca".
    var showOnboarding by rememberSaveable { mutableStateOf<Boolean?>(null) }

    val loaded = settings
    LaunchedEffect(loaded?.onboardingCompleted) {
        if (loaded == null) return@LaunchedEffect
        if (showOnboarding == null) showOnboarding = !loaded.onboardingCompleted
        else if (!loaded.onboardingCompleted) showOnboarding = true
        onSettingsLoaded()
    }

    val target = when {
        showOnboarding == true -> AppScreen.ONBOARDING
        screen == AppScreen.READER && bookId == null -> AppScreen.LIBRARY
        else -> screen
    }

    fun navigateBack() {
        when (target) {
            AppScreen.READER -> screen = AppScreen.LIBRARY
            AppScreen.SETTINGS -> screen = settingsReturn
            else -> Unit
        }
    }

    // Gesto atrás predictivo (Android 14+): la pantalla actual se encoge y
    // se desplaza siguiendo el dedo; si se suelta, se completa la navegación
    // con la transición normal, y si se cancela vuelve con un muelle.
    val scope = rememberCoroutineScope()
    val backProgress = remember { Animatable(0f) }
    var backFromLeft by remember { mutableStateOf(true) }
    var backSource by remember { mutableStateOf<AppScreen?>(null) }
    PredictiveBackHandler(enabled = target == AppScreen.READER || target == AppScreen.SETTINGS) { events ->
        backSource = target
        try {
            events.collect { event ->
                backFromLeft = event.swipeEdge == BackEventCompat.EDGE_LEFT
                backProgress.snapTo(event.progress)
            }
            navigateBack()
        } catch (cancelled: CancellationException) {
            scope.launch { backProgress.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
            throw cancelled
        }
    }
    LaunchedEffect(target) {
        // Tras la transición de salida, la pantalla que se "arrastró" ya no está.
        delay(NAV_DURATION_MS + 80L)
        backProgress.snapTo(0f)
        backSource = null
    }

    val density = LocalDensity.current
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (loaded == null) return@Surface // la splash sigue visible
        AnimatedContent(
            targetState = target,
            transitionSpec = { navTransition(initialState, targetState) },
            label = "root-nav"
        ) { destination ->
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (destination == backSource) {
                            val p = backProgress.value
                            val scale = 1f - 0.1f * p
                            scaleX = scale
                            scaleY = scale
                            translationX = (if (backFromLeft) 1 else -1) * with(density) { 24.dp.toPx() } * p
                            shape = RoundedCornerShape(32.dp * p)
                            clip = p > 0f
                        }
                    }
            ) {
                when (destination) {
                    AppScreen.ONBOARDING -> {
                        val connectionViewModel: ConnectionViewModel = viewModel(
                            key = "onboarding-connection",
                            factory = ConnectionViewModel.factory(app)
                        )
                        OnboardingScreen(
                            viewModel = connectionViewModel,
                            onFinished = {
                                showOnboarding = false
                                screen = AppScreen.LIBRARY
                            }
                        )
                    }
                    AppScreen.LIBRARY -> {
                        val libraryViewModel: LibraryViewModel = viewModel(
                            factory = LibraryViewModel.factory(app)
                        )
                        LibraryScreen(
                            viewModel = libraryViewModel,
                            aiLabel = loaded.takeIf { it.isConfigured }
                                ?.let { it.providerLabel.ifBlank { it.chatModel } },
                            onOpenBook = { id ->
                                bookId = id
                                screen = AppScreen.READER
                            },
                            onOpenSettings = {
                                settingsReturn = AppScreen.LIBRARY
                                screen = AppScreen.SETTINGS
                            }
                        )
                    }
                    AppScreen.SETTINGS -> {
                        val settingsViewModel: SettingsViewModel = viewModel(
                            factory = SettingsViewModel.factory(app)
                        )
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onBack = { screen = settingsReturn }
                        )
                    }
                    AppScreen.READER -> {
                        val id = bookId
                        if (id != null) {
                            ReaderHost(
                                app = app,
                                bookId = id,
                                onBack = { screen = AppScreen.LIBRARY },
                                onOpenSettings = {
                                    settingsReturn = AppScreen.READER
                                    screen = AppScreen.SETTINGS
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderHost(
    app: TerminoApp,
    bookId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val store = remember(bookId) { ViewModelStore() }
    val readerViewModel = remember(bookId) {
        ViewModelProvider(store, ReaderViewModel.factory(app, bookId))[ReaderViewModel::class.java]
    }
    DisposableEffect(bookId) {
        onDispose { store.clear() }
    }
    BoxWithConstraints {
        val isNarrow = maxWidth < 800.dp
        LaunchedEffect(isNarrow) {
            // En móvil el libro abre limpio: el asistente queda en la burbuja
            // y se abre a pantalla completa solo cuando se pide.
            readerViewModel.useCompactLayout(isNarrow)
        }
        TabletReaderScreen(
            viewModel = readerViewModel,
            onBack = onBack,
            onOpenSettings = onOpenSettings
        )
    }
}

private const val NAV_DURATION_MS = 380

/**
 * Transiciones Material: eje X compartido para entrar/salir de Ajustes,
 * "contenedor" (escala + fundido) al abrir/cerrar un libro y fundido a
 * través para el onboarding.
 */
private fun navTransition(from: AppScreen, to: AppScreen): ContentTransform {
    val emphasized = tween<Float>(NAV_DURATION_MS, easing = FastOutSlowInEasing)
    return when {
        from == AppScreen.ONBOARDING || to == AppScreen.ONBOARDING ->
            (fadeIn(tween(260, delayMillis = 90)) + scaleIn(tween(320, delayMillis = 90), initialScale = 0.94f))
                .togetherWith(fadeOut(tween(110)))
        to == AppScreen.SETTINGS || from == AppScreen.SETTINGS -> {
            val forward = to == AppScreen.SETTINGS
            val sign = if (forward) 1 else -1
            val slide = tween<androidx.compose.ui.unit.IntOffset>(NAV_DURATION_MS, easing = FastOutSlowInEasing)
            (slideInHorizontally(slide) { sign * it / 5 } + fadeIn(tween(210, delayMillis = 90, easing = LinearOutSlowInEasing)))
                .togetherWith(slideOutHorizontally(slide) { -sign * it / 5 } + fadeOut(tween(90)))
        }
        to == AppScreen.READER ->
            (scaleIn(emphasized, initialScale = 0.92f) + fadeIn(tween(220, delayMillis = 40)))
                .togetherWith(fadeOut(tween(150)) + scaleOut(emphasized, targetScale = 1.04f))
        else ->
            (scaleIn(emphasized, initialScale = 1.04f) + fadeIn(tween(220, delayMillis = 60)))
                .togetherWith(scaleOut(emphasized, targetScale = 0.92f) + fadeOut(tween(160)))
    }.apply { targetContentZIndex = if (to == AppScreen.LIBRARY) 0f else 1f }
}
