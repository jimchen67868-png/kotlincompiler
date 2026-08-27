package com.example.kotlincompiler.engine

import java.io.File

object RJavaGenerator {

    fun generate(rTxt: File, packageName: String, outputDir: File): File {
        val byType = LinkedHashMap<String, MutableList<Pair<String, String>>>()

        if (rTxt.exists()) {
            rTxt.forEachLine { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEachLine
                if (line.startsWith("int[]") || line.contains(" styleable ")) return@forEachLine

                val parts = line.split(Regex("\\s+"), limit = 4)
                if (parts.size < 4 || parts[0] != "int") return@forEachLine
                val type = parts[1]
                val name = parts[2]
                val value = parts[3]
                byType.getOrPut(type) { mutableListOf() }.add(name to value)
            }
        }

        outputDir.mkdirs()
        val rFile = File(outputDir, "R.java")
        rFile.writeText(buildString {
            appendLine("package $packageName;")
            appendLine()
            appendLine("public final class R {")
            byType.forEach { (type, entries) ->
                appendLine("    public static final class $type {")
                entries.forEach { (name, value) ->
                    appendLine("        public static final int $name = $value;")
                }
                appendLine("    }")
            }
            appendLine("}")
        })
        return rFile
    }
}
