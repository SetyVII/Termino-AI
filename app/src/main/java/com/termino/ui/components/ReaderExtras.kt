package com.termino.ui.components

import com.termino.core.reader.ReaderColors
import com.termino.core.reader.ReaderFont
import com.termino.core.reader.ReaderTheme
import com.termino.core.reader.readerColors
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termino.data.db.BookmarkEntity
import com.termino.data.db.ChapterSummary
import com.termino.ui.reader.ReaderUiState
import com.termino.ui.reader.SearchHit
import com.termino.ui.reader.SearchUi
import java.text.DateFormat
import java.util.Date

/** Búsqueda de texto completo dentro del libro con fragmentos resaltados. */
@Composable
fun ReaderSearchSheet(
    search: SearchUi,
    colors: ReaderColors,
    onQueryChange: (String) -> Unit,
    onOpenHit: (SearchHit) -> Unit,
    onDismiss: () -> Unit
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
        contentColor = colors.foreground
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding()
        ) {
            OutlinedTextField(
                value = search.query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                placeholder = { Text("Buscar en el libro", color = colors.muted) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = colors.muted) },
                trailingIcon = {
                    if (search.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Outlined.Close, "Limpiar", tint = colors.muted)
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onQueryChange(search.query) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.foreground.copy(alpha = 0.14f),
                    focusedTextColor = colors.foreground,
                    unfocusedTextColor = colors.foreground,
                    cursorColor = colors.accent
                ),
                shape = RoundedCornerShape(14.dp)
            )
            Box(Modifier.height(3.dp).fillMaxWidth()) {
                androidx.compose.animation.AnimatedVisibility(visible = search.searching, enter = fadeIn(), exit = fadeOut()) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.accent,
                        trackColor = colors.foreground.copy(alpha = 0.06f)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            AnimatedContent(
                targetState = when {
                    search.hits.isNotEmpty() -> 2
                    search.searched -> 1
                    else -> 0
                },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "search-results"
            ) { mode ->
                when (mode) {
                    0 -> Text(
                        "Escribe al menos dos letras. Se busca en todo el texto del libro.",
                        modifier = Modifier.padding(vertical = 24.dp),
                        fontSize = 12.5.sp,
                        color = colors.muted
                    )
                    1 -> Text(
                        "Sin resultados para «${search.query.trim()}».",
                        modifier = Modifier.padding(vertical = 24.dp),
                        fontSize = 12.5.sp,
                        color = colors.muted
                    )
                    else -> Column {
                        Text(
                            text = if (search.truncated) "Más de ${search.hits.size} resultados" else "${search.hits.size} resultados",
                            fontSize = 11.sp,
                            color = colors.muted
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 520.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            itemsIndexed(search.hits, key = { i, hit -> "${hit.chapterIndex}-$i" }) { index, hit ->
                                val newChapter = index == 0 || search.hits[index - 1].chapterIndex != hit.chapterIndex
                                if (newChapter) {
                                    Text(
                                        hit.chapterTitle,
                                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.accent,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                SearchHitRow(hit, colors) { onOpenHit(hit) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SearchHitRow(hit: SearchHit, colors: ReaderColors, onClick: () -> Unit) {
    val text = remember(hit) {
        buildAnnotatedString {
            val start = hit.matchStart.coerceIn(0, hit.snippet.length)
            val end = hit.matchEnd.coerceIn(start, hit.snippet.length)
            append(hit.snippet.substring(0, start))
            withStyle(SpanStyle(background = colors.highlightYellow, fontWeight = FontWeight.SemiBold)) {
                append(hit.snippet.substring(start, end))
            }
            append(hit.snippet.substring(end))
        }
    }
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = colors.foreground,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
    )
}

/** Lista de marcadores para la hoja de navegación. */
@Composable
fun BookmarkList(
    bookmarks: List<BookmarkEntity>,
    chapters: List<ChapterSummary>,
    colors: ReaderColors,
    onOpen: (BookmarkEntity) -> Unit,
    onDelete: (BookmarkEntity) -> Unit
) {
    if (bookmarks.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Pulsa el marcador de la barra superior para guardar la página actual.",
                fontSize = 12.5.sp,
                color = colors.muted
            )
        }
        return
    }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        itemsIndexed(bookmarks, key = { _, item -> item.id }) { _, bookmark ->
            val chapter = chapters.firstOrNull { it.chapterIndex == bookmark.chapterIndex }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.foreground.copy(alpha = 0.04f))
                    .clickable { onOpen(bookmark) }
                    .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Bookmark, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = (chapter?.title ?: "Capítulo ${bookmark.chapterIndex + 1}") +
                            " · pág. ${bookmark.pageIndex + 1}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        bookmark.excerpt,
                        fontSize = 11.5.sp,
                        color = colors.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        dateFormat.format(Date(bookmark.createdAt)),
                        fontSize = 10.sp,
                        color = colors.muted.copy(alpha = 0.7f)
                    )
                }
                IconButton(onClick = { onDelete(bookmark) }) {
                    Icon(Icons.Outlined.Delete, "Borrar marcador", tint = colors.muted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/**
 * Aspecto del lector en una hoja inferior (móvil): tema, cuidado de la vista
 * y tipografía. En tablet se usan los menús desplegables de la barra.
 */
@Composable
fun ReaderAppearanceSheet(
    state: ReaderUiState,
    colors: ReaderColors,
    onTheme: (ReaderTheme) -> Unit,
    onEyeCare: (Boolean) -> Unit,
    onFontSize: (Int) -> Unit,
    onLineHeight: (Float) -> Unit,
    onFont: (ReaderFont) -> Unit,
    typefaceFor: (ReaderFont) -> android.graphics.Typeface,
    onImportFont: (android.net.Uri) -> Unit,
    onDeleteFont: (ReaderFont) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.foreground
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding()
        ) {
            Text("Tema", fontSize = 12.sp, color = colors.muted)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                ReaderTheme.entries.forEach { theme ->
                    val preview = readerColors(theme)
                    val selected = state.themeChoice == theme
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onTheme(theme) }
                            .padding(6.dp)
                    ) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(preview.background)
                                .then(
                                    if (theme == ReaderTheme.AUTO) {
                                        Modifier.background(
                                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                                0.5f to readerColors(ReaderTheme.LIGHT).background,
                                                0.5f to readerColors(ReaderTheme.DARK).background
                                            )
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .border(
                                    if (selected) 2.5.dp else 1.dp,
                                    if (selected) colors.accent else colors.foreground.copy(alpha = 0.2f),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) Icon(Icons.Outlined.Check, null, tint = preview.accent, modifier = Modifier.size(20.dp))
                            else if (theme != ReaderTheme.AUTO) Text("Aa", color = preview.foreground, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(theme.label, fontSize = 11.sp, color = if (selected) colors.accent else colors.muted)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Cuidado de la vista", modifier = Modifier.weight(1f), fontSize = 13.sp, color = colors.foreground)
                Switch(
                    checked = state.eyeCare,
                    onCheckedChange = onEyeCare,
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.accent)
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = colors.foreground.copy(alpha = 0.08f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tamaño de letra", modifier = Modifier.weight(1f), fontSize = 13.sp, color = colors.foreground)
                IconButton(onClick = { onFontSize(state.fontSize - 1) }, enabled = state.fontSize > com.termino.core.reader.MIN_FONT_SIZE) {
                    Icon(Icons.Outlined.Remove, "Reducir", tint = colors.foreground)
                }
                Text("${state.fontSize}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.foreground)
                IconButton(onClick = { onFontSize(state.fontSize + 1) }, enabled = state.fontSize < com.termino.core.reader.MAX_FONT_SIZE) {
                    Icon(Icons.Outlined.Add, "Aumentar", tint = colors.foreground)
                }
            }
            Text("Interlineado", fontSize = 12.sp, color = colors.muted)
            Slider(
                value = state.lineHeight,
                onValueChange = onLineHeight,
                valueRange = 1.2f..2.2f,
                colors = SliderDefaults.colors(
                    thumbColor = colors.accent,
                    activeTrackColor = colors.accent,
                    inactiveTrackColor = colors.foreground.copy(alpha = 0.15f)
                )
            )
            Spacer(Modifier.height(4.dp))
            Text("Fuente", fontSize = 12.sp, color = colors.muted)
            FontPickerRow(
                fonts = state.fonts,
                selected = state.font,
                accent = colors.accent,
                foreground = colors.foreground,
                muted = colors.muted,
                typefaceFor = typefaceFor,
                onSelect = onFont,
                onImport = onImportFont,
                onDelete = onDeleteFont
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Consejo: pellizca el texto con dos dedos para cambiar el tamaño.",
                fontSize = 11.sp,
                color = colors.muted
            )
        }
    }
}

/**
 * Selector de fuente: cada tarjeta muestra "Aa" con la propia fuente. La
 * última tarjeta importa una TTF/OTF; mantener pulsada una fuente importada
 * permite quitarla.
 */
@Composable
fun FontPickerRow(
    fonts: List<com.termino.core.reader.ReaderFont>,
    selected: com.termino.core.reader.ReaderFont,
    accent: androidx.compose.ui.graphics.Color,
    foreground: androidx.compose.ui.graphics.Color,
    muted: androidx.compose.ui.graphics.Color,
    typefaceFor: (com.termino.core.reader.ReaderFont) -> android.graphics.Typeface,
    onSelect: (com.termino.core.reader.ReaderFont) -> Unit,
    onImport: (android.net.Uri) -> Unit,
    onDelete: (com.termino.core.reader.ReaderFont) -> Unit
) {
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImport(uri) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = fonts.indexOf(selected).coerceAtLeast(0)
    )
    androidx.compose.foundation.lazy.LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
    ) {
        items(fonts.size, key = { fonts[it].id }) { index ->
            val font = fonts[index]
            val isSelected = font == selected
            val family = remember(font) {
                androidx.compose.ui.text.font.FontFamily(
                    androidx.compose.ui.text.font.Typeface(typefaceFor(font))
                )
            }
            var menu by remember { androidx.compose.runtime.mutableStateOf(false) }
            val borderWidth by androidx.compose.animation.core.animateDpAsState(
                if (isSelected) 2.dp else 1.dp, label = "font-border"
            )
            Box(Modifier.animateItem()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(76.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(
                            borderWidth,
                            if (isSelected) accent else foreground.copy(alpha = 0.15f),
                            RoundedCornerShape(14.dp)
                        )
                        .background(if (isSelected) accent.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
                        .combinedClickable(
                            onClick = { onSelect(font) },
                            onLongClick = { if (font.isCustom) menu = true }
                        )
                        .padding(vertical = 10.dp, horizontal = 6.dp)
                ) {
                    Text("Aa", fontFamily = family, fontSize = 22.sp, color = if (isSelected) accent else foreground)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        font.label,
                        fontSize = 10.5.sp,
                        color = if (isSelected) accent else muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Quitar fuente") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = {
                            menu = false
                            onDelete(font)
                        }
                    )
                }
            }
        }
        item(key = "import") {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(76.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, foreground.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                    .clickable {
                        launcher.launch(
                            arrayOf(
                                "font/ttf", "font/otf", "font/sfnt",
                                "application/x-font-ttf", "application/x-font-otf",
                                "application/vnd.ms-opentype", "application/octet-stream"
                            )
                        )
                    }
                    .padding(vertical = 10.dp, horizontal = 6.dp)
            ) {
                Icon(Icons.Outlined.Add, null, tint = accent, modifier = Modifier.size(26.dp))
                Spacer(Modifier.height(2.dp))
                Text("Importar", fontSize = 10.5.sp, color = muted, maxLines = 1)
            }
        }
    }
}

/** Círculo de vista previa de un tema; AUTO se dibuja mitad claro, mitad oscuro. */
@Composable
fun ThemeSwatch(
    theme: ReaderTheme,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    if (theme == ReaderTheme.AUTO) {
        Row(modifier.size(size).clip(CircleShape)) {
            Box(Modifier.weight(1f).height(size).background(readerColors(ReaderTheme.LIGHT).background))
            Box(Modifier.weight(1f).height(size).background(readerColors(ReaderTheme.DARK).background))
        }
    } else {
        Box(modifier.size(size).clip(CircleShape).background(readerColors(theme).background))
    }
}
