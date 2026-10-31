package edu.umg.misgastos

import android.os.Bundle
import android.content.Context
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import edu.umg.misgastos.data.MetaAhorroEntity
import edu.umg.misgastos.data.MetaAhorroDao
import edu.umg.misgastos.data.PresupuestoEntity
import edu.umg.misgastos.data.PresupuestoDao
import edu.umg.misgastos.data.GastoRoomRepositorio
import edu.umg.misgastos.data.IngresoRoomRepositorio
import edu.umg.misgastos.data.MisGastosDatabase
import edu.umg.misgastos.ui.theme.MISGASTOSTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import umg.edu.gt.misgastos.modelo.Gasto
import umg.edu.gt.misgastos.modelo.Ingreso
import umg.edu.gt.misgastos.servicios.CalculadoraFinanciera
import umg.edu.gt.misgastos.servicios.CatalogoCategorias
import umg.edu.gt.misgastos.servicios.ControlFinanciero
import umg.edu.gt.misgastos.servicios.ConversorMoneda
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS presupuestos (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, categoria TEXT NOT NULL, limite REAL NOT NULL, mes INTEGER NOT NULL, anio INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_presupuestos_categoria_mes_anio ON presupuestos (categoria, mes, anio)")
                db.execSQL("CREATE TABLE IF NOT EXISTS metas_ahorro (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, nombre TEXT NOT NULL, objetivo REAL NOT NULL, ahorrado REAL NOT NULL)")
            }
        }
        val tasaAlMigrar = getSharedPreferences("tipo_cambio", Context.MODE_PRIVATE)
            .getString("usd_gtq", null)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: 7.70
        val migrationRate = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE gastos ADD COLUMN tasaCambio REAL NOT NULL DEFAULT 7.70")
                db.execSQL("ALTER TABLE ingresos ADD COLUMN tasaCambio REAL NOT NULL DEFAULT 7.70")
                db.execSQL("UPDATE gastos SET tasaCambio = $tasaAlMigrar WHERE moneda = 'USD'")
                db.execSQL("UPDATE ingresos SET tasaCambio = $tasaAlMigrar WHERE moneda = 'USD'")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel("alertas_presupuesto", "Alertas de presupuesto", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
        val database = Room.databaseBuilder(applicationContext, MisGastosDatabase::class.java, "mis-gastos.db").addMigrations(migration, migrationRate).build()
        val gastoRepo = GastoRoomRepositorio(database.gastoDao())
        val ingresoRepo = IngresoRoomRepositorio(database.ingresoDao())
        val control = ControlFinanciero(gastoRepo, ingresoRepo)
        setContent { MISGASTOSTheme { MisGastosApp(gastoRepo, ingresoRepo, control, database.presupuestoDao(), database.metaAhorroDao()) } }
    }
}

private enum class Pantalla { RESUMEN, GASTOS, INGRESOS, PRESUPUESTOS, METAS }

@Composable
private fun MisGastosApp(
    gastoRepo: GastoRoomRepositorio,
    ingresoRepo: IngresoRoomRepositorio,
    control: ControlFinanciero,
    presupuestoDao: PresupuestoDao,
    metaAhorroDao: MetaAhorroDao
) {
    val gastos = remember { mutableStateListOf<Gasto>() }
    val ingresos = remember { mutableStateListOf<Ingreso>() }
    val tasasGastos = remember { mutableStateMapOf<Long, Double>() }
    val tasasIngresos = remember { mutableStateMapOf<Long, Double>() }
    val presupuestos = remember { mutableStateListOf<PresupuestoEntity>() }
    val metas = remember { mutableStateListOf<MetaAhorroEntity>() }
    var pantalla by remember { mutableStateOf(Pantalla.RESUMEN) }
    var mostrarFormulario by remember { mutableStateOf(false) }
    var tipoFormulario by remember { mutableStateOf(Pantalla.GASTOS) }
    var mensaje by remember { mutableStateOf<String?>(null) }
    var mostrarFormularioPresupuesto by remember { mutableStateOf(false) }
    var mostrarFormularioMeta by remember { mutableStateOf(false) }
    var metaParaAporte by remember { mutableStateOf<MetaAhorroEntity?>(null) }
    var movimientoEditando by remember { mutableStateOf<Movimiento?>(null) }
    var permisoPendientePresupuesto by remember { mutableStateOf(false) }
    var fechaTasa by remember { mutableStateOf("") }
    var estadoTasa by remember { mutableStateOf("Cargando tasa...") }
    val contexto = LocalContext.current
    val preferenciasTasa = remember { contexto.getSharedPreferences("tipo_cambio", Context.MODE_PRIVATE) }
    var notificacionesPermitidas by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val scope = rememberCoroutineScope()
    val permisoNotificaciones = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        notificacionesPermitidas = concedido
        if (permisoPendientePresupuesto) mostrarFormularioPresupuesto = true
        permisoPendientePresupuesto = false
    }

    suspend fun recargar() {
        gastos.clear(); gastos.addAll(gastoRepo.obtenerTodos())
        ingresos.clear(); ingresos.addAll(ingresoRepo.obtenerTodos())
        tasasGastos.clear(); tasasGastos.putAll(gastoRepo.obtenerTasas())
        tasasIngresos.clear(); tasasIngresos.putAll(ingresoRepo.obtenerTasas())
        val hoy = LocalDate.now()
        presupuestos.clear(); presupuestos.addAll(presupuestoDao.delMes(hoy.monthValue, hoy.year))
        metas.clear(); metas.addAll(metaAhorroDao.obtenerTodas())
    }
    LaunchedEffect(Unit) {
        recargar()
        emitirAvisosPresupuesto(contexto, gastos.toList(), tasasGastos.toMap(), presupuestos.toList())
    }

    fun crearPresupuesto() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permisoPendientePresupuesto = true
            permisoNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            mostrarFormularioPresupuesto = true
        }
    }

    fun actualizarTipoCambio() {
        scope.launch {
            estadoTasa = "Actualizando tasa..."
            try {
                val respuesta = withContext(Dispatchers.IO) { consultarTasaDolarQuetzal() }
                ConversorMoneda.actualizarTasa(respuesta.valor)
                preferenciasTasa.edit().putString("usd_gtq", respuesta.valor.toString()).putString("fecha", respuesta.fecha).apply()
                fechaTasa = respuesta.fecha
                estadoTasa = "Tipo de cambio de referencia · Banco de Guatemala"
                emitirAvisosPresupuesto(contexto, gastos.toList(), tasasGastos.toMap(), presupuestos.toList())
            } catch (_: Exception) {
                estadoTasa = if (fechaTasa.isNotBlank()) "Sin conexión · se usa la tasa guardada" else "Sin conexión · se usa tasa de respaldo"
            }
        }
    }

    LaunchedEffect(Unit) {
        val guardada = preferenciasTasa.getString("usd_gtq", null)?.toDoubleOrNull()
        val fechaGuardada = preferenciasTasa.getString("fecha", "").orEmpty()
        if (guardada != null && guardada.isFinite() && guardada > 0) {
            ConversorMoneda.actualizarTasa(guardada)
            fechaTasa = fechaGuardada
            estadoTasa = "Tasa guardada · ${fechaGuardada.ifBlank { "fecha no disponible" }}"
        }
        actualizarTipoCambio()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                val items = listOf(
                    Pantalla.RESUMEN to ("⌂" to "Inicio"),
                    Pantalla.GASTOS to ("↘" to "Gastos"),
                    Pantalla.INGRESOS to ("↗" to "Ingresos"),
                    Pantalla.PRESUPUESTOS to ("▤" to "Presup."),
                    Pantalla.METAS to ("◎" to "Metas")
                )
                items.forEach { (destino, contenido) ->
                    NavigationBarItem(
                        selected = pantalla == destino,
                        onClick = { pantalla = destino },
                        icon = { Text(contenido.first, style = MaterialTheme.typography.titleLarge) },
                        label = { Text(contenido.second) }
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (pantalla) {
                Pantalla.RESUMEN -> ResumenScreen(gastos, ingresos, tasasGastos.toMap(), tasasIngresos.toMap(), onGastos = { pantalla = Pantalla.GASTOS }, onIngresos = { pantalla = Pantalla.INGRESOS }, onAgregarGasto = { tipoFormulario = Pantalla.GASTOS; movimientoEditando = null; mostrarFormulario = true }, tasaEstado = estadoTasa, fechaTasa = fechaTasa, onActualizarTasa = ::actualizarTipoCambio)
                Pantalla.GASTOS -> ListaScreen(
                    titulo = "Mis gastos", vacio = "Aún no has registrado gastos.",
                    movimientos = gastos.map { Movimiento(it.id, it.descripcion, it.categoria, it.fecha.toString(), it.monto, it.moneda, tasasGastos[it.id] ?: 7.70) },
                    onAgregar = { tipoFormulario = Pantalla.GASTOS; movimientoEditando = null; mostrarFormulario = true },
                    onEditar = { movimiento -> movimientoEditando = movimiento; tipoFormulario = Pantalla.GASTOS; mostrarFormulario = true },
                    onEliminar = { id -> scope.launch { gastoRepo.eliminar(id); recargar() } }, esGasto = true
                )
                Pantalla.INGRESOS -> ListaScreen(
                    titulo = "Mis ingresos", vacio = "Aún no has registrado ingresos.",
                    movimientos = ingresos.map { Movimiento(it.id, it.descripcion, it.categoria, it.fecha.toString(), it.monto, it.moneda, tasasIngresos[it.id] ?: 7.70) },
                    onAgregar = { tipoFormulario = Pantalla.INGRESOS; movimientoEditando = null; mostrarFormulario = true },
                    onEditar = { movimiento -> movimientoEditando = movimiento; tipoFormulario = Pantalla.INGRESOS; mostrarFormulario = true },
                    onEliminar = { id -> scope.launch { ingresoRepo.eliminar(id); recargar() } }, esGasto = false
                )
                Pantalla.PRESUPUESTOS -> PresupuestosScreen(
                    presupuestos = presupuestos,
                    gastos = gastos,
                    tasasGastos = tasasGastos.toMap(),
                    avisosActivos = notificacionesPermitidas,
                    onAgregar = { crearPresupuesto() },
                    onEliminar = { id -> scope.launch { presupuestoDao.eliminar(id); recargar() } }
                )
                Pantalla.METAS -> MetasScreen(
                    metas = metas,
                    onAgregar = { mostrarFormularioMeta = true },
                    onAportar = { metaParaAporte = it },
                    onEliminar = { id -> scope.launch { metaAhorroDao.eliminar(id); recargar() } }
                )
            }
        }
    }
    if (mostrarFormulario) {
        FormularioMovimiento(
            esGasto = tipoFormulario == Pantalla.GASTOS,
            inicial = movimientoEditando,
            alCerrar = { mostrarFormulario = false; movimientoEditando = null },
            alGuardar = { descripcion, monto, categoria, fecha, moneda ->
                scope.launch {
                    try {
                        val editando = movimientoEditando
                        val tasaAplicada = if (moneda == "USD") {
                            if (editando?.moneda == "USD") editando.tasaCambio else ConversorMoneda.obtenerTasa()
                        } else 1.0
                        if (tipoFormulario == Pantalla.GASTOS && editando != null) {
                            gastoRepo.actualizar(Gasto(id = editando.id, descripcion = descripcion, monto = monto, categoria = categoria, fecha = fecha, moneda = moneda), tasaAplicada)
                        } else if (tipoFormulario == Pantalla.INGRESOS && editando != null) {
                            ingresoRepo.actualizar(Ingreso(id = editando.id, descripcion = descripcion, monto = monto, categoria = categoria, fecha = fecha, moneda = moneda), tasaAplicada)
                        } else if (tipoFormulario == Pantalla.GASTOS) {
                            val guardado = control.registrarGasto(Gasto(descripcion = descripcion, monto = monto, categoria = categoria, fecha = fecha, moneda = moneda))
                            gastoRepo.guardarTasa(guardado.id, tasaAplicada)
                        } else {
                            val guardado = control.registrarIngreso(Ingreso(descripcion = descripcion, monto = monto, categoria = categoria, fecha = fecha, moneda = moneda))
                            ingresoRepo.guardarTasa(guardado.id, tasaAplicada)
                        }
                        recargar()
                        emitirAvisosPresupuesto(contexto, gastos.toList(), tasasGastos.toMap(), presupuestos.toList())
                        mostrarFormulario = false; movimientoEditando = null
                        mensaje = if (editando == null) "Movimiento guardado correctamente." else "Movimiento actualizado correctamente."
                    } catch (e: Exception) { mensaje = e.message ?: "No se pudo guardar el movimiento." }
                }
            }
        )
    }
    if (mostrarFormularioPresupuesto) {
        FormularioPresupuesto(
            onCerrar = { mostrarFormularioPresupuesto = false },
            onGuardar = { categoria, limite ->
                scope.launch {
                    val hoy = LocalDate.now()
                    val existente = presupuestoDao.buscar(categoria, hoy.monthValue, hoy.year)
                    presupuestoDao.guardar(existente?.copy(limite = limite) ?: PresupuestoEntity(categoria = categoria, limite = limite, mes = hoy.monthValue, anio = hoy.year))
                    recargar(); emitirAvisosPresupuesto(contexto, gastos.toList(), tasasGastos.toMap(), presupuestos.toList()); mostrarFormularioPresupuesto = false
                }
            }
        )
    }
    if (mostrarFormularioMeta) {
        FormularioMeta(onCerrar = { mostrarFormularioMeta = false }, onGuardar = { nombre, objetivo ->
            scope.launch {
                metaAhorroDao.insertar(MetaAhorroEntity(nombre = nombre, objetivo = objetivo))
                recargar(); mostrarFormularioMeta = false
            }
        })
    }
    metaParaAporte?.let { meta ->
        DialogoAporte(meta = meta, onCerrar = { metaParaAporte = null }, onGuardar = { aporte ->
            scope.launch {
                metaAhorroDao.actualizar(meta.copy(ahorrado = meta.ahorrado + aporte))
                recargar(); metaParaAporte = null
            }
        })
    }
    mensaje?.let { texto -> AlertDialog(onDismissRequest = { mensaje = null }, confirmButton = { TextButton(onClick = { mensaje = null }) { Text("Aceptar") } }, title = { Text("Mis Gastos") }, text = { Text(texto) }) }
}

private data class Movimiento(val id: Long, val descripcion: String, val categoria: String, val fecha: String, val monto: Double, val moneda: String, val tasaCambio: Double = 7.70)

@Composable
private fun ResumenScreen(
    gastos: List<Gasto>,
    ingresos: List<Ingreso>,
    tasasGastos: Map<Long, Double>,
    tasasIngresos: Map<Long, Double>,
    onGastos: () -> Unit,
    onIngresos: () -> Unit,
    onAgregarGasto: () -> Unit,
    tasaEstado: String,
    fechaTasa: String,
    onActualizarTasa: () -> Unit
) {
    val totalIngresos = ingresos.sumOf { montoEnQuetzales(it.monto, it.moneda, tasasIngresos[it.id] ?: 7.70) }
    val totalGastos = gastos.sumOf { montoEnQuetzales(it.monto, it.moneda, tasasGastos[it.id] ?: 7.70) }
    val balance = totalIngresos - totalGastos
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Hola, qué gusto verte 👋", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Mis Gastos", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Tus finanzas, claras y bajo control", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Tu panorama", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(50)) {
                Text("Todos", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saldo disponible", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
                Text("Q ${dinero(balance)}", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                Text("Ingresos menos gastos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .82f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TarjetaTotal("Ingresos", totalIngresos, MaterialTheme.colorScheme.surface, "Total de entradas", Modifier.weight(1f))
            TarjetaTotal("Gastos", totalGastos, MaterialTheme.colorScheme.surface, "Total de salidas", Modifier.weight(1f))
        }
        Text("Accesos rápidos", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccesoRapido("＋", "Añadir ingreso", onIngresos, Modifier.weight(1f))
            AccesoRapido("≡", "Ver movimientos", onGastos, Modifier.weight(1f))
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("US$1 = Q ${dinero(ConversorMoneda.obtenerTasa())}", fontWeight = FontWeight.SemiBold)
                    Text(if (fechaTasa.isNotBlank()) "$tasaEstado · $fechaTasa" else tasaEstado, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onActualizarTasa) { Text("Actualizar") }
            }
        }
        Button(onClick = onAgregarGasto, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(vertical = 14.dp)) { Text("＋   Añadir gasto") }
    }
}

@Composable
private fun TarjetaTotal(titulo: String, monto: Double, color: androidx.compose.ui.graphics.Color, detalle: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = color), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(titulo, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Q ${dinero(monto)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(detalle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AccesoRapido(icono: String, titulo: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onClick, modifier = modifier.heightIn(min = 108.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(icono, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(titulo, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PresupuestosScreen(presupuestos: List<PresupuestoEntity>, gastos: List<Gasto>, tasasGastos: Map<Long, Double>, avisosActivos: Boolean, onAgregar: () -> Unit, onEliminar: (Long) -> Unit) {
    val hoy = LocalDate.now()
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Presupuestos", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                val mes = hoy.month.getDisplayName(java.time.format.TextStyle.FULL, Locale("es", "GT"))
                Text("${mes.replaceFirstChar { it.titlecase(Locale("es", "GT")) }} ${hoy.year}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onAgregar) { Text("＋ Crear") }
        }
        Text(
            if (avisosActivos) "Avisos al llegar al 80% y al superar el límite." else "Permite las notificaciones para recibir avisos de presupuesto.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        if (presupuestos.isEmpty()) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Aún no tienes presupuestos", fontWeight = FontWeight.SemiBold)
                    Text("Define un límite para una categoría y observa cuánto has utilizado este mes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(presupuestos, key = { it.id }) { presupuesto ->
                    val gastado = gastos.filter { it.categoria == presupuesto.categoria && it.fecha.year == hoy.year && it.fecha.monthValue == hoy.monthValue }
                        .sumOf { montoEnQuetzales(it.monto, it.moneda, tasasGastos[it.id] ?: 7.70) }
                    val restante = presupuesto.limite - gastado
                    val progreso = (gastado / presupuesto.limite).toFloat().coerceIn(0f, 1f)
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(presupuesto.categoria, fontWeight = FontWeight.SemiBold)
                                Text("Q ${dinero(presupuesto.limite)}", fontWeight = FontWeight.Bold)
                            }
                            LinearProgressIndicator(progress = { progreso }, modifier = Modifier.fillMaxWidth(), color = if (gastado > presupuesto.limite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            Text("Gastado Q ${dinero(gastado)}  ·  ${if (restante >= 0) "Disponible Q ${dinero(restante)}" else "Excedido Q ${dinero(-restante)}"}", style = MaterialTheme.typography.bodySmall, color = if (restante < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { onEliminar(presupuesto.id) }, contentPadding = PaddingValues(0.dp)) { Text("Quitar presupuesto") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetasScreen(metas: List<MetaAhorroEntity>, onAgregar: () -> Unit, onAportar: (MetaAhorroEntity) -> Unit, onEliminar: (Long) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("Metas de ahorro", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("Ahorra paso a paso", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Button(onClick = onAgregar) { Text("＋ Nueva") }
        }
        if (metas.isEmpty()) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("¿Qué quieres alcanzar?", fontWeight = FontWeight.SemiBold)
                    Text("Crea una meta y registra tus aportes para ver cómo avanzas.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(metas, key = { it.id }) { meta ->
                    val avance = (meta.ahorrado / meta.objetivo).toFloat().coerceIn(0f, 1f)
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(meta.nombre, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            LinearProgressIndicator(progress = { avance }, modifier = Modifier.fillMaxWidth())
                            Text("Q ${dinero(meta.ahorrado)} de Q ${dinero(meta.objetivo)} · ${(avance * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onAportar(meta) }, modifier = Modifier.weight(1f)) { Text("＋ Aportar") }
                                TextButton(onClick = { onEliminar(meta.id) }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormularioPresupuesto(onCerrar: () -> Unit, onGuardar: (String, Double) -> Unit) {
    var categoria by remember { mutableStateOf("") }
    var limite by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Presupuesto mensual") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Selector("Categoría", categoria.ifBlank { "Seleccionar" }, CatalogoCategorias.gastos.map { it.nombre }) { categoria = it }
                OutlinedTextField(limite, { limite = it }, label = { Text("Límite en quetzales") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                Text("El límite se aplica al mes actual.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = {
            val valor = limite.replace(',', '.').toDoubleOrNull()
            if (categoria.isBlank() || valor == null || !valor.isFinite() || valor <= 0) error = "Selecciona una categoría e ingresa un límite válido."
            else onGuardar(categoria, valor)
        }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } }
    )
}

@Composable
private fun FormularioMeta(onCerrar: () -> Unit, onGuardar: (String, Double) -> Unit) {
    var nombre by remember { mutableStateOf("") }
    var objetivo by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Nueva meta de ahorro") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(nombre, { nombre = it }, label = { Text("¿Para qué estás ahorrando?") }, singleLine = true)
                OutlinedTextField(objetivo, { objetivo = it }, label = { Text("Objetivo en quetzales") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = {
            val valor = objetivo.replace(',', '.').toDoubleOrNull()
            if (nombre.isBlank() || valor == null || !valor.isFinite() || valor <= 0) error = "Escribe un nombre y un objetivo válido."
            else onGuardar(nombre.trim(), valor)
        }) { Text("Crear meta") } },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } }
    )
}

@Composable
private fun DialogoAporte(meta: MetaAhorroEntity, onCerrar: () -> Unit, onGuardar: (Double) -> Unit) {
    var cantidad by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Aportar a ${meta.nombre}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Ahorrado: Q ${dinero(meta.ahorrado)} de Q ${dinero(meta.objetivo)}")
                OutlinedTextField(cantidad, { cantidad = it }, label = { Text("Aporte en quetzales") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { Button(onClick = {
            val valor = cantidad.replace(',', '.').toDoubleOrNull()
            if (valor == null || !valor.isFinite() || valor <= 0) error = "Ingresa un aporte mayor que cero."
            else onGuardar(valor)
        }) { Text("Registrar aporte") } },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } }
    )
}

@Composable
private fun ListaScreen(titulo: String, vacio: String, movimientos: List<Movimiento>, onAgregar: () -> Unit, onEditar: (Movimiento) -> Unit, onEliminar: (Long) -> Unit, esGasto: Boolean) {
    var busqueda by remember { mutableStateOf("") }
    var categoriaFiltro by remember { mutableStateOf("Todas") }
    var desde by remember { mutableStateOf("") }
    var hasta by remember { mutableStateOf("") }
    val categorias = listOf("Todas") + movimientos.map { it.categoria }.distinct().sorted()
    val filtrados = movimientos.filter { movimiento ->
        val coincideTexto = movimiento.descripcion.contains(busqueda, ignoreCase = true) || movimiento.categoria.contains(busqueda, ignoreCase = true)
        val coincideCategoria = categoriaFiltro == "Todas" || movimiento.categoria == categoriaFiltro
        val coincideDesde = desde.isBlank() || movimiento.fecha >= desde.trim()
        val coincideHasta = hasta.isBlank() || movimiento.fecha <= hasta.trim()
        coincideTexto && coincideCategoria && coincideDesde && coincideHasta
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text(titulo, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("${filtrados.size} de ${movimientos.size} registrados", style = MaterialTheme.typography.bodySmall) }
            Button(onClick = onAgregar) { Text(if (esGasto) "+ Gasto" else "+ Ingreso") }
        }
        OutlinedTextField(busqueda, { busqueda = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Buscar descripción o categoría") }, shape = RoundedCornerShape(16.dp))
        Selector("Categoría", categoriaFiltro, categorias) { categoriaFiltro = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(desde, { desde = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Desde") }, placeholder = { Text("AAAA-MM-DD") })
            OutlinedTextField(hasta, { hasta = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Hasta") }, placeholder = { Text("AAAA-MM-DD") })
        }
        if (movimientos.isEmpty()) {
            Card(Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(vacio, style = MaterialTheme.typography.bodyLarge)
                    Text("Usa el botón para agregar tu primer movimiento.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (filtrados.isEmpty()) {
            Card(Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(20.dp)) {
                Text("No encontramos movimientos con esos filtros.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtrados, key = { it.id }) { movimiento ->
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(movimiento.descripcion, fontWeight = FontWeight.SemiBold)
                                Text("${movimiento.categoria} · ${movimiento.fecha}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${if (movimiento.moneda == "USD") "US$" else "Q"} ${dinero(movimiento.monto)}", fontWeight = FontWeight.Bold)
                                Row {
                                    TextButton(onClick = { onEditar(movimiento) }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Editar") }
                                    TextButton(onClick = { onEliminar(movimiento.id) }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormularioMovimiento(esGasto: Boolean, inicial: Movimiento?, alCerrar: () -> Unit, alGuardar: (String, Double, String, LocalDate, String) -> Unit) {
    var descripcion by remember { mutableStateOf(inicial?.descripcion.orEmpty()) }
    var monto by remember { mutableStateOf(inicial?.monto?.toString().orEmpty()) }
    var categoria by remember { mutableStateOf(inicial?.categoria.orEmpty()) }
    var fechaTexto by remember { mutableStateOf(inicial?.fecha ?: LocalDate.now().toString()) }
    var moneda by remember { mutableStateOf(inicial?.moneda ?: "GTQ") }
    var error by remember { mutableStateOf<String?>(null) }
    val categorias = (if (esGasto) CatalogoCategorias.gastos else CatalogoCategorias.ingresos).map { it.nombre }
    AlertDialog(
        onDismissRequest = alCerrar,
        title = { Text(if (inicial == null) { if (esGasto) "Nuevo gasto" else "Nuevo ingreso" } else { if (esGasto) "Editar gasto" else "Editar ingreso" }) },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(descripcion, { descripcion = it }, label = { Text("Descripción") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(monto, { monto = it }, label = { Text("Monto") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                Selector("Categoría", categoria.ifBlank { "Seleccionar" }, categorias) { categoria = it }
                Selector("Moneda", moneda, listOf("GTQ", "USD")) { moneda = it }
                OutlinedTextField(fechaTexto, { fechaTexto = it }, label = { Text("Fecha (AAAA-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Ejemplo: ${LocalDate.now()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (inicial?.moneda == "USD") Text("Se conservará la tasa original: Q ${dinero(inicial.tasaCambio)} por US$1", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                try {
                    val valor = monto.replace(',', '.').toDouble()
                    val fecha = LocalDate.parse(fechaTexto.trim())
                    require(descripcion.isNotBlank()) { "Escribe una descripción." }
                    require(categoria.isNotBlank()) { "Selecciona una categoría." }
                    require(valor.isFinite() && valor > 0) { "El monto debe ser mayor que cero." }
                    alGuardar(descripcion.trim(), valor, categoria, fecha, moneda)
                } catch (e: DateTimeParseException) { error = "La fecha debe tener formato AAAA-MM-DD." }
                catch (e: NumberFormatException) { error = "Escribe un monto válido." }
                catch (e: IllegalArgumentException) { error = e.message ?: "Revisa los datos." }
            }) { Text(if (inicial == null) "Guardar" else "Guardar cambios") }
        },
        dismissButton = { TextButton(onClick = alCerrar) { Text("Cancelar") } }
    )
}

@Composable
private fun Selector(etiqueta: String, valor: String, opciones: List<String>, alSeleccionar: (String) -> Unit) {
    var abierto by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { abierto = true }, modifier = Modifier.fillMaxWidth()) { Text("$etiqueta: $valor") }
        DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
            opciones.forEach { opcion -> DropdownMenuItem(text = { Text(opcion) }, onClick = { alSeleccionar(opcion); abierto = false }) }
        }
    }
}

private data class RespuestaTasa(val valor: Double, val fecha: String)

private fun consultarTasaDolarQuetzal(): RespuestaTasa {
    val conexion = URL("https://www.banguat.gob.gt/variables/ws/TipoCambio.asmx").openConnection() as HttpURLConnection
    conexion.requestMethod = "POST"
    conexion.connectTimeout = 10_000
    conexion.readTimeout = 10_000
    conexion.doOutput = true
    conexion.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
    conexion.setRequestProperty("SOAPAction", "\"http://www.banguat.gob.gt/variables/ws/TipoCambioDia\"")
    try {
        val solicitud = """<?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body><TipoCambioDia xmlns="http://www.banguat.gob.gt/variables/ws/" /></soap:Body>
            </soap:Envelope>""".trimIndent()
        conexion.outputStream.use { it.write(solicitud.toByteArray(Charsets.UTF_8)) }
        val codigo = conexion.responseCode
        check(codigo in 200..299) { "El servicio de tipo de cambio respondió $codigo." }
        val respuesta = conexion.inputStream.bufferedReader().use { it.readText() }
        val parser = android.util.Xml.newPullParser().apply {
            setInput(respuesta.reader())
        }
        var valor: Double? = null
        var fecha = ""
        var etiqueta = ""
        while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> etiqueta = parser.name
                org.xmlpull.v1.XmlPullParser.TEXT -> when (etiqueta) {
                    "referencia" -> parser.text.trim().toDoubleOrNull()?.let { valor = it }
                    "fecha" -> if (parser.text.isNotBlank()) fecha = parser.text.trim()
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> etiqueta = ""
            }
            parser.next()
        }
        val tasa = valor ?: error("El Banco de Guatemala no devolvió una tasa de referencia.")
        check(tasa.isFinite() && tasa > 0) { "La tasa recibida no es válida." }
        return RespuestaTasa(tasa, fecha)
    } finally {
        conexion.disconnect()
    }
}

private fun montoEnQuetzales(monto: Double, moneda: String, tasaCambio: Double): Double =
    if (moneda.equals("USD", ignoreCase = true)) monto * tasaCambio else monto

private fun emitirAvisosPresupuesto(
    contexto: Context,
    gastos: List<Gasto>,
    tasasGastos: Map<Long, Double>,
    presupuestos: List<PresupuestoEntity>
) {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val hoy = LocalDate.now()
    val preferencias = contexto.getSharedPreferences("avisos_presupuesto", Context.MODE_PRIVATE)
    val gestor = contexto.getSystemService(NotificationManager::class.java)
    presupuestos.forEach { presupuesto ->
        if (presupuesto.mes != hoy.monthValue || presupuesto.anio != hoy.year) return@forEach
        val gastado = gastos.filter { it.categoria == presupuesto.categoria && it.fecha.year == hoy.year && it.fecha.monthValue == hoy.monthValue }
            .sumOf { montoEnQuetzales(it.monto, it.moneda, tasasGastos[it.id] ?: 7.70) }
        val prefijo = "${hoy.year}_${hoy.monthValue}_${presupuesto.id}"
        val excedido = gastado >= presupuesto.limite
        val cerca = gastado >= presupuesto.limite * 0.80
        val clave = if (excedido) "${prefijo}_excedido" else "${prefijo}_cerca"
        if ((excedido || cerca) && !preferencias.getBoolean(clave, false)) {
            val titulo = if (excedido) "Presupuesto excedido" else "Presupuesto casi alcanzado"
            val texto = if (excedido) {
                "${presupuesto.categoria}: llevas Q ${dinero(gastado)} de Q ${dinero(presupuesto.limite)}."
            } else {
                "${presupuesto.categoria}: llevas ${(gastado / presupuesto.limite * 100).toInt()}% del límite mensual."
            }
            val notificacion = NotificationCompat.Builder(contexto, "alertas_presupuesto")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            gestor.notify(clave.hashCode(), notificacion)
            preferencias.edit().putBoolean(clave, true).apply()
            if (excedido) preferencias.edit().putBoolean("${prefijo}_cerca", true).apply()
        }
    }
}

private fun dinero(valor: Double): String = String.format(Locale.US, "%,.2f", valor)
