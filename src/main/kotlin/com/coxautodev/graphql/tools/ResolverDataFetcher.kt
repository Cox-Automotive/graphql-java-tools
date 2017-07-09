package com.coxautodev.graphql.tools

import com.esotericsoftware.reflectasm.MethodAccess
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.util.Optional

class ResolverDataFetcher(val sourceResolver: SourceResolver, method: Method, val args: List<ArgumentPlaceholder>): DataFetcher<Any> {

    companion object {
        val mapper = ObjectMapper().registerModule(Jdk8Module()).registerKotlinModule()

        @JvmStatic fun create(method: Resolver.Method): ResolverDataFetcher {

            val args = mutableListOf<ArgumentPlaceholder>()

            // Add source argument if this is a resolver (but not a root resolver)
            if(method.sourceArgument) {
                val expectedType = method.resolver.dataClassType
                args.add({ environment ->
                    val source = environment.getSource<Any>()
                    if (!(expectedType.isAssignableFrom(source.javaClass))) {
                        throw ResolverError("Source type (${source.javaClass.name}) is not expected type (${expectedType.name})!")
                    }

                    source
                })
            }

            // Add an argument for each argument defined in the GraphQL schema
            method.field.inputValueDefinitions.forEachIndexed { index, definition ->

                val genericType = method.getJavaMethodParameterType(index) ?: throw ResolverError("Missing method type at position ${method.getJavaMethodParameterIndex(index)}, this is most likely a bug with graphql-java-tools")
                val rawType = method.genericMethod.getRawClass(genericType)
                val rawTypeWithoutOptional = if(genericType is ParameterizedType && method.genericMethod.isTypeAssignableFromRawClass(genericType, Optional::class.java)) method.genericMethod.getRawClass(genericType.actualTypeArguments.first()) else rawType

                val isNonNull = definition.type is NonNullType
                val isOptional = rawType == Optional::class.java
                val isMap = Map::class.java.isAssignableFrom(rawTypeWithoutOptional)
                val isList = definition.type is ListType

                val typeReference = object: TypeReference<Any>() {
                    override fun getType() = genericType
                }

                args.add({ environment ->
                    val value = environment.arguments[definition.name] ?: if(isNonNull) {
                        throw ResolverError("Missing required argument with name '${definition.name}', this is most likely a bug with graphql-java-tools")
                    } else {
                        null
                    }

                    if(value == null && isOptional) {
                        return@add Optional.empty<Any>()
                    }

                    // Convert to specific type if actual argument value is Map<?, ?> and method parameter type is not Map<?, ?>
                    if (value is Map<*, *>) {
                        if (isMap) {
                            return@add value
                        }

                        return@add mapper.convertValue(value, typeReference)
                    }
                    
                    if (isList) {
                        return@add mapper.convertValue(value, typeReference)
                    }

                    value
                })
            }

            // Add DataFetchingEnvironment argument
            if(method.dataFetchingEnvironment) {
                args.add({ environment -> environment })
            }

            // Add source resolver depending on whether or not this is a resolver method
            val sourceResolver: SourceResolver = if(method.resolverMethod) ({ method.resolver.resolver }) else ({ environment ->
                val source = environment.getSource<Any>()

                if(!method.genericMethod.baseType.isAssignableFrom(source.javaClass)) {
                    throw ResolverError("Expected source object to be an instance of '${method.genericMethod.baseType.name}' but instead got '${source.javaClass.name}'")
                }

                source
            })

            return ResolverDataFetcher(sourceResolver, method.genericMethod.javaMethod, args)
        }
    }

    // Convert to reflactasm reflection
    val methodAccess = MethodAccess.get(method.declaringClass)!!
    val methodIndex = methodAccess.getIndex(method.name, *method.parameterTypes)

    override fun get(environment: DataFetchingEnvironment): Any? {
        val source = sourceResolver(environment)
        val args = this.args.map { it(environment) }.toTypedArray()
        val result = methodAccess.invoke(source, methodIndex, *args)
        return if(result is Optional<*>) result.orElse(null) else result
    }
}

class ResolverError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
typealias SourceResolver = (DataFetchingEnvironment) -> Any
typealias ArgumentPlaceholder = (DataFetchingEnvironment) -> Any?
