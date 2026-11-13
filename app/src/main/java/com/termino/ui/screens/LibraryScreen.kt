package com.termino.ui.screens

import com.termino.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termino.data.db.BookEntity
import com.termino.data.model.ImportJob
import com.termino.data.model.ImportJobState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    aiLabel: String?,
    onOpenBook: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importBooks(uris)
    }
    var bookToDelete by remember { mutableStateOf<BookEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        LibraryHeader(
            aiLabel = aiLabel,
            showImport = !state.isEmpty,
            onImport = {
                launcher.launch(
                    arrayOf(
                        "application/epub+zip",
                        "application/pdf",
                        "application/octet-stream"
                    )
                )
            },
            onOpenSettings = onOpenSettings
        )
        if (!state.isEmpty) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                placeholder = {
                    Text(
                        stringResource(R.string.library_search_placeholder),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.toc_search_clear),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(168.dp),
            state = rememberLazyGridState(),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (state.isEmpty) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyLibrary(
                        onImport = {
                            launcher.launch(
                                arrayOf(
                                    "application/epub+zip",
                                    "application/pdf",
                                    "application/octet-stream"
                                )
                            )
                        },
                        onSampleEpub = viewModel::importSampleEpub,
                        onSamplePdf = viewModel::importSamplePdf
                    )
                }
            } else {
                if (state.imports.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ImportJobs(
                            jobs = state.imports,
                            onOpenBook = onOpenBook,
                            onDismiss = viewModel::dismissImport
                        )
                    }
                }
                state.books.firstOrNull()?.let { recent ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ContinueReadingCard(
                            book = recent,
                            onOpen = { onOpenBook(recent.id) }
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.library_title),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${state.books.size}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        if (state.imports.any {
                                it.state == ImportJobState.SUCCESS ||
                                    it.state == ImportJobState.ERROR
                            }
                        ) {
                            TextButton(onClick = viewModel::clearFinishedImports) {
                                Text(stringResource(R.string.library_clear_notices), fontSize = 11.sp)
                            }
                        }
                    }
                }
                if (state.filteredBooks.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        NoResults()
                    }
                } else {
                    items(state.filteredBooks, key = { it.id }) { book ->
                        BookCard(
                            modifier = Modifier.animateItem(),
                            book = book,
                            onOpen = { onOpenBook(book.id) },
                            onReprocess = { viewModel.reprocessBook(book) },
                            onDelete = { bookToDelete = book }
                        )
                    }
                }
            }
        }
    }

    bookToDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { bookToDelete = null },
            title = { Text(stringResource(R.string.library_delete_title)) },
            text = {
                Text(
                    stringResource(R.string.library_delete_body, book.title)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteBook(book)
                        bookToDelete = null
                    }
                ) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { bookToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun LibraryHeader(
    aiLabel: String?,
    showImport: Boolean,
    onImport: () -> Unit,
    onOpenSettings: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    // On phones (< 600 dp) the AI pill shrinks to a dot and "Import" to an icon.
    val narrow = maxWidth < 600.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (narrow) 16.dp else 20.dp, end = if (narrow) 4.dp else 12.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Término",
                fontSize = 23.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1
            )
            Text(
                text = stringResource(R.string.library_tagline),
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        AiStatusPill(label = aiLabel, compact = narrow, onClick = onOpenSettings)
        Spacer(Modifier.width(if (narrow) 2.dp else 8.dp))
        if (showImport && narrow) {
            IconButton(onClick = onImport) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.action_import),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else if (showImport) {
            FilledTonalButton(
                onClick = onImport,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_import), fontSize = 12.sp)
            }
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.settings_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
}

@Composable
private fun EmptyLibrary(
    onImport: () -> Unit,
    onSampleEpub: () -> Unit,
    onSamplePdf: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 720.dp
        if (wide) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(Modifier.weight(1.12f)) {
                    EmptyHero(
                        onImport = onImport,
                        onSampleEpub = onSampleEpub,
                        onSamplePdf = onSamplePdf
                    )
                }
                Column(
                    modifier = Modifier.weight(0.88f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.library_hero_title),
                        fontSize = 18.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.library_hero_body),
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FeatureCard(
                        icon = Icons.Outlined.Search,
                        title = stringResource(R.string.feature_search_title),
                        text = stringResource(R.string.feature_search_body)
                    )
                    FeatureCard(
                        icon = Icons.Outlined.BookmarkBorder,
                        title = stringResource(R.string.feature_highlights_title),
                        text = stringResource(R.string.feature_highlights_body)
                    )
                    FeatureCard(
                        icon = Icons.Outlined.Lock,
                        title = stringResource(R.string.feature_byok_title),
                        text = stringResource(R.string.feature_byok_body)
                    )
                    FeatureCard(
                        icon = Icons.Outlined.AutoAwesome,
                        title = stringResource(R.string.feature_context_title),
                        text = stringResource(R.string.feature_context_body)
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                EmptyHero(
                    onImport = onImport,
                    onSampleEpub = onSampleEpub,
                    onSamplePdf = onSamplePdf
                )
                Spacer(Modifier.height(16.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    FeatureCard(
                        icon = Icons.Outlined.Search,
                        title = stringResource(R.string.feature_search_title),
                        text = stringResource(R.string.feature_search_body)
                    )
                    FeatureCard(
                        icon = Icons.Outlined.BookmarkBorder,
                        title = stringResource(R.string.feature_highlights_title),
                        text = stringResource(R.string.feature_highlights_body)
                    )
                    FeatureCard(
                        icon = Icons.Outlined.Lock,
                        title = stringResource(R.string.feature_byok_title),
                        text = stringResource(R.string.feature_byok_body)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHero(
    onImport: () -> Unit,
    onSampleEpub: () -> Unit,
    onSamplePdf: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.library_empty_title),
                fontSize = 25.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.library_empty_body),
                modifier = Modifier.fillMaxWidth(0.92f),
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onImport,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(stringResource(R.string.library_import_button), fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSampleEpub, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.library_sample_epub), fontSize = 11.5.sp)
                }
                OutlinedButton(onClick = onSamplePdf, shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.library_sample_pdf), fontSize = 11.5.sp)
                }
            }
        }
    }
}

@Composable
private fun FeatureCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    text: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = text,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(
    book: BookEntity,
    onOpen: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookCover(
                book = book,
                modifier = Modifier
                    .width(96.dp)
                    .aspectRatio(0.68f)
                    .clip(RoundedCornerShape(12.dp))
            )
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.library_continue_reading),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 0.8.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = book.title,
                    fontSize = 21.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = book.author,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!book.description.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = book.description,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = book.progress,
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "${(book.progress * 100).toInt()}%",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportJobs(
    jobs: List<ImportJob>,
    onOpenBook: (String) -> Unit,
    onDismiss: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        jobs.forEach { job ->
            ImportJobCard(
                job = job,
                onOpenBook = onOpenBook,
                onDismiss = { onDismiss(job.id) }
            )
        }
    }
}

@Composable
private fun ImportJobCard(
    job: ImportJob,
    onOpenBook: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val success = job.state == ImportJobState.SUCCESS
    val error = job.state == ImportJobState.ERROR
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (success && job.bookId != null) {
                    Modifier.clickable { onOpenBook(job.bookId) }
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (error) MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when {
                    success -> Icons.Outlined.CheckCircle
                    error -> Icons.Outlined.ErrorOutline
                    else -> Icons.Outlined.AutoAwesome
                },
                contentDescription = null,
                tint = when {
                    success -> MaterialTheme.colorScheme.secondary
                    error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = job.displayName,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = job.message ?: when (job.state) {
                        ImportJobState.QUEUED -> stringResource(R.string.import_queued)
                        ImportJobState.RUNNING -> stringResource(R.string.import_processing)
                        ImportJobState.SUCCESS -> stringResource(R.string.import_ready)
                        ImportJobState.ERROR -> stringResource(R.string.import_state_error)
                    },
                    fontSize = 10.5.sp,
                    color = if (error) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!success && !error) {
                    Spacer(Modifier.height(5.dp))
                    LinearProgressIndicator(
                        progress = job.progress / 100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    )
                }
            }
            if (success && job.bookId != null) {
                TextButton(onClick = { onOpenBook(job.bookId) }) {
                    Text(stringResource(R.string.action_open), fontSize = 11.sp)
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.library_dismiss_notice),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun BookCard(
    modifier: Modifier = Modifier,
    book: BookEntity,
    onOpen: () -> Unit,
    onReprocess: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // Touch feedback: the card sinks slightly when pressed.
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.6f,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        ),
        label = "book-press"
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(),
                onClick = onOpen
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(14.dp))
        ) {
            BookCover(book = book, modifier = Modifier.fillMaxSize())
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                shape = RoundedCornerShape(7.dp),
                color = Color.Black.copy(alpha = 0.58f),
                contentColor = Color.White
            ) {
                Text(
                    text = book.format,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.48f))
            ) {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.library_book_options),
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_open), fontSize = 13.sp) },
                        onClick = {
                            menuExpanded = false
                            onOpen()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_reprocess), fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onReprocess()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.action_delete),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
            if (book.progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                            )
                        )
                        .padding(horizontal = 9.dp, vertical = 8.dp)
                ) {
                    LinearProgressIndicator(
                        progress = book.progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Color(0xFFF2C14E),
                        trackColor = Color.White.copy(alpha = 0.22f)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = book.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = book.author,
            fontSize = 10.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BookCover(
    book: BookEntity,
    modifier: Modifier = Modifier
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, book.coverPath) {
        value = withContext(Dispatchers.IO) {
            book.coverPath?.let { path ->
                runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
                )
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = book.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.size(34.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = book.title.take(1).uppercase(),
                    fontSize = 34.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun NoResults() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.library_no_matches),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.library_no_matches_hint),
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * AI connection status in the header: green dot + provider when connected,
 * or an invitation to connect. Changes with a fade and size animation.
 */
@Composable
private fun AiStatusPill(label: String?, compact: Boolean = false, onClick: () -> Unit) {
    val connected = label != null
    val container by androidx.compose.animation.animateColorAsState(
        if (connected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        label = "ai-pill"
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = container,
        modifier = Modifier.animateContentSize()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (compact) 10.dp else 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (compact) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = label ?: stringResource(R.string.library_connect_ai),
                    modifier = Modifier.size(16.dp),
                    tint = if (connected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(5.dp))
            }
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (connected) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.outline
                    )
            )
            if (!compact) Spacer(Modifier.width(7.dp))
            if (!compact) Text(
                text = if (label != null) {
                    androidx.compose.ui.res.stringResource(com.termino.R.string.library_ai_on, label)
                } else {
                    androidx.compose.ui.res.stringResource(com.termino.R.string.library_ai_off)
                },
                fontSize = 12.sp,
                maxLines = 1,
                color = if (connected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
