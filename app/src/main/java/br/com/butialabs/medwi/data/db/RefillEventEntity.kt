package br.com.butialabs.medwi.data.db

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

/** One stock replenishment ("+1 box", manual adjustment), for the refill history. */
@Serializable
@Entity(
    tableName = "refill_event",
    foreignKeys = [
        ForeignKey(
            entity = MedicineEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicineId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("medicineId")],
)
data class RefillEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicineId: Long,
    val atMillis: Long,
    /** Units added (negative when the stock was corrected down). */
    val amount: Int,
    val resultingStock: Int,
)
