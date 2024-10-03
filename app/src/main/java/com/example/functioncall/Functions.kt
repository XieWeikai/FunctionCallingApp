package com.example.functioncall

import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.reflect.KFunction
import kotlin.reflect.full.memberFunctions
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.jvm.isAccessible
import androidx.activity.ComponentActivity
import kotlin.reflect.full.instanceParameter

/**
 * Data class representing the structure of the JSON input for function execution.
 * @property name The name of the function to execute.
 * @property arguments A map of argument names to their values.
 */
data class FunctionCall(
    val name: String,
    val arguments: Map<String, Any>
)

/**
 * Data class representing the result of executing a function.
 * @property state The state of execution: "ok" or "error".
 * @property message An error message if the state is "error"; otherwise, null.
 * @property return_type The fully qualified name of the return type if available; otherwise, null.
 * @property return_value The actual return value of the function; null if the function returns Unit or in case of errors.
 */
data class Result(
    val state: String,           // "ok" or "error"
    val message: String?,        // Error message if any
    val return_type: String?,    // Fully qualified name of the return type
    val return_value: Any?       // Actual return value
)

/**
 * The Functions class containing various methods and the execute method.
 * It dynamically invokes functions based on JSON input.
 */
class Functions(private var context: ComponentActivity?, private var outerFunctionsMap: Map<String, KFunction<*>>?=null) {

    // Initialize Jackson ObjectMapper with Kotlin module
    private val objectMapper = jacksonObjectMapper()

    // Cache of functions by name for quick lookup
    // Retrieve all member functions of this class, excluding the 'execute' and other utility methods
    private val functionsMap: Map<String, KFunction<*>> = this::class.memberFunctions
        .filter { it.name != "execute" && it.name != "prepareArguments" && it.name != "convertValue" }
        .associateBy { it.name }

    /**
     * Executes a function based on the provided JSON string.
     * The JSON should have the structure:
     * {
     *    "name": "functionName",
     *    "arguments": {
     *        "param1": value1,
     *        "param2": value2,
     *        ...
     *    }
     * }
     *
     * @param json The JSON string specifying the function to execute and its arguments
     * @return A Result object containing the execution outcome
     */
    fun execute(functionCall: FunctionCall): Result {
        return try {
            // Retrieve the function by name
            val function: KFunction<*> = functionsMap[functionCall.name]
                ?: outerFunctionsMap?.get(functionCall.name) ?:return Result(
                    state = "error",
                    message = "Function '${functionCall.name}' not found.",
                    return_type = null,
                    return_value = null
                )

            // Make the function accessible if it's not public
            function.isAccessible = true

            // Prepare the arguments for the function
            val args = prepareArguments(function, functionCall.arguments)

            val returnValue: Any?
            val instanseParam = function.instanceParameter!!
            if (functionCall.name in functionsMap) {
                // Invoke the function with the prepared arguments
                returnValue = function.callBy(args + (instanseParam to this))
//                returnValue = function.call(this, *args.toTypedArray())
            }else{
                returnValue = function.callBy(args + (instanseParam to context))
//                returnValue = function.call(context, *args.toTypedArray())
            }

            // Determine the return type
            val returnType = function.returnType.classifier as? KClass<*>
            val returnTypeName = if (returnType == Unit::class) null else returnType?.qualifiedName

            Result(
                state = "ok",
                message = null,
                return_type = returnTypeName,
                return_value = returnValue
            )
        } catch (e: Exception) {
            // Handle exceptions such as JSON parsing errors, missing functions, etc.
            e.printStackTrace()
            Result(
                state = "error",
                message = e.message,
                return_type = null,
                return_value = null
            )
        }
    }

    /**
     * Prepares the arguments for the function invocation by matching parameter names and types.
     *
     * @param function The KFunction to be invoked
     * @param providedArgs The map of argument names to their values from the JSON
     * @return A list of arguments ordered as per the function's parameters
     */
    private fun prepareArguments(function: KFunction<*>, providedArgs: Map<String, Any>): Map<KParameter, Any?> {
        val filteredParam = function.parameters.filter {
            it.kind == KParameter.Kind.VALUE
        }

        if ( filteredParam.any{!it.isOptional && !providedArgs.containsKey(it.name)} ){
            throw IllegalArgumentException("no enough arguments provided")
        }

        return filteredParam.filter { !(it.isOptional && !providedArgs.containsKey(it.name)) }.associateWith { param->
            providedArgs[param.name]
        }

//        return function.parameters
//            .filter { it.kind == KParameter.Kind.VALUE } // Filter out instance and other special parameters
//            .map { param ->
//                val value = providedArgs[param.name]
//                    ?: throw IllegalArgumentException("Missing argument '${param.name}' for function '${function.name}'.")
//
//                // Convert the value to the required type
//                convertValue(value, param)
//            }
    }

    /**
     * Converts the provided value to the target type if necessary.
     * Supports primitive types and lists.
     *
     * @param value The value to convert
     * @param parameter The KParameter containing type information
     * @return The converted value
     */
    private fun convertValue(value: Any, parameter: KParameter): Any? {
        val type = parameter.type

        return convertValueWithType(value, type)
    }

    private fun convertValueWithType(value: Any, type: KType): Any? {
        val classifier = type.classifier as? KClass<*> ?: throw IllegalArgumentException("Unsupported type classification.")

        return when (classifier) {
            Int::class -> (value as Number).toInt()
            Double::class -> (value as Number).toDouble()
            Float::class -> (value as Number).toFloat()
            Long::class -> (value as Number).toLong()
            Short::class -> (value as Number).toShort()
            Byte::class -> (value as Number).toByte()
            Boolean::class -> value as Boolean
            String::class -> value.toString()
            List::class -> {
                // 处理List类型
                // 确定元素类型
                val elementType = type.arguments.firstOrNull()?.type
                    ?: throw IllegalArgumentException("List parameter does not specify a generic type.")

                // 转换列表中的每个元素
                val listValue = value as List<*>
                listValue.map { element ->
                    if (element == null) {
                        null
                    } else {
                        convertValueWithType(element, elementType)
                    }
                }
            }
            else -> {
                // 对于其他类型，尝试使用ObjectMapper进行转换
                objectMapper.convertValue(value, object : TypeReference<Any>() {})
            }
        }
    }

    fun createAlarm(message: String, hour: Int, minutes: Int) {
        Log.d("createAlarm", "get into createAlarm")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minutes)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (context?.packageManager?.let { intent.resolveActivity(it) } != null) {
            context?.startActivity(intent)
            Log.d("createAlarm", "start Intent")
        }else{
            Log.d("createAlarm", "can not resolveActivity")
        }
    }

    fun test1(a: Int):Int {
        println("here is a test function that accept an integer a($a)")
        return 2 * a
    }

    fun test2(name: String, score: List<Float>): Float{
        val totalScore = score.sum()
        println("The total score of $name is $totalScore")
        return totalScore
    }

    fun test(a: Int, b: Int=3, c: Int){
        println("a: $a, b: $b, c: $c")
    }

    fun testMap(data: Map<String, String>){
        // print key and value
        data.forEach { (key, value) ->
            println("$key -> $value")
        }
    }
}

fun testFunction(functions: Functions, task: String){
    val objectMapper = jacksonObjectMapper()
    val taskObj = objectMapper.readValue<FunctionCall>(task)
    println("taskObj: $taskObj")
    val res = functions.execute(taskObj)
    println(res)
    val json = objectMapper.writeValueAsString(res)
    println("json:\n$json\n\n")
}



fun main(){
    val functions = Functions(null)

    val test = """
    {
        "name": "test",
        "arguments": {
            "a": 9,
            "b": 10,
            "c": 11
        }
    }    
    """.trimIndent()

    val test2 = """
    {
        "name": "test2",
        "arguments": {
            "name": "John",
            "score": [2.4, 1.2, 3]
        }
    }
    """.trimIndent()

    val testMap = """
    {
        "name": "testMap",
        "arguments": {
        "data": {
                "a": "1",
                "b": "2",
                "c": "3"
            }
        }
    }
    """.trimIndent()

    listOf(
//        test,
//        test2,
        testMap
    ).forEach{task-> testFunction(functions, task)}

//    val f: KFunction<*> = Functions::test
//
//    println(f.parameters)
//
//    functions.test(1, c = 5)
//
//    val arg: Map<String, Any> = mapOf(
//        "a" to 1,
////        "b" to 8,
//        "c" to 5
//    )
//
//    val tmp = f.parameters.filter {
//        it.kind == KParameter.Kind.VALUE
//    }
//
//    if (tmp.any{
//        !it.isOptional && !arg.containsKey(it.name)
//    }){
//        println("argument not enough")
//        return
//    }
//
//    val prepared = tmp.filter{!(it.isOptional && !arg.containsKey(it.name))}.associateWith { param->
//        arg[param.name]
//    }
//
//
//    val instance = f.instanceParameter!!
//    println(prepared + (instance to functions))
//    f.callBy(prepared + (instance to functions))
}
