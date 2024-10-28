package com.example.functioncall

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.VectorDistanceType
import java.io.IOException

private object EMBEDDING {
    const val DIMENSIONS: Long = 512
    const val NUM_RESULTS: Int = 4
}

@Entity
data class Document(
    @Id
    var id: Long = 0,
    var doc: String = "",
    @HnswIndex(dimensions = EMBEDDING.DIMENSIONS, distanceType = VectorDistanceType.DEFAULT)
    var embedding: FloatArray = FloatArray(EMBEDDING.DIMENSIONS.toInt())
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Document

        if (id != other.id) return false
        if (doc != other.doc) return false
        if (!embedding.contentEquals(other.embedding)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + doc.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

abstract class TextEmbedding {
    abstract val dimension: Int

    /**
     * 将给定的文本转换为数值向量。
     *
     * @param text 要转换的文本。
     * @return 维度为 dimension 的向量，其中每个元素是 Float 类型。
     */
    abstract fun embedding(text: String): FloatArray
}

class DummyEmbedding : TextEmbedding() {
    override val dimension: Int
        get() = EMBEDDING.DIMENSIONS.toInt()

    override fun embedding(text: String): FloatArray {
        val result = FloatArray(dimension)
        for (i in text.indices) {
            if (i < dimension) {
                result[i] = text[i].code.toFloat()
            } else {
                break
            }
        }
        return result
    }
}

object DocumentVecDB {
    val docEmb = DummyEmbedding()
    val queryEmb = DummyEmbedding()

    lateinit var store: BoxStore
        private set

    private lateinit var docBox: Box<Document>
        private set


    fun init(context: Context, jsonlFileName: String) {
        store = MyObjectBox.builder()
            .androidContext(context)
            .build()
        docBox = store.boxFor(Document::class.java)

        // 检查数据库是否为空
        if (docBox.isEmpty) {
            loadDocumentsFromAssets(context, jsonlFileName)
        }
    }

    fun reloadDocuments(context: Context, jsonlFileName: String) {
        // 清空现有的所有文档
        docBox.removeAll()

        // 从文件重新加载文档
        loadDocumentsFromAssets(context, jsonlFileName)
    }

    private fun loadDocumentsFromAssets(context: Context, fileName: String) {
        val mapper = jacksonObjectMapper()
        val assetManager = context.assets
        try {
            assetManager.open(fileName).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    try {
                        val formattedJson = mapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(line)
                        // 更新 Document 对象以使用格式化的 JSON
                        addDocument(formattedJson)
                        Log.d("DocumentVecDB", "Loaded document: $formattedJson")
                    } catch (e: Exception) {
                        Log.e("DocumentVecDB", "Failed to load document: $line with error: $e")
                    }
                }
            }
        } catch (e: IOException) {
            // 处理文件读取错误
            Log.e("DocumentVecDB", "Failed to load documents from assets")
        }
    }

    fun addDocument(doc: String) {
        val docEntity = Document(doc = doc, embedding = docEmb.embedding(doc))
        docBox.put(docEntity)
    }

    fun queryDocument(query: String): List<String> {
        val queryEmb = queryEmb.embedding(query)
        val q = docBox.query(
            Document_.embedding.nearestNeighbors(queryEmb, EMBEDDING.NUM_RESULTS)
        ).build()

        // return the top 4 documents
        return q.find().map { it.doc }
    }
}

