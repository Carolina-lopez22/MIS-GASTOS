package edu.umg.misgastos.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.Update

@Entity(tableName = "gastos")
data class GastoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val descripcion: String,
    val monto: Double,
    val categoria: String,
    val fecha: String,
    val moneda: String,
    val tasaCambio: Double = 7.70
)

@Entity(tableName = "ingresos")
data class IngresoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val descripcion: String,
    val monto: Double,
    val categoria: String,
    val fecha: String,
    val moneda: String,
    val tasaCambio: Double = 7.70
)

data class TasaMovimientoEntity(val id: Long, val tasaCambio: Double)

@Entity(tableName = "presupuestos", indices = [androidx.room.Index(value = ["categoria", "mes", "anio"], unique = true)])
data class PresupuestoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoria: String,
    val limite: Double,
    val mes: Int,
    val anio: Int
)

@Entity(tableName = "metas_ahorro")
data class MetaAhorroEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nombre: String,
    val objetivo: Double,
    val ahorrado: Double = 0.0
)

@Dao
interface GastoDao {
    @Insert suspend fun insertar(gasto: GastoEntity): Long
    @Update suspend fun actualizar(gasto: GastoEntity)
    @androidx.room.Query("UPDATE gastos SET tasaCambio = :tasa WHERE id = :id") suspend fun actualizarTasa(id: Long, tasa: Double)
    @Query("SELECT * FROM gastos ORDER BY fecha DESC, id DESC") suspend fun obtenerTodos(): List<GastoEntity>
    @Query("SELECT id, tasaCambio FROM gastos") suspend fun obtenerTasas(): List<TasaMovimientoEntity>
    @Query("SELECT * FROM gastos WHERE id = :id LIMIT 1") suspend fun buscarPorId(id: Long): GastoEntity?
    @Query("DELETE FROM gastos WHERE id = :id") suspend fun eliminar(id: Long)
}

@Dao
interface IngresoDao {
    @Insert suspend fun insertar(ingreso: IngresoEntity): Long
    @Update suspend fun actualizar(ingreso: IngresoEntity)
    @androidx.room.Query("UPDATE ingresos SET tasaCambio = :tasa WHERE id = :id") suspend fun actualizarTasa(id: Long, tasa: Double)
    @Query("SELECT * FROM ingresos ORDER BY fecha DESC, id DESC") suspend fun obtenerTodos(): List<IngresoEntity>
    @Query("SELECT id, tasaCambio FROM ingresos") suspend fun obtenerTasas(): List<TasaMovimientoEntity>
    @Query("SELECT * FROM ingresos WHERE id = :id LIMIT 1") suspend fun buscarPorId(id: Long): IngresoEntity?
    @Query("DELETE FROM ingresos WHERE id = :id") suspend fun eliminar(id: Long)
}

@Dao
interface PresupuestoDao {
    @Upsert suspend fun guardar(presupuesto: PresupuestoEntity)
    @Query("SELECT * FROM presupuestos WHERE categoria = :categoria AND mes = :mes AND anio = :anio LIMIT 1") suspend fun buscar(categoria: String, mes: Int, anio: Int): PresupuestoEntity?
    @Query("SELECT * FROM presupuestos WHERE mes = :mes AND anio = :anio ORDER BY categoria") suspend fun delMes(mes: Int, anio: Int): List<PresupuestoEntity>
    @Query("DELETE FROM presupuestos WHERE id = :id") suspend fun eliminar(id: Long)
}

@Dao
interface MetaAhorroDao {
    @Insert suspend fun insertar(meta: MetaAhorroEntity): Long
    @Upsert suspend fun actualizar(meta: MetaAhorroEntity)
    @Query("SELECT * FROM metas_ahorro ORDER BY id DESC") suspend fun obtenerTodas(): List<MetaAhorroEntity>
    @Query("DELETE FROM metas_ahorro WHERE id = :id") suspend fun eliminar(id: Long)
}

@Database(entities = [GastoEntity::class, IngresoEntity::class, PresupuestoEntity::class, MetaAhorroEntity::class], version = 3, exportSchema = false)
abstract class MisGastosDatabase : RoomDatabase() {
    abstract fun gastoDao(): GastoDao
    abstract fun ingresoDao(): IngresoDao
    abstract fun presupuestoDao(): PresupuestoDao
    abstract fun metaAhorroDao(): MetaAhorroDao
}
