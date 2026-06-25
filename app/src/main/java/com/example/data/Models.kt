package com.example.data

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

@JsonClass(generateAdapter = true)
data class BoardPoint(
    val x: Float,
    val y: Float
)

enum class ElementType {
    PATH, SHAPE, TEXT, STICKY_NOTE, IMAGE
}

enum class PenType {
    PEN, PENCIL, MARKER, HIGHLIGHTER, ERASER
}

enum class ShapeType {
    CIRCLE, RECTANGLE, ARROW, LINE
}

@JsonClass(generateAdapter = true)
data class BoardElement(
    val id: String, // UUID
    val type: ElementType,
    
    // Path drawing parameters
    val points: List<BoardPoint>? = null,
    val penType: PenType? = null,
    
    // Shape parameters
    val shapeType: ShapeType? = null,
    
    // Position and size (all coordinate frames are relative to infinite canvas space)
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
    
    // Styles
    val colorHex: String = "#FF000000", // e.g. #FFFFC107 for sticky notes
    val strokeWidth: Float = 5f,
    val isFilled: Boolean = false,
    
    // Content parameters
    val text: String? = null,
    val imageUri: String? = null, // Path to local storage or gallery
    val layerIndex: Int = 0, // Order layer
    val rotation: Float = 0f
)

object BoardJsonSerializer {
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        
    private val listType = Types.newParameterizedType(List::class.java, BoardElement::class.java)
    private val adapter = moshi.adapter<List<BoardElement>>(listType)
    
    fun toJson(elements: List<BoardElement>): String {
        return adapter.toJson(elements) ?: "[]"
    }
    
    fun fromJson(json: String): List<BoardElement> {
        return try {
            adapter.fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
