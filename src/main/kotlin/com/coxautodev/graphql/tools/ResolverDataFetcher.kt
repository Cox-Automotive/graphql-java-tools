package com.coxautodev.graphql.tools

import com.esotericsoftware.reflectasm.MethodAccess
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import graphql.language.InputValueDefinition
import graphql.language.NonNullType
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import java.lang.reflect.Method

class ResolverDataFetcher(val sourceResolver: SourceResolver, method: Method, val args: List<ArgumentPlaceholder>): DataFetcher {

    companion object {
        val mapper = ObjectMapper().registerKotlinModule()

        @JvmStatic fun create(resolver: Resolver, name: String, argumentDefinitions: List<InputValueDefinition>): ResolverDataFetcher {

            val (method, methodClass, isResolverMethod) = resolver.getMethod(name)
            val args = mutableListOf<ArgumentPlaceholder>()

            val shouldPassSource = isResolverMethod && resolver.dataClassType != null
            val argumentOffset = if(shouldPassSource) 1 else 0

            val expectedArgCount = argumentDefinitions.size + argumentOffset
            val actualArgCount = method.parameterTypes.size
            val argumentDiff = actualArgCount - (expectedArgCount)
            if (argumentDiff < 0) throw ResolverError("Method '${method.name}' of class '${methodClass.name}' has too few parameters!  Expected: $expectedArgCount or ${expectedArgCount + 1}, actual: $actualArgCount")
            if (argumentDiff > 1) throw ResolverError("Method '${method.name}' of class '${methodClass.name}' has too many parameters!  Expected: $expectedArgCount or ${expectedArgCount + 1}, actual: $actualArgCount")

            // Add source argument if this is a resolver (but not a root resolver)
            if(shouldPassSource) {
                val expectedType = resolver.dataClassType!! // We've already checked this when setting shouldPassSource
                args.add({ environment ->
                    val source = environment.source
                    if (!(expectedType.isAssignableFrom(source.javaClass))) {
                        throw ResolverError("Source type (${source.javaClass.name}) is not expected type (${expectedType.name})!")
                    }

                    source
                })
            }

            // Add an argument for each argument defined in the GraphQL schema
            val methodParameters = method.parameterTypes
            argumentDefinitions.forEachIndexed { index, definition ->
                args.add({ environment ->
                    val value = environment.arguments[definition.name] ?: if(definition.type is NonNullType) {
                        throw ResolverError("Missing required argument with name '$name', this is most likely a bug with graphql-java-tools")
                    } else {
                        return@add null
                    }

                    // Convert to specific type if actual argument value is Map<?, ?> and method parameter type is not Map<?, ?>
                    if (value is Map<*, *>) {
                        val methodParameterIndex = index + argumentOffset
                        val type = methodParameters[methodParameterIndex] ?: throw ResolverError("Missing method type at position $methodParameterIndex, this is most likely a bug with graphql-java-tools")
                        if (!Map::class.java.isAssignableFrom(type)) {
                            return@add mapper.convertValue(value, type)
                        }
                    }

                    value
                })
            }

            // Add DataFetchingEnvironment argument
            if(argumentDiff == 1) {
                if(!DataFetchingEnvironment::class.java.isAssignableFrom(methodParameters.last()!!)) {
                    throw ResolverError("Method '${method.name}' of class '${methodClass.name}' has an extra parameter, but the last parameter is not of type ${DataFetchingEnvironment::class.java.name}!")
                }
                args.add({ environment -> environment })
            }

            // Add source resolver depending on whether or not this is a resolver method
            val sourceResolver: SourceResolver = if(isResolverMethod) ({ resolver.resolver }) else ({ environment ->
                if(!methodClass.isAssignableFrom(environment.source.javaClass)) {
                    throw ResolverError("Expected source object to be an instance of '${methodClass.name}' but instead got '${environment.source.javaClass.name}'")
                }

                environment.source
            })

            return ResolverDataFetcher(sourceResolver, method, args)
        }
    }

    // Convert to reflactasm reflection
    val methodAccess = MethodAccess.get(method.declaringClass)!!
    val methodIndex = methodAccess.getIndex(method.name, *method.parameterTypes)

    override fun get(environment: DataFetchingEnvironment): Any? {
        val source = sourceResolver(environment)
        val args = this.args.map { it(environment) }.toTypedArray()
        return methodAccess.invoke(source, methodIndex, *args)
    }
}

class ResolverError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
typealias SourceResolver = (DataFetchingEnvironment) -> Any
typealias ArgumentPlaceholder = (DataFetchingEnvironment) -> Any?
