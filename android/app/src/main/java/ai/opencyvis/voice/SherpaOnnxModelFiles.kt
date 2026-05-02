package ai.opencyvis.voice

import java.io.File

object SherpaOnnxModelFiles {
    const val ASSET_DIR = "asr/zh_en_small_int8"
    const val FILES_DIR = "asr/zh_en_small_int8"

    val requiredFiles = listOf(
        "encoder-epoch-99-avg-1.int8.onnx",
        "decoder-epoch-99-avg-1.onnx",
        "joiner-epoch-99-avg-1.int8.onnx",
        "tokens.txt",
        "bpe.model"
    )

    fun paths(baseDir: File): Paths {
        val modelDir = File(baseDir, FILES_DIR)
        return Paths(
            modelDir = modelDir,
            encoder = File(modelDir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
            decoder = File(modelDir, "decoder-epoch-99-avg-1.onnx").absolutePath,
            joiner = File(modelDir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath,
            tokens = File(modelDir, "tokens.txt").absolutePath,
            bpeModel = File(modelDir, "bpe.model").absolutePath
        )
    }

    data class Paths(
        val modelDir: File,
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String,
        val bpeModel: String
    )
}
