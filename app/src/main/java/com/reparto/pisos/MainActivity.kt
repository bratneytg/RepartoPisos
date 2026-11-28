@file:OptIn(ExperimentalMaterial3Api::class)

package com.reparto.pisos

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                App()
            }
        }
    }
}

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showSettings by remember { mutableStateOf(false) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        vm.onCameraResult(ok)
    }
    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MainViewModel.MAX_PHOTOS),
    ) { uris -> vm.importUris(uris) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reparto · Pisos", fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = { showSettings = true }) { Text("⚙ Ajustes", fontSize = 16.sp) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---------------- 1. paquetes
            SectionTitle("1. Paquetes")
            Text(
                "Un nombre por línea. Si hay un número delante es la cantidad de paquetes y se ignora al buscar.",
                fontSize = 16.sp,
            )
            OutlinedTextField(
                value = vm.parcelsText,
                onValueChange = vm::onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 280.dp),
                textStyle = TextStyle(fontSize = 22.sp, lineHeight = 30.sp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                placeholder = { Text("2 Juan Pérez\nAna López\nHans Müller", fontSize = 22.sp, lineHeight = 30.sp) },
            )

            // ---------------- 2. edificio / fotos
            SectionTitle("2. Edificio")
            BuildingPicker(vm)
            if (vm.selectedId == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { camera.launch(vm.prepareCameraUri()) },
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp),
                        enabled = vm.photos.size < MainViewModel.MAX_PHOTOS,
                    ) { Text("📷 Hacer foto", fontSize = 18.sp) }
                    OutlinedButton(
                        onClick = {
                            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp),
                        enabled = vm.photos.size < MainViewModel.MAX_PHOTOS,
                    ) { Text("🖼 Galería", fontSize = 18.sp) }
                }
                if (vm.photos.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        vm.photos.toList().forEach { path -> Thumb(path) { vm.removePhoto(path) } }
                    }
                    Text("Puedes añadir varias fotos si el timbre es grande.", fontSize = 14.sp)
                }
            }

            // ---------------- 3. buscar
            Button(
                onClick = vm::search,
                enabled = !vm.loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp),
            ) {
                Text(if (vm.loading) "Buscando…" else "BUSCAR PISOS", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            if (vm.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(vm.status, fontSize = 16.sp)
            }
            if (vm.apiKey.isBlank() && !vm.loading && vm.results == null && vm.selectedId == null) {
                Text(
                    "Consejo: pon tu clave de API en Ajustes para leer el timbre con IA. " +
                        "Sin clave uso la lectura del móvil, que falla más si el timbre es raro.",
                    fontSize = 14.sp,
                )
            }

            vm.notice?.let { InfoCard(it, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer) }
            vm.error?.let { InfoCard(it, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) }

            // ---------------- resultados
            vm.results?.let { res ->
                ResultsView(
                    results = res,
                    panel = vm.panel,
                    sourceLabel = vm.sourceLabel,
                    onCopy = { text ->
                        clipboard.setText(AnnotatedString(text))
                        Toast.makeText(context, "Copiado", Toast.LENGTH_SHORT).show()
                    },
                    onShare = { text ->
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, "Compartir"))
                    },
                )
            }

            if (vm.canSave) {
                SaveBuildingCard(vm) { id ->
                    Toast.makeText(context, "Edificio «$id» guardado", Toast.LENGTH_SHORT).show()
                }
            }

            if (vm.parcelsText.isNotBlank() || vm.photos.isNotEmpty()) {
                TextButton(onClick = vm::clearAll) { Text("Borrar todo y empezar de nuevo", fontSize = 16.sp) }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(vm) { showSettings = false }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun InfoCard(text: String, container: Color, content: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = container, contentColor = content), modifier = Modifier.fillMaxWidth()) {
        Text(text, fontSize = 17.sp, modifier = Modifier.padding(14.dp))
    }
}

@Composable
fun Thumb(path: String, onRemove: () -> Unit) {
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { ImageUtil.load(path, 400).asImageBitmap() }.getOrNull()
        }
    }
    Box(modifier = Modifier.size(110.dp)) {
        bmp?.let {
            Image(
                bitmap = it,
                contentDescription = "Foto del timbre",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.LightGray, RoundedCornerShape(10.dp)),
            )
        }
        TextButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd)) {
            Text("✕", fontSize = 22.sp, color = Color.White, modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp))
        }
    }
}

// ============================================================ resultados

@Composable
fun ResultsView(
    results: List<ParcelResult>,
    panel: List<PanelEntry>,
    sourceLabel: String,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    val groups = Matcher.group(results)
    val text = Matcher.exportText(results)
    var showPanel by remember { mutableStateOf(false) }
    val anyGuessed = results.any { r -> r.refs.any { it.guessed } }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Resultado")
        Text(
            sourceLabel +
                " · ${results.count { it.status == Status.OK }} seguros de ${results.size}",
            fontSize = 15.sp,
        )
        if (anyGuessed) {
            InfoCard(
                "No he visto números de piso en la foto: he contado las filas del timbre. Compruébalo antes de subir.",
                MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }

        for ((floor, items) in groups.byFloor) {
            GroupCard(floor.label.uppercase(), items, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        }
        if (groups.unknownFloor.isNotEmpty()) {
            GroupCard("PISO DESCONOCIDO", groups.unknownFloor, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        }
        if (groups.ambiguous.isNotEmpty()) {
            GroupCard("REVISAR · VARIOS POSIBLES", groups.ambiguous, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        }
        if (groups.notFound.isNotEmpty()) {
            GroupCard("NO ENCONTRADOS", groups.notFound, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { onCopy(text) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Copiar", fontSize = 17.sp) }
            OutlinedButton(onClick = { onShare(text) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("Compartir", fontSize = 17.sp) }
        }

        if (panel.isNotEmpty()) {
            TextButton(onClick = { showPanel = !showPanel }) {
                Text(if (showPanel) "Ocultar lo que he leído del timbre" else "Ver lo que he leído del timbre (${panel.size})", fontSize = 16.sp)
            }
            if (showPanel) {
                val byFloor = panel.groupBy { it.floor }.entries.sortedBy { it.key?.order ?: Int.MAX_VALUE }
                for ((floor, entries) in byFloor) {
                    Text(
                        "${floor?.label ?: "Piso ?"}: " + entries.joinToString("  ·  ") { it.text },
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
fun GroupCard(title: String, items: List<ParcelResult>, container: Color, content: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            for (r in items) {
                Column {
                    Text(r.parcel.raw, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                    if (r.note.isNotBlank()) {
                        Text("⚠ ${r.note}", fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

// ============================================================ edificios

@Composable
fun BuildingPicker(vm: MainViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selected = vm.buildings.firstOrNull { it.id == vm.selectedId }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
        ) {
            Text(
                if (selected != null) "🏢 ${selected.id}  ▾" else "📷 Edificio nuevo (leer foto)  ▾",
                fontSize = 18.sp,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("📷 Edificio nuevo (leer foto)", fontSize = 18.sp) },
                onClick = {
                    vm.selectBuilding(null)
                    expanded = false
                },
            )
            vm.buildings.forEach { b ->
                DropdownMenuItem(
                    text = { Text("🏢 ${b.id} · ${b.entries.size} nombres", fontSize = 18.sp) },
                    onClick = {
                        vm.selectBuilding(b.id)
                        expanded = false
                    },
                )
            }
        }
    }

    if (selected != null) {
        val date = remember(selected.savedAt) {
            java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date(selected.savedAt))
        }
        Text("Guardado el $date · ${selected.entries.size} nombres · funciona sin internet ni IA", fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = vm::rereadSelected) { Text("Releer con foto") }
            TextButton(onClick = { confirmDelete = true }) { Text("Borrar edificio") }
        }
    } else if (vm.buildings.isEmpty()) {
        Text(
            "Cuando leas un edificio podrás guardarlo con un nombre y no tendrás que volver a hacer la foto.",
            fontSize = 14.sp,
        )
    }

    if (confirmDelete && selected != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteBuilding(selected.id)
                    confirmDelete = false
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
            title = { Text("¿Borrar «${selected.id}»?") },
            text = { Text("Tendrás que volver a leer el timbre con una foto.") },
        )
    }
}

@Composable
fun SaveBuildingCard(vm: MainViewModel, onSaved: (String) -> Unit) {
    var name by remember(vm.saveNameDraft) { mutableStateOf(vm.saveNameDraft) }
    val exists = vm.buildings.any { it.id.equals(name.trim(), ignoreCase = true) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Guardar este edificio", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "La próxima vez eliges el edificio y no hace falta foto ni internet.",
                fontSize = 15.sp,
            )
            if (vm.panel.any { it.guessed || it.floor == null }) {
                Text(
                    "⚠ Algunos pisos son estimados o desconocidos. Revisa «Ver lo que he leído del timbre» antes de guardar.",
                    fontSize = 14.sp,
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Identificador (p. ej. Hauptstr. 12)") },
                singleLine = true,
                textStyle = TextStyle(fontSize = 20.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { if (vm.saveBuilding(name)) onSaved(name.trim()) },
                enabled = name.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) { Text(if (exists) "Actualizar edificio" else "Guardar edificio", fontSize = 18.sp) }
        }
    }
}

// ============================================================== ajustes

@Composable
fun SettingsDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf(vm.apiKey) }
    var model by remember { mutableStateOf(vm.model) }
    var useAi by remember { mutableStateOf(vm.useAi) }
    var topIsHighest by remember { mutableStateOf(vm.topIsHighest) }
    var bottomFloor by remember { mutableStateOf(vm.bottomFloor) }
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                vm.saveSettings(key, model, useAi, topIsHighest, bottomFloor)
                onDismiss()
            }) { Text("Guardar", fontSize = 18.sp) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", fontSize = 18.sp) } },
        title = { Text("Ajustes") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Leer el timbre con IA (Claude)", modifier = Modifier.weight(1f), fontSize = 16.sp)
                    Switch(checked = useAi, onCheckedChange = { useAi = it })
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Clave de API de Anthropic") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val cleaned = AiClient.cleanKey(key)
                if (cleaned.isNotEmpty()) {
                    Text(
                        "Pegada: ${cleaned.length} caracteres · empieza por «${cleaned.take(12)}»",
                        fontSize = 13.sp,
                    )
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            testing = true
                            testMsg = null
                            val problem = AiClient.keyProblem(cleaned)
                            testMsg = if (problem != null) {
                                "❌ $problem"
                            } else {
                                try {
                                    AiClient.testKey(cleaned, model)
                                    "✅ Clave correcta, la IA funciona."
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    "❌ ${e.message ?: e}"
                                }
                            }
                            testing = false
                        }
                    },
                    enabled = !testing && key.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (testing) "Probando…" else "Probar clave") }
                testMsg?.let { Text(it, fontSize = 15.sp) }
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Modelo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Con IA las fotos y los nombres se envían a la API de Anthropic. " +
                        "Si falla o no hay internet, uso la lectura local.",
                    fontSize = 13.sp,
                )

                Text("Lectura local (sin IA)", fontWeight = FontWeight.Bold)
                Text(
                    "Si la foto no trae números de piso, cuento las filas del timbre:",
                    fontSize = 14.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("La fila de arriba es el piso más alto", modifier = Modifier.weight(1f), fontSize = 16.sp)
                    Switch(checked = topIsHighest, onCheckedChange = { topIsHighest = it })
                }
                Text("La fila de abajo del timbre es el piso:", fontSize = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = bottomFloor == 0, onClick = { bottomFloor = 0 }, label = { Text("0 (planta baja)") })
                    FilterChip(selected = bottomFloor == 1, onClick = { bottomFloor = 1 }, label = { Text("1") })
                }
            }
        },
    )
}
