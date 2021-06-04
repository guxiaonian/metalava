/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.metalava

import com.android.SdkConstants
import com.android.tools.metalava.model.AnnotationAttributeValue
import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.Codebase
import com.android.tools.metalava.model.FieldItem
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.model.MethodItem
import com.android.tools.metalava.model.ParameterItem
import com.android.tools.metalava.model.TypeItem
import com.android.tools.metalava.model.visitors.ApiVisitor
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.psi.PsiJavaFile

/** Misc API suggestions */
class AndroidApiChecks {
    fun check(codebase: Codebase) {
        codebase.accept(object : ApiVisitor(
            // Sort by source order such that warnings follow source line number order
            methodComparator = MethodItem.sourceOrderComparator,
            fieldComparator = FieldItem.comparator
        ) {
            override fun skip(item: Item): Boolean {
                // Limit the checks to the android.* namespace (except for ICU)
                if (item is ClassItem) {
                    val name = item.qualifiedName()
                    return !(name.startsWith("android.") && !name.startsWith("android.icu."))
                }
                return super.skip(item)
            }

            override fun visitItem(item: Item) {
                checkTodos(item)
            }

            override fun visitMethod(method: MethodItem) {
                checkRequiresPermission(method)
                if (!method.isConstructor()) {
                    checkVariable(method, "@return", "Return value of '" + method.name() + "'", method.returnType())
                }
            }

            override fun visitField(field: FieldItem) {
                if (field.name().contains("ACTION")) {
                    checkIntentAction(field)
                }
                checkVariable(field, null, "Field '" + field.name() + "'", field.type())
            }

            override fun visitParameter(parameter: ParameterItem) {
                checkVariable(
                    parameter,
                    parameter.name(),
                    "Parameter '" + parameter.name() + "' of '" + parameter.containingMethod().name() + "'",
                    parameter.type()
                )
            }
        })
    }

    private var cachedDocumentation: String = ""
    private var cachedDocumentationItem: Item? = null
    private var cachedDocumentationTag: String? = null

    // Cache around findDocumentation
    private fun getDocumentation(item: Item, tag: String?): String {
        return if (item === cachedDocumentationItem && cachedDocumentationTag == tag) {
            cachedDocumentation
        } else {
            cachedDocumentationItem = item
            cachedDocumentationTag = tag
            cachedDocumentation = findDocumentation(item, tag)
            cachedDocumentation
        }
    }

    private fun findDocumentation(item: Item, tag: String?): String {
        if (item is ParameterItem) {
            return findDocumentation(item.containingMethod(), item.name())
        }

        val doc = item.documentation
        if (doc.isBlank()) {
            return ""
        }

        if (tag == null) {
            return doc
        }

        var begin: Int
        if (tag == "@return") {
            // return tag
            begin = doc.indexOf("@return")
        } else {
            begin = 0
            while (true) {
                begin = doc.indexOf(tag, begin)
                if (begin == -1) {
                    return ""
                } else {
                    // See if it's prefixed by @param
                    // Scan backwards and allow whitespace and *
                    var ok = false
                    for (i in begin - 1 downTo 0) {
                        val c = doc[i]
                        if (c != '*' && !Character.isWhitespace(c)) {
                            if (c == 'm' && doc.startsWith("@param", i - 5, true)) {
                                begin = i - 5
                                ok = true
                            }
                            break
                        }
                    }
                    if (ok) {
                        // found beginning
                        break
                    }
                }
                begin += tag.length
            }
        }

        if (begin == -1) {
            return ""
        }

        // Find end
        // This is the first block tag on a new line
        var isLinePrefix = false
        var end = doc.length
        for (i in begin + 1 until doc.length) {
            val c = doc[i]

            if (c == '@' && (isLinePrefix ||
                    doc.startsWith("@param", i, true) ||
                    doc.startsWith("@return", i, true))
            ) {
                // Found it
                end = i
                break
            } else if (c == '\n') {
                isLinePrefix = true
            } else if (c != '*' && !Character.isWhitespace(c)) {
                isLinePrefix = false
            }
        }

        return doc.substring(begin, end)
    }

    private fun checkTodos(item: Item) {
        if (item.documentation.contains("TODO:") || item.documentation.contains("TODO(")) {
            reporter.report(Issues.TODO, item, "Documentation mentions 'TODO'")
        }
    }

    fun start() {
        jsonResult = JSONObject()
        jsonMethod = JSONArray()
        jsonField = JSONArray()
    }

    fun end() {
        val file = File("metalava.json")
        if (!file.exists()) {
            file.createNewFile()
        }
        val os = FileOutputStream(file)
        val osw = OutputStreamWriter(os, "utf-8")
        val bf = BufferedWriter(osw)
        jsonResult!!.put("method", jsonMethod)
        jsonResult!!.put("field", jsonField)
        val jsonObject: JsonObject = JsonParser.parseString(jsonResult.toString()).asJsonObject
        val gson: Gson = GsonBuilder().setPrettyPrinting().create()
        bf.write(gson.toJson(jsonObject))
        bf.flush()
        bf.close()
    }

    private fun searchFromImport(data: String, method: MethodItem): String? {
        if (data.contains(".")) {
            return data
        }
        if (data == "String") {
            return "java.lang.String"
        }
        if (data == "String[]") {
            return "java.lang.String[]"
        }
        if (data in listOf(
                "boolean",
                "boolean[]",
                "byte",
                "byte[]",
                "char",
                "char[]",
                "short",
                "short[]",
                "int",
                "int[]",
                "long",
                "long[]",
                "float",
                "float[]",
                "double",
                "double[]",
                "void"
            )
        ) {
            return data
        }
        val file = method.containingClass().getCompilationUnit()?.file
        if (file!=null&&file is PsiJavaFile) {
            val importList = file.importList
            if (importList != null) {
                for (importStatement in importList.importStatements) {
                    val str = importStatement.qualifiedName
                    if (str!=null) {
                        val newStr = str.substring(str.lastIndexOf("."))
                        if (newStr == data) {
                            return str
                        }
                    }
                }
            }
        }
        return method.containingClass().containingPackage().qualifiedName()+"."+data
    }

    private fun getTypeName(data: String, method: MethodItem): String? {
        var newData = data
        if (newData.endsWith("[]")) {
            newData = newData.substring(0, newData.length - 2)
            return searchFromImport(newData, method) + "[]"
        }
        if (newData.contains("<") && newData.contains(">")) {
            val left = newData.substring(0, newData.indexOf("<"))
            val right = newData.substring(newData.indexOf("<") + 1, newData.indexOf(">"))
            return searchFromImport(left, method) + "<" + searchFromImport(right, method) + ">"
        }
        return searchFromImport(data, method)
    }

    private fun checkRequiresPermission(method: MethodItem) {
        //val text = method.documentation
        val annotation = method.modifiers.findAnnotation("android.support.annotation.RequiresPermission")
        if (annotation != null) {
            for (attribute in annotation.attributes()) {
                var values: List<AnnotationAttributeValue>? = null
                when (attribute.name) {
                    "value", "allOf", "anyOf" -> {
                        values = attribute.leafValues()
                    }
                }
                if (values == null || values.isEmpty()) {
                    continue
                }
                val jsonObject = JSONObject()
                jsonObject.put("methodName", method.name())
                jsonObject.put("attribute", attribute.name)
                jsonObject.put("class", method.containingClass().qualifiedName())
                jsonObject.put("return", getTypeName(method.returnType()!!.toTypeString(),method))
                //println(getTypeName(method.returnType()!!.toTypeString(),method))
                //println("end--")
                val jsonArray = JSONArray()
                for (i in method.parameters()) {
                    //println(getTypeName(i.type().toTypeString(),method))
                    jsonArray.put(getTypeName(i.type().toTypeString(),method))
                      }
                jsonObject.put("param", jsonArray)
                val perArray = JSONArray()
                for (value in values) {
                    // var perm = String.valueOf(value.value())
                    var perm = value.toSource()
                    if (perm.indexOf('.') >= 0) perm = perm.substring(perm.lastIndexOf('.') + 1)
                    println("Method " + method.name()+" permission "+perm)
                    perArray.put("android.permission."+ perm)
                }
                 jsonObject.put("permission", perArray)
                 jsonMethod!!.put(jsonObject)
            }
        }
    }

    private fun checkIntentAction(field: FieldItem) {
        // Intent rules don't apply to support library
        // if (field.containingClass().qualifiedName().startsWith("android.support.")) {
        //     return
        // }
        // println("Field " + field.name())
        val annotation = field.modifiers.findAnnotation("android.support.annotation.RequiresPermission")
       if (annotation != null) {
            for (attribute in annotation.attributes()) {
                var values: List<AnnotationAttributeValue>? = null
                when (attribute.name) {
                    "value", "allOf", "anyOf" -> {
                        values = attribute.leafValues()
                    }
                }
                if (values == null || values.isEmpty()) {
                    continue
                }

                val jsonObject = JSONObject()
                jsonObject.put("fieldName", field.name())
                jsonObject.put("attribute", attribute.name)
                jsonObject.put("class", field.containingClass().qualifiedName())
                jsonObject.put("value", field.initialValue(requireConstant = false))
                val perArray = JSONArray()
                for (value in values) {
                    // var perm = String.valueOf(value.value())
                    var perm = value.toSource()
                    if (perm.indexOf('.') >= 0) perm = perm.substring(perm.lastIndexOf('.') + 1)
                   println("Field " + field.name()+" permission "+perm)
                   perArray.put("android.permission."+ perm)
                }
                 jsonObject.put("permission", perArray)
                 jsonField!!.put(jsonObject)
            }
        }
        // val hasSdkConstant = field.modifiers.findAnnotation("android.annotation.SdkConstant") != null

        // val text = field.documentation

        // if (text.contains("Broadcast Action:") ||
        //     text.contains("protected intent") && text.contains("system")
        // ) {
        //     if (!hasBehavior) {
        //         reporter.report(
        //             Issues.BROADCAST_BEHAVIOR, field,
        //             "Field '" + field.name() + "' is missing @BroadcastBehavior"
        //         )
        //     }
        //     if (!hasSdkConstant) {
        //         reporter.report(
        //             Issues.SDK_CONSTANT, field, "Field '" + field.name() +
        //                 "' is missing @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)"
        //         )
        //     }
        // }

        // if (text.contains("Activity Action:")) {
        //     if (!hasSdkConstant) {
        //         reporter.report(
        //             Issues.SDK_CONSTANT, field, "Field '" + field.name() +
        //                 "' is missing @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)"
        //         )
        //     }
        // }
    }

    private fun checkVariable(
        item: Item,
        tag: String?,
        ident: String,
        type: TypeItem?
    ) {
        type ?: return
        if (type.toString() == "int" && constantPattern.matcher(getDocumentation(item, tag)).find()) {
            var foundTypeDef = false
            for (annotation in item.modifiers.annotations()) {
                val cls = annotation.resolve() ?: continue
                val modifiers = cls.modifiers
                if (modifiers.findAnnotation(SdkConstants.INT_DEF_ANNOTATION.oldName()) != null ||
                    modifiers.findAnnotation(SdkConstants.INT_DEF_ANNOTATION.newName()) != null
                ) {
                    // TODO: Check that all the constants listed in the documentation are included in the
                    // annotation?
                    foundTypeDef = true
                    break
                }
            }

            if (!foundTypeDef) {
                reporter.report(
                    Issues.INT_DEF, item,
                    // TODO: Include source code you can paste right into the code?
                    "$ident documentation mentions constants without declaring an @IntDef"
                )
            }
        }

        if (nullPattern.matcher(getDocumentation(item, tag)).find() &&
            !item.hasNullnessInfo()
        ) {
            reporter.report(
                Issues.NULLABLE, item,
                "$ident documentation mentions 'null' without declaring @NonNull or @Nullable"
            )
        }
    }

    companion object {
        val constantPattern: Pattern = Pattern.compile("[A-Z]{3,}_([A-Z]{3,}|\\*)")
        @Suppress("SpellCheckingInspection")
        val nullPattern: Pattern = Pattern.compile("\\bnull\\b")
        var jsonMethod: JSONArray? = null
        var jsonField: JSONArray? = null
        var jsonResult: JSONObject? = null
    }
}
