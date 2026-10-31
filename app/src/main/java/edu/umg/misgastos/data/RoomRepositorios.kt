package edu.umg.misgastos.data

import umg.edu.gt.misgastos.modelo.Gasto
import umg.edu.gt.misgastos.modelo.Ingreso
import umg.edu.gt.misgastos.repositorio.GastoRepositorio
import umg.edu.gt.misgastos.repositorio.IngresoRepositorio
import java.time.LocalDate

class GastoRoomRepositorio(private val dao: GastoDao) : GastoRepositorio {
    override suspend fun guardar(gasto: Gasto): Gasto {
        val id = dao.insertar(gasto.toEntity().copy(id = 0))
        return gasto.copy(id = id)
    }
    override suspend fun obtenerTodos(): List<Gasto> = dao.obtenerTodos().map { it.toModelo() }
    suspend fun obtenerTasas(): Map<Long, Double> = dao.obtenerTasas().associate { it.id to it.tasaCambio }
    suspend fun guardarTasa(id: Long, tasaCambio: Double) { dao.actualizarTasa(id, tasaCambio) }
    suspend fun actualizar(gasto: Gasto, tasaCambio: Double) { dao.actualizar(gasto.toEntity(tasaCambio)) }
    override suspend fun buscarPorId(id: Long): Gasto? = dao.buscarPorId(id)?.toModelo()
    override suspend fun eliminar(id: Long) { dao.eliminar(id) }
}

class IngresoRoomRepositorio(private val dao: IngresoDao) : IngresoRepositorio {
    override suspend fun guardar(ingreso: Ingreso): Ingreso {
        val id = dao.insertar(ingreso.toEntity().copy(id = 0))
        return ingreso.copy(id = id)
    }
    override suspend fun obtenerTodos(): List<Ingreso> = dao.obtenerTodos().map { it.toModelo() }
    suspend fun obtenerTasas(): Map<Long, Double> = dao.obtenerTasas().associate { it.id to it.tasaCambio }
    suspend fun guardarTasa(id: Long, tasaCambio: Double) { dao.actualizarTasa(id, tasaCambio) }
    suspend fun actualizar(ingreso: Ingreso, tasaCambio: Double) { dao.actualizar(ingreso.toEntity(tasaCambio)) }
    override suspend fun buscarPorId(id: Long): Ingreso? = dao.buscarPorId(id)?.toModelo()
    override suspend fun eliminar(id: Long) { dao.eliminar(id) }
}

private fun Gasto.toEntity(tasaCambio: Double = 7.70) = GastoEntity(id, descripcion, monto, categoria, fecha.toString(), moneda, tasaCambio)
private fun GastoEntity.toModelo() = Gasto(id, descripcion, monto, categoria, LocalDate.parse(fecha), moneda)
private fun Ingreso.toEntity(tasaCambio: Double = 7.70) = IngresoEntity(id, descripcion, monto, categoria, fecha.toString(), moneda, tasaCambio)
private fun IngresoEntity.toModelo() = Ingreso(id, descripcion, monto, categoria, LocalDate.parse(fecha), moneda)
