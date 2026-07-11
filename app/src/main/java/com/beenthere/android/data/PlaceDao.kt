package com.beenthere.android.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places ORDER BY year DESC")
    fun getAllPlaces(): Flow<List<Place>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(place: Place)

    @Delete
    suspend fun delete(place: Place)
}
