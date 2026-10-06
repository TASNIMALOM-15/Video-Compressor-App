package com.local.vc.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val inputUri: String,
    val originalName: String,
    val originalSize: Long,
    val outputName: String? = null,
    val outputUri: String? = null,
    val resolution: Int = 720,
    val fps: Int = 30,
    val status: String = "WAITING",
    val progress: Int = 0,
    val speed: Float = 0f,
    val etaSeconds: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val error: String? = null,
    val queuePosition: Int = 0
)
